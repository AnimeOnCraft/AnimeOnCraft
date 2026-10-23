package ru.animeoncraft.integration.neoforge.mixin;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.animeoncraft.integration.neoforge.ContraptionPermission;
@Pseudo
@Mixin(targets="com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity",remap=false)
public abstract class CreateBearingControllerMixin {
    @Shadow(remap=false) public abstract ControlledContraptionEntity getMovedContraption();
    @Inject(method="disassemble",at=@At("HEAD"),remap=false,cancellable=true)
    private void animeoncraft$keepController(CallbackInfo result){
        var moving=getMovedContraption();
        if(moving!=null&&!((ContraptionPermission)moving).animeoncraft$canDisassemble())result.cancel();
    }
}
