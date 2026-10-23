package ru.animeoncraft.integration.neoforge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.animeoncraft.integration.neoforge.TerritoryProtection;

@Pseudo
@Mixin(targets="com.simibubi.create.content.kinetics.base.BlockBreakingKineticBlockEntity",remap=false)
public abstract class CreateStationaryBreakerMixin {
    @Shadow(remap=false) protected abstract BlockPos getBreakingPos();
    @Inject(method="tick",at=@At("HEAD"),remap=false,cancellable=true)
    private void animeoncraft$break(CallbackInfo result){
        var entity=(BlockEntity)(Object)this;
        if(entity.getLevel()!=null&&!TerritoryProtection.canConstructFrom(entity.getLevel(),entity.getBlockPos(),getBreakingPos()))result.cancel();
    }
}
