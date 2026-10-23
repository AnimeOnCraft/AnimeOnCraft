package ru.animeoncraft.integration.neoforge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegionPolicyTest {
    /** The active regions of world-states.toml in the backend reply format (fixture generated from the catalog). */
    private static JsonObject states() throws Exception {
        return JsonParser.parseString(Files.readString(Path.of("src/test/resources/regions-states.json"))).getAsJsonObject();
    }
    private static JsonObject reply(String regionsJson) {
        return JsonParser.parseString("{\"revision\":\"" + "a".repeat(64) + "\",\"world_id\":\"states-20261020\",\"regions\":" + regionsJson + "}").getAsJsonObject();
    }

    @Test void statesWorldOwnersFollowTheCatalog() throws Exception {
        var regions = RegionPolicy.parse(states());
        assertEquals(RegionPolicy.NOBODY, regions.ownerAt(0, 0), "Sora: nobody builds");
        assertEquals(RegionPolicy.NOBODY, regions.ownerAt(0, 800), "boss arena lies in the core");
        assertEquals("SCAM", regions.ownerAt(0, -2704));
        assertEquals("NYA", regions.ownerAt(2560, -832));
        assertEquals("ZXC", regions.ownerAt(1584, 2192));
        assertEquals("AKUMA", regions.ownerAt(-1584, 2192));
        assertEquals("YAKUZA", regions.ownerAt(-2560, -832));
        assertNull(regions.ownerAt(0, -1248), "ring outpost: open borderland");
        assertNull(regions.ownerAt(1584, -2192), "W1 fort: open wedge");
        assertNull(regions.ownerAt(0, -8000), "frontier and reserved slots are unprotected");
        assertEquals(5, regions.spawns().size());
    }

    @Test void chunkCacheAgreesWithTheExactTestEverywhere() throws Exception {
        var cached = RegionPolicy.parse(states());
        var random = new Random(20261021);
        for (int i = 0; i < 200_000; i++) {
            int x = random.nextInt(11000) - 5500, z = random.nextInt(11000) - 5500;
            var fresh = i % 2000 == 0 ? RegionPolicy.parse(states()) : null; // no cache yet: the first lookup is computed
            String expected = null;
            for (var region : cached.regions()) if (region.contains(x, z)) { expected = region.owner(); break; }
            if ("".equals(expected)) expected = null;
            assertEquals(expected, cached.ownerAt(x, z), "at " + x + ", " + z);
            if (fresh != null) assertEquals(expected, fresh.ownerAt(x, z));
        }
    }

    @Test void bordersSplitColumnsInsideOneChunk() {
        // Two states meet at x = 8, inside chunk 0: columns 0..7 are AAA, 8..15 are BBB.
        var regions = RegionPolicy.parse(reply("[{\"code\":\"state.a\",\"build_policy\":\"MEMBERS\",\"clan_code\":\"AAA\",\"polygon\":[[-64,-64],[8,-64],[8,64],[-64,64]]},"
            + "{\"code\":\"state.b\",\"build_policy\":\"MEMBERS\",\"clan_code\":\"BBB\",\"polygon\":[[8,-64],[80,-64],[80,64],[8,64]]}]"));
        assertEquals("AAA", regions.ownerAt(7, 3));
        assertEquals("BBB", regions.ownerAt(8, 3));
        assertEquals("AAA", regions.ownerAt(-40, 3));
        assertNull(regions.ownerAt(80, 3));
        assertNull(regions.ownerAt(-65, 0));
    }

    @Test void policyMapsBuildRulesAndFailsClosed() {
        var regions = RegionPolicy.parse(reply("[{\"code\":\"state.a\",\"build_policy\":\"MEMBERS\",\"clan_code\":\"KITSUNE\",\"polygon\":[[0,0],[512,0],[512,512],[0,512]],\"spawn\":{\"x\":256,\"z\":256}},"
            + "{\"code\":\"core\",\"build_policy\":\"NONE\",\"clan_code\":null,\"polygon\":[[-512,0],[0,0],[0,512],[-512,512]]},"
            + "{\"code\":\"state.orphan\",\"build_policy\":\"MEMBERS\",\"clan_code\":null,\"polygon\":[[0,-512],[512,-512],[512,0],[0,0]]},"
            + "{\"code\":\"ring.a\",\"build_policy\":\"OPEN\",\"polygon\":[[512,0],[1024,0],[1024,512],[512,512]]}]"));
        var policy = TerritoryPermissions.states(regions, 16);
        assertTrue(policy.canBuild(true, false, "KITSUNE", 100, 100), "a sixth clan from the catalog builds at home");
        assertFalse(policy.canBuild(true, false, "SCAM", 100, 100));
        assertTrue(policy.canBuild(true, false, "KITSUNE", 256, 256), "members rebuild their clan spawn");
        assertFalse(policy.canBuild(true, false, "SCAM", 256, 256));
        assertFalse(policy.canBuild(false, false, "KITSUNE", 256, 256));
        assertTrue(policy.canMove(256, 256, 257, 256));
        assertFalse(policy.canBuild(true, false, "KITSUNE", -100, 100), "NONE: nobody");
        assertTrue(policy.canBuild(true, true, null, -100, 100), "operators maintain the core");
        assertFalse(policy.canBuild(true, false, "KITSUNE", 100, -100), "a region without a clan is nobody's");
        assertTrue(policy.canBuild(true, false, null, 700, 100), "open borderland");
        assertTrue(policy.canBuild(true, false, null, 5000, 5000), "outside every region");
        assertFalse(policy.canMove(100, 100, 700, 100), "automation cannot carry blocks out of a state");
        assertTrue(policy.canMove(700, 100, 800, 100));

        var closed = TerritoryPermissions.states(null, 16);
        assertFalse(closed.canBuild(true, false, null, 5000, 5000), "no catalog yet: fail closed");
        assertTrue(closed.protectedBlock(5000, 5000));
        assertTrue(closed.canBuild(true, true, null, 5000, 5000));
    }

    @Test void everyStatesClanCanRebuildItsCapitalSpawnWhileTheCoreStaysProtected() throws Exception {
        var regions = RegionPolicy.parse(states());
        var policy = TerritoryPermissions.states(regions, 16);
        assertEquals(5, regions.spawns().size());
        assertTrue(policy.spawns().isEmpty(), "clan spawns use ordinary territory rights");
        for (var spawn : regions.spawns()) {
            String clan = regions.ownerAt(spawn.x(), spawn.z());
            assertNotNull(clan);
            assertNotEquals(RegionPolicy.NOBODY, clan);
            for (int offset : new int[] {-16, 0, 16}) {
                int x = spawn.x() + offset, z = spawn.z() - offset;
                assertTrue(policy.canBuild(true, false, clan, x, z), clan);
                assertFalse(policy.canBuild(true, false, "FOREIGN", x, z), clan);
                assertFalse(policy.canBuild(true, false, null, x, z), clan);
                assertTrue(policy.protectedBlock(x, z));
                assertTrue(policy.canMove(x, z, x + 1, z));
            }
        }
        assertFalse(policy.canBuild(true, false, "SCAM", 0, 0));
        assertFalse(policy.canBuild(true, false, null, 0, 800));
    }

    @Test void nonClanSpawnsKeepTheirUnconditionalBuildLock() {
        var policy = TerritoryPermissions.states(RegionPolicy.parse(reply("""
            [{"code":"core","build_policy":"NONE","polygon":[[-512,0],[0,0],[0,512],[-512,512]],
              "spawn":{"x":-256,"z":256}},
             {"code":"ring.a","build_policy":"OPEN","polygon":[[0,0],[512,0],[512,512],[0,512]],
              "spawn":{"x":256,"z":256}}]
            """)), 16);
        assertEquals(2, policy.spawns().size());
        assertFalse(policy.canBuild(true, false, "SCAM", -256, 256));
        assertFalse(policy.canBuild(true, false, "SCAM", 256, 256));
        assertFalse(policy.canMove(256, 256, 257, 256));
        assertTrue(policy.canBuild(true, false, null, 280, 256));
    }

    @Test void malformedCatalogsAreRejectedAsAWhole() {
        String square = "[[0,0],[64,0],[64,64],[0,64]]";
        for (String bad : new String[] {
            "[{\"code\":\"state.a\",\"build_policy\":\"MAYBE\",\"polygon\":" + square + "}]",
            "[{\"code\":\"state.a\",\"build_policy\":\"MEMBERS\",\"clan_code\":\"#NONE\",\"polygon\":" + square + "}]",
            "[{\"code\":\"../x\",\"build_policy\":\"OPEN\",\"polygon\":" + square + "}]",
            "[{\"code\":\"state.a\",\"build_policy\":\"OPEN\",\"polygon\":[[0,0],[1,1]]}]",
            "[{\"code\":\"state.a\",\"build_policy\":\"OPEN\",\"polygon\":[[0,0],[200000,0],[0,64]]}]",
        }) assertThrows(RuntimeException.class, () -> RegionPolicy.parse(reply(bad)), bad);
        assertThrows(RuntimeException.class, () -> RegionPolicy.parse(JsonParser.parseString("{\"revision\":\"x\",\"world_id\":\"states-20261020\",\"regions\":[]}").getAsJsonObject()));
        assertThrows(RuntimeException.class, () -> RegionPolicy.parse(reply("[]")), "an empty catalog would open the whole world");
        assertThrows(RuntimeException.class, () -> RegionPolicy.parse(reply("[{\"code\":\"ring.a\",\"build_policy\":\"OPEN\",\"polygon\":" + square + "}]")), "a catalog that protects nothing");
        var noWorld = reply("[{\"code\":\"core\",\"build_policy\":\"NONE\",\"polygon\":" + square + "}]"); noWorld.remove("world_id");
        assertThrows(RuntimeException.class, () -> RegionPolicy.parse(noWorld));
        assertEquals("states-20261020", RegionPolicy.parse(reply("[{\"code\":\"core\",\"build_policy\":\"NONE\",\"polygon\":" + square + "}]")).worldId());
    }

    @Test void clientMapPolygonsMatchServerOwners() throws Exception {
        var policy = RegionPolicy.parse(states());
        var payload = RegionMapPayload.of(policy);
        assertEquals(5, payload.regions().size(), "only clan regions are drawn");
        var scam = payload.regions().stream().filter(r -> r.clan().equals("SCAM")).findFirst().orElseThrow();
        assertEquals(0xE84A5F, scam.color());
        var random = new java.util.Random(7);
        for (int i = 0; i < 50_000; i++) {
            int x = random.nextInt(11000) - 5500, z = random.nextInt(11000) - 5500;
            String server = policy.ownerAt(x, z);
            String client = payload.regions().stream().filter(r -> r.contains(x, z)).map(RegionMapPayload.Region::clan).findFirst().orElse(null);
            if (server != null && !RegionPolicy.NOBODY.equals(server)) assertEquals(server, client, "at " + x + ", " + z);
            else assertNull(client, "at " + x + ", " + z);
        }
        assertThrows(IllegalArgumentException.class, () -> new RegionMapPayload.Region("SCAM", "SCAM", 0, new int[] {0, 1}, new int[] {0, 1}));
    }

    @Test void regionMapPayloadSurvivesTheWire() throws Exception {
        var payload = RegionMapPayload.of(RegionPolicy.parse(states()));
        var buffer = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        RegionMapPayload.CODEC.encode(buffer, payload);
        var decoded = RegionMapPayload.CODEC.decode(buffer);
        assertEquals(payload.regions().size(), decoded.regions().size());
        for (int i = 0; i < payload.regions().size(); i++) {
            var a = payload.regions().get(i); var b = decoded.regions().get(i);
            assertEquals(a.clan(), b.clan()); assertEquals(a.owner(), b.owner()); assertEquals(a.color(), b.color());
            assertArrayEquals(a.xs(), b.xs()); assertArrayEquals(a.zs(), b.zs());
        }
        assertTrue(buffer.writerIndex() < 32_000, "fits a single custom payload easily: " + buffer.writerIndex());
    }
}
