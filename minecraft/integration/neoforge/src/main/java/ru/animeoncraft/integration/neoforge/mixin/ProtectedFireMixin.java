package ru.animeoncraft.integration.neoforge.mixin;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FireBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.animeoncraft.integration.neoforge.TerritoryProtection;
@Mixin(FireBlock.class)
public abstract class ProtectedFireMixin {
    @Inject(method="checkBurnOut",at=@At("HEAD"),cancellable=true)
    private void animeoncraft$burn(Level level,BlockPos target,int chance,RandomSource random,int age,Direction face,CallbackInfo result){
        if(TerritoryProtection.protectedPosition(level,target))result.cancel();
    }
}
