package ru.animeoncraft.integration.neoforge.mixin;

import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * World Design stage 5: no random structure (village, dungeon, tower of any mod) starts in Sora, a clan capital
 * or a point zone of the states world. Only the overworld of a world with the states layout is affected; vanilla
 * exclusion_zone works only between structures, so the start itself is skipped here.
 */
@Mixin(ChunkGenerator.class)
abstract class StructureExclusionMixin {
    @Inject(method = "createStructures", at = @At("HEAD"), cancellable = true)
    private void animeoncraft$excludeStructures(RegistryAccess access, ChunkGeneratorStructureState state, StructureManager structures,
                                                ChunkAccess chunk, StructureTemplateManager templates, CallbackInfo info) {
        if (ru.animeoncraft.integration.neoforge.WorldLayoutAccess.excluded((ChunkGenerator) (Object) this, chunk.getPos().x, chunk.getPos().z)) info.cancel();
    }
}
