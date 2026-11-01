package ru.animeoncraft.integration.neoforge;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Rift Gate stage 4: Nightmare and Mythic, weekly affixes, the Mythic timer and guild DUNGEON quests. */
class DungeonStage4Test {
    private static long at(String instant) { return Instant.parse(instant).toEpochMilli(); }

    @Test void affixesChangeOnMondayInYekaterinburgAndCycle() throws Exception {
        long sunday = DungeonRules.week(at("2026-11-01T18:59:00Z")), monday = DungeonRules.week(at("2026-11-01T19:00:00Z")); // 19:00 UTC = 00:00 YEKT
        assertEquals(sunday + 1, monday, "a new week at midnight Monday, Yekaterinburg");
        assertEquals(monday, DungeonRules.week(at("2026-11-08T18:59:00Z")), "the whole week shares one affix");
        var c = DungeonCatalog.builtIn(id -> true, id -> true, new ArrayList<>());
        assertEquals(List.of("BLOODSUCKERS", "RIFT", "SHADOWS", "NO_REGEN"), c.affixes().stream().map(DungeonCatalog.Affix::code).toList());
        var seen = new LinkedHashSet<String>(); for (long w = monday; w < monday + 4; w++) seen.add(c.affix(w).code());
        assertEquals(4, seen.size(), "four weeks show all four affixes"); assertEquals(c.affix(monday), c.affix(monday + 4));
        assertEquals(-1, DungeonRules.affix(5, 0));
        for (var f : c.difficulties()) assertEquals(f.code().equals("NIGHTMARE") || f.code().equals("MYTHIC"), f.affixes(), "affixes only on Nightmare and Mythic: " + f.code());
    }

    @Test void nightmareAndMythicFollowThePlan() throws Exception {
        var c = DungeonCatalog.builtIn(id -> true, id -> true, new ArrayList<>());
        var nightmare = c.difficulty("NIGHTMARE"); var mythic = c.difficulty("MYTHIC");
        assertEquals(3.0, nightmare.health()); assertEquals(2.0, nightmare.damage()); assertEquals(2, nightmare.minPlayers()); assertEquals(2, nightmare.lives()); assertTrue(nightmare.teamLives());
        assertEquals(5.0, mythic.health()); assertEquals(2.8, mythic.damage()); assertEquals(3, mythic.minPlayers()); assertEquals(0, mythic.lives(), "Mythic: a death makes a watcher");
        assertEquals(1200, mythic.timeLimit()); assertEquals(0, nightmare.timeLimit());
        assertNotNull(DungeonRules.teamCheck(List.of(5, 5), mythic.minRank(), mythic.minPlayers(), 4), "Mythic needs three");
        assertNull(DungeonRules.teamCheck(List.of(5, 5, 4), mythic.minRank(), mythic.minPlayers(), 4), "an A pair with a B");
        assertEquals(-1, DungeonRules.timeLeft(0, 999_999, 0)); assertEquals(1200, DungeonRules.timeLeft(1000, 1000, 1200));
        assertEquals(600, DungeonRules.timeLeft(0, 600_000, 1200)); assertEquals(0, DungeonRules.timeLeft(0, 1_300_000, 1200));
        assertEquals(Set.of("BLOODSUCKERS", "RIFT", "SHADOWS", "NO_REGEN"), DungeonCatalog.AFFIXES, "a catalog may only reorder or retune the four implemented affixes");
        assertThrows(IllegalArgumentException.class, () -> new DungeonCatalog.Affix("bad", "X", "y", 0));
    }

    @Test void guildDungeonQuestsMatchByDungeonAndLowestDifficulty() throws Exception {
        var order = List.of("NORMAL", "HARD", "NIGHTMARE", "MYTHIC");
        assertTrue(DungeonRules.questMatches("dungeon:any/normal", "sakura_crypt", "NORMAL", order));
        assertTrue(DungeonRules.questMatches("dungeon:any/nightmare", "sakura_crypt", "MYTHIC", order), "harder counts");
        assertFalse(DungeonRules.questMatches("dungeon:any/nightmare", "sakura_crypt", "HARD", order), "easier does not");
        assertTrue(DungeonRules.questMatches("dungeon:sakura_crypt/hard", "sakura_crypt", "HARD", order));
        assertFalse(DungeonRules.questMatches("dungeon:rift_forge/hard", "sakura_crypt", "HARD", order), "another dungeon");
        assertFalse(DungeonRules.questMatches("minecraft:zombie", "sakura_crypt", "HARD", order)); assertFalse(DungeonRules.questMatches("dungeon:any/legend", "sakura_crypt", "MYTHIC", order));
        try (var in = GuildCatalog.class.getResourceAsStream(GuildCatalog.DEFAULT)) {
            var skipped = new ArrayList<String>();
            var guild = GuildCatalog.parse(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject(), q -> true, skipped);
            var rift = guild.quests().stream().filter(q -> q.kind() == GuildCatalog.Kind.DUNGEON).toList();
            assertEquals(List.of("d_rift_first", "c_rift_sakura", "b_rift_nightmare", "a_rift_mythic"), rift.stream().map(GuildCatalog.Quest::code).toList());
            assertTrue(rift.stream().allMatch(q -> q.target().startsWith("dungeon:") && q.count() == 1 && q.min() > 0), "coins come from the guild ledger");
            assertEquals(2, guild.find("b_rift_nightmare").party()); assertEquals(3, guild.find("a_rift_mythic").party());
            assertTrue(guild.team(GuildRules.Rank.B).contains(guild.find("b_rift_nightmare")), "team pool of rank B");
            assertTrue(guild.rank(GuildRules.Rank.C).contains(guild.find("c_rift_sakura")), "solo board of rank C");
        }
        assertThrows(IllegalArgumentException.class, () -> new GuildCatalog.Quest("x_bad_item", GuildRules.Rank.B, GuildCatalog.Kind.ITEM, "minecraft:dirt", 1, "Т", "З", 1, 2, false, 2, false), "item quests stay solo");
    }
}
