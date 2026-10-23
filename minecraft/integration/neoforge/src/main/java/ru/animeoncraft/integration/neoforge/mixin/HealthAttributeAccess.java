package ru.animeoncraft.integration.neoforge.mixin;

import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RangedAttribute.class)
public interface HealthAttributeAccess {
    @Accessor("maxValue") double aocMaximum();
    @Mutable @Accessor("maxValue") void aocMaximum(double value);
}
