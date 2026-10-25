package ru.animeoncraft.integration.neoforge.mixin;

import com.orangemiracle.cleanmaid.data.CleanContext;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.animeoncraft.integration.neoforge.ServerEcology;

/** Optional bridge for CleanMaid 1.0.0, whose death protection map is never populated. */
@Pseudo
@Mixin(targets = "com.orangemiracle.cleanmaid.protection.ProtectionModule", remap = false)
public abstract class CleanMaidDeathDropMixin {
    @Inject(method = "isProtected", at = @At("RETURN"), cancellable = true, remap = false)
    private void aoc$protectDeathDrop(Entity entity, CleanContext context, boolean itemBlacklistHit,
            boolean manualItemCleanup, CallbackInfoReturnable<Boolean> result) {
        if (!result.getReturnValue() && entity instanceof ItemEntity
                && context.config().protection().protectPlayerDeathDrops()
                && !context.options().force() && context.options().uuid() == null && !itemBlacklistHit
                && ServerEcology.protectedDeathDrop(entity)) result.setReturnValue(true);
    }
}
