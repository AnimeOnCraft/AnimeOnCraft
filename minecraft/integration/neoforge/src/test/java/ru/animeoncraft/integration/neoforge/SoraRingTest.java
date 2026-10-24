package ru.animeoncraft.integration.neoforge;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Sora's outer ring: old city and markets untouched, closed wall with gates on the roads, guild clear, all protected. */
class SoraRingTest {
    static final List<String> CLANS = List.of("SCAM", "NYA", "ZXC", "AKUMA", "YAKUZA");
    @AfterEach void clear() { WorldLayout.clear(); }

    static SoraRing ring() throws Exception {
        var layout = WorldLayout.load(WorldLayoutTest.LAYOUT);
        var expansion = layout.pois().get("core.capital.expansion");
        assertEquals(SoraExpansion.TYPE, expansion.type());
        return SoraRing.of(layout, expansion);
    }

    @Test void oldCityMarketsAndPortalHallAreNeverTouched() throws Exception {
        var ring = ring(); var regions = RegionPolicy.parse(ClanMarketTest.catalog());
        var plots = regions.regions().stream().filter(RegionPolicy.Region::market).toList();
        assertEquals(5, plots.size());
        for (var plot : plots) for (int x = plot.minX() - 2; x <= plot.maxX() + 2; x++) for (int z = plot.minZ() - 2; z <= plot.maxZ() + 2; z++)
            assertEquals(SoraRing.Cell.NONE, ring.cell(x, z), "market " + plot.clan() + " " + x + "," + z);
        for (int x = -9; x <= 9; x++) for (int z = 45; z <= 60; z++) assertEquals(SoraRing.Cell.NONE, ring.cell(x, z), "portal hall");
        for (int dx = -114; dx <= 114; dx++) for (int dz = -114; dz <= 114; dz++) {
            var cell = ring.cell(dx, dz);
            double d = Math.hypot(dx, dz);
            if (d < SoraRing.STREET_START) assertEquals(SoraRing.Cell.NONE, cell, "old city " + dx + "," + dz);
            else if (d < SoraRing.START) assertTrue(cell == SoraRing.Cell.NONE || cell == SoraRing.Cell.STREET, "only street joints next to the old wall");
        }
    }

    @Test void wallIsClosedExceptFiveGatesOnTheRoads() throws Exception {
        var ring = ring();
        assertEquals(160, ring.radius); assertEquals(5, ring.bearings.size());
        int gates = 0; boolean wasGate = ring.cell((int) Math.round(ring.radius * Math.sin(-Math.PI / 1800)), -(int) Math.round(ring.radius * Math.cos(-Math.PI / 1800))) == SoraRing.Cell.STREET;
        for (int step = 0; step < 3600; step++) {
            double a = step * Math.PI / 1800;
            int x = (int) Math.round(ring.radius * Math.sin(a)), z = -(int) Math.round(ring.radius * Math.cos(a));
            var cell = ring.cell(x, z);
            assertTrue(cell == SoraRing.Cell.WALL || cell == SoraRing.Cell.STREET, "wall at " + x + "," + z + " is " + cell);
            boolean gate = cell == SoraRing.Cell.STREET;
            if (gate && !wasGate) gates++;
            wasGate = gate;
        }
        assertEquals(5, gates);
        for (double b : ring.bearings) {
            for (int r = SoraRing.STREET_START + 1; r < ring.reach(); r++)
                assertEquals(SoraRing.Cell.STREET, ring.cell((int) Math.round(r * Math.sin(b)), -(int) Math.round(r * Math.cos(b))), "street to the road at r" + r);
        }
        // Diagonal leaks: no two diagonal wall cells with both side neighbours open outside the gates.
        for (int dx = -ring.radius - 2; dx <= ring.radius + 2; dx++) for (int dz = -ring.radius - 2; dz <= ring.radius + 2; dz++) {
            if (ring.cell(dx, dz) != SoraRing.Cell.WALL) continue;
            if (ring.cell(dx + 1, dz + 1) == SoraRing.Cell.WALL)
                assertTrue(ring.cell(dx + 1, dz) == SoraRing.Cell.WALL || ring.cell(dx, dz + 1) == SoraRing.Cell.WALL, "diagonal gap at " + dx + "," + dz);
            if (ring.cell(dx + 1, dz - 1) == SoraRing.Cell.WALL)
                assertTrue(ring.cell(dx + 1, dz) == SoraRing.Cell.WALL || ring.cell(dx, dz - 1) == SoraRing.Cell.WALL, "diagonal gap at " + dx + "," + dz);
        }
    }

    @Test void guildStandsOnLawnAndItsPathMeetsTheRingStreet() throws Exception {
        var ring = ring(); var guild = WorldLayout.state().pois().get("core.guild");
        assertEquals("ADVENTURER_GUILD", guild.type());
        var f = new AdventurerGuild.Frame(guild.x(), guild.z(), Direction.NORTH);
        for (int u = -AdventurerGuild.HALF_WIDTH - 2; u <= AdventurerGuild.HALF_WIDTH + 2; u++) for (int v = -1; v <= AdventurerGuild.DEPTH + 1; v++)
            assertEquals(SoraRing.Cell.LAWN, ring.cell(f.x(u, v), f.z(u, v)), "guild site " + u + "," + v);
        for (int v = -AdventurerGuild.PATH; v < -1; v++) assertNotEquals(SoraRing.Cell.WALL, ring.cell(f.x(0, v), f.z(0, v)));
        assertEquals(SoraRing.Cell.STREET, ring.cell(f.x(0, -AdventurerGuild.PATH), f.z(0, -AdventurerGuild.PATH)), "path reaches the ring street");
        assertArrayEquals(new int[] {0, 64 + 3, 149}, AdventurerGuild.board(f, 64));
        for (int[] t : ring.towers()) assertTrue(Math.hypot(t[0] - f.x(0, 8), t[1] - f.z(0, 8)) > 40, "towers away from the guild");
    }

    @Test void ringIsProtectedCoreAndInsideTheMobBan() throws Exception {
        var ring = ring(); var layout = WorldLayout.state();
        var regions = RegionPolicy.parse(ClanMarketTest.catalog()); var policy = TerritoryPermissions.states(regions, 16);
        var zone = CentralSpawnRules.fromLayout(layout);
        assertEquals(178, zone.radius());
        assertFalse(zone.contains(0, 800), "boss arena keeps its mobs");
        for (int dx = -ring.reach(); dx <= ring.reach(); dx += 3) for (int dz = -ring.reach(); dz <= ring.reach(); dz += 3) {
            var cell = ring.cell(dx, dz);
            if (cell == SoraRing.Cell.NONE || cell == SoraRing.Cell.SKIRT) continue;
            if (Math.hypot(dx, dz) <= ring.radius + 3) assertTrue(zone.contains(dx, dz), "mob ban at " + dx + "," + dz);
            assertEquals(RegionPolicy.NOBODY, regions.ownerAt(dx, dz));
            for (var clan : CLANS) assertFalse(policy.canBuild(true, false, clan, dx, dz), clan + " builds at " + dx + "," + dz);
        }
        var guild = layout.pois().get("core.guild");
        assertTrue(zone.contains(guild.x(), guild.z() + AdventurerGuild.DEPTH));
        for (var exclusion : layout.exclusions()) if (exclusion.code().equals("core.capital")) assertTrue(exclusion.radius() >= ring.reach(), "no random structures in the ring");
    }
}
