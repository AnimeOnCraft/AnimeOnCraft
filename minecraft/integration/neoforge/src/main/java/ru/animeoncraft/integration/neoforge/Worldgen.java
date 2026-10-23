package ru.animeoncraft.integration.neoforge;

import com.mojang.serialization.MapCodec;
import java.nio.file.Files;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.placement.PlacementModifierType;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.world.BiomeModifier;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * Registers the states-world worldgen types. They do nothing on their own: only the world datapack
 * {@code animeoncraft-states} references them, so the current world is unaffected (World Design stage 3).
 */
final class Worldgen {
    private static final org.apache.logging.log4j.Logger LOGGER = org.apache.logging.log4j.LogManager.getLogger("AnimeOnCraft");
    private static final DeferredRegister<MapCodec<? extends DensityFunction>> DENSITY = DeferredRegister.create(Registries.DENSITY_FUNCTION_TYPE, "animeoncraft");
    private static final DeferredRegister<PlacementModifierType<?>> PLACEMENT = DeferredRegister.create(Registries.PLACEMENT_MODIFIER_TYPE, "animeoncraft");
    private static final DeferredRegister<MapCodec<? extends BiomeModifier>> BIOME_MODIFIERS = DeferredRegister.create(NeoForgeRegistries.Keys.BIOME_MODIFIER_SERIALIZERS, "animeoncraft");
    private Worldgen() {}

    static void register(IEventBus bus) {
        DENSITY.register("mask", () -> MaskDensity.MAP_CODEC);
        PLACEMENT.register("region_weight", () -> {
            PlacementModifierType<RegionWeightModifier> type = () -> RegionWeightModifier.CODEC;
            RegionWeightModifier.type = type;
            return type;
        });
        BIOME_MODIFIERS.register("region_weighted_features", () -> RegionWeightedFeatures.CODEC);
        DENSITY.register(bus); PLACEMENT.register(bus); BIOME_MODIFIERS.register(bus);
        var events = net.neoforged.neoforge.common.NeoForge.EVENT_BUS;
        events.addListener(Worldgen::aboutToStart);
        events.addListener(Worldgen::stopped);
    }

    /** Masks must be ready before the level and its spawn point are generated. */
    static void aboutToStart(ServerAboutToStartEvent event) {
        WorldMasks.clear(); WorldLayout.clear();
        var server = event.getServer();
        var aoc = server.getWorldPath(LevelResource.DATAPACK_DIR).resolve(WorldMasks.PACK).resolve("aoc");
        if (!Files.isDirectory(aoc)) return;
        try {
            WorldMasks.load(aoc, server.getWorldPath(LevelResource.ROOT));
            LOGGER.info("AOC states worldgen enabled: masks={} digest={}", WorldMasks.maskNames(), WorldMasks.state().digest());
            // Stage 5: POIs, roads and structure exclusion zones (optional file; absent on stage-3 test packs).
            var layout = aoc.resolve("layout.json");
            if (Files.isRegularFile(layout)) {
                var loaded = WorldLayout.load(layout);
                LOGGER.info("AOC states layout: pois={} roads={} structure exclusions={}", loaded.pois().size(), loaded.roads().size(), loaded.exclusions().size());
            }
        } catch (Exception failure) {
            throw new IllegalStateException("AOC states worldgen datapack is present but its masks are invalid; refusing to generate a broken world", failure);
        }
    }

    static void stopped(ServerStoppedEvent event) { WorldMasks.clear(); WorldLayout.clear(); }
}
