package ru.animeoncraft.integration.neoforge;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class NightRulesTest {
    @Test void daylightUnchangedAndNightTakesFiveMinutes() {
        var rules = new NightRules(true, 2);
        long time = 0;
        for (int tick = 0; tick < 12000; tick++) time += 1 + rules.extraTicks(time + 1, true);
        assertEquals(12001, time);
        // Starting exactly at dusk requires 6000 simulation ticks, including the next dawn.
        time = 12000;
        for (int tick = 0; tick < 6000; tick++) time += 1 + rules.extraTicks(time + 1, true);
        assertEquals(24000, time);
    }
    @Test void frozenClockDisabledAndSleepAreRespected() {
        assertEquals(0, new NightRules(true, 2).extraTicks(18000, false));
        assertEquals(0, new NightRules(false, 2).extraTicks(18000, true));
        assertEquals(0, new NightRules(true, 2).extraTicks(24000, true));
        assertEquals(0, new NightRules(true, 2).extraTicks(23999 + 1, true));
        assertEquals(1, new NightRules(true, 2).extraTicks(23999, true));
    }
    @Test void invalidSpeedFailsInsteadOfFreezingOrSkippingTheWorld() {
        assertThrows(IllegalArgumentException.class, () -> new NightRules(true, 0));
        assertThrows(IllegalArgumentException.class, () -> new NightRules(true, 5));
    }
}
