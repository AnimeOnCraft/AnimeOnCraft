package ru.animeoncraft.integration.neoforge.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.animeoncraft.integration.neoforge.TerritoryProtection;

/** Reject hostile area targets before damage, freezing or movement is applied. */
@Pseudo
@Mixin(targets="io.redspace.ironsspellbooks.entity.spells.AoeEntity",remap=false)
public abstract class SpellAreaTargetMixin {
    @Inject(method="canHitEntity",at=@At("HEAD"),remap=false,cancellable=true)
    private void animeoncraft$checkTarget(Entity target,CallbackInfoReturnable<Boolean> result){
        var projectile=(Projectile)(Object)this;
        if(!projectile.level().isClientSide()&&TerritoryProtection.hostilePlayerEffectDenied(target,projectile.getOwner()))result.setReturnValue(false);
    }
}
