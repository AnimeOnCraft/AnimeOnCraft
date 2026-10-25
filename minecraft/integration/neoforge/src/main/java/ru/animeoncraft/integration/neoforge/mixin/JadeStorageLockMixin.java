package ru.animeoncraft.integration.neoforge.mixin;

import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.animeoncraft.integration.neoforge.ContainerLocks;

/** Jade does not show the contents of a locked storage to players who cannot open it. */
@Pseudo
@Mixin(targets="snownee.jade.addon.universal.ItemStorageProvider",remap=false)
public abstract class JadeStorageLockMixin {
    @Inject(method="putData",at=@At("HEAD"),cancellable=true,remap=false)
    private static void animeoncraft$locked(snownee.jade.api.Accessor<?> accessor,CallbackInfo ci){
        if(accessor.getTarget() instanceof BlockEntity be&&!ContainerLocks.canOpen(accessor.getPlayer(),accessor.getLevel(),be.getBlockPos()))ci.cancel();
    }
}
