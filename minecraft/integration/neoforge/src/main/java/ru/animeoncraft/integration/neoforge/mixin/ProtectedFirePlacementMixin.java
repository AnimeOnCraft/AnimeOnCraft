package ru.animeoncraft.integration.neoforge.mixin;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.animeoncraft.integration.neoforge.TerritoryProtection;
@Mixin(Level.class)
public abstract class ProtectedFirePlacementMixin {
    @Inject(method="setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",at=@At("HEAD"),cancellable=true)
    private void animeoncraft$fire(BlockPos pos,BlockState state,int flags,int recursion,CallbackInfoReturnable<Boolean> result){
        if((state.is(Blocks.FIRE)||state.is(Blocks.SOUL_FIRE))&&TerritoryProtection.firePlacementDenied((Level)(Object)this,pos))result.setReturnValue(false);
    }
}
