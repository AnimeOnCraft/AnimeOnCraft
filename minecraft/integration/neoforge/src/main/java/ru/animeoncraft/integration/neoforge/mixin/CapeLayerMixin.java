package ru.animeoncraft.integration.neoforge.mixin;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.animeoncraft.integration.neoforge.CapeRenderProbe;
@Mixin(CapeLayer.class)
public abstract class CapeLayerMixin {
 @Inject(method="render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;FFFFFF)V",at=@At("HEAD"),cancellable=true)
 private void animeoncraft$mantle(PoseStack pose,MultiBufferSource source,int light,AbstractClientPlayer player,float a,float b,float c,float d,float e,float f,CallbackInfo callback){if(ru.animeoncraft.integration.neoforge.ClientBetaPass.mantle(player.getUUID()))callback.cancel();}
 @Inject(method="render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;FFFFFF)V",at=@At("TAIL"))
 private void animeoncraft$cape(PoseStack pose,MultiBufferSource source,int light,AbstractClientPlayer player,float a,float b,float c,float d,float e,float f,CallbackInfo callback){CapeRenderProbe.drawn(player);}
}
