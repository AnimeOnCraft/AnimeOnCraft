package ru.animeoncraft.integration.neoforge.mixin;

import net.minecraft.client.model.PlayerModel;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.animeoncraft.integration.neoforge.ClientEmotes;

@Mixin(PlayerModel.class)
public abstract class PlayerEmoteMixin {
    @Inject(method="setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V",at=@At("HEAD"))
    private void animeoncraft$reset(LivingEntity player,float walk,float amount,float age,float yaw,float pitch,CallbackInfo info){ClientEmotes.reset((PlayerModel<?>)(Object)this);}
    @Inject(method="setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V",at=@At("RETURN"))
    private void animeoncraft$emote(LivingEntity player,float walk,float amount,float age,float yaw,float pitch,CallbackInfo info){ClientEmotes.apply((PlayerModel<?>)(Object)this,player);}
}
