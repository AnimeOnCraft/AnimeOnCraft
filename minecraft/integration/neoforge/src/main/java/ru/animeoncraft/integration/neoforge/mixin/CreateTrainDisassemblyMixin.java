package ru.animeoncraft.integration.neoforge.mixin;
import com.simibubi.create.content.trains.entity.Train;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.animeoncraft.integration.neoforge.ContraptionPermission;
@Pseudo
@Mixin(targets="com.simibubi.create.content.trains.entity.Train",remap=false)
public abstract class CreateTrainDisassemblyMixin {
    @Inject(method="canDisassemble",at=@At("HEAD"),remap=false,cancellable=true)
    private void animeoncraft$keepTrain(CallbackInfoReturnable<Boolean> result){
        var allowed=new java.util.concurrent.atomic.AtomicBoolean(true);
        for(var carriage:((Train)(Object)this).carriages)carriage.forEachPresentEntity(entity->{
            if(!((ContraptionPermission)entity).animeoncraft$canDisassemble())allowed.set(false);
        });
        if(!allowed.get())result.setReturnValue(false);
    }

    @Inject(method="disassemble",at=@At("HEAD"),remap=false,cancellable=true)
    private void animeoncraft$checkStation(net.minecraft.core.Direction direction,net.minecraft.core.BlockPos station,CallbackInfoReturnable<Boolean> result){
        var train=(Train)(Object)this;
        int offset=1;
        // Create relocates each carriage before calling entity.disassemble(). Preflight the
        // complete destination, before storage, entity positions or railway state can change.
        for(int i=0;i<train.carriages.size();i++){
            int index=train.currentlyBackwards?train.carriages.size()-i-1:i;
            var carriage=train.carriages.get(index);
            var entity=carriage.anyAvailableEntity();
            if(entity==null||entity.getContraption()==null){result.setReturnValue(false);return;}
            var target=station.relative(direction,train.currentlyBackwards?offset+carriage.bogeySpacing:offset)
                .below(carriage.leadingBogey().isUpsideDown()?2:0);
            var destination=net.minecraft.world.phys.Vec3.atLowerCornerOf(target);
            var anchor=entity.getAnchorVec().add(destination.subtract(entity.position())).add(0.5,0.5,0.5);
            var transform=new com.simibubi.create.content.contraptions.StructureTransform(net.minecraft.core.BlockPos.containing(anchor),0,-entity.yaw+entity.getInitialYaw(),0);
            var contraption=entity.getContraption();
            for(var local:contraption.getBlocks().keySet()){
                if(!ru.animeoncraft.integration.neoforge.TerritoryProtection.canConstructFrom(entity.level(),contraption.anchor,transform.apply(local))){
                    result.setReturnValue(false);return;
                }
            }
            offset+=carriage.bogeySpacing;
            if(i<train.carriageSpacing.size())offset+=train.carriageSpacing.get(train.currentlyBackwards?train.carriageSpacing.size()-i-1:i);
        }
    }
}
