package ru.animeoncraft.integration.neoforge;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CentralSpawnRulesTest {
    @Test void protectsCityButNotBossArenaOrClanCapitals() {
        var city = new CentralSpawnRules.Zone(0, 0);
        assertTrue(city.contains(0, 0));
        assertTrue(city.contains(110, 0));
        assertTrue(city.contains(128, 0));
        assertFalse(city.contains(128.01, 0));
        assertFalse(city.contains(100, 100));
        assertFalse(city.contains(0, 800));
        assertFalse(city.contains(-1584, 2192));
    }
    @Test void usesCentralCapitalInsteadOfFirstClanCapital() {
        var clan = new WorldLayout.Poi("clan.nya.capital", "CAPITAL", "NYA", 2560, -832, 64, "neutral", null, 0, null, null);
        var core = new WorldLayout.Poi("core.capital", "CAPITAL", "Sora", 20, -30, 64, "neutral", null, 0, null, null);
        var layout = new WorldLayout.State("test", Map.of(), Map.of(clan.code(), clan, core.code(), core), Map.of(), List.of());
        assertEquals(new CentralSpawnRules.Zone(20, -30), CentralSpawnRules.fromLayout(layout));
        assertNull(CentralSpawnRules.fromLayout(null));
        assertNull(CentralSpawnRules.fromLayout(new WorldLayout.State("test", Map.of(), Map.of(clan.code(), clan), Map.of(), List.of())));
    }
    @Test void outerRingWidensTheBanByTheSameMarginPastItsWall() {
        var core = new WorldLayout.Poi("core.capital", "CAPITAL", "Sora", 0, 0, 64, "neutral", null, 0, null, null);
        var ring = new WorldLayout.Poi("core.capital.expansion", "CITY_EXPANSION", "Ring", 0, 0, 160, "neutral", null, 0, null, null);
        var elsewhere = new WorldLayout.Poi("other.expansion", "CITY_EXPANSION", "Other", 2560, -832, 240, "neutral", null, 0, null, null);
        var zone = CentralSpawnRules.fromLayout(new WorldLayout.State("test", Map.of(), Map.of(core.code(), core, ring.code(), ring, elsewhere.code(), elsewhere), Map.of(), List.of()));
        assertEquals(new CentralSpawnRules.Zone(0, 0, 178), zone);
        assertTrue(zone.contains(160, 0)); assertTrue(zone.contains(0, 178)); assertFalse(zone.contains(0, 178.01));
        assertFalse(zone.contains(0, 800), "boss arena");
    }
}
