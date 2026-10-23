package ru.animeoncraft.integration.neoforge;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** World Design stage 4: the capture battle rules of docs/CAPTURE_SYSTEM.md, second by second. */
class CaptureEngineTest {
    // The shipped capture-rules.toml values.
    static final CaptureEngine.Settings RULES = new CaptureEngine.Settings(0.7, 1.15, 1.25, 360, 600, 3, 2, 60, 5, 50, 10, 180);
    static final long START = 1_800_000_000L, END = START + 900;

    @BeforeEach void clans() { ClanCodes.register(List.of("SCAM", "NYA", "AKUMA", "ZXC", "YAKUZA")); }
    @AfterEach void clear() { ClanCodes.register(List.of()); }

    private static CaptureEngine battle(String owner, boolean controlled) { return new CaptureEngine("e", owner, controlled, END, RULES); }
    private static void run(CaptureEngine engine, Map<String, Integer> present, long from, int seconds) {
        for (int i = 0; i < seconds; i++) engine.second(present, from + i);
    }

    @Test void oneUncontestedPlayerCapturesInSixMinutes() {
        var engine = battle(null, false);
        run(engine, Map.of("SCAM", 1), START, 359);
        assertEquals(CaptureEngine.Status.RUNNING, engine.status());
        assertTrue(engine.progress("SCAM") > 99.5);
        assertEquals(CaptureEngine.Status.WON, engine.second(Map.of("SCAM", 1), START + 359));
        assertEquals("SCAM", engine.winner());
        assertEquals(360, engine.scores().get("SCAM").presenceSeconds);
        assertEquals(CaptureEngine.Status.WON, engine.second(Map.of("NYA", 8), START + 400), "a finished battle no longer changes");
    }

    @Test void strengthHasDiminishingReturnsAndOwnerBonus() {
        var engine = battle("NYA", true);
        assertEquals(1.0, engine.strength("SCAM", 1), 1e-9);
        assertEquals(1.62, engine.strength("SCAM", 2), 0.005);
        assertEquals(3.09, engine.strength("SCAM", 5), 0.005);
        assertEquals(4.29, engine.strength("SCAM", 8), 0.005);
        assertEquals(1.15, engine.strength("NYA", 1), 1e-9);
        assertEquals(1.0, battle("NYA", false).strength("NYA", 1), 1e-9, "no bonus while AVAILABLE");
    }

    @Test void equalForcesLockEveryGauge() {
        var engine = battle(null, false);
        run(engine, Map.of("SCAM", 1), START, 60);
        double before = engine.progress("SCAM");
        run(engine, Map.of("SCAM", 2, "NYA", 2), START + 60, 120);
        assertTrue(engine.locked());
        assertEquals(before, engine.progress("SCAM"), 1e-9);
        assertEquals(0, engine.progress("NYA"), 1e-9);
        // Owner bonus 1.15 is below the 1.25 dominance ratio: one defender locks one attacker.
        var held = battle("NYA", true);
        run(held, Map.of("SCAM", 1, "NYA", 1), START, 30);
        assertTrue(held.locked());
        assertEquals(0, held.progress("SCAM"), 1e-9);
    }

    @Test void threeClansDominanceLockAndRelease() {
        var engine = battle(null, false);
        // SCAM 3 players (2.16) dominates NYA 1 and AKUMA 1 but grows slower than alone.
        engine.second(Map.of("SCAM", 3, "NYA", 1, "AKUMA", 1), START);
        assertEquals((100.0 / 360) * (1 - 1 / Math.pow(3, 0.7)), engine.progress("SCAM"), 1e-9);
        run(engine, Map.of("SCAM", 3, "NYA", 1, "AKUMA", 1), START + 1, 99);
        double scam = engine.progress("SCAM");
        // Two more NYA: 3 against 3 is a lock; two against three would not be (2.16 ≥ 1.25 × 1.62).
        engine.second(Map.of("SCAM", 3, "NYA", 2, "AKUMA", 1), START + 100);
        assertFalse(engine.locked());
        scam = engine.progress("SCAM");
        run(engine, Map.of("SCAM", 3, "NYA", 3, "AKUMA", 1), START + 101, 59);
        assertTrue(engine.locked());
        assertEquals(scam, engine.progress("SCAM"), 1e-9);
        // NYA leaves: the lock is released and SCAM continues.
        run(engine, Map.of("SCAM", 3, "AKUMA", 1), START + 160, 10);
        assertFalse(engine.locked());
        assertTrue(engine.progress("SCAM") > scam);
    }

    @Test void lateSinglePlayerCannotSnatchAnAlmostFinishedCapture() {
        var engine = battle(null, false);
        run(engine, Map.of("SCAM", 1), START, 340);
        double almost = engine.progress("SCAM");
        assertTrue(almost > 94);
        run(engine, Map.of("SCAM", 1, "NYA", 1), START + 340, 60);
        assertEquals(almost, engine.progress("SCAM"), 1e-9, "a late arrival only locks");
        assertEquals(0, engine.progress("NYA"), 1e-9);
        // Even a large late group needs minutes: SCAM decays at 100/600 per second while NYA grows from zero.
        run(engine, Map.of("SCAM", 1, "NYA", 8), START + 400, 20);
        assertEquals(CaptureEngine.Status.RUNNING, engine.status());
        assertTrue(engine.progress("NYA") < 5 && engine.progress("SCAM") > 90);
    }

    @Test void killLimitsSurviveRestart() {
        var engine=battle(null,false);var victim=UUID.randomUUID();
        assertTrue(engine.kill("SCAM","NYA",victim,START+10));
        var resumed=battle(null,false);resumed.restore(engine.scoresJson());resumed.restoreKillLimits(engine.killLimitsJson());
        assertFalse(resumed.kill("SCAM","NYA",victim,START+20));
        for(int i=1;i<5;i++)assertTrue(resumed.kill("SCAM","NYA",victim,START+10+60L*i));
        var again=battle(null,false);again.restore(resumed.scoresJson());again.restoreKillLimits(resumed.killLimitsJson());
        assertFalse(again.kill("SCAM","NYA",victim,START+400));
    }
    @Test void killsCountWithRepeatAndPerVictimLimits() {
        var engine = battle(null, false);
        run(engine, Map.of("NYA", 1), START, 36); // NYA 10 %
        UUID victim = UUID.randomUUID();
        assertTrue(engine.kill("SCAM", "NYA", victim, START + 40));
        assertEquals(3, engine.progress("SCAM"), 1e-9);
        assertEquals(8, engine.progress("NYA"), 1e-6);
        assertFalse(engine.kill("SCAM", "NYA", victim, START + 70), "same victim within 60 s");
        assertFalse(engine.kill("SCAM", "SCAM", UUID.randomUUID(), START + 70), "friendly kill");
        for (int i = 1; i < 5; i++) assertTrue(engine.kill("SCAM", "NYA", victim, START + 40 + 60L * i));
        assertFalse(engine.kill("SCAM", "NYA", victim, START + 40 + 60L * 6), "at most five kills of one victim per event");
        assertTrue(engine.kill("SCAM", "NYA", UUID.randomUUID(), START + 500));
        assertEquals(6, engine.scores().get("SCAM").kills);
        assertEquals(6, engine.scores().get("NYA").deaths);
    }

    @Test void timeoutWinOvertimeAndNoWinner() {
        var clear = battle(null, false);
        run(clear, Map.of("SCAM", 1), START, 216); // 60 %
        clear.second(Map.of(), END - 1);
        assertEquals(CaptureEngine.Status.WON, clear.second(Map.of(), END), "≥ 50 % and more than 10 points ahead at the end");
        assertEquals("SCAM", clear.winner());

        // 55 vs 50 at the end: overtime.
        var exact = battle(null, false);
        var rows = new com.google.gson.JsonArray();
        for (var pair : List.of(List.of("SCAM", 55.0), List.of("NYA", 50.0))) {
            var row = new com.google.gson.JsonObject();
            row.addProperty("clan_code", (String) pair.get(0)); row.addProperty("progress", (Double) pair.get(1));
            row.addProperty("kills", 0); row.addProperty("deaths", 0); row.addProperty("presence_seconds", 1); row.addProperty("peak_players", 1);
            rows.add(row);
        }
        exact.restore(rows);
        assertEquals(CaptureEngine.Status.OVERTIME, exact.second(Map.of(), END));
        assertEquals(CaptureEngine.Status.OVERTIME, exact.second(Map.of("SCAM", 1, "NYA", 1), END + 60));
        run(exact, Map.of("SCAM", 1), END + 61, 20); // 55 + 5.6 → lead 10.6
        assertEquals(CaptureEngine.Status.WON, exact.status());
        assertEquals("SCAM", exact.winner());

        exact = battle(null, false); exact.restore(rows);
        exact.second(Map.of(), END);
        assertEquals(CaptureEngine.Status.NO_WINNER, exact.second(Map.of("SCAM", 2, "NYA", 2), END + 180), "overtime is capped at three minutes");
        assertNull(exact.winner());

        var weak = battle("NYA", true);
        run(weak, Map.of("SCAM", 1), START, 100); // 27.8 %
        assertEquals(CaptureEngine.Status.NO_WINNER, weak.second(Map.of(), END), "below 50 %: the owner keeps the point");
    }

    @Test void snapshotRoundTripResumesTheBattle() {
        var engine = battle("NYA", true);
        run(engine, Map.of("SCAM", 2), START, 100);
        engine.kill("SCAM", "NYA", UUID.randomUUID(), START + 100);
        var resumed = battle("NYA", true);
        resumed.restore(engine.scoresJson());
        assertEquals(engine.progress("SCAM"), resumed.progress("SCAM"), 1e-3);
        assertEquals(1, resumed.scores().get("SCAM").kills);
        assertEquals(100, resumed.scores().get("SCAM").presenceSeconds);
        assertTrue(resumed.summary().startsWith("SCAM "));
        var bad = new com.google.gson.JsonArray(); var row = new com.google.gson.JsonObject();
        row.addProperty("clan_code", "scam lower"); bad.add(row);
        assertThrows(IllegalArgumentException.class, () -> battle(null, false).restore(bad));
    }
}
