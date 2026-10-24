package ru.animeoncraft.integration.neoforge;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** World Design stage 5: the generated layout, structure exclusion zones and road geometry. */
class WorldLayoutTest {
    static final Path LAYOUT = Path.of("../../server/world-config/worldgen-pack/aoc/layout.json");
    @AfterEach void clear() { WorldLayout.clear(); }

    @Test void generatedLayoutHasEveryPoiRoadAndZone() throws Exception {
        var layout = WorldLayout.load(LAYOUT);
        assertEquals(69, layout.pois().size(), "67 catalog points + Sora's outer ring and the guild");
        assertEquals(20, layout.roads().size());
        assertEquals(69, layout.exclusions().size());
        assertEquals("CITY_EXPANSION", layout.pois().get("core.capital.expansion").type());
        assertEquals(160, layout.pois().get("core.capital.expansion").radius());
        assertEquals("ADVENTURER_GUILD", layout.pois().get("core.guild").type());
        for (var clan : List.of("SCAM", "NYA", "ZXC", "AKUMA", "YAKUZA")) assertTrue(layout.palettes().containsKey(clan), clan + " palette from clan-world.json");
        assertEquals("CAPITAL", layout.pois().get("core.capital").type());
        assertEquals("road.cross.w1", layout.pois().get("border.w1.pass").road());
        for (var poi : layout.pois().values()) assertTrue(layout.palettes().containsKey(poi.palette()));
    }

    @Test void randomStructuresAreExcludedOnlyNearPois() throws Exception {
        WorldLayout.load(LAYOUT);
        assertTrue(WorldLayout.excludedChunk(0, 0), "Sora");
        assertTrue(WorldLayout.excludedChunk(300 >> 4, 0), "Sora city radius 300 + margin");
        assertFalse(WorldLayout.excludedChunk(500 >> 4, 0), "Sora outskirts allow structures");
        assertTrue(WorldLayout.excludedChunk(0, -2704 >> 4), "SCAM capital");
        assertTrue(WorldLayout.excludedChunk(1584 >> 4, -2192 >> 4), "Fort of the pass");
        assertFalse(WorldLayout.excludedChunk(6000 >> 4, 6000 >> 4), "wild frontier");
        WorldLayout.clear();
        assertFalse(WorldLayout.excludedChunk(0, 0), "no layout, no exclusion (current world)");
    }

    @Test void rejectsBrokenLayouts() throws Exception {
        var text = Files.readString(LAYOUT);
        for (var broken : List.of(text.replace("\"format_version\":1", "\"format_version\":2"), text.replace("\"palette\":\"core\"", "\"palette\":\"nowhere\""),
                text.replace("\"neutral\":", "\"plain\":"), text.replaceFirst("\"radius\":\\d+", "\"radius\":999")))
            assertThrows(RuntimeException.class, () -> WorldLayout.parse(JsonParser.parseString(broken).getAsJsonObject()));
    }

    @Test void roadDirectionFollowsTheNearestSegment() throws Exception {
        var layout = WorldLayout.load(LAYOUT);
        var pass = layout.pois().get("border.w1.pass");
        var direction = WorldLayout.roadDirection(layout, pass.x(), pass.z(), 200);
        assertNotNull(direction);
        assertEquals(1, Math.hypot(direction[0], direction[1]), 1e-9);
        assertNull(WorldLayout.roadDirection(layout, 9000, 9000, 50));
    }

    @Test void traceIsContinuousAndSmoothingLimitsTheSlope() {
        var trace = RoadGeometry.trace(List.of(new int[] {0, 0}, new int[] {10, 3}, new int[] {10, 20}));
        for (int i = 1; i < trace.size(); i++)
            assertTrue(Math.abs(trace.get(i)[0] - trace.get(i - 1)[0]) <= 1 && Math.abs(trace.get(i)[1] - trace.get(i - 1)[1]) <= 1, "8-connected");
        assertArrayEquals(new int[] {10, 20}, trace.getLast());
        int n = 60; int[] ground = new int[n]; boolean[] water = new boolean[n];
        for (int i = 0; i < n; i++) ground[i] = i < 20 ? 64 : i < 30 ? 90 : 62;
        for (int i = 40; i < 50; i++) { water[i] = true; ground[i] = 40; }
        int[] y = RoadGeometry.smooth(ground, water, 62);
        for (int i = 1; i < n; i++) assertTrue(Math.abs(y[i] - y[i - 1]) <= 1, "slope at " + i);
        for (int i = 1; i < n - 1; i++) assertTrue(Math.abs(y[i + 1] - 2 * y[i] + y[i - 1]) <= 2);
        for (int i = 40; i < 50; i++) assertTrue(y[i] >= 63, "bridge deck never below the water surface");
        assertTrue(y[45] >= 64, "mid-river deck two above the sea");
    }
}
