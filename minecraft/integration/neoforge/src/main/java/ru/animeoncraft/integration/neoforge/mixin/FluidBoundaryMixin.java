package ru.animeoncraft.integration.neoforge.mixin;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.animeoncraft.integration.neoforge.TerritoryProtection;
@Mixin(FlowingFluid.class)
public abstract class FluidBoundaryMixin {
    @Inject(method="spreadTo",at=@At("HEAD"),cancellable=true)
    private void animeoncraft$flow(LevelAccessor level,BlockPos target,BlockState state,Direction direction,FluidState fluid,CallbackInfo result){
        if(!TerritoryProtection.canTransfer(level,target.relative(direction.getOpposite()),target))result.cancel();
    }
}
