package ru.animeoncraft.integration.neoforge;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** VØRIX, the clan added to the played states world (docs/world-design/added-clans.json). */
class AddedClanTest {
    /** Active regions of the current world-states.toml in the backend reply format. */
    static JsonObject catalog() throws Exception {
        return JsonParser.parseString(Files.readString(Path.of("src/test/resources/regions-vorix.json"))).getAsJsonObject();
    }
    @AfterEach void clear() { WorldLayout.clear(); }

    @Test void vorixOwnsItsLandWhileOldStatesAndNeutralPointsStay() throws Exception {
        var regions = RegionPolicy.parse(catalog());
        assertEquals("VORIX", regions.ownerAt(4500, 880), "capital");
        assertEquals("VORIX", regions.ownerAt(5000, 0), "northern strip");
        assertEquals("VORIX", regions.ownerAt(3900, 3700), "southern tip");
        assertNull(regions.ownerAt(3328, 1088), "Мельничная деревня stays neutral");
        assertNull(regions.ownerAt(3760, 1216), "Золотое русло stays neutral");
        assertNull(regions.ownerAt(4288, 1904), "Кратер мифрила stays neutral");
        assertNull(regions.ownerAt(1227, 239), "VØRIX outpost: open ring");
        assertNull(regions.ownerAt(2560, 832), "Форт Мельниц: open wedge");
        assertEquals("NYA", regions.ownerAt(2560, -832));
        assertEquals("ZXC", regions.ownerAt(1584, 2192));
        assertEquals("VORIX", regions.ownerAt(143, 0), "VØRIX market in Sora's outer ring");
        assertEquals(RegionPolicy.NOBODY, regions.ownerAt(127, 0), "ring street before the market stays Sora's");
        assertEquals(6, regions.spawns().size());
    }

    @Test void chunkCacheAgreesWithTheExactTestAroundVorix() throws Exception {
        var cached = RegionPolicy.parse(catalog());
        var random = new Random(20261024);
        for (int i = 0; i < 200_000; i++) {
            int x = 2600 + random.nextInt(3100), z = -700 + random.nextInt(5000);
            String expected = null;
            for (var region : cached.regions()) if (region.contains(x, z)) { expected = region.owner(); break; }
            if ("".equals(expected)) expected = null;
            assertEquals(expected, cached.ownerAt(x, z), "at " + x + ", " + z);
        }
    }

    @Test void layoutGuardsVorixObjectsAndKeepsSoraFiveGates() throws Exception {
        var layout = WorldLayout.load(WorldLayoutTest.LAYOUT);
        assertEquals(Set.of("region.vorix.capital", "ring.outpost.vorix", "road.vorix.main"),
            layout.guarded().stream().filter(code -> !code.startsWith("mine.") && !code.startsWith("decor.")).collect(java.util.stream.Collectors.toSet()));
        assertEquals("VORIX", layout.pois().get("region.vorix.capital").palette());
        assertEquals("CLAN_CAPITAL", layout.pois().get("region.vorix.capital").type());
        assertFalse(layout.roads().containsKey("road.main.vorix"), "road.main.* would open a sixth gate in Sora");
        var ring = SoraRing.of(layout, layout.pois().get("core.capital.expansion"));
        assertEquals(5, ring.bearings.size());
        for (int x = 129; x < 157; x++) for (int z = -18; z < 18; z++) assertEquals(SoraRing.Cell.LAWN, ring.cell(x, z), "market " + x + "," + z);
    }

    @Test void vorixIsShownWithItsOwnSpelling() {
        assertEquals("VØRIX", ClanCodes.display("VORIX"));
        assertEquals("NYA", ClanCodes.display("NYA"));
        assertTrue(ClanCodes.valid("VORIX"));
    }
}
