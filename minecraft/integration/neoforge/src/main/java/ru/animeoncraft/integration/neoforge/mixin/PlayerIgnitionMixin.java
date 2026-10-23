package ru.animeoncraft.integration.neoforge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import ru.animeoncraft.integration.neoforge.TerritoryProtection;

/** Authorizes only the single fire placement from a player's ignition item. */
@Mixin(FlintAndSteelItem.class)
public abstract class PlayerIgnitionMixin {
    @Redirect(method="useOn", at=@At(value="INVOKE",target="Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean animeoncraft$ignite(Level level, BlockPos pos, BlockState state, int flags, UseOnContext context) {
        return TerritoryProtection.placePlayerFire(level,pos,state,flags,context.getPlayer());
    }
}
