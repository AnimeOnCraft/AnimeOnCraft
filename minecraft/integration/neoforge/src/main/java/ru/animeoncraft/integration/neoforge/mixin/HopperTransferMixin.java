package ru.animeoncraft.integration.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import ru.animeoncraft.integration.neoforge.ContainerLocks;
import ru.animeoncraft.integration.neoforge.TerritoryProtection;

@Mixin(HopperBlockEntity.class)
public abstract class HopperTransferMixin {
    @WrapMethod(method="ejectItems")
    private static boolean animeoncraft$eject(Level level,BlockPos pos,HopperBlockEntity hopper,Operation<Boolean> original){
        BlockPos destination=pos.relative(hopper.getBlockState().getValue(HopperBlock.FACING));
        if(!TerritoryProtection.canTransfer(level,pos,destination)||!ContainerLocks.canReach(level,pos,destination))return false;
        return ContainerLocks.trusted(()->original.call(level,pos,hopper));
    }
    @WrapMethod(method="suckInItems")
    private static boolean animeoncraft$extract(Level level,Hopper hopper,Operation<Boolean> original){
        BlockPos destination=BlockPos.containing(hopper.getLevelX(),hopper.getLevelY(),hopper.getLevelZ());
        if(!TerritoryProtection.canTransfer(level,destination.above(),destination)||!ContainerLocks.canReach(level,destination,destination.above()))return false;
        return ContainerLocks.trusted(()->original.call(level,hopper));
    }
}
