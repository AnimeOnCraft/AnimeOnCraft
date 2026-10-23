package ru.animeoncraft.integration.neoforge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pure capture battle model (docs/CAPTURE_SYSTEM.md, "Прогресс и доминирование" and "Исход").
 * One instance per running capture event; {@link #second} advances it by one second of presence.
 * No Minecraft types here, so the scoring is unit-tested exactly as it runs on the server.
 */
final class CaptureEngine {
    record Settings(double exponent, double defenceBonus, double dominanceRatio, int fullCaptureSeconds, int decaySeconds,
                    double killBonus, double killPenalty, int killRepeatSeconds, int killMaxPerVictim,
                    double winProgress, double winMargin, int overtimeSeconds) {
        static Settings from(JsonObject score, JsonObject timing) {
            var settings = new Settings(score.get("exponent").getAsDouble(), score.get("defence_bonus").getAsDouble(),
                score.get("dominance_ratio").getAsDouble(), score.get("full_capture_seconds").getAsInt(), score.get("decay_seconds").getAsInt(),
                score.get("kill_bonus").getAsDouble(), score.get("kill_penalty").getAsDouble(), score.get("kill_repeat_seconds").getAsInt(),
                score.get("kill_max_per_victim").getAsInt(), score.get("win_progress").getAsDouble(), score.get("win_margin").getAsDouble(),
                timing.get("overtime_seconds").getAsInt());
            if (!(settings.exponent > 0 && settings.exponent <= 1) || settings.dominanceRatio < 1 || settings.fullCaptureSeconds < 1
                || settings.decaySeconds < 1 || settings.overtimeSeconds < 0) throw new IllegalArgumentException("Invalid capture settings");
            return settings;
        }
    }
    enum Status { RUNNING, OVERTIME, WON, NO_WINNER }
    static final class Score {
        double progress; int kills, deaths, presenceSeconds, peakPlayers;
    }
    private record KillRecord(int count, long last) {}

    final String eventId;
    final String owner;
    final boolean ownerBonus;
    final long activeEnd;
    private final Settings settings;
    private final Map<String, Score> scores = new LinkedHashMap<>();
    private final Map<UUID, KillRecord> victims = new HashMap<>();
    private Status status = Status.RUNNING;
    private String winner;
    private String leader;
    private boolean locked;

    /** @param owner current owner clan or null; @param ownerBonus true while the owner holds it CONTROLLED (not AVAILABLE). */
    CaptureEngine(String eventId, String owner, boolean ownerBonus, long activeEndEpochSecond, Settings settings) {
        this.eventId = eventId; this.owner = owner; this.ownerBonus = ownerBonus && owner != null; this.activeEnd = activeEndEpochSecond; this.settings = settings;
    }

    Status status() { return status; }
    String winner() { return winner; }
    String leader() { return leader; }
    boolean locked() { return locked; }
    boolean finished() { return status == Status.WON || status == Status.NO_WINNER; }
    Map<String, Score> scores() { return java.util.Collections.unmodifiableMap(scores); }
    double progress(String clan) { var s = scores.get(clan); return s == null ? 0 : s.progress; }
    private Score score(String clan) { return scores.computeIfAbsent(clan, ignored -> new Score()); }

    double strength(String clan, int players) {
        if (players <= 0) return 0;
        double s = Math.pow(players, settings.exponent);
        return clan.equals(owner) && ownerBonus ? s * settings.defenceBonus : s;
    }

    /**
     * One second of the battle. {@code present} maps clan code to the number of eligible players in the zone.
     * Returns the status after this second; once finished, further calls change nothing.
     */
    Status second(Map<String, Integer> present, long now) {
        if (finished()) return status;
        String best = null; double bestS = 0, secondS = 0;
        for (var entry : present.entrySet()) {
            int n = entry.getValue();
            if (n <= 0) continue;
            var score = score(entry.getKey());
            score.presenceSeconds++; score.peakPlayers = Math.max(score.peakPlayers, n);
            double s = strength(entry.getKey(), n);
            if (s > bestS) { secondS = bestS; bestS = s; best = entry.getKey(); }
            else if (s > secondS) secondS = s;
        }
        leader = best;
        locked = best != null && secondS > 0 && bestS < settings.dominanceRatio * secondS;
        if (best != null && !locked) {
            // Dominance: only the leader grows (slower against resistance); everyone else decays.
            var lead = score(best);
            lead.progress = clamp(lead.progress + (100.0 / settings.fullCaptureSeconds) * (1 - secondS / bestS));
            for (var entry : scores.entrySet()) if (!entry.getKey().equals(best))
                entry.getValue().progress = clamp(entry.getValue().progress - 100.0 / settings.decaySeconds);
        }
        return evaluate(now);
    }

    /** A player of another clan killed in or near the zone. Repeat kills of the same victim are limited. */
    boolean kill(String killerClan, String victimClan, UUID victim, long now) {
        if (finished() || killerClan == null || victimClan == null || killerClan.equals(victimClan) || victim == null) return false;
        var record = victims.get(victim);
        if (record != null && (record.count >= settings.killMaxPerVictim || now - record.last < settings.killRepeatSeconds)) return false;
        victims.put(victim, new KillRecord(record == null ? 1 : record.count + 1, now));
        var killer = score(killerClan); killer.kills++; killer.progress = clamp(killer.progress + settings.killBonus);
        var dead = score(victimClan); dead.deaths++; dead.progress = clamp(dead.progress - settings.killPenalty);
        evaluate(now);
        return true;
    }

    private Status evaluate(long now) {
        if (finished()) return status;
        var ranked = ranked();
        if (!ranked.isEmpty() && scores.get(ranked.getFirst()).progress >= 100) { winner = ranked.getFirst(); return status = Status.WON; }
        if (now < activeEnd) return status = Status.RUNNING;
        double first = ranked.isEmpty() ? 0 : scores.get(ranked.getFirst()).progress, second = ranked.size() < 2 ? 0 : scores.get(ranked.get(1)).progress;
        if (first >= settings.winProgress && first - second > settings.winMargin) { winner = ranked.getFirst(); return status = Status.WON; }
        if (first >= settings.winProgress && now < activeEnd + settings.overtimeSeconds) return status = Status.OVERTIME;
        winner = null;
        return status = Status.NO_WINNER;
    }

    private List<String> ranked() {
        var list = new ArrayList<>(scores.keySet());
        list.sort(Comparator.comparingDouble((String clan) -> scores.get(clan).progress).reversed());
        return list;
    }
    /** 0..100; values within floating error of 100 count as complete (360 steps of 100/360 must win). */
    private static double clamp(double value) { return value >= 100 - 1e-6 ? 100 : Math.max(0, value); }

    /** Scores in the integration API shape (snapshot and finish). */
    JsonArray scoresJson() {
        var array = new JsonArray();
        for (var entry : scores.entrySet()) {
            var row = new JsonObject(); var s = entry.getValue();
            row.addProperty("clan_code", entry.getKey());
            row.addProperty("progress", Math.round(s.progress * 1000) / 1000.0);
            row.addProperty("kills", s.kills); row.addProperty("deaths", s.deaths);
            row.addProperty("presence_seconds", s.presenceSeconds); row.addProperty("peak_players", s.peakPlayers);
            array.add(row);
        }
        return array;
    }

    JsonArray killLimitsJson() {
        var rows=new JsonArray();
        for(var entry:victims.entrySet()) {var row=new JsonArray();row.add(entry.getKey().toString());row.add(entry.getValue().count());row.add(entry.getValue().last());rows.add(row);}
        return rows;
    }
    void restoreKillLimits(JsonArray rows) {
        if(rows.size()>4096)throw new IllegalArgumentException("Too many kill limits");
        var restored=new HashMap<UUID,KillRecord>();
        for(var value:rows){var row=value.getAsJsonArray();
            if(row.size()!=3)throw new IllegalArgumentException("Invalid kill limit");
            UUID victim=UUID.fromString(row.get(0).getAsString());int count=row.get(1).getAsInt();long last=row.get(2).getAsLong();
            if(count<1||count>settings.killMaxPerVictim||last<0||restored.put(victim,new KillRecord(count,last))!=null)throw new IllegalArgumentException("Invalid kill limit");
        }
        victims.clear();victims.putAll(restored);
    }
    /** Resumes scores; restoreKillLimits restores the repeat-kill protection from the same snapshot. */
    void restore(JsonArray rows) {
        if (rows.size() > 64) throw new IllegalArgumentException("Too many scores");
        for (var value : rows) {
            var row = value.getAsJsonObject();
            String clan = row.get("clan_code").getAsString();
            if (!ClanCodes.valid(clan)) throw new IllegalArgumentException("Invalid clan in snapshot");
            var score = score(clan);
            score.progress = clamp(row.get("progress").getAsDouble());
            score.kills = Math.max(0, row.get("kills").getAsInt()); score.deaths = Math.max(0, row.get("deaths").getAsInt());
            score.presenceSeconds = Math.max(0, row.get("presence_seconds").getAsInt()); score.peakPlayers = Math.max(0, row.get("peak_players").getAsInt());
        }
    }

    /** "SCAM 45% · NYA 12%" — leader first, for the boss bar and chat. */
    String summary() {
        var parts = new ArrayList<String>();
        for (String clan : ranked()) parts.add(clan + " " + (int) Math.floor(scores.get(clan).progress) + "%");
        return parts.isEmpty() ? "никого нет" : String.join(" · ", parts);
    }
}
