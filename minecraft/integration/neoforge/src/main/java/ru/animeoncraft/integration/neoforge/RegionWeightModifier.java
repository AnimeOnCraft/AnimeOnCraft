package ru.animeoncraft.integration.neoforge;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.PlacementModifierType;

/**
 * {@code animeoncraft:region_weight}: repeats (or drops) a placement attempt according to the ore multiplier of
 * the region at the chunk origin. Prepended to ore features, so the following count/in_square modifiers
 * spread the extra attempts. Pass-through when masks are inactive (any world without the states datapack).
 */
final class RegionWeightModifier extends PlacementModifier {
    static final MapCodec<RegionWeightModifier> CODEC = Codec.STRING.fieldOf("family").xmap(RegionWeightModifier::new, m -> m.family);
    static volatile PlacementModifierType<RegionWeightModifier> type;
    final String family;

    RegionWeightModifier(String family) {
        if (!family.matches("[A-Z]{3,16}")) throw new IllegalArgumentException("Invalid resource family");
        this.family = family;
    }

    @Override public Stream<BlockPos> getPositions(PlacementContext context, RandomSource random, BlockPos pos) {
        if (!WorldMasks.active()) return Stream.of(pos);
        int n = WorldMasks.attempts(WorldMasks.multiplier(family, pos.getX() + 8, pos.getZ() + 8), random.nextDouble());
        return n == 0 ? Stream.empty() : n == 1 ? Stream.of(pos) : Stream.generate(() -> pos).limit(n);
    }

    @Override public PlacementModifierType<?> type() { return type; }
    @Override public boolean equals(Object other) { return other instanceof RegionWeightModifier m && m.family.equals(family); }
    @Override public int hashCode() { return family.hashCode(); }
}
