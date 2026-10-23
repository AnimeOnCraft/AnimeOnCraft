package ru.animeoncraft.integration.neoforge;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.Unpooled;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** World Design stage 1: nothing about the five starting clans is compiled into the mod. */
class ClanRegistryTest {
    @AfterEach void reset() { ClanCodes.register(List.of()); }

    private static JsonObject plan() throws Exception {
        return JsonParser.parseString(Files.readString(Path.of("../../server/clan-world.json"))).getAsJsonObject();
    }

    /** The tracked plan plus a sixth official clan far from the others, as an admin would add it. */
    private static JsonObject withKitsune() throws Exception {
        var root = plan();
        var spawn = new JsonObject(); spawn.addProperty("x", 6000); spawn.addProperty("y", 0); spawn.addProperty("z", -6000);
        root.getAsJsonObject("clans").add("KITSUNE", spawn);
        root.getAsJsonObject("territories").getAsJsonObject("colors").addProperty("KITSUNE", "FF8A3D");
        return root;
    }

    @Test void registryComesFromConfigurationInFileOrder() throws Exception {
        assertEquals(List.of("SCAM", "NYA", "AKUMA", "ZXC", "YAKUZA"), ClanCodes.fromConfig(plan()));
        ClanCodes.register(List.of());
        assertTrue(ClanCodes.all().isEmpty(), "an empty registry knows no clan");
        assertFalse(ClanCodes.known("SCAM"));
        ClanCodes.register(ClanCodes.fromConfig(withKitsune()));
        assertTrue(ClanCodes.known("KITSUNE"));
        assertEquals(6, ClanCodes.all().size());
    }

    @Test void sixthClanGetsTerritoryProtectionAndSpawn() throws Exception {
        var root = withKitsune();
        var policy = TerritoryPermissions.read(root, 20261016L);
        assertEquals(6, policy.territories().size());
        assertEquals(1, policy.spawns().size(), "only the common spawn has an unconditional build lock");
        assertEquals("KITSUNE", policy.territory(6000, -6000));
        assertTrue(policy.canBuild(true, false, "KITSUNE", 6000, -6000));
        assertFalse(policy.canBuild(true, false, "SCAM", 6000, -6000));
        assertTrue(policy.canBuild(true, false, "KITSUNE", 6100, -6100));
        assertFalse(policy.canBuild(true, false, "SCAM", 6100, -6100));
        var kitsune = TerritoryLayout.read(root, 20261016L).stream().filter(t -> t.clan().equals("KITSUNE")).findFirst().orElseThrow();
        assertEquals(0xFF8A3D, kitsune.color());
    }

    @Test void territoryPayloadIsNotLimitedToFiveClans() throws Exception {
        var regions = TerritoryLayout.read(withKitsune(), 20261016L);
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        TerritoryPayload.CODEC.encode(buffer, new TerritoryPayload(regions));
        var decoded = TerritoryPayload.CODEC.decode(buffer);
        assertEquals(regions, decoded.regions());
    }

    @Test void invalidOrDuplicateCodesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> ClanCodes.register(List.of("scam")));
        assertThrows(IllegalArgumentException.class, () -> ClanCodes.register(List.of("SCAM", "SCAM")));
        assertThrows(IllegalArgumentException.class, () -> ClanCodes.register(List.of("COMMON")));
        assertTrue(ClanCodes.valid("KITSUNE"));
        assertFalse(ClanCodes.valid("K"));
        assertFalse(ClanCodes.valid(null));
    }
}
