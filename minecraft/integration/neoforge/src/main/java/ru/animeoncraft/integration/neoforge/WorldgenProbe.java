package ru.animeoncraft.integration.neoforge;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/**
 * Disposable-server worldgen probe (JVM flag {@code -Danimeoncraft.worldgenProbe=<output dir>}). Writes:
 * worldgen-inventory.json (placed features per biome, structure sets, wrapped ores), preview.json (biome and
 * height grid of the whole world from the noise generator, no chunk generation) and census.json (ores and
 * structures in really generated sample chunks per region). Never runs on a normal server.
 */
final class WorldgenProbe {
    private static final org.apache.logging.log4j.Logger LOGGER = org.apache.logging.log4j.LogManager.getLogger("AnimeOnCraft");
    private WorldgenProbe() {}

    static void started(ServerStartedEvent event) {
        String output = System.getProperty("animeoncraft.worldgenProbe");
        if (output == null) return;
        MinecraftServer server = event.getServer();
        try {
            if (!server.isDedicatedServer() || !server.getWorldData().getLevelName().startsWith("aoc-worldgen-test")) throw new IllegalStateException("Worldgen probe only runs in a disposable aoc-worldgen-test world");
            Path out = Path.of(output);
            Files.createDirectories(out);
            ServerLevel level = server.overworld();
            var gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
            Files.writeString(out.resolve("worldgen-inventory.json"), gson.toJson(inventory(server)));
            LOGGER.info("AOC_WORLDGEN_INVENTORY_DONE");
            int half = Integer.getInteger("animeoncraft.worldgenPreviewHalf", 10240), step = Integer.getInteger("animeoncraft.worldgenPreviewStep", 64);
            Files.writeString(out.resolve("preview.json"), gson.toJson(preview(level, half, step)));
            LOGGER.info("AOC_WORLDGEN_PREVIEW_DONE");
            if (Boolean.getBoolean("animeoncraft.worldgenLocate")) {
                Files.writeString(out.resolve("locate.json"), gson.toJson(locate(level, Integer.getInteger("animeoncraft.worldgenLocateChunks", 250))));
                LOGGER.info("AOC_WORLDGEN_LOCATE_DONE");
            }
            int radius = Integer.getInteger("animeoncraft.worldgenCensusRadius", 5600), spacing = Integer.getInteger("animeoncraft.worldgenCensusSpacing", 800);
            Files.writeString(out.resolve("census.json"), gson.toJson(census(level, radius, spacing, Integer.getInteger("animeoncraft.worldgenCensusCluster", 2))));
            LOGGER.info("AOC_WORLDGEN_PROBE_PASS");
        } catch (Exception failure) {
            LOGGER.error("AOC_WORLDGEN_PROBE_FAILED", failure);
        }
    }

    private static JsonObject inventory(MinecraftServer server) {
        var access = server.registryAccess();
        var root = new JsonObject();
        root.addProperty("masks_active", WorldMasks.active());
        if (WorldMasks.active()) root.addProperty("mask_digest", WorldMasks.state().digest());
        var placed = new JsonArray();
        new TreeSet<>(access.registryOrThrow(Registries.PLACED_FEATURE).keySet().stream().map(Object::toString).toList()).forEach(placed::add);
        root.add("placed_features", placed);
        var biomes = new JsonObject();
        var biomeRegistry = access.registryOrThrow(Registries.BIOME);
        for (var key : new TreeSet<>(biomeRegistry.keySet().stream().map(Object::toString).toList())) {
            Biome biome = biomeRegistry.get(net.minecraft.resources.ResourceLocation.parse(key));
            var steps = new JsonObject();
            var lists = biome.getGenerationSettings().features();
            for (GenerationStep.Decoration step : GenerationStep.Decoration.values()) {
                if (step.ordinal() >= lists.size()) continue;
                var ids = new JsonArray();
                for (var holder : lists.get(step.ordinal())) ids.add(holder.unwrapKey().map(k -> k.location().toString()).orElseGet(() -> "direct:" + describe(holder.value())));
                if (!ids.isEmpty()) steps.add(step.getSerializedName(), ids);
            }
            biomes.add(key, steps);
        }
        root.add("biomes", biomes);
        var structures = new JsonArray();
        new TreeSet<>(access.registryOrThrow(Registries.STRUCTURE_SET).keySet().stream().map(Object::toString).toList()).forEach(structures::add);
        root.add("structure_sets", structures);
        var wrapped = new JsonObject();
        new TreeMap<>(RegionWeightedFeatures.WRAPPED).forEach((family, ids) -> { var list = new JsonArray(); ids.forEach(list::add); wrapped.add(family, list); });
        root.add("region_weighted", wrapped);
        return root;
    }

    private static String describe(net.minecraft.world.level.levelgen.placement.PlacedFeature feature) {
        String inner = feature.feature().unwrapKey().map(k -> k.location().toString()).orElse("inline");
        boolean weighted = feature.placement().stream().anyMatch(m -> m instanceof RegionWeightModifier);
        return (weighted ? "aoc-weighted:" : "") + inner;
    }

    /** Biome id and surface height on a grid, straight from the noise generator (no chunks are created). */
    private static JsonObject preview(ServerLevel level, int half, int step) {
        var source = level.getChunkSource();
        var generator = source.getGenerator();
        var random = source.randomState();
        var sampler = random.sampler();
        int size = half * 2 / step;
        var palette = new LinkedHashMap<String, Integer>();
        var biomes = new JsonArray(); var heights = new JsonArray(); var regions = new JsonArray();
        for (int row = 0; row < size; row++) for (int column = 0; column < size; column++) {
            int x = -half + column * step + step / 2, z = -half + row * step + step / 2;
            int height = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, random);
            var biome = generator.getBiomeSource().getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(Math.max(height, generator.getSeaLevel())), QuartPos.fromBlock(z), sampler);
            String id = biome.unwrapKey().map(k -> k.location().toString()).orElse("unknown");
            biomes.add(palette.computeIfAbsent(id, ignored -> palette.size()));
            heights.add(height);
            regions.add(WorldMasks.active() ? WorldMasks.regionIndex(x, z) : -1);
        }
        var result = new JsonObject();
        result.addProperty("half", half); result.addProperty("step", step); result.addProperty("size", size);
        result.addProperty("sea_level", generator.getSeaLevel());
        var names = new JsonArray(); palette.keySet().forEach(names::add);
        result.add("palette", names); result.add("biomes", biomes); result.add("heights", heights); result.add("regions", regions);
        return result;
    }

    /**
     * Structure placement census without generating anything: for every structure set, the chunks within the
     * radius where its placement fires and the biome at that spot is allowed for one of its structures. Same
     * test for the states world and the vanilla baseline, so missing or vanished mod structures show up.
     * (Terrain checks of individual jigsaw pieces are not evaluated; /locate would, but is far too slow.)
     */
    private static JsonObject locate(ServerLevel level, int radiusChunks) {
        var source = level.getChunkSource();
        var generator = source.getGenerator();
        var state = source.getGeneratorState();
        var sampler = source.randomState().sampler();
        var sets = level.registryAccess().registryOrThrow(Registries.STRUCTURE_SET);
        var structures = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        var counts = new TreeMap<String, Integer>();
        for (var entry : sets.entrySet()) {
            var set = entry.getValue();
            for (var item : set.structures()) structures.getResourceKey(item.structure().value()).ifPresent(key -> counts.putIfAbsent(key.location().toString(), 0));
            for (int cx = -radiusChunks; cx <= radiusChunks; cx++) for (int cz = -radiusChunks; cz <= radiusChunks; cz++) {
                if (!set.placement().isStructureChunk(state, cx, cz)) continue;
                int x = (cx << 4) + 8, z = (cz << 4) + 8;
                var biome = generator.getBiomeSource().getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(generator.getSeaLevel() + 8), QuartPos.fromBlock(z), sampler);
                for (var item : set.structures()) {
                    var structure = item.structure().value();
                    if (structure.biomes().contains(biome)) {
                        structures.getResourceKey(structure).ifPresent(key -> counts.merge(key.location().toString(), 1, Integer::sum));
                        break;
                    }
                }
            }
        }
        var result = new JsonObject();
        result.addProperty("radius_chunks", radiusChunks);
        var found = new JsonObject(); counts.forEach(found::addProperty); result.add("candidates", found);
        return result;
    }

    /** Real chunk generation at grid points: ore block counts and structure starts per region. */
    private static JsonObject census(ServerLevel level, int radius, int spacing, int cluster) {
        var perRegion = new TreeMap<String, Map<String, Long>>();
        var chunksPerRegion = new TreeMap<String, Integer>();
        var structures = new TreeMap<String, Map<String, Integer>>();
        var structureRegistry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        long started = System.nanoTime();
        int generated = 0;
        for (int x = -radius; x <= radius; x += spacing) for (int z = -radius; z <= radius; z += spacing) {
            if ((long) x * x + (long) z * z > (long) radius * radius) continue;
            String region = WorldMasks.active() ? WorldMasks.regionCode(WorldMasks.regionIndex(x, z)) : "vanilla";
            for (int dx = 0; dx < cluster; dx++) for (int dz = 0; dz < cluster; dz++) {
                int cx = (x >> 4) + dx, cz = (z >> 4) + dz;
                ChunkAccess chunk = level.getChunk(cx, cz, ChunkStatus.FULL, true);
                generated++;
                chunksPerRegion.merge(region, 1, Integer::sum);
                var counts = perRegion.computeIfAbsent(region, ignored -> new TreeMap<>());
                var position = new BlockPos.MutableBlockPos();
                for (int y = level.getMinBuildHeight(); y < level.getMaxBuildHeight(); y++) for (int bx = 0; bx < 16; bx++) for (int bz = 0; bz < 16; bz++) {
                    var state = chunk.getBlockState(position.set((cx << 4) + bx, y, (cz << 4) + bz));
                    if (state.isAir()) continue;
                    String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                    if (id.contains("_ore") || id.endsWith("amethyst_cluster") || id.endsWith("budding_amethyst") || id.endsWith("arcane_debris")) counts.merge(id, 1L, Long::sum);
                }
                for (var start : chunk.getAllStarts().entrySet()) {
                    if (!start.getValue().isValid()) continue;
                    String id = structureRegistry.getKey(start.getKey()) == null ? "unknown" : structureRegistry.getKey(start.getKey()).toString();
                    structures.computeIfAbsent(region, ignored -> new TreeMap<>()).merge(id, 1, Integer::sum);
                }
            }
            if (generated % 64 == 0) LOGGER.info("AOC worldgen census: {} chunks", generated);
        }
        var result = new JsonObject();
        result.addProperty("radius", radius); result.addProperty("spacing", spacing); result.addProperty("cluster", cluster);
        result.addProperty("chunks", generated);
        result.addProperty("seconds", (System.nanoTime() - started) / 1e9);
        var regions = new JsonObject();
        for (var entry : perRegion.entrySet()) {
            var row = new JsonObject();
            row.addProperty("chunks", chunksPerRegion.get(entry.getKey()));
            var ores = new JsonObject(); entry.getValue().forEach(ores::addProperty); row.add("ores", ores);
            var found = new JsonObject(); structures.getOrDefault(entry.getKey(), Map.of()).forEach(found::addProperty); row.add("structures", found);
            regions.add(entry.getKey(), row);
        }
        result.add("regions", regions);
        return result;
    }

}
