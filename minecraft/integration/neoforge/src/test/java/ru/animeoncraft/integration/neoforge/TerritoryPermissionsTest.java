package ru.animeoncraft.integration.neoforge;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TerritoryPermissionsTest {
    private TerritoryPermissions policy() throws Exception {
        return TerritoryPermissions.read(JsonParser.parseString(Files.readString(Path.of("../../server/clan-world.json"))).getAsJsonObject(), 20261016L);
    }

    @Test void clanRightsComeFromAdmissionAndCoverAllFiveTerritories() throws Exception {
        var policy = policy();
        for (var region : policy.territories()) {
            int x = region.minX(), z = region.minZ();
            assertTrue(policy.canBuild(true, false, region.clan(), x, z));
            assertFalse(policy.canBuild(true, false, null, x, z));
            for (String other : java.util.List.of("SCAM", "NYA", "AKUMA", "ZXC", "YAKUZA", "KITSUNE")) if (!other.equals(region.clan())) assertFalse(policy.canBuild(true, false, other, x, z));
            assertFalse(policy.canBuild(false, true, region.clan(), x, z), "OP alone does not replace admission");
            assertTrue(policy.canBuild(true, true, null, x, z));
            assertTrue(policy.protectedBlock(x, z));
        }
    }

    @Test void membersCanRebuildTheirClanSpawnButNotTheCommonSpawn() throws Exception {
        var policy = policy();
        var root = JsonParser.parseString(Files.readString(Path.of("../../server/clan-world.json"))).getAsJsonObject();
        for (String clan : ClanCodes.fromConfig(root)) {
            var point = root.getAsJsonObject("clans").getAsJsonObject(clan);
            int x = point.get("x").getAsInt(), z = point.get("z").getAsInt();
            assertFalse(policy.spawn(x, z));
            assertTrue(policy.canBuild(true, false, clan, x, z));
            assertFalse(policy.canBuild(true, false, null, x, z));
            assertFalse(policy.canBuild(true, false, "FOREIGN", x, z));
            assertFalse(policy.canBuild(false, false, clan, x, z));
            assertTrue(policy.protectedBlock(x, z), "clan protection still applies");
            assertTrue(policy.canMove(x, z, x + 1, z), "automation can work at a clan spawn");
        }
        assertEquals(1, policy.spawns().size());
        for (var spawn : policy.spawns()) {
            assertTrue(policy.spawn(spawn.x() + 16, spawn.z() - 16));
            assertFalse(policy.spawn(spawn.x() + 17, spawn.z()));
            assertFalse(policy.canBuild(true, false, "SCAM", spawn.x(), spawn.z()));
            assertTrue(policy.canBuild(true, true, null, spawn.x(), spawn.z()));
            assertFalse(policy.canMove(spawn.x(), spawn.z(), spawn.x() + 1, spawn.z()));
        }
    }

    @Test void neutralLandAndHalfOpenNegativeBoundariesRemainUsable() throws Exception {
        var policy = policy();
        var region = policy.territories().stream().filter(r -> r.clan().equals("ZXC")).findFirst().orElseThrow();
        assertEquals("ZXC", policy.territory(region.minX(), -400));
        assertNull(policy.territory(region.minX(), -401));
        assertNull(policy.territory(region.maxX(), region.minZ()));
        assertTrue(policy.canBuild(true, false, null, 10000, 10000));
        assertFalse(policy.protectedBlock(10000, 10000));
        assertFalse(policy.canBuild(false, false, null, 10000, 10000));
    }

    @Test void pistonCanWorkInsideAClaimButNotAcrossItsBoundary() throws Exception {
        var policy = policy();
        var region = policy.territories().getFirst();
        assertTrue(policy.canMove(region.minX(), region.minZ(), region.minX() + 1, region.minZ()));
        assertFalse(policy.canMove(region.minX() - 1, region.minZ(), region.minX(), region.minZ()));
        assertFalse(policy.canMove(region.minX(), region.minZ(), region.minX() - 1, region.minZ()));
        assertTrue(policy.canMove(10000, 10000, 10001, 10000));
    }

    @Test void invalidConfigurationIsRejected() throws Exception {
        var root = JsonParser.parseString(Files.readString(Path.of("../../server/clan-world.json"))).getAsJsonObject();
        assertThrows(IllegalArgumentException.class, () -> TerritoryPermissions.read(root, 1));
        var protection = new com.google.gson.JsonObject(); protection.addProperty("spawn_radius_blocks", 65); root.add("protection", protection);
        assertThrows(IllegalArgumentException.class, () -> TerritoryPermissions.read(root, 20261016L));
    }

    @Test void missingTerritoryLayoutDoesNotMakeClanSpawnsPublic() throws Exception {
        var root = JsonParser.parseString(Files.readString(Path.of("../../server/clan-world.json"))).getAsJsonObject();
        root.remove("territories");
        var policy = TerritoryPermissions.read(root, 20261016L);
        for (var point : root.getAsJsonObject("clans").entrySet()) {
            int x = point.getValue().getAsJsonObject().get("x").getAsInt();
            int z = point.getValue().getAsJsonObject().get("z").getAsInt();
            assertFalse(policy.canBuild(true, false, "FOREIGN", x, z));
        }
    }
}
