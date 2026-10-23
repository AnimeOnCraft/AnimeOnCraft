package ru.animeoncraft.integration.neoforge.mixin;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.animeoncraft.integration.neoforge.ClientSkins;
@Mixin(AbstractClientPlayer.class)
public abstract class PlayerSkinMixin {
 @Inject(method="getSkin",at=@At("RETURN"),cancellable=true)
 private void animeoncraft$skin(CallbackInfoReturnable<PlayerSkin> result){
  var player=(AbstractClientPlayer)(Object)this;
  result.setReturnValue(ClientSkins.appearance(player.getUUID(),result.getReturnValue()));
 }
}
