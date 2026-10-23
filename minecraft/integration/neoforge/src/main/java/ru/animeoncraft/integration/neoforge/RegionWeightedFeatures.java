package ru.animeoncraft.integration.neoforge;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.neoforged.neoforge.common.world.BiomeModifier;
import net.neoforged.neoforge.common.world.ModifiableBiomeInfo;

/**
 * {@code animeoncraft:region_weighted_features}: wraps listed placed features (vanilla and modded ores) with
 * {@link RegionWeightModifier}. Runs in AFTER_EVERYTHING so ores added by other mods' biome modifiers are
 * found. Feature ids are plain strings: unknown ids are reported by the worldgen probe instead of failing.
 */
record RegionWeightedFeatures(Map<String, List<ResourceLocation>> families) implements BiomeModifier {
    static final MapCodec<RegionWeightedFeatures> CODEC = Codec.unboundedMap(Codec.STRING, ResourceLocation.CODEC.listOf())
            .fieldOf("families").xmap(RegionWeightedFeatures::new, RegionWeightedFeatures::families);
    /** Probe statistics: family → wrapped feature ids actually found in biomes. */
    static final Map<String, Set<String>> WRAPPED = new ConcurrentHashMap<>();
    private static final Map<String, Holder<PlacedFeature>> CACHE = new ConcurrentHashMap<>();

    @Override public void modify(Holder<Biome> biome, Phase phase, ModifiableBiomeInfo.BiomeInfo.Builder builder) {
        if (phase != Phase.AFTER_EVERYTHING) return;
        var lookup = new HashMap<String, String>();
        families.forEach((family, ids) -> ids.forEach(id -> lookup.put(id.toString(), family)));
        var settings = builder.getGenerationSettings();
        for (GenerationStep.Decoration step : GenerationStep.Decoration.values()) {
            List<Holder<PlacedFeature>> features = settings.getFeatures(step);
            for (int i = 0; i < features.size(); i++) {
                Holder<PlacedFeature> holder = features.get(i);
                var key = holder.unwrapKey();
                if (key.isEmpty()) continue;
                String id = key.get().location().toString(), family = lookup.get(id);
                if (family == null) continue;
                features.set(i, CACHE.computeIfAbsent(id, ignored -> {
                    PlacedFeature original = holder.value();
                    var modifiers = new ArrayList<PlacementModifier>(original.placement().size() + 1);
                    modifiers.add(new RegionWeightModifier(family));
                    modifiers.addAll(original.placement());
                    return Holder.direct(new PlacedFeature(original.feature(), List.copyOf(modifiers)));
                }));
                WRAPPED.computeIfAbsent(family, ignored -> new TreeSet<>()).add(id);
            }
        }
    }

    @Override public MapCodec<? extends BiomeModifier> codec() { return CODEC; }
}
