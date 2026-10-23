package ru.animeoncraft.integration.neoforge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TerritoryLayoutTest {
    private JsonObject plan() throws Exception {
        return JsonParser.parseString(Files.readString(Path.of("../../server/clan-world.json"))).getAsJsonObject();
    }

    @Test void actualPlanHasFiveLargeDisjointTownsAndNeutralSpawn() throws Exception {
        var plan = plan();
        var regions = TerritoryLayout.read(plan, 20261016L);
        assertEquals(5, regions.size());
        assertEquals(java.util.Set.copyOf(ClanCodes.fromConfig(plan)), regions.stream().map(Territory::clan).collect(java.util.stream.Collectors.toSet()));
        for (var region : regions) {
            assertEquals(768, region.maxX() - region.minX());
            var spawn = plan.getAsJsonObject("clans").getAsJsonObject(region.clan());
            assertTrue(region.contains(spawn.get("x").getAsInt(), spawn.get("z").getAsInt()));
            assertFalse(region.contains(-257, 1535));
            assertEquals(0, region.minX() % 16);
            assertFalse(region.contains(region.maxX(), region.minZ()));
            for (var other : regions) if (!other.clan().equals(region.clan())) {
                assertFalse(region.overlaps(other));
                var otherSpawn = plan.getAsJsonObject("clans").getAsJsonObject(other.clan());
                assertTrue(Math.hypot(spawn.get("x").getAsInt() - otherSpawn.get("x").getAsInt(), spawn.get("z").getAsInt() - otherSpawn.get("z").getAsInt()) >= 1000);
            }
        }
        var zxc = regions.stream().filter(region -> region.clan().equals("ZXC")).findFirst().orElseThrow();
        assertEquals(-400, zxc.minZ(), "Negative coordinates must floor to the preceding chunk");
    }

    @Test void wrongWorldAndOverlappingOrNeutralClaimsAreRejected() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> TerritoryLayout.read(plan(), 1));
        var overlap = plan();
        overlap.getAsJsonObject("clans").add("NYA", overlap.getAsJsonObject("clans").get("SCAM").deepCopy());
        assertThrows(IllegalArgumentException.class, () -> TerritoryLayout.read(overlap, 20261016L));
        var common = plan();
        common.add("common", common.getAsJsonObject("clans").get("SCAM").deepCopy());
        assertThrows(IllegalArgumentException.class, () -> TerritoryLayout.read(common, 20261016L));
    }

    @Test void invalidSizeColorAndOlderConfigAreHandled() throws Exception {
        var size = plan(); size.getAsJsonObject("territories").addProperty("width_blocks", 769);
        assertThrows(IllegalArgumentException.class, () -> TerritoryLayout.read(size, 20261016L));
        var color = plan(); color.getAsJsonObject("territories").getAsJsonObject("colors").addProperty("NYA", "FFFFFFF");
        assertThrows(IllegalArgumentException.class, () -> TerritoryLayout.read(color, 20261016L));
        var older = plan(); older.remove("territories");
        assertTrue(TerritoryLayout.read(older, 20261016L).isEmpty());
    }
}
