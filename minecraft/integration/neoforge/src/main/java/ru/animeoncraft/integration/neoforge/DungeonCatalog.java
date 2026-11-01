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
 * Dungeons, difficulties, multipliers, mobs and loot of the Rift Gate. The default ships in the mod; a server may
 * override it with {@code <world>/serverconfig/animeoncraft-dungeons.json} and {@code /aocdungeon reload}.
 * Unknown mobs and items are dropped with a note in the log, never fatal.
 */
final class DungeonCatalog {
    /** minRank: guild rank ordinal for full access; lives per player, or for the whole team when teamLives. */
    record Difficulty(String code, String name, int minRank, int minPlayers, int fee, int lossPercent, double health, double damage,
                      int lives, boolean teamLives, int crystals, int essenceMin, int essenceMax, int rolls, int wave, int timeLimit, boolean affixes) {
        Difficulty {
            if (!code.matches("[A-Z]{3,16}") || name.isBlank() || name.length() > 24 || minRank < 0 || minRank > 6 || minPlayers < 1 || minPlayers > 4
                || fee < 0 || fee > 1_000_000 || lossPercent < 0 || lossPercent > 100 || health < 0.1 || health > 50 || damage < 0.1 || damage > 50
                || lives < 0 || lives > 20 || crystals < 0 || crystals > 1000 || essenceMin < 0 || essenceMax < essenceMin || essenceMax > 64 || rolls < 1 || rolls > 12 || wave < 1 || wave > 12 || timeLimit < 0 || timeLimit > 7200)
                throw new IllegalArgumentException("Invalid dungeon difficulty " + code);
        }
    }
    /** A weekly affix of Nightmare and Mythic; value: its strength (heal %, explosion power ×10, visibility radius, unused). */
    record Affix(String code, String name, String description, int value) {
        Affix { if (!code.matches("[A-Z_]{3,24}") || name.isBlank() || name.length() > 24 || description.length() > 64 || value < 0 || value > 100) throw new IllegalArgumentException("Invalid dungeon affix " + code); }
    }
    static final Set<String> AFFIXES = Set.of("BLOODSUCKERS", "RIFT", "SHADOWS", "NO_REGEN");
    /** A named mob of the run; health/damage 0 keep the mob's own value (mod bosses). */
    record Special(String type, String name, int health, int damage, String summon, int summonSeconds) {
        Special {
            if (!type.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+") || name.isBlank() || name.length() > 32 || health < 0 || health > 100_000 || damage < 0 || damage > 1000
                || summon != null && !summon.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+") || summonSeconds < 0 || summonSeconds > 600) throw new IllegalArgumentException("Invalid dungeon mob " + type);
        }
    }
    /** enchant > 0: the item (a book) is enchanted as at a table of that level. */
    record Loot(String item, int min, int max, int weight, int enchant) {
        Loot { if (!item.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+") || min < 1 || max < min || max > 64 || weight < 1 || weight > 1000 || enchant < 0 || enchant > 30) throw new IllegalArgumentException("Invalid dungeon loot " + item); }
    }
    record Dungeon(String code, String name, String theme, String description, boolean open, int rooms, int parSeconds, List<String> mobs,
                   Special elite, Special boss, Map<String, Special> bossFrom, Map<String, List<Loot>> loot) {
        Dungeon {
            mobs = List.copyOf(mobs); bossFrom = Map.copyOf(bossFrom); loot = Map.copyOf(loot);
            if (!code.matches("[a-z0-9_]{3,32}") || name.isBlank() || name.length() > 32 || theme.length() > 16 || description.length() > 48 || rooms < 3 || rooms > 7 || parSeconds < 60 || parSeconds > 7200
                || open && (mobs.isEmpty() || elite == null || boss == null)) throw new IllegalArgumentException("Invalid dungeon " + code);
        }
        /** The boss of a difficulty: the strongest override at or below it, else the base boss. */
        Special bossFor(List<Difficulty> order, String difficulty) {
            Special found = boss;
            for (var d : order) { if (bossFrom.containsKey(d.code())) found = bossFrom.get(d.code()); if (d.code().equals(difficulty)) break; }
            return found;
        }
    }
    record Catalog(int dailyChests, int reconnectSeconds, int victorySeconds, double teamHealthPerPlayer, int maxPlayers, List<Difficulty> difficulties, List<Dungeon> dungeons, List<Affix> affixes) {
        Catalog {
            difficulties = List.copyOf(difficulties); dungeons = List.copyOf(dungeons); affixes = List.copyOf(affixes);
            if (dailyChests < 0 || dailyChests > 100 || reconnectSeconds < 10 || reconnectSeconds > 3600 || victorySeconds < 5 || victorySeconds > 600 || teamHealthPerPlayer < 0 || teamHealthPerPlayer > 5
                || maxPlayers < 1 || maxPlayers > GuildRules.MAX_PARTY || difficulties.isEmpty() || difficulties.size() > 6 || dungeons.size() > 8) throw new IllegalArgumentException("Invalid dungeon catalog");
        }
        Dungeon dungeon(String code) { return dungeons.stream().filter(d -> d.code().equals(code)).findFirst().orElse(null); }
        Difficulty difficulty(String code) { return difficulties.stream().filter(d -> d.code().equals(code)).findFirst().orElse(null); }
        List<String> order() { return difficulties.stream().map(Difficulty::code).toList(); }
        /** The affix of the week, or null when the catalog has none. */
        Affix affix(long week) { int i = DungeonRules.affix(week, affixes.size()); return i < 0 ? null : affixes.get(i); }
        int index(String difficulty) { for (int i = 0; i < difficulties.size(); i++) if (difficulties.get(i).code().equals(difficulty)) return i; return -1; }
    }
    static final String DEFAULT = "/animeoncraft/dungeons.json", OVERRIDE = "serverconfig/animeoncraft-dungeons.json";
    private DungeonCatalog() {}

    private static Special special(JsonObject o) {
        if (o == null) return null;
        return new Special(o.get("type").getAsString(), o.get("name").getAsString(), o.has("health") ? o.get("health").getAsInt() : 0, o.has("damage") ? o.get("damage").getAsInt() : 0,
            o.has("summon") ? o.get("summon").getAsString() : null, o.has("summonSeconds") ? o.get("summonSeconds").getAsInt() : 0);
    }
    /** entity/item: whether the registries know the id; unknown mobs, bosses and loot rows are skipped and listed. */
    static Catalog parse(JsonObject root, Predicate<String> entity, Predicate<String> item, List<String> skipped) {
        if (root.get("version").getAsInt() != 1) throw new IllegalArgumentException("Unsupported dungeon catalog version");
        var difficulties = new ArrayList<Difficulty>(); var codes = new HashSet<String>();
        for (var e : root.getAsJsonArray("difficulties")) {
            var o = e.getAsJsonObject(); var essence = o.getAsJsonArray("essence");
            var d = new Difficulty(o.get("code").getAsString(), o.get("name").getAsString(), GuildRules.Rank.valueOf(o.get("minRank").getAsString()).ordinal(), o.get("minPlayers").getAsInt(),
                o.get("fee").getAsInt(), o.get("lossPercent").getAsInt(), o.get("health").getAsDouble(), o.get("damage").getAsDouble(), o.get("lives").getAsInt(), o.get("teamLives").getAsBoolean(),
                o.get("crystals").getAsInt(), essence.get(0).getAsInt(), essence.get(1).getAsInt(), o.get("rolls").getAsInt(), o.get("wave").getAsInt(),
                o.has("timeLimitSeconds") ? o.get("timeLimitSeconds").getAsInt() : 0, o.has("affixes") && o.get("affixes").getAsBoolean());
            if (!codes.add(d.code())) throw new IllegalArgumentException("Duplicate difficulty " + d.code());
            difficulties.add(d);
        }
        var dungeons = new ArrayList<Dungeon>(); var names = new HashSet<String>();
        for (var e : root.getAsJsonArray("dungeons")) {
            var o = e.getAsJsonObject(); String code = o.get("code").getAsString();
            var mobs = new ArrayList<String>(); for (var m : o.getAsJsonArray("mobs")) if (entity.test(m.getAsString())) mobs.add(m.getAsString()); else skipped.add(code + " mob " + m.getAsString());
            var elite = special(o.has("elite") ? o.getAsJsonObject("elite") : null); if (elite != null && !entity.test(elite.type())) { skipped.add(code + " elite " + elite.type()); elite = null; }
            var boss = special(o.has("boss") ? o.getAsJsonObject("boss") : null); if (boss != null && !entity.test(boss.type())) { skipped.add(code + " boss " + boss.type()); boss = null; }
            var bossFrom = new HashMap<String, Special>();
            if (o.has("bossFrom")) for (var b : o.getAsJsonObject("bossFrom").entrySet()) {
                if (!codes.contains(b.getKey())) throw new IllegalArgumentException("Unknown difficulty " + b.getKey());
                var s = special(b.getValue().getAsJsonObject()); if (entity.test(s.type())) bossFrom.put(b.getKey(), s); else skipped.add(code + " boss " + s.type());
            }
            var loot = new HashMap<String, List<Loot>>();
            if (o.has("loot")) for (var l : o.getAsJsonObject("loot").entrySet()) {
                if (!codes.contains(l.getKey())) throw new IllegalArgumentException("Unknown difficulty " + l.getKey());
                var rows = new ArrayList<Loot>();
                for (var r : l.getValue().getAsJsonArray()) {
                    var row = r.getAsJsonObject();
                    var entry = new Loot(row.get("item").getAsString(), row.get("min").getAsInt(), row.get("max").getAsInt(), row.get("weight").getAsInt(), row.has("enchant") ? row.get("enchant").getAsInt() : 0);
                    if (item.test(entry.item())) rows.add(entry); else skipped.add(code + " loot " + entry.item());
                }
                loot.put(l.getKey(), rows);
            }
            boolean open = o.get("open").getAsBoolean() && !mobs.isEmpty() && elite != null && boss != null;
            if (o.get("open").getAsBoolean() && !open) skipped.add(code + " closed: no mobs, elite or boss");
            var d = new Dungeon(code, o.get("name").getAsString(), o.get("theme").getAsString(), o.has("description") ? o.get("description").getAsString() : "", open,
                o.get("rooms").getAsInt(), o.get("parSeconds").getAsInt(), mobs, elite, boss, bossFrom, loot);
            if (!names.add(code)) throw new IllegalArgumentException("Duplicate dungeon " + code);
            dungeons.add(d);
        }
        var affixes = new ArrayList<Affix>();
        if (root.has("affixes")) for (var e : root.getAsJsonArray("affixes")) {
            var o = e.getAsJsonObject(); var a = new Affix(o.get("code").getAsString(), o.get("name").getAsString(), o.get("description").getAsString(), o.has("value") ? o.get("value").getAsInt() : 0);
            if (!AFFIXES.contains(a.code())) throw new IllegalArgumentException("Unknown dungeon affix " + a.code());
            affixes.add(a);
        }
        return new Catalog(root.get("dailyChests").getAsInt(), root.get("reconnectSeconds").getAsInt(), root.get("victorySeconds").getAsInt(),
            root.get("teamHealthPerPlayer").getAsDouble(), root.get("maxPlayers").getAsInt(), difficulties, dungeons, affixes);
    }
    static Catalog load(Path world, Predicate<String> entity, Predicate<String> item, List<String> skipped) throws IOException {
        var file = world.resolve(OVERRIDE);
        if (Files.isRegularFile(file)) {
            if (Files.size(file) > 262_144) throw new IOException("Dungeon catalog too large");
            return parse(JsonParser.parseString(Files.readString(file)).getAsJsonObject(), entity, item, skipped);
        }
        return builtIn(entity, item, skipped);
    }
    static Catalog builtIn(Predicate<String> entity, Predicate<String> item, List<String> skipped) throws IOException {
        try (var in = DungeonCatalog.class.getResourceAsStream(DEFAULT)) {
            if (in == null) throw new IOException("Missing " + DEFAULT);
            return parse(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject(), entity, item, skipped);
        }
    }
    /** Weighted rolls from a table, deterministic for a seed: (item, count, enchant) rows. */
    static List<Loot> roll(List<Loot> table, int rolls, long seed) {
        var out = new ArrayList<Loot>(); if (table.isEmpty()) return out;
        int total = 0; for (var l : table) total += l.weight();
        var random = new SplittableRandom(seed);
        for (int i = 0; i < rolls; i++) {
            int pick = random.nextInt(total);
            for (var l : table) { pick -= l.weight(); if (pick < 0) { int n = l.min() + random.nextInt(l.max() - l.min() + 1); out.add(new Loot(l.item(), n, n, 1, l.enchant())); break; } }
        }
        return out;
    }
}
