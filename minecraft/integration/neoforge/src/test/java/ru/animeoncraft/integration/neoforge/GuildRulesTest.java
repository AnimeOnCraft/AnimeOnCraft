package ru.animeoncraft.integration.neoforge;

import com.google.gson.JsonParser;
import io.netty.buffer.Unpooled;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static ru.animeoncraft.integration.neoforge.GuildRules.Rank.*;

class GuildRulesTest {
    private static final long NOON = Instant.parse("2026-10-24T07:00:00Z").toEpochMilli();
    private static GuildCatalog.Quest quest(String code, GuildRules.Rank rank, GuildCatalog.Kind kind, int count, int min, int max) {
        return new GuildCatalog.Quest(code, rank, kind, kind == GuildCatalog.Kind.ITEM ? "minecraft:wheat" : "minecraft:zombie", count, "Тест", "Задача", min, max, false, 0, false);
    }

    @Test void ranksCooldownsAndXpFollowTheOwnersTable() {
        assertEquals(7, GuildRules.Rank.values().length);
        int minutes = 5; for (var r : GuildRules.Rank.values()) { assertEquals(r.ordinal() + 1, r.level); assertEquals(minutes, r.cooldownMinutes); minutes += 5; }
        assertEquals(1, GuildRules.xpFor(F, F)); assertEquals(3, GuildRules.xpFor(D, D));
        assertEquals(1, GuildRules.xpFor(E, F), "one rank below gives half, at least 1"); assertEquals(1, GuildRules.xpFor(D, E));
        assertEquals(0, GuildRules.xpFor(D, F), "farming far below gives no card XP"); assertEquals(0, GuildRules.xpFor(F, E));
        assertTrue(GuildRules.canRankUp(F, 10, false)); assertFalse(GuildRules.canRankUp(F, 9, false)); assertTrue(GuildRules.canRankUp(D, 60, false));
        assertFalse(GuildRules.canRankUp(C, 125, false), "B needs the exam"); assertTrue(GuildRules.canRankUp(C, 125, true));
        assertTrue(GuildRules.canRankUp(B, 240, false)); assertFalse(GuildRules.canRankUp(S, 100_000, true), "S is the top");
        assertEquals(0, GuildRules.dailyQuests(D)); assertEquals(8, GuildRules.dailyQuests(C)); assertEquals(8, GuildRules.dailyQuests(S));
    }

    @Test void payoutIsFullInsideCapsAndQuarterOverThem() {
        assertEquals(5, GuildRules.payout(5, F, 0, 0));
        assertEquals(2 + 3 * 25 / 100, GuildRules.payout(5, F, 98, 0));
        assertEquals(1, GuildRules.payout(5, F, 100, 0));
        assertEquals(1, GuildRules.payout(5, F, 0, GuildRules.economy.budget()), "server budget also caps");
        for (int seed = 0; seed < 200; seed++) { long r = GuildRules.roll(3, 5, seed); assertTrue(r >= 3 && r <= 5); }
    }

    @Test void coinsSplitAndFeesGiveChange() {
        assertArrayEquals(new long[]{0, 0, 1, 2, 3, 4}, GuildRules.coins(1234));
        assertEquals(1234, GuildRules.value(GuildRules.coins(1234)));
        assertEquals("1 изум. 2 зол. 3 жел. 4 мед.", GuildRules.money(1234)); assertEquals("0 мед.", GuildRules.money(0));
        assertNull(GuildRules.pay(new long[]{0, 0, 0, 0, 1, 9}, 20));
        var p = GuildRules.pay(new long[]{0, 0, 0, 0, 3, 5}, 20); assertEquals(20, GuildRules.value(p.take()) - p.change());
        var gold = GuildRules.pay(new long[]{0, 0, 0, 1, 0, 0}, 50); assertEquals(1, gold.take()[3]); assertEquals(50, gold.change());
        assertThrows(IllegalArgumentException.class, () -> GuildRules.coins(-1));
    }

    @Test void offersAreDistinctAndStableUntilSeedChanges() {
        for (long seed = 0; seed < 100; seed++) { var o = GuildRules.offers(14, seed, 0); assertEquals(3, new HashSet<>(o).size()); assertEquals(o, GuildRules.offers(14, seed, 0)); o.forEach(i -> assertTrue(i >= 0 && i < 14)); }
        assertEquals(2, GuildRules.offers(2, 7, 0).size()); assertEquals(List.of(), GuildRules.offers(0, 7, 0));
    }

    @Test void shippedCatalogParsesWithEnoughQuestsPerRank() throws Exception {
        var skipped = new ArrayList<String>(); var c = GuildCatalog.load(Path.of("does-not-exist"), q -> true, skipped);
        assertTrue(skipped.isEmpty()); assertTrue(c.rank(F).size() >= 10); assertTrue(c.rank(E).size() >= 10); assertTrue(c.rank(D).size() >= 10); assertTrue(c.rank(C).size() >= 10); assertTrue(c.rank(B).isEmpty(), "B board waits for team quests");
        for (var r : List.of(B, A, S)) { assertTrue(c.team(r).size() >= 4, r + " team pool"); for (var t : c.team(r)) { assertEquals(GuildRules.partyMin(r), t.party()); assertEquals(GuildCatalog.Kind.KILL, t.kind()); } }
        assertThrows(IllegalArgumentException.class, () -> new GuildCatalog.Quest("bad_team", D, GuildCatalog.Kind.KILL, "minecraft:zombie", 1, "a", "b", 1, 2, false, 2, false), "team quests start at B");
        assertTrue(c.exams().size() >= 3); for (var e : c.exams()) { assertEquals(B, e.rank()); assertEquals(0, e.max()); assertEquals(e, c.find(e.code())); }
        for (var q : c.quests()) { assertTrue(q.max() <= c.economy().caps()[q.rank().ordinal()] / 3, q.code() + " reward too big for the daily cap"); assertNotNull(c.find(q.code())); }
        assertEquals(GuildRules.Economy.DEFAULT.toString(), c.economy().toString(), "the shipped file spells out the default economy");
        var unknown = new ArrayList<String>(); var only = GuildCatalog.load(Path.of("does-not-exist"), q -> q.kind() == GuildCatalog.Kind.KILL, unknown);
        assertEquals(c.quests().size(), only.quests().size() + unknown.size());
        var dup = JsonParser.parseString("{\"version\":1,\"quests\":[{\"code\":\"aaa\",\"rank\":\"F\",\"kind\":\"ITEM\",\"target\":\"minecraft:wheat\",\"count\":1,\"name\":\"a\",\"task\":\"b\",\"reward\":[1,2]},"
            + "{\"code\":\"aaa\",\"rank\":\"F\",\"kind\":\"ITEM\",\"target\":\"minecraft:wheat\",\"count\":1,\"name\":\"a\",\"task\":\"b\",\"reward\":[1,2]}]}").getAsJsonObject();
        assertThrows(IllegalArgumentException.class, () -> GuildCatalog.parse(dup, q -> true, new ArrayList<>()));
        assertThrows(IllegalArgumentException.class, () -> quest("BAD CODE", F, GuildCatalog.Kind.ITEM, 1, 1, 2));
    }

    @Test void cardTakesFinishesAbandonsAndRanksUp() {
        var ledger = new GuildStore.Ledger(); var c = new GuildStore.Card(); var wheat = quest("wheat", F, GuildCatalog.Kind.ITEM, 32, 3, 5);
        assertEquals(GuildStore.Result.CLOSED, c.take(wheat, NOON));
        assertTrue(c.open(42)); assertFalse(c.open(7)); assertEquals(F, c.rank());
        assertEquals(GuildStore.Result.RANK, c.take(quest("eee", E, GuildCatalog.Kind.ITEM, 1, 10, 15), NOON));
        assertEquals(GuildStore.Result.OK, c.take(wheat, NOON)); assertEquals(GuildStore.Result.BUSY, c.take(wheat, NOON));
        long reward = c.reward; assertTrue(reward >= 3 && reward <= 5);
        long paid = c.finish(wheat, NOON, ledger); assertEquals(reward, paid); assertEquals(1, c.xp); assertEquals(paid, ledger.issued); assertEquals(1, c.completed);
        assertEquals(GuildStore.Result.COOLDOWN, c.take(wheat, NOON + 60_000)); assertEquals(5 * 60_000, c.cooldown(NOON));
        assertEquals(GuildStore.Result.OK, c.take(wheat, NOON + 5 * 60_000));
        long seed = c.seed; assertEquals(GuildStore.Result.OK, c.abandon(NOON + 5 * 60_000)); assertEquals(seed + 1, c.seed); assertEquals("", c.active); assertEquals(1, c.xp);
        var zombie = quest("zombie", F, GuildCatalog.Kind.KILL, 2, 3, 5); c.cooldownUntil = 0; c.take(zombie, NOON);
        c.kill(wheat); assertEquals(0, c.progress); c.kill(zombie); c.kill(zombie); c.kill(zombie); assertEquals(2, c.progress);
        assertEquals(GuildStore.Result.XP, c.canRankUp()); c.xp = 12; assertEquals(GuildStore.Result.OK, c.canRankUp());
        c.rankUp(ledger, NOON); assertEquals(E, c.rank()); assertEquals(2, c.xp); assertEquals(20, ledger.burned); assertEquals(20, ledger.totalBurned);
        c.rank = S.ordinal(); c.xp = 1000; assertEquals(GuildStore.Result.MAX_RANK, c.canRankUp());
    }

    @Test void rankCHasDailyLimitAndExamUnlocksB() {
        var ledger = new GuildStore.Ledger(); var c = new GuildStore.Card(); c.open(1); c.rank = C.ordinal(); c.reset(NOON);
        var pie = quest("c_pie", C, GuildCatalog.Kind.ITEM, 1, 34, 44);
        for (int i = 0; i < 8; i++) { c.cooldownUntil = 0; assertEquals(GuildStore.Result.OK, c.take(pie, NOON)); c.finish(pie, NOON, ledger); }
        assertEquals(8, c.doneToday); c.cooldownUntil = 0; assertEquals(GuildStore.Result.LIMIT, c.take(pie, NOON));
        assertEquals(GuildStore.Result.LIMIT, c.take(quest("f_low", F, GuildCatalog.Kind.ITEM, 1, 3, 5), NOON), "the limit counts every rank");
        assertTrue(c.reset(Instant.parse("2026-10-25T07:00:00Z").toEpochMilli())); assertEquals(0, c.doneToday);
        var exam = new GuildCatalog.Quest("exam_test", B, GuildCatalog.Kind.KILL, "minecraft:ravager", 1, "Экзамен", "Убить", 0, 0, true, 0, false);
        assertEquals(GuildStore.Result.RANK, c.take(exam, NOON), "an exam is never taken from the board");
        c.xp = 40; assertEquals(GuildStore.Result.XP, c.exam(exam)); assertEquals(GuildStore.Result.XP, c.canRankUp());
        c.xp = 125; assertEquals(GuildStore.Result.EXAM, c.canRankUp());
        c.cooldownUntil = NOON + 600_000; assertEquals(GuildStore.Result.OK, c.exam(exam), "exam ignores cooldown"); assertEquals(0, c.reward);
        c.kill(exam); long issued = ledger.issued; int completed = c.completed;
        assertEquals(0, c.finish(exam, NOON, ledger)); assertTrue(c.exam); assertEquals(issued, ledger.issued); assertEquals(completed, c.completed); assertEquals(125, c.xp);
        assertEquals(GuildStore.Result.OK, c.canRankUp()); c.rankUp(ledger, NOON); assertEquals(B, c.rank()); assertEquals(300, ledger.burned); assertFalse(c.exam);
        var store = new GuildStore(); var id = UUID.randomUUID(); var s = store.card(id); s.exam = true; s.doneToday = 5;
        var back = GuildStore.load(store.save(new CompoundTag(), null), null).existing(id); assertTrue(back.exam); assertEquals(5, back.doneToday);
    }

    @Test void teamsFormFromInvitesAndLeaderPassesOn() {
        var parties = new GuildParty(); UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID(), d = UUID.randomUUID(), e = UUID.randomUUID();
        assertEquals("Нельзя пригласить себя.", parties.invite(a, a, NOON));
        assertNull(parties.invite(a, b, NOON)); assertNull(parties.accept(c, NOON), "no invite");
        var party = parties.accept(b, NOON + 1000); assertNotNull(party); assertEquals(a, party.leader); assertEquals(List.of(a, b), party.members);
        assertNotNull(parties.invite(b, c, NOON), "only the leader invites"); assertNotNull(parties.invite(a, b, NOON), "already in a team");
        assertNull(parties.invite(a, c, NOON)); assertNull(parties.accept(c, NOON + GuildParty.INVITE_MILLIS + 1), "invite expired");
        assertNull(parties.invite(a, c, NOON)); parties.accept(c, NOON); assertNull(parties.invite(a, d, NOON)); parties.accept(d, NOON);
        assertEquals(4, party.members.size()); assertNotNull(parties.invite(a, e, NOON), "team of four is full");
        party.active = "b_zombie_horde"; party.progress = 5;
        assertSame(party, parties.leave(a)); assertEquals(b, party.leader); assertNull(parties.of(a)); assertEquals("b_zombie_horde", party.active);
        parties.leave(c); var last = parties.leave(d); assertSame(party, last); assertEquals(List.of(b), party.members); assertNull(parties.of(b), "a team of one dissolves"); assertEquals("", party.active);
    }

    @Test void teamWindowsSharesAndOncePerWindow() {
        long w = GuildRules.window(NOON); assertEquals(w, GuildRules.window(Instant.parse("2026-10-24T07:59:59Z").toEpochMilli()), "12:00–13:59 Ekaterinburg is one window");
        assertEquals(w + 1, GuildRules.window(Instant.parse("2026-10-24T09:00:00Z").toEpochMilli()), "14:00 Ekaterinburg starts the next");
        assertEquals(Instant.parse("2026-10-24T09:00:00Z").toEpochMilli(), GuildRules.windowEnds(NOON));
        assertEquals(2, GuildRules.partyMin(B)); assertEquals(3, GuildRules.partyMin(A)); assertEquals(4, GuildRules.partyMin(S));
        assertEquals(90, GuildRules.teamShare(90, A, B)); assertEquals(45, GuildRules.teamShare(90, C, B)); assertEquals(0, GuildRules.teamShare(90, D, B));
        assertEquals(8, GuildRules.teamXp(B, B)); assertEquals(4, GuildRules.teamXp(C, B)); assertEquals(0, GuildRules.teamXp(D, B));
        var set = GuildRules.offers(6, w, B.ordinal(), GuildRules.TEAM_PER_RANK); assertEquals(2, new HashSet<>(set).size()); assertEquals(set, GuildRules.offers(6, w, B.ordinal(), GuildRules.TEAM_PER_RANK), "same set for everyone");
        var horde = new GuildCatalog.Quest("b_horde", B, GuildCatalog.Kind.KILL, "minecraft:zombie", 40, "Орда", "Убить", 80, 100, false, 2, false);
        var ledger = new GuildStore.Ledger(); var lead = new GuildStore.Card(); lead.open(1); lead.rank = B.ordinal(); var low = new GuildStore.Card(); low.open(2); low.rank = C.ordinal();
        assertEquals(90, lead.teamFinish(horde, w, GuildRules.teamShare(90, lead.rank(), B), NOON, ledger)); assertEquals(45, low.teamFinish(horde, w, GuildRules.teamShare(90, low.rank(), B), NOON, ledger));
        assertEquals(8, lead.xp); assertEquals(4, low.xp); assertEquals(135, ledger.issued); assertEquals(0, lead.doneToday, "team quests do not use the daily limit"); assertEquals(0, lead.cooldownUntil);
        assertTrue(lead.teamDone(w, "b_horde")); assertFalse(lead.teamDone(w + 1, "b_horde"));
        lead.teamFinish(horde, w + 20, 0, NOON, ledger); assertFalse(lead.teamDone(w, "b_horde"), "old windows are pruned");
        var store = new GuildStore(); var id = UUID.randomUUID(); store.card(id).team.add(w + ":b_horde");
        assertTrue(GuildStore.load(store.save(new CompoundTag(), null), null).existing(id).teamDone(w, "b_horde"));
    }

    @Test void dailyEarningsResetAtEkaterinburgMidnight() {
        var c = new GuildStore.Card(); c.reset(NOON); c.earned = 99;
        assertFalse(c.reset(NOON + 3_600_000)); assertEquals(99, c.earned);
        assertTrue(c.reset(Instant.parse("2026-10-24T19:00:01Z").toEpochMilli()), "00:00:01 in Ekaterinburg (UTC+5) is a new day"); assertEquals(0, c.earned);
    }

    @Test void storeRoundTripsThroughNbtAndPacketsStayValid() {
        var store = new GuildStore(); var id = UUID.randomUUID(); var c = store.card(id); c.open(9); c.rank = 2; c.xp = 17; c.completed = 40; c.active = "wheat"; c.reward = 4; c.earned = 77; c.day = "2026-10-24"; c.cooldownUntil = 123;
        store.ledger.issued = 500; store.ledger.totalBurned = 70;
        var copy = GuildStore.load(store.save(new CompoundTag(), null), null); var back = copy.existing(id);
        assertTrue(back.opened); assertEquals(2, back.rank); assertEquals(17, back.xp); assertEquals(40, back.completed); assertEquals("wheat", back.active); assertEquals(4, back.reward); assertEquals(77, back.earned); assertEquals(123, back.cooldownUntil);
        assertEquals(500, copy.ledger.issued); assertEquals(70, copy.ledger.totalBurned);
        var q = new GuildPayloads.Quest("wheat", 0, false, "minecraft:wheat", 32, "Урожай", "Принеси пшеницу", 3, 5, 1, 2, true);
        var snap = new GuildPayloads.Snapshot(true, 3, 3, 125, 4, 120, 12, 300, false, 300, false, 6, 8, 1, List.of(q, q, q), q, 7, GuildPayloads.SCREEN_BOARD, "Готово!",
            List.of(q), List.of("★ Лидер · B", "Друг · C"), true, q, 3, 5400, 5, 10);
        var buf = new FriendlyByteBuf(Unpooled.buffer()); GuildPayloads.Snapshot.CODEC.encode(buf, snap); assertEquals(snap, GuildPayloads.Snapshot.CODEC.decode(buf));
        assertThrows(IllegalArgumentException.class, () -> new GuildPayloads.Snapshot(true, 3, 3, 125, 4, 120, 12, 300, false, 300, false, 6, 8, 3, List.of(), null, 0, 0, "", List.of(), List.of(), false, null, 0, 0, 0, 0));
        for (var action : GuildPayloads.ACTIONS) { var req = new GuildPayloads.Request(action, 1, 1); buf = new FriendlyByteBuf(Unpooled.buffer()); GuildPayloads.Request.CODEC.encode(buf, req); assertEquals(req, GuildPayloads.Request.CODEC.decode(buf), action); }
        assertThrows(IllegalArgumentException.class, () -> new GuildPayloads.Request("DELETE", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new GuildPayloads.Request("TAKE", 0, 3));
    }

    @Test void bossesOfTheModsComeOnePerTeamSet() throws Exception {
        var c = GuildCatalog.load(Path.of("does-not-exist"), q -> true, new ArrayList<>());
        assertTrue(c.team(B).stream().noneMatch(GuildCatalog.Quest::boss), "B has hordes only");
        for (var r : List.of(A, S)) {
            var bosses = c.team(r).stream().filter(GuildCatalog.Quest::boss).toList(); assertTrue(bosses.size() >= 8, r + " bosses");
            assertTrue(bosses.stream().anyMatch(q -> q.tag() && q.target().startsWith("#animeoncraft:guild_bosses_")), r + " has an any-boss quest");
            var seen = new HashSet<String>();
            for (long w = 0; w < 400; w++) {
                var set = c.teamSet(w, r); assertEquals(2, set.size()); assertFalse(set.get(0).boss()); assertTrue(set.get(1).boss()); assertEquals(set, c.teamSet(w, r)); seen.add(set.get(1).code());
            }
            assertEquals(bosses.size(), seen.size(), r + ": every boss comes up sooner or later");
        }
        for (long w = 0; w < 50; w++) assertTrue(c.teamSet(w, B).stream().noneMatch(GuildCatalog.Quest::boss));
        assertThrows(IllegalArgumentException.class, () -> new GuildCatalog.Quest("bad_boss", A, GuildCatalog.Kind.KILL, "minecraft:zombie", 5, "a", "b", 1, 2, false, 3, true), "a boss quest is one kill");
        assertThrows(IllegalArgumentException.class, () -> new GuildCatalog.Quest("solo_boss", C, GuildCatalog.Kind.KILL, "minecraft:wither", 1, "a", "b", 1, 2, false, 0, true), "bosses are team quests");
    }

    @Test void economyIsTunedFromTheQuestFileWithoutANewBuild() {
        var shipped = GuildCatalog.economy(JsonParser.parseString("{}").getAsJsonObject());
        assertArrayEquals(GuildRules.Economy.DEFAULT.caps(), shipped.caps()); assertEquals(3000, shipped.budget()); assertEquals(8, shipped.dailyQuests()); assertEquals(5, shipped.rerollFee()); assertEquals(10, shipped.teamEntryPercent());
        var tuned = GuildCatalog.economy(JsonParser.parseString("{\"dailyCap\": {\"F\": 60, \"S\": 900}, \"rankFee\": {\"B\": 500}, \"serverBudget\": 2000, \"overCapPercent\": 10, \"dailyQuests\": 6}").getAsJsonObject());
        assertEquals(60, tuned.caps()[F.ordinal()]); assertEquals(150, tuned.caps()[E.ordinal()], "keys left out keep the default"); assertEquals(900, tuned.caps()[S.ordinal()]);
        assertEquals(500, tuned.fees()[B.ordinal()]); assertEquals(800, tuned.fees()[A.ordinal()]);
        assertThrows(IllegalArgumentException.class, () -> GuildCatalog.economy(JsonParser.parseString("{\"overCapPercent\": 150}").getAsJsonObject()));
        assertThrows(IllegalArgumentException.class, () -> GuildCatalog.economy(JsonParser.parseString("{\"dailyCap\": {\"Z\": 1}}").getAsJsonObject()));
        try {
            GuildRules.economy = tuned;
            assertEquals(60 + 4, GuildRules.payout(100, F, 0, 0), "60 in the cap, 10% of the 40 over it");
            assertEquals(6, GuildRules.dailyQuests(C)); assertEquals(500, GuildRules.fee(B));
            assertEquals(1, GuildRules.payout(10, F, 0, 2000), "budget spent: the smaller share of the two");
            GuildRules.economy = GuildCatalog.economy(JsonParser.parseString("{\"overBudgetPercent\": 0}").getAsJsonObject());
            assertEquals(0, GuildRules.payout(10, F, 0, 3000), "a hard server budget pays nothing more today"); assertEquals(3, GuildRules.payout(10, F, 0, 2997));
            assertEquals(10 + 5 * 25 / 100, GuildRules.payout(15, F, 90, 0), "the player's cap still pays its share");
            GuildRules.economy = tuned;
            var ledger = new GuildStore.Ledger(); var c = new GuildStore.Card(); c.open(1); c.rank = A.ordinal(); c.xp = 480;
            c.rankUp(ledger, NOON); assertEquals(S, c.rank()); assertEquals(GuildRules.fee(S), ledger.burned);
        } finally { GuildRules.economy = GuildRules.Economy.DEFAULT; }
    }

    @Test void newBoardAndTeamEntryBurnCoins() throws Exception {
        assertEquals(46, GuildRules.teamEntry(230, 2), "10% of the reward for each member"); assertEquals(1, GuildRules.teamEntry(3, 2), "rounded up");
        try { GuildRules.economy = GuildCatalog.economy(JsonParser.parseString("{\"rerollFee\": 0, \"teamEntryPercent\": 0}").getAsJsonObject()); assertEquals(0, GuildRules.teamEntry(230, 4)); assertEquals(0, GuildRules.economy.rerollFee()); }
        finally { GuildRules.economy = GuildRules.Economy.DEFAULT; }
        assertThrows(IllegalArgumentException.class, () -> GuildCatalog.economy(JsonParser.parseString("{\"teamEntryPercent\": 101}").getAsJsonObject()));
        var ledger = new GuildStore.Ledger(); ledger.sink(5); ledger.sink(46);
        assertEquals(51, ledger.burned); assertEquals(51, ledger.totalBurned); assertEquals(0, ledger.rankUps, "sinks are not rank-ups");
        for (int rank = -1; rank <= 6; rank++) { var badge = new GuildPayloads.Badge(UUID.randomUUID(), rank); var buf = new FriendlyByteBuf(Unpooled.buffer()); GuildPayloads.Badge.CODEC.encode(buf, badge); assertEquals(badge, GuildPayloads.Badge.CODEC.decode(buf)); }
        assertThrows(IllegalArgumentException.class, () -> new GuildPayloads.Badge(UUID.randomUUID(), 7));
        assertEquals(4, ServerMenuGuide.GUILD.size());
        var c = GuildCatalog.load(Path.of("does-not-exist"), q -> true, new ArrayList<>()); var sizes = new int[4]; for (int r = 0; r < 4; r++) sizes[r] = c.rank(GuildRules.Rank.of(r)).size();
        for (long seed = 0; seed < 200; seed++) {
            long next = GuildRules.freshSeed(seed, sizes); assertTrue(next > seed);
            for (int r = 0; r < 4; r++) { var old = GuildRules.offers(sizes[r], seed, r); for (var i : GuildRules.offers(sizes[r], next, r)) assertFalse(old.contains(i), "a paid new board repeats no quest, rank " + r + " seed " + seed); }
        }
    }

    @Test void ledgerKeepsDailyHistoryForTuning() {
        var store = new GuildStore(); var ledger = store.ledger; var c = store.card(UUID.randomUUID()); c.open(1);
        var wheat = quest("wheat", F, GuildCatalog.Kind.ITEM, 32, 3, 5); long day = 86_400_000L;
        for (int d = 0; d < 35; d++) {
            long now = NOON + d * day; c.cooldownUntil = 0; c.reset(now); c.earned = 99;
            assertEquals(GuildStore.Result.OK, c.take(wheat, now)); long reward = c.reward; long paid = c.finish(wheat, now, ledger); ledger.player(UUID.randomUUID());
            assertTrue(paid < reward); assertEquals(reward - paid, ledger.withheld, "the cap kept the rest back");
        }
        assertEquals(GuildStore.Ledger.HISTORY, ledger.history.size(), "only the last 30 closed days are kept");
        assertEquals(GuildRules.day(NOON + 33 * day), ledger.history.peekFirst().day()); assertEquals(1, ledger.history.peekFirst().solo()); assertEquals(1, ledger.history.peekFirst().players());
        assertEquals(7, ledger.last(7).size()); assertEquals(ledger.day, ledger.last(7).get(0).day());
        ledger.burn(300); ledger.byRank[B.ordinal()] = 77;
        var copy = GuildStore.load(store.save(new CompoundTag(), null), null).ledger;
        assertEquals(ledger.day, copy.day); assertEquals(300, copy.burned); assertEquals(1, copy.rankUps); assertEquals(77, copy.byRank[B.ordinal()]); assertEquals(1, copy.players.size());
        assertEquals(GuildStore.Ledger.HISTORY, copy.history.size()); assertEquals(ledger.history.peekLast().day(), copy.history.peekLast().day()); assertEquals(ledger.totalIssued, copy.totalIssued);
        var report = GuildServer.economyReport(store, 7); assertEquals(4, report.size()); assertTrue(report.get(1).startsWith("За 7 дн."), report.get(1));
        assertEquals(14, GuildServer.economyDays(store).size());
    }
}
