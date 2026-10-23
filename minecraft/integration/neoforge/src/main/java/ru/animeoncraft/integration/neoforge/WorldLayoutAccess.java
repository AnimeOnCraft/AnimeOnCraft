package ru.animeoncraft.integration.neoforge;

import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;

/** Public bridge for the mixin package (the layout itself stays package-private). */
public final class WorldLayoutAccess {
    private WorldLayoutAccess() {}
    /**
     * Only the overworld generator of a states world is filtered: masks and layout are loaded only for such a world,
     * and the Nether, the End and mod dimensions use their own noise settings.
     */
    public static boolean excluded(ChunkGenerator generator, int chunkX, int chunkZ) {
        if (!WorldMasks.active() || !WorldLayout.active()) return false;
        return generator instanceof NoiseBasedChunkGenerator noise && noise.generatorSettings().is(NoiseGeneratorSettings.OVERWORLD)
            && WorldLayout.excludedChunk(chunkX, chunkZ);
    }
}
