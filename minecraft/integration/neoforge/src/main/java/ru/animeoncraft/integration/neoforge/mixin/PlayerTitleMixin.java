package ru.animeoncraft.integration.neoforge.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.animeoncraft.integration.neoforge.ClientProfiles;

@Mixin(EntityRenderer.class)
public abstract class PlayerTitleMixin {
    @Inject(method="renderNameTag",at=@At("HEAD"))
    private void animeoncraft$title(Entity entity,Component name,PoseStack pose,MultiBufferSource buffers,int light,float partial,CallbackInfo info){ClientProfiles.renderTitle(entity,pose,buffers,light,partial);}
}
