package ru.animeoncraft.integration.neoforge.mixin;
import net.createmod.catnip.math.BlockFace;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.animeoncraft.integration.neoforge.TerritoryProtection;
@Pseudo
@Mixin(targets="com.simibubi.create.foundation.blockEntity.behaviour.inventory.CapManipulationBehaviourBase",remap=false)
public abstract class CreateInventoryBoundaryMixin extends com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour {
    protected CreateInventoryBoundaryMixin(com.simibubi.create.foundation.blockEntity.SmartBlockEntity entity){super(entity);}
    @Shadow(remap=false) public abstract BlockFace getTarget();
    @Inject(method="getInventory",at=@At("HEAD"),remap=false,cancellable=true)
    private void animeoncraft$inventory(CallbackInfoReturnable<Object> result){
        var face=getTarget();if(face==null)return;
        if(!TerritoryProtection.canTransfer(getWorld(),getPos(),face.getPos())
            ||!TerritoryProtection.canTransfer(getWorld(),getPos(),face.getConnectedPos()))result.setReturnValue(null);
    }
}
