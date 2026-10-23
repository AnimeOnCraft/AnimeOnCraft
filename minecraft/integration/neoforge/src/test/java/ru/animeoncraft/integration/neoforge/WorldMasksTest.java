package ru.animeoncraft.integration.neoforge;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** World Design stage 3: masks load from the world datapack, sample bilinearly and lock the world to their digest. */
class WorldMasksTest {
    @TempDir Path temp;
    @AfterEach void clear() { WorldMasks.clear(); }

    private Path pack(int continentValue) throws Exception {
        Path aoc = Files.createDirectories(temp.resolve("pack/aoc"));
        var gray = new BufferedImage(4, 4, BufferedImage.TYPE_BYTE_GRAY);
        for (int x = 0; x < 4; x++) for (int z = 0; z < 4; z++) gray.getRaster().setSample(x, z, 0, x < 2 ? 0 : continentValue);
        ImageIO.write(gray, "png", aoc.resolve("continents.png").toFile());
        var regions = new BufferedImage(4, 4, BufferedImage.TYPE_BYTE_GRAY);
        for (int x = 0; x < 4; x++) for (int z = 0; z < 4; z++) regions.getRaster().setSample(x, z, 0, z < 2 ? 3 : 7);
        ImageIO.write(regions, "png", aoc.resolve("regions.png").toFile());
        Files.writeString(aoc.resolve("masks.json"), """
            {"format_version":1,"origin_x":-32,"origin_z":-32,"pixel":16,
             "masks":{"continents":"continents.png"},
             "regions":{"file":"regions.png","profiles":[
               {"index":3,"code":"state.scam","multipliers":{"FERROUS":1.8,"ARCANE":0.0}},
               {"index":7,"code":"state.nya","multipliers":{"FERROUS":0.2}}]}}
            """);
        return aoc;
    }

    @Test void samplesBilinearlyAndClampsAtEdges() throws Exception {
        WorldMasks.load(pack(255), temp.resolve("world"));
        assertEquals(0.0, WorldMasks.sample("continents", -32, -32), 1e-9);
        assertEquals(1.0, WorldMasks.sample("continents", 31, 0), 1e-9);
        assertEquals(0.5, WorldMasks.sample("continents", 0, 0), 1e-9, "pixel boundary between 0 and 255 interpolates");
        assertEquals(1.0, WorldMasks.sample("continents", 100000, 0), 1e-9, "outside the raster clamps");
        assertThrows(IllegalStateException.class, () -> WorldMasks.sample("missing", 0, 0));
    }

    @Test void regionMultipliersAndPassThroughWhenInactive() throws Exception {
        assertEquals(1.0, WorldMasks.multiplier("FERROUS", 0, 0), "no masks → vanilla generation");
        WorldMasks.load(pack(255), temp.resolve("world"));
        assertEquals("state.scam", WorldMasks.regionCode(WorldMasks.regionIndex(-20, -20)));
        assertEquals(1.8, WorldMasks.multiplier("FERROUS", -20, -20));
        assertEquals(0.0, WorldMasks.multiplier("ARCANE", -20, -20));
        assertEquals(0.2, WorldMasks.multiplier("FERROUS", -20, 20));
        assertEquals(1.0, WorldMasks.multiplier("PRECIOUS", -20, 20), "unlisted family keeps vanilla density");
    }

    @Test void worldIsLockedToTheMasksItWasGeneratedWith() throws Exception {
        Path world = temp.resolve("world");
        WorldMasks.load(pack(255), world);
        String digest = WorldMasks.state().digest();
        assertEquals(digest, Files.readString(world.resolve("animeoncraft-worldgen.sha256")).trim());
        WorldMasks.load(pack(255), world);
        assertThrows(java.io.IOException.class, () -> WorldMasks.load(pack(128), world), "changed masks are refused");
    }

    @Test void attemptsFollowTheMultiplier() {
        assertEquals(0, WorldMasks.attempts(0.0, 0.0));
        assertEquals(1, WorldMasks.attempts(0.2, 0.1));
        assertEquals(0, WorldMasks.attempts(0.2, 0.5));
        assertEquals(1, WorldMasks.attempts(1.0, 0.99));
        assertEquals(2, WorldMasks.attempts(1.8, 0.7));
        assertEquals(1, WorldMasks.attempts(1.8, 0.9));
        double sum = 0; int n = 100000; var random = new java.util.Random(7);
        for (int i = 0; i < n; i++) sum += WorldMasks.attempts(1.3, random.nextDouble());
        assertEquals(1.3, sum / n, 0.01, "expected attempts equal the multiplier");
    }

}
