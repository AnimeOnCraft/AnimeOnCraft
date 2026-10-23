package ru.animeoncraft.integration.neoforge.mixin;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.animeoncraft.integration.neoforge.ContraptionPermission;
@Pseudo
@Mixin(targets={"com.simibubi.create.content.contraptions.piston.MechanicalPistonBlockEntity","com.simibubi.create.content.contraptions.pulley.PulleyBlockEntity"},remap=false)
public abstract class CreateLinearControllerMixin extends com.simibubi.create.content.contraptions.piston.LinearActuatorBlockEntity {
    protected CreateLinearControllerMixin(net.minecraft.world.level.block.entity.BlockEntityType<?> type,net.minecraft.core.BlockPos pos,net.minecraft.world.level.block.state.BlockState state){super(type,pos,state);}
    @Inject(method="disassemble",at=@At("HEAD"),remap=false,cancellable=true)
    private void animeoncraft$keepController(CallbackInfo result){
        var moving=movedContraption;
        if(moving!=null){
            var permission=(ContraptionPermission)moving;
            if(!permission.animeoncraft$canDisassemble()||!permission.animeoncraft$canDisassembleAt(toPosition(offset)))result.cancel();
        }
    }
}
