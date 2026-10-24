package ru.animeoncraft.integration.neoforge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;

/**
 * Quest list of the guild board. The default ships in the mod; a server may override it with
 * {@code <world>/serverconfig/animeoncraft-guild-quests.json} and {@code /aocguild reload}.
 * A target is an item or entity id, or a tag ({@code #minecraft:logs}).
 */
final class GuildCatalog {
    enum Kind { ITEM, KILL }
    /** An exam quest pays no coins and no XP; handing it in unlocks the rank-up out of {@link GuildRules#EXAM_RANK}. */
    /** party > 0 marks a team quest (KILL only, ranks B–S): the smallest team that may take it. */
    /** boss: a team quest for one boss of a mod; every team set of the rank has one of them, and only members near the boss are paid. */
    record Quest(String code, GuildRules.Rank rank, Kind kind, String target, int count, String name, String task, int min, int max, boolean exam, int party, boolean boss) {
        Quest {
            if (boss && (party == 0 || count != 1)) throw new IllegalArgumentException("Invalid boss quest " + code);
            if (party != 0 && (party < 2 || party > GuildRules.MAX_PARTY || exam || kind != Kind.KILL || rank.ordinal() < GuildRules.TEAM_FROM.ordinal()))
                throw new IllegalArgumentException("Invalid team quest " + code);
            if (!code.matches("[a-z0-9_]{3,32}") || count < 1 || count > 512 || min < (exam ? 0 : 1) || max < min || max > 100_000
                || name.isBlank() || name.length() > 48 || task.isBlank() || task.length() > 64
                || !target.matches("#?[a-z0-9_.-]+:[a-z0-9_/.-]+") || target.length() > 96)
                throw new IllegalArgumentException("Invalid guild quest " + code);
        }
        boolean tag() { return target.startsWith("#"); }
        boolean team() { return party > 0; }
        String id() { return tag() ? target.substring(1) : target; }
    }
    record Catalog(List<Quest> quests, GuildRules.Economy economy) {
        Catalog { quests = List.copyOf(quests); }
        Catalog(List<Quest> quests) { this(quests, GuildRules.Economy.DEFAULT); }
        List<Quest> rank(GuildRules.Rank rank) { return quests.stream().filter(q -> q.rank() == rank && !q.exam() && !q.team()).toList(); }
        List<Quest> team(GuildRules.Rank rank) { return quests.stream().filter(q -> q.rank() == rank && q.team()).toList(); }
        /** The team set of one rank for a two-hour window, the same for everyone: one ordinary quest and one boss when the rank has both. */
        List<Quest> teamSet(long window, GuildRules.Rank rank) {
            var pool = team(rank); var bosses = pool.stream().filter(Quest::boss).toList(); var others = pool.stream().filter(q -> !q.boss()).toList();
            if (bosses.isEmpty() || others.isEmpty()) return GuildRules.offers(pool.size(), window, rank.ordinal(), GuildRules.TEAM_PER_RANK).stream().map(pool::get).toList();
            return List.of(others.get(GuildRules.offers(others.size(), window, rank.ordinal(), 1).get(0)), bosses.get(GuildRules.offers(bosses.size(), window * 31 + 7, rank.ordinal(), 1).get(0)));
        }
        List<Quest> exams() { return quests.stream().filter(Quest::exam).toList(); }
        Quest find(String code) { return quests.stream().filter(q -> q.code().equals(code)).findFirst().orElse(null); }
    }
    static final String DEFAULT = "/animeoncraft/guild-quests.json", OVERRIDE = "serverconfig/animeoncraft-guild-quests.json";
    private GuildCatalog() {}

    /** Quests whose target the registries do not know are skipped and named in the log, not fatal. */
    static Catalog parse(JsonObject root, Predicate<Quest> known, List<String> skipped) {
        if (root.get("version").getAsInt() != 1) throw new IllegalArgumentException("Unsupported guild quest version");
        var out = new ArrayList<Quest>(); var codes = new HashSet<String>();
        for (var element : root.getAsJsonArray("quests")) {
            var row = element.getAsJsonObject(); var reward = row.getAsJsonArray("reward");
            var quest = new Quest(row.get("code").getAsString(), GuildRules.Rank.valueOf(row.get("rank").getAsString()),
                Kind.valueOf(row.get("kind").getAsString()), row.get("target").getAsString(), row.get("count").getAsInt(),
                row.get("name").getAsString(), row.get("task").getAsString(), reward.get(0).getAsInt(), reward.get(1).getAsInt(),
                row.has("exam") && row.get("exam").getAsBoolean(), row.has("party") ? row.get("party").getAsInt() : 0,
                row.has("boss") && row.get("boss").getAsBoolean());
            if (!codes.add(quest.code())) throw new IllegalArgumentException("Duplicate guild quest " + quest.code());
            if (known.test(quest)) out.add(quest); else skipped.add(quest.code() + " (" + quest.target() + ")");
        }
        if (out.size() > 256) throw new IllegalArgumentException("Too many guild quests");
        return new Catalog(out, root.has("economy") ? economy(root.getAsJsonObject("economy")) : GuildRules.Economy.DEFAULT);
    }
    /** Every key is optional and falls back to the default: {"dailyCap": {"F": 100}, "rankFee": {"E": 20}, "serverBudget", "overCapPercent", "overBudgetPercent", "dailyQuests", "rerollFee", "teamEntryPercent"}. */
    static GuildRules.Economy economy(JsonObject row) {
        var base = GuildRules.Economy.DEFAULT; var caps = base.caps().clone(); var fees = base.fees().clone();
        if (row.has("dailyCap")) for (var e : row.getAsJsonObject("dailyCap").entrySet()) caps[GuildRules.Rank.valueOf(e.getKey()).ordinal()] = e.getValue().getAsInt();
        if (row.has("rankFee")) for (var e : row.getAsJsonObject("rankFee").entrySet()) fees[GuildRules.Rank.valueOf(e.getKey()).ordinal()] = e.getValue().getAsInt();
        return new GuildRules.Economy(caps, fees, row.has("serverBudget") ? row.get("serverBudget").getAsInt() : base.budget(),
            row.has("overCapPercent") ? row.get("overCapPercent").getAsInt() : base.overCapPercent(),
            row.has("overBudgetPercent") ? row.get("overBudgetPercent").getAsInt() : base.overBudgetPercent(), row.has("dailyQuests") ? row.get("dailyQuests").getAsInt() : base.dailyQuests(),
            row.has("rerollFee") ? row.get("rerollFee").getAsInt() : base.rerollFee(), row.has("teamEntryPercent") ? row.get("teamEntryPercent").getAsInt() : base.teamEntryPercent());
    }
    static Catalog load(Path world, Predicate<Quest> known, List<String> skipped) throws IOException {
        var file = world.resolve(OVERRIDE);
        if (Files.isRegularFile(file)) {
            if (Files.size(file) > 262_144) throw new IOException("Guild quest file too large");
            return parse(JsonParser.parseString(Files.readString(file)).getAsJsonObject(), known, skipped);
        }
        try (var in = GuildCatalog.class.getResourceAsStream(DEFAULT)) {
            if (in == null) throw new IOException("Missing " + DEFAULT);
            return parse(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject(), known, skipped);
        }
    }
}
