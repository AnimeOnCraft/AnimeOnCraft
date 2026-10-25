package ru.animeoncraft.integration.neoforge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.animeoncraft.integration.neoforge.ContainerLocks;

/** Pipes, chutes, arms and controllers of any mod see no item handler on a personally locked block. */
@Mixin(value=BlockCapability.class,remap=false)
public abstract class LockedCapabilityMixin {
    @Inject(method="getCapability",at=@At("HEAD"),cancellable=true,remap=false)
    private void animeoncraft$locked(Level level,BlockPos pos,BlockState state,BlockEntity blockEntity,Object context,CallbackInfoReturnable<Object> result){
        if((Object)this==Capabilities.ItemHandler.BLOCK&&!level.isClientSide()&&ContainerLocks.capabilityHidden(level,pos))result.setReturnValue(null);
    }
}
