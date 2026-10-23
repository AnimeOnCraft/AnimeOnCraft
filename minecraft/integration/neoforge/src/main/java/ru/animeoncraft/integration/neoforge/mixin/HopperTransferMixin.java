package ru.animeoncraft.integration.neoforge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.animeoncraft.integration.neoforge.TerritoryProtection;

@Mixin(HopperBlockEntity.class)
public abstract class HopperTransferMixin {
    @Inject(method="ejectItems",at=@At("HEAD"),cancellable=true)
    private static void animeoncraft$eject(Level level,BlockPos pos,HopperBlockEntity hopper,CallbackInfoReturnable<Boolean> result){
        BlockPos destination=pos.relative(hopper.getBlockState().getValue(HopperBlock.FACING));
        if(!TerritoryProtection.canTransfer(level,pos,destination))result.setReturnValue(false);
    }
    @Inject(method="suckInItems",at=@At("HEAD"),cancellable=true)
    private static void animeoncraft$extract(Level level,Hopper hopper,CallbackInfoReturnable<Boolean> result){
        BlockPos destination=BlockPos.containing(hopper.getLevelX(),hopper.getLevelY(),hopper.getLevelZ());
        if(!TerritoryProtection.canTransfer(level,destination.above(),destination))result.setReturnValue(false);
    }
}
