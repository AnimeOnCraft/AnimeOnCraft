package ru.animeoncraft.integration.neoforge.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.animeoncraft.integration.neoforge.TerritoryProtection;

@Pseudo
@Mixin(targets="io.redspace.ironsspellbooks.entity.spells.BlizzardAoe",remap=false)
public abstract class SpellBlizzardEffectMixin {
    @Inject(method="applyEffect",at=@At("HEAD"),remap=false,cancellable=true)
    private void animeoncraft$checkFreeze(LivingEntity target,CallbackInfo result){
        var projectile=(Projectile)(Object)this;
        if(!projectile.level().isClientSide()&&TerritoryProtection.hostilePlayerEffectDenied(target,projectile.getOwner()))result.cancel();
    }
}
