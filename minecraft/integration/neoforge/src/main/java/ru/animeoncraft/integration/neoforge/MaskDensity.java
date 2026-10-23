package ru.animeoncraft.integration.neoforge;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * {@code animeoncraft:mask}: a 2D macro raster value mapped to [min, max]. Used only by the states-world
 * datapack to steer continentalness, erosion, temperature and vegetation (docs/WORLDGEN_PIPELINE.md).
 */
record MaskDensity(String mask, double min, double max) implements DensityFunction.SimpleFunction {
    static final MapCodec<MaskDensity> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.STRING.fieldOf("mask").forGetter(MaskDensity::mask),
            Codec.doubleRange(-4.0, 4.0).fieldOf("min").forGetter(MaskDensity::min),
            Codec.doubleRange(-4.0, 4.0).fieldOf("max").forGetter(MaskDensity::max))
        .apply(instance, MaskDensity::new));
    static final KeyDispatchDataCodec<MaskDensity> CODEC = KeyDispatchDataCodec.of(MAP_CODEC);

    MaskDensity {
        if (!mask.matches("[a-z0-9_]{1,32}") || !(max > min)) throw new IllegalArgumentException("Invalid AOC mask density");
    }

    @Override public double compute(FunctionContext context) {
        return min + (max - min) * WorldMasks.sample(mask, context.blockX(), context.blockZ());
    }
    @Override public double minValue() { return min; }
    @Override public double maxValue() { return max; }
    @Override public KeyDispatchDataCodec<? extends DensityFunction> codec() { return CODEC; }
}
