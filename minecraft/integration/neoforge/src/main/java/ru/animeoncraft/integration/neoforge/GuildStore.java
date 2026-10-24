package ru.animeoncraft.integration.neoforge;

import java.util.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/** Adventurer cards and the coin ledger of the guild; part of the ordinary world backup. */
final class GuildStore extends SavedData {
    enum Result { OK, CLOSED, COOLDOWN, BUSY, RANK, NONE, NOT_DONE, MAX_RANK, XP, LIMIT, EXAM }
    static final class Card {
        boolean opened, exam; int rank, xp, completed, progress, doneToday; long seed, reward, cooldownUntil, earned; String active = "", day = "";
        /** Team quests handed in: "window:code", the last day of windows only. */
        final Set<String> team = new HashSet<>();
        GuildRules.Rank rank() { return GuildRules.Rank.of(rank); }
        boolean reset(long now) { String d = GuildRules.day(now); if (d.equals(day)) return false; day = d; earned = 0; doneToday = 0; return true; }
        long cooldown(long now) { return Math.max(0, cooldownUntil - now); }
        boolean open(long seedValue) { if (opened) return false; opened = true; rank = 0; xp = 0; seed = seedValue; return true; }
        Result take(GuildCatalog.Quest quest, long now) {
            if (!opened) return Result.CLOSED;
            if (!active.isEmpty()) return Result.BUSY;
            if (cooldown(now) > 0) return Result.COOLDOWN;
            if (quest.exam() || quest.rank().ordinal() > rank) return Result.RANK;
            int limit = GuildRules.dailyQuests(rank()); if (limit > 0 && doneToday >= limit) return Result.LIMIT;
            active = quest.code(); progress = 0; reward = GuildRules.roll(quest.min(), quest.max(), seed ^ now); return Result.OK;
        }
        Result abandon(long now) { if (active.isEmpty()) return Result.NONE; active = ""; progress = 0; reward = 0; seed++; cooldownUntil = now + GuildRules.cooldownMillis(rank()); return Result.OK; }
        /** The exam ignores the cooldown and the daily limit; it needs the XP for the next rank first. */
        Result exam(GuildCatalog.Quest quest) {
            if (!opened) return Result.CLOSED;
            if (!active.isEmpty()) return Result.BUSY;
            if (rank() != GuildRules.EXAM_RANK || exam || !quest.exam()) return Result.RANK;
            if (xp < rank().next) return Result.XP;
            active = quest.code(); progress = 0; reward = 0; return Result.OK;
        }
        void kill(GuildCatalog.Quest quest) { if (quest != null && quest.code().equals(active) && quest.kind() == GuildCatalog.Kind.KILL) progress = Math.min(quest.count(), progress + 1); }
        /** Closes the active quest after the server took the items or saw the kills; returns coins actually paid. */
        long finish(GuildCatalog.Quest quest, long now, Ledger ledger) {
            reset(now); ledger.reset(now);
            if (quest.exam()) { exam = true; active = ""; progress = 0; reward = 0; seed++; return 0; }
            long paid = GuildRules.payout(reward, rank(), earned, ledger.issued);
            xp = Math.min(100_000, xp + GuildRules.xpFor(rank(), quest.rank()));
            earned += paid; ledger.paid(rank(), paid, reward - paid, false);
            completed++; doneToday++; active = ""; progress = 0; reward = 0; seed++; cooldownUntil = now + GuildRules.cooldownMillis(rank());
            return paid;
        }
        boolean teamDone(long window, String code) { return team.contains(window + ":" + code); }
        /** Pays one member of a finished team quest; no cooldown and no daily quest count. */
        long teamFinish(GuildCatalog.Quest quest, long window, long share, long now, Ledger ledger) {
            reset(now); ledger.reset(now);
            long paid = GuildRules.payout(share, rank(), earned, ledger.issued);
            xp = Math.min(100_000, xp + GuildRules.teamXp(rank(), quest.rank()));
            earned += paid; ledger.paid(rank(), paid, share - paid, true); completed++;
            team.removeIf(e -> { try { return Long.parseLong(e.substring(0, e.indexOf(':'))) < window - 12; } catch (RuntimeException bad) { return true; } });
            team.add(window + ":" + quest.code());
            return paid;
        }
        Result canRankUp() {
            if (rank().up() == null || rank >= GuildRules.RANK_UP_TO.ordinal()) return Result.MAX_RANK;
            if (xp < rank().next) return Result.XP;
            return rank() == GuildRules.EXAM_RANK && !exam ? Result.EXAM : Result.OK;
        }
        /** After the fee was taken: the XP of the old rank is spent, the rest carries over. */
        void rankUp(Ledger ledger, long now) { long fee = GuildRules.fee(rank().up()); ledger.reset(now); ledger.burn(fee); xp -= rank().next; rank++; seed++; exam = false; }
    }
    /** Coins of the guild: today, all time, and the last {@link #HISTORY} closed days for tuning the balance. */
    static final class Ledger {
        static final int HISTORY = 30;
        /** withheld: coins the caps kept back; byRank: coins paid to cards of each rank. */
        record Day(String day, long issued, long burned, long withheld, int solo, int team, int rankUps, int players, long[] byRank) {}
        String day = ""; long issued, burned, withheld, totalIssued, totalBurned; int solo, team, rankUps;
        long[] byRank = new long[GuildRules.Rank.values().length];
        final Set<UUID> players = new HashSet<>();
        final ArrayDeque<Day> history = new ArrayDeque<>();
        void reset(long now) {
            String d = GuildRules.day(now); if (d.equals(day)) return;
            if (!day.isEmpty() && (issued > 0 || burned > 0 || solo + team > 0)) {
                var closed = today(); history.addFirst(closed); while (history.size() > HISTORY) history.removeLast();
                System.out.println("AOC_GUILD_DAY " + closed.day() + " issued=" + issued + " burned=" + burned + " withheld=" + withheld + " solo=" + solo + " team=" + team + " rank_ups=" + rankUps + " players=" + players.size());
            }
            day = d; issued = 0; burned = 0; withheld = 0; solo = 0; team = 0; rankUps = 0; byRank = new long[byRank.length]; players.clear();
        }
        Day today() { return new Day(day, issued, burned, withheld, solo, team, rankUps, players.size(), byRank.clone()); }
        void paid(GuildRules.Rank rank, long coins, long held, boolean teamQuest) {
            issued += coins; totalIssued += coins; withheld += Math.max(0, held); byRank[rank.ordinal()] += coins;
            if (teamQuest) team++; else solo++;
        }
        void burn(long fee) { burned += fee; totalBurned += fee; rankUps++; }
        /** Coins burned outside rank-ups: a new board, a team quest entry. */
        void sink(long coins) { burned += coins; totalBurned += coins; }
        void player(UUID id) { if (players.size() < 10_000) players.add(id); }
        /** Today and the closed days of the last n days, newest first. */
        List<Day> last(int n) {
            var out = new ArrayList<Day>(); out.add(today());
            for (var d : history) { if (out.size() >= n) break; out.add(d); }
            return out;
        }
        private static CompoundTag write(Day d) {
            var t = new CompoundTag(); t.putString("day", d.day()); t.putLong("issued", d.issued()); t.putLong("burned", d.burned()); t.putLong("withheld", d.withheld());
            t.putInt("solo", d.solo()); t.putInt("team", d.team()); t.putInt("rankUps", d.rankUps()); t.putInt("players", d.players()); t.putLongArray("byRank", d.byRank()); return t;
        }
        private static Day read(CompoundTag t) {
            var byRank = Arrays.copyOf(t.getLongArray("byRank"), GuildRules.Rank.values().length);
            return new Day(t.getString("day"), t.getLong("issued"), t.getLong("burned"), t.getLong("withheld"), t.getInt("solo"), t.getInt("team"), t.getInt("rankUps"), t.getInt("players"), byRank);
        }
        CompoundTag save() {
            var l = write(today()); l.putLong("totalIssued", totalIssued); l.putLong("totalBurned", totalBurned);
            var ids = new ListTag(); for (var id : players) ids.add(NbtUtils.createUUID(id)); l.put("playerIds", ids);
            var past = new ListTag(); for (var d : history) past.add(write(d)); l.put("history", past);
            return l;
        }
        void load(CompoundTag l) {
            var d = read(l); day = d.day(); issued = d.issued(); burned = d.burned(); withheld = d.withheld(); solo = d.solo(); team = d.team(); rankUps = d.rankUps(); byRank = d.byRank();
            totalIssued = l.getLong("totalIssued"); totalBurned = l.getLong("totalBurned");
            var ids = l.getList("playerIds", Tag.TAG_INT_ARRAY); for (int i = 0; i < ids.size() && i < 10_000; i++) players.add(NbtUtils.loadUUID(ids.get(i)));
            var past = l.getList("history", Tag.TAG_COMPOUND); for (int i = 0; i < past.size() && i < HISTORY; i++) history.addLast(read(past.getCompound(i)));
        }
    }
    private final Map<UUID, Card> cards = new HashMap<>();
    final Ledger ledger = new Ledger();

    static GuildStore get(MinecraftServer server) { return server.overworld().getDataStorage().computeIfAbsent(new Factory<>(GuildStore::new, GuildStore::load), "animeoncraft-guild"); }
    Card card(UUID id) { return cards.computeIfAbsent(id, key -> new Card()); }
    Card existing(UUID id) { return cards.get(id); }
    Map<UUID, Card> cards() { return Collections.unmodifiableMap(cards); }

    static GuildStore load(CompoundTag tag, HolderLookup.Provider registries) {
        var store = new GuildStore(); var list = tag.getList("cards", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            var row = list.getCompound(i); if (!row.hasUUID("uuid")) continue; var c = store.card(row.getUUID("uuid"));
            c.opened = row.getBoolean("opened"); c.rank = Math.clamp(row.getInt("rank"), 0, GuildRules.Rank.values().length - 1); c.xp = Math.max(0, row.getInt("xp"));
            c.completed = Math.max(0, row.getInt("completed")); c.progress = Math.max(0, row.getInt("progress")); c.seed = row.getLong("seed"); c.reward = Math.max(0, row.getLong("reward"));
            c.cooldownUntil = row.getLong("cooldown"); c.earned = Math.max(0, row.getLong("earned")); c.active = row.getString("active"); c.day = row.getString("day");
            c.doneToday = Math.max(0, row.getInt("done")); c.exam = row.getBoolean("exam");
            var team = row.getList("team", Tag.TAG_STRING); for (int t = 0; t < team.size() && t < 64; t++) c.team.add(team.getString(t));
        }
        store.ledger.load(tag.getCompound("ledger"));
        return store;
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        var list = new ListTag();
        for (var e : cards.entrySet()) {
            var c = e.getValue(); var row = new CompoundTag(); row.putUUID("uuid", e.getKey()); row.putBoolean("opened", c.opened); row.putInt("rank", c.rank); row.putInt("xp", c.xp);
            row.putInt("completed", c.completed); row.putInt("progress", c.progress); row.putLong("seed", c.seed); row.putLong("reward", c.reward); row.putLong("cooldown", c.cooldownUntil);
            row.putLong("earned", c.earned); row.putString("active", c.active); row.putString("day", c.day); row.putInt("done", c.doneToday); row.putBoolean("exam", c.exam);
            var team = new ListTag(); for (var t : c.team) team.add(StringTag.valueOf(t)); row.put("team", team); list.add(row);
        }
        tag.put("cards", list);
        tag.put("ledger", ledger.save()); tag.putInt("version", 1);
        return tag;
    }
}
