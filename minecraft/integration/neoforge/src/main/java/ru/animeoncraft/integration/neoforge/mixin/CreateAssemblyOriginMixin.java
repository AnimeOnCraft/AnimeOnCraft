package ru.animeoncraft.integration.neoforge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.animeoncraft.integration.neoforge.AssemblyOrigin;

@Pseudo
@Mixin(targets={
    "com.simibubi.create.content.contraptions.bearing.BearingContraption",
    "com.simibubi.create.content.contraptions.bearing.ClockworkContraption",
    "com.simibubi.create.content.contraptions.piston.PistonContraption",
    "com.simibubi.create.content.contraptions.pulley.PulleyContraption",
    "com.simibubi.create.content.contraptions.gantry.GantryContraption",
    "com.simibubi.create.content.trains.entity.CarriageContraption",
    "com.simibubi.create.content.contraptions.mounted.MountedContraption"
},remap=false)
public abstract class CreateAssemblyOriginMixin {
    @Inject(method="assemble",at=@At("HEAD"),remap=false,require=0)
    private void animeoncraft$source(Level level,BlockPos pos,CallbackInfoReturnable<Boolean> result){
        ((AssemblyOrigin)(Object)this).animeoncraft$origin(pos);
    }
}
