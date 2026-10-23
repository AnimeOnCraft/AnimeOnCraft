package ru.animeoncraft.integration.neoforge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.animeoncraft.integration.neoforge.AssemblyOrigin;
import ru.animeoncraft.integration.neoforge.TerritoryProtection;

@Pseudo
@Mixin(targets="com.simibubi.create.content.contraptions.Contraption",remap=false)
public abstract class CreateContraptionMixin implements AssemblyOrigin {
    @Shadow(remap=false) public BlockPos anchor;
    @Unique private BlockPos animeoncraft$origin;
    @Override public void animeoncraft$origin(BlockPos pos){animeoncraft$origin=pos.immutable();}
    @Inject(method="moveBlock",at=@At("HEAD"),remap=false,cancellable=true)
    private void animeoncraft$assembly(Level level,Direction direction,java.util.Queue<BlockPos> queue,java.util.Set<BlockPos> visited,CallbackInfoReturnable<Boolean> result){
        BlockPos target=queue.peek();
        BlockPos source=animeoncraft$origin==null?anchor:animeoncraft$origin;
        if(target!=null&&source!=null&&!TerritoryProtection.canConstructFrom(level,source,target))result.setReturnValue(false);
    }
}
