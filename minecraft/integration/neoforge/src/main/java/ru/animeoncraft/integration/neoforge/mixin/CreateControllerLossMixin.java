package ru.animeoncraft.integration.neoforge.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.animeoncraft.integration.neoforge.ContraptionPermission;

@Pseudo
@Mixin(targets="com.simibubi.create.content.contraptions.ControlledContraptionEntity",remap=false)
public abstract class CreateControllerLossMixin {
    @Inject(method="tickContraption",at=@At(value="INVOKE",target="Lcom/simibubi/create/content/contraptions/ControlledContraptionEntity;discard()V",remap=false),remap=false,cancellable=true)
    private void animeoncraft$preserveUntilControllerReturns(CallbackInfo result){
        var entity=(Entity)(Object)this;
        // onRemove may have been unable to place blocks in a foreign claim. Create would
        // discard the still-mounted blocks on its next tick, losing the entire mechanism.
        // Clients wait for the server's removal packet, so a temporarily missing
        // controller cannot hide a mechanism that the server has preserved.
        if(entity.level().isClientSide()||!((ContraptionPermission)this).animeoncraft$canDisassemble()){
            entity.setDeltaMovement(Vec3.ZERO);
            result.cancel();
        }
    }
}
