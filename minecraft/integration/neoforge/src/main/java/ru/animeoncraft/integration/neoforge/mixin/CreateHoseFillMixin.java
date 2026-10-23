package ru.animeoncraft.integration.neoforge.mixin;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.animeoncraft.integration.neoforge.TerritoryProtection;
@Pseudo
@Mixin(targets="com.simibubi.create.content.fluids.transfer.FluidFillingBehaviour",remap=false)
public abstract class CreateHoseFillMixin {
    @Inject(method="getAtPos",at=@At("HEAD"),cancellable=true,remap=false)
    private void animeoncraft$space(Level level,BlockPos target,net.minecraft.world.level.material.Fluid fluid,CallbackInfoReturnable<Object> result){
        var hose=(BlockEntityBehaviour)(Object)this;
        if(!TerritoryProtection.canConstructFrom(level,hose.getPos(),target)){
            try{
                for(Object value:Class.forName("com.simibubi.create.content.fluids.transfer.FluidFillingBehaviour$SpaceType").getEnumConstants()){
                    if(((Enum<?>)value).name().equals("BLOCKING")){result.setReturnValue(value);return;}
                }
                throw new IllegalStateException("Missing Create BLOCKING space type");
            }catch(ClassNotFoundException failure){throw new IllegalStateException("Unsupported Create fluid API",failure);}
        }
    }
    @Inject(method="canBeReplacedByFluid",at=@At("HEAD"),cancellable=true,remap=false)
    private void animeoncraft$select(BlockGetter level,BlockPos target,BlockState state,CallbackInfoReturnable<Boolean> result){
        var hose=(BlockEntityBehaviour)(Object)this;
        if(!TerritoryProtection.canConstructFrom(hose.getWorld(),hose.getPos(),target))result.setReturnValue(false);
    }
    @Inject(method="replaceBlock",at=@At("HEAD"),cancellable=true,remap=false)
    private void animeoncraft$fill(Level level,BlockPos target,BlockState state,CallbackInfo result){
        var hose=(BlockEntityBehaviour)(Object)this;
        if(!TerritoryProtection.canConstructFrom(level,hose.getPos(),target))result.cancel();
    }
}
