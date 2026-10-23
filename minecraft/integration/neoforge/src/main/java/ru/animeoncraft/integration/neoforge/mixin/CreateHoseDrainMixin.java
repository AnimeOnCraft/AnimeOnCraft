package ru.animeoncraft.integration.neoforge.mixin;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.animeoncraft.integration.neoforge.TerritoryProtection;
@Pseudo
@Mixin(targets="com.simibubi.create.content.fluids.transfer.FluidDrainingBehaviour",remap=false)
public abstract class CreateHoseDrainMixin {
    @Inject(method="canPullFluidsFrom",at=@At("HEAD"),cancellable=true,remap=false)
    private void animeoncraft$source(net.minecraft.world.level.block.state.BlockState state,BlockPos target,CallbackInfoReturnable<Object> result){
        var hose=(BlockEntityBehaviour)(Object)this;
        if(!TerritoryProtection.canConstructFrom(hose.getWorld(),hose.getPos(),target)){
            try{
                for(Object value:Class.forName("com.simibubi.create.content.fluids.transfer.FluidDrainingBehaviour$FluidBlockType").getEnumConstants()){
                    if(((Enum<?>)value).name().equals("NONE")){result.setReturnValue(value);return;}
                }
                throw new IllegalStateException("Missing Create NONE fluid type");
            }catch(ClassNotFoundException failure){throw new IllegalStateException("Unsupported Create fluid API",failure);}
        }
    }
    @Inject(method="getDrainableFluid",at=@At("HEAD"),cancellable=true,remap=false)
    private void animeoncraft$drain(BlockPos target,CallbackInfoReturnable<FluidStack> result){
        var hose=(BlockEntityBehaviour)(Object)this;
        if(!TerritoryProtection.canConstructFrom(hose.getWorld(),hose.getPos(),target))result.setReturnValue(FluidStack.EMPTY);
    }
}
