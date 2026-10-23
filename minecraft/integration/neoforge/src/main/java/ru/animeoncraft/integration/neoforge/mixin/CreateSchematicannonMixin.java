package ru.animeoncraft.integration.neoforge.mixin;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.animeoncraft.integration.neoforge.TerritoryProtection;
@Pseudo
@Mixin(targets="com.simibubi.create.content.schematics.cannon.SchematicannonBlockEntity",remap=false)
public abstract class CreateSchematicannonMixin {
    @Inject(method="shouldPlace",at=@At("HEAD"),cancellable=true,remap=false)
    private void animeoncraft$print(BlockPos target,BlockState state,BlockEntity printedEntity,BlockState existing,BlockState other,boolean flag,CallbackInfoReturnable<Boolean> result){
        var cannon=(BlockEntity)(Object)this;
        if(cannon.getLevel()!=null&&!TerritoryProtection.canConstructFrom(cannon.getLevel(),cannon.getBlockPos(),target))result.setReturnValue(false);
    }
}
