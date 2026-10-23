package ru.animeoncraft.integration.neoforge.mixin;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.FireChargeItem;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import ru.animeoncraft.integration.neoforge.TerritoryProtection;
@Mixin(FireChargeItem.class)
public abstract class PlayerFireChargeMixin {
    @Redirect(method="useOn",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/Level;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean animeoncraft$ignite(Level level,BlockPos pos,BlockState state,UseOnContext context){
        return TerritoryProtection.placePlayerFire(level,pos,state,3,context.getPlayer());
    }
}
