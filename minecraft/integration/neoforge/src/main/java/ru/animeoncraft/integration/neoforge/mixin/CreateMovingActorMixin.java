package ru.animeoncraft.integration.neoforge.mixin;

import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.animeoncraft.integration.neoforge.TerritoryProtection;

@Pseudo
@Mixin(targets="com.simibubi.create.content.contraptions.AbstractContraptionEntity",remap=false)
public abstract class CreateMovingActorMixin {
    @Inject(method="isActorActive",at=@At("HEAD"),remap=false,cancellable=true)
    private void animeoncraft$actor(MovementContext context,MovementBehaviour behaviour,CallbackInfoReturnable<Boolean> result){
        if(context.position==null||context.contraption==null||context.contraption.anchor==null)return;
        var pos=BlockPos.containing(context.position);
        for(int dx=-8;dx<=8;dx+=8)for(int dz=-8;dz<=8;dz+=8){
            if(!TerritoryProtection.canConstructFrom(context.world,context.contraption.anchor,pos.offset(dx,0,dz))){result.setReturnValue(false);return;}
        }
    }
}
