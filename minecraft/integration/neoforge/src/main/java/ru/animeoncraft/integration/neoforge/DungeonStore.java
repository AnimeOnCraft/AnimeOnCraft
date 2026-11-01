package ru.animeoncraft.integration.neoforge;

import java.util.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Rift Gate state that must survive a restart: daily reward chests, first clears, where each player stood before a
 * run, fees to hand back after an interrupted run, the slots in use (wiped at the next start) and best times.
 * Part of the ordinary world backup (overworld data folder).
 */
final class DungeonStore extends SavedData {
    static final class Player {
        String day = ""; int chests; long refund;
        final Set<String> cleared = new HashSet<>();
        /** Where the player came from: dimension id and position; empty when not in a run. */
        String returnLevel = ""; double rx, ry, rz; float ryRot;
        /** The run the player is part of (0 none) and when they left the game during it. */
        long run; boolean watcher;
        /** Personal chest items of a run that ended while the player was offline (saved item tags). */
        final ListTag pending = new ListTag();
        boolean reset(long now) { String d = GuildRules.day(now); if (d.equals(day)) return false; day = d; chests = 0; return true; }
    }
    /** A run as the next start needs it: its slot to wipe and the fee each member paid, to hand back. */
    record Run(long id, int slot, String dungeon, String difficulty, Map<UUID, Long> fees) {}
    record Best(long seconds, String team) {}
    private final Map<UUID, Player> players = new HashMap<>();
    final Map<Long, Run> runs = new LinkedHashMap<>();
    final Map<String, Best> best = new HashMap<>();
    /** Slots that were in use when the server stopped and still need wiping. */
    final Set<Integer> dirtySlots = new TreeSet<>();
    long burned, started, won, failed, nextId = 1;
    /** An operator's affix for every Nightmare and Mythic run ("" = the week's affix); kept across restarts. */
    String affixOverride = "";
    /** Economy journal, one row per Ekaterinburg day (last {@link #HISTORY}): runs, wins, fails, coins burned, chests, essence, restarts. */
    static final int RUNS = 0, WINS = 1, FAILS = 2, BURNED = 3, CHESTS = 4, ESSENCE = 5, INTERRUPTED = 6, FIELDS = 7, HISTORY = 30;
    final TreeMap<String, long[]> days = new TreeMap<>();
    long[] today(long now) {
        var row = days.computeIfAbsent(GuildRules.day(now), d -> new long[FIELDS]);
        while (days.size() > HISTORY) days.pollFirstEntry();
        setDirty(); return row;
    }

    static DungeonStore get(MinecraftServer server) { return server.overworld().getDataStorage().computeIfAbsent(new Factory<>(DungeonStore::new, DungeonStore::load), "animeoncraft-dungeons"); }
    Player player(UUID id) { return players.computeIfAbsent(id, key -> new Player()); }
    Player existing(UUID id) { return players.get(id); }
    Map<UUID, Player> players() { return Collections.unmodifiableMap(players); }

    static DungeonStore load(CompoundTag tag, HolderLookup.Provider registries) {
        var s = new DungeonStore(); var list = tag.getList("players", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            var row = list.getCompound(i); if (!row.hasUUID("uuid")) continue; var p = s.player(row.getUUID("uuid"));
            p.day = row.getString("day"); p.chests = Math.max(0, row.getInt("chests")); p.refund = Math.max(0, row.getLong("refund"));
            var cleared = row.getList("cleared", Tag.TAG_STRING); for (int c = 0; c < cleared.size() && c < 256; c++) p.cleared.add(cleared.getString(c));
            p.returnLevel = row.getString("returnLevel"); p.rx = row.getDouble("rx"); p.ry = row.getDouble("ry"); p.rz = row.getDouble("rz"); p.ryRot = row.getFloat("ryRot");
            p.run = row.getLong("run"); p.watcher = row.getBoolean("watcher");
            var items = row.getList("pending", Tag.TAG_COMPOUND); for (int c = 0; c < items.size() && c < 64; c++) p.pending.add(items.getCompound(c));
        }
        var runs = tag.getList("runs", Tag.TAG_COMPOUND);
        for (int i = 0; i < runs.size(); i++) {
            var row = runs.getCompound(i); var fees = new LinkedHashMap<UUID, Long>(); var members = row.getList("fees", Tag.TAG_COMPOUND);
            for (int m = 0; m < members.size(); m++) { var f = members.getCompound(m); if (f.hasUUID("uuid")) fees.put(f.getUUID("uuid"), Math.max(0, f.getLong("fee"))); }
            var run = new Run(row.getLong("id"), Math.clamp(row.getInt("slot"), 0, DungeonRules.SLOTS - 1), row.getString("dungeon"), row.getString("difficulty"), fees);
            s.runs.put(run.id(), run);
        }
        for (int slot : tag.getIntArray("dirtySlots")) if (slot >= 0 && slot < DungeonRules.SLOTS) s.dirtySlots.add(slot);
        var best = tag.getCompound("best"); for (var key : best.getAllKeys()) { var b = best.getCompound(key); s.best.put(key, new Best(b.getLong("seconds"), b.getString("team"))); }
        s.affixOverride = tag.getString("affix");
        var days = tag.getCompound("days"); for (var key : days.getAllKeys()) s.days.put(key, Arrays.copyOf(days.getLongArray(key), FIELDS));
        while (s.days.size() > HISTORY) s.days.pollFirstEntry();
        s.burned = tag.getLong("burned"); s.started = tag.getLong("started"); s.won = tag.getLong("won"); s.failed = tag.getLong("failed"); s.nextId = Math.max(1, tag.getLong("nextId"));
        return s;
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        var list = new ListTag();
        for (var e : players.entrySet()) {
            var p = e.getValue(); var row = new CompoundTag(); row.putUUID("uuid", e.getKey()); row.putString("day", p.day); row.putInt("chests", p.chests); row.putLong("refund", p.refund);
            var cleared = new ListTag(); for (var c : p.cleared) cleared.add(StringTag.valueOf(c)); row.put("cleared", cleared);
            row.putString("returnLevel", p.returnLevel); row.putDouble("rx", p.rx); row.putDouble("ry", p.ry); row.putDouble("rz", p.rz); row.putFloat("ryRot", p.ryRot); row.putLong("run", p.run); row.putBoolean("watcher", p.watcher); row.put("pending", p.pending.copy());
            list.add(row);
        }
        tag.put("players", list);
        var runs = new ListTag();
        for (var r : this.runs.values()) {
            var row = new CompoundTag(); row.putLong("id", r.id()); row.putInt("slot", r.slot()); row.putString("dungeon", r.dungeon()); row.putString("difficulty", r.difficulty());
            var fees = new ListTag(); for (var f : r.fees().entrySet()) { var m = new CompoundTag(); m.putUUID("uuid", f.getKey()); m.putLong("fee", f.getValue()); fees.add(m); } row.put("fees", fees);
            runs.add(row);
        }
        tag.put("runs", runs);
        tag.putIntArray("dirtySlots", dirtySlots.stream().mapToInt(Integer::intValue).toArray());
        var best = new CompoundTag(); for (var e : this.best.entrySet()) { var b = new CompoundTag(); b.putLong("seconds", e.getValue().seconds()); b.putString("team", e.getValue().team()); best.put(e.getKey(), b); }
        tag.put("best", best);
        tag.putString("affix", affixOverride);
        var days = new CompoundTag(); for (var e : this.days.entrySet()) days.putLongArray(e.getKey(), e.getValue()); tag.put("days", days);
        tag.putLong("burned", burned); tag.putLong("started", started); tag.putLong("won", won); tag.putLong("failed", failed); tag.putLong("nextId", nextId); tag.putInt("version", 1);
        return tag;
    }
}
