package ru.animeoncraft.integration.neoforge.mixin;

import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.StructureTransform;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.animeoncraft.integration.neoforge.ContraptionPermission;
import ru.animeoncraft.integration.neoforge.TerritoryProtection;

@Pseudo
@Mixin(targets="com.simibubi.create.content.contraptions.AbstractContraptionEntity",remap=false)
public abstract class CreateDisassemblyMixin implements ContraptionPermission {
    @Shadow(remap=false) public abstract Contraption getContraption();
    @Shadow(remap=false) protected abstract StructureTransform makeStructureTransform();
    @Override public boolean animeoncraft$canDisassemble(){
        return animeoncraft$canDisassembleAt(null);
    }
    @Override public boolean animeoncraft$canDisassembleAt(net.minecraft.world.phys.Vec3 position){
        var entity=(com.simibubi.create.content.contraptions.AbstractContraptionEntity)(Object)this;var contraption=getContraption();
        if(contraption==null||entity.level().isClientSide())return true;
        var transform=makeStructureTransform();
        // Both Controlled and Oriented entities round their translated anchor this way in
        // pinned Create6.0.10. Preserve the actual rotation; never move the entity to test it.
        if(position!=null)transform.offset=net.minecraft.core.BlockPos.containing(entity.getAnchorVec().add(position.subtract(entity.position())).add(0.5,0.5,0.5));
        for(var local:contraption.getBlocks().keySet())if(!TerritoryProtection.canConstructFrom(entity.level(),contraption.anchor,transform.apply(local)))return false;
        return true;
    }
    @Inject(method="disassemble",at=@At("HEAD"),remap=false,cancellable=true)
    private void animeoncraft$protect(CallbackInfo result){
        if(!animeoncraft$canDisassemble()){
            ((Entity)(Object)this).setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            result.cancel();
        }
    }
}
