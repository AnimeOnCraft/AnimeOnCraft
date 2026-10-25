package ru.animeoncraft.integration.neoforge.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import ru.animeoncraft.integration.neoforge.ContainerLocks;

/** A Sophisticated Storage controller does not see storages locked by somebody other than the controller's own lock owner. */
@Pseudo
@Mixin(targets="net.p3pp3rf1y.sophisticatedcore.controller.ControllerBlockEntityBase",remap=false)
public abstract class SophisticatedControllerLockMixin {
    @WrapOperation(method="*",at=@At(value="INVOKE",target="Lnet/p3pp3rf1y/sophisticatedcore/util/WorldHelper;getLoadedBlockEntity(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Ljava/lang/Class;)Ljava/util/Optional;",remap=false),remap=false)
    private Optional<?> animeoncraft$loaded(Level level,BlockPos pos,Class<?> type,Operation<Optional<?>> original){
        if(!ContainerLocks.canReach(level,((BlockEntity)(Object)this).getBlockPos(),pos))return Optional.empty();
        return original.call(level,pos,type);
    }
    @WrapOperation(method="*",at=@At(value="INVOKE",target="Lnet/p3pp3rf1y/sophisticatedcore/util/WorldHelper;getBlockEntity(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Ljava/lang/Class;)Ljava/util/Optional;",remap=false),remap=false)
    private Optional<?> animeoncraft$any(BlockGetter level,BlockPos pos,Class<?> type,Operation<Optional<?>> original){
        if(level instanceof Level world&&!ContainerLocks.canReach(world,((BlockEntity)(Object)this).getBlockPos(),pos))return Optional.empty();
        return original.call(level,pos,type);
    }
}
