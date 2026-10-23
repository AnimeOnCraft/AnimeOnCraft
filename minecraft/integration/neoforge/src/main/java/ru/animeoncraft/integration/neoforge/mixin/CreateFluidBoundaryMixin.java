package ru.animeoncraft.integration.neoforge.mixin;
import net.createmod.catnip.math.BlockFace;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.animeoncraft.integration.neoforge.TerritoryProtection;
@Pseudo
@Mixin(targets="com.simibubi.create.content.fluids.FluidNetwork",remap=false)
public abstract class CreateFluidBoundaryMixin {
    @Shadow(remap=false) Level world;
    @Shadow(remap=false) BlockFace start;
    @Inject(method="tick",at=@At("HEAD"),remap=false,cancellable=true)
    private void animeoncraft$source(CallbackInfo result){
        if(start!=null&&!TerritoryProtection.canTransfer(world,start.getPos(),start.getConnectedPos()))result.cancel();
    }
    @Inject(method="get",at=@At("HEAD"),remap=false,cancellable=true)
    private void animeoncraft$pipe(BlockFace face,CallbackInfoReturnable<Object> result){
        if(!TerritoryProtection.canTransfer(world,face.getPos(),face.getConnectedPos()))result.setReturnValue(null);
    }
}
