package ru.animeoncraft.integration.neoforge;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.Tags;

/**
 * Enlarges an already built Sora without rebuilding it (layout type CITY_EXPANSION): levels the ring between the old
 * wall and a new outer wall at the POI radius, extends the five streets through new gates, lays a ring street and
 * puts up gate towers and lanterns. Resumable, a few columns per step, so a live server keeps ticking.
 *
 * Only natural ground and the layout's own blocks are changed. A column holding anything else (a player's build,
 * a chest, a modded block) is left as it is, together with a 2-block margin, and counted in the log.
 */
final class SoraExpansion implements LayoutJobs.Job {
    static final String TYPE = "CITY_EXPANSION";
    private static final int CLEAR = 24, MARGIN = 2, SCAN = 64, SHAPE = 12;
    private final WorldLayout.Poi poi;
    private PoiBuilder.Canvas canvas;
    private SoraRing ring;
    private List<int[]> columns;
    private final Set<Long> preserved = new HashSet<>();
    private final java.util.Map<String, Integer> kinds = new java.util.HashMap<>();
    private int y, phase, index, foreign;

    SoraExpansion(WorldLayout.Poi poi) { this.poi = poi; }
    @Override public String code() { return poi.code(); }
    @Override public String progress() {
        return poi.code() + " " + new String[] {"подготовка", "проверка построек", "кольцо", "башни"}[Math.min(phase, 3)] + (columns == null ? "" : " " + index + "/" + columns.size());
    }

    @Override public boolean step(ServerLevel level) {
        switch (phase) {
            case 0 -> {
                var layout = WorldLayout.state();
                var palette = layout.palettes().getOrDefault(poi.palette(), layout.palettes().get("neutral"));
                canvas = new PoiBuilder.Canvas(level, palette); ring = SoraRing.of(layout, poi);
                y = cityFloor(level, layout, poi.x(), poi.z());
                columns = new ArrayList<>();
                for (int dx = -ring.reach(); dx <= ring.reach(); dx++) for (int dz = -ring.reach(); dz <= ring.reach(); dz++)
                    if (ring.cell(dx, dz) != SoraRing.Cell.NONE) columns.add(new int[] {poi.x() + dx, poi.z() + dz});
                // Chunk by chunk keeps generation and lighting local.
                columns.sort((a, b) -> a[0] >> 4 != b[0] >> 4 ? Integer.compare(a[0] >> 4, b[0] >> 4) : a[1] >> 4 != b[1] >> 4 ? Integer.compare(a[1] >> 4, b[1] >> 4) : 0);
                System.out.println("AOC_SORA_EXPANSION start radius=" + poi.radius() + " floor=" + y + " columns=" + columns.size());
                phase = 1;
            }
            case 1 -> {
                for (int done = 0; done < SCAN && index < columns.size(); done++, index++) {
                    int[] c = columns.get(index);
                    int ground = PoiBuilder.surface(level, c[0], c[1]);
                    var found = firstForeign(level, canvas, c[0], c[1], Math.min(ground, y) - 12, ceiling(ground, y, false), ring.onRoad(c[0] - poi.x(), c[1] - poi.z()));
                    if (found != null) {
                        foreign++; kinds.merge(BuiltInRegistries.BLOCK.getKey(found.getBlock()).toString(), 1, Integer::sum);
                        for (int mx = -MARGIN; mx <= MARGIN; mx++) for (int mz = -MARGIN; mz <= MARGIN; mz++) preserved.add(ChunkPos.asLong(c[0] + mx, c[1] + mz));
                    }
                }
                if (index >= columns.size()) { phase = 2; index = 0; }
            }
            case 2 -> {
                for (int done = 0; done < SHAPE && index < columns.size(); done++, index++) shape(columns.get(index));
                if (index >= columns.size()) phase = 3;
            }
            default -> {
                for (int[] t : ring.towers()) if (free(t[0], t[1], 3)) PoiBuilder.tower(canvas, t[0], y, t[1], 2, 10);
                for (int[] l : ring.lanterns()) if (free(l[0], l[1], 0)) canvas.post(l[0], y, l[1], 3);
                CaptureAnchors.record(level.getServer(), poi.code(), y);
                System.out.println("AOC_SORA_EXPANSION done radius=" + poi.radius() + " floor=" + y + " columns=" + columns.size()
                    + " preserved_columns=" + preserved.size() + " foreign_columns=" + foreign);
                if (!kinds.isEmpty()) System.out.println("AOC_SORA_EXPANSION kept columns by first foreign block: " + kinds.entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue()).limit(12).map(e -> e.getKey() + "=" + e.getValue()).collect(java.util.stream.Collectors.joining(", ")));
                return true;
            }
        }
        return false;
    }

    private boolean free(int x, int z, int half) {
        for (int dx = -half; dx <= half; dx++) for (int dz = -half; dz <= half; dz++) if (preserved.contains(ChunkPos.asLong(x + dx, z + dz))) return false;
        return true;
    }

    private void shape(int[] c) {
        if (preserved.contains(ChunkPos.asLong(c[0], c[1]))) return;
        var level = canvas.level;
        int dx = c[0] - poi.x(), dz = c[1] - poi.z();
        var cell = ring.cell(dx, dz);
        int ground = PoiBuilder.surface(level, c[0], c[1]), target = y;
        double d = Math.hypot(dx, dz);
        boolean outer = d > ring.radius + 3;
        if (outer) { // skirt and street ramps: ease towards the natural ground, at most 1 block per SKIRT/|Δ|
            double t = Math.min(1, (d - ring.radius - 3) / SoraRing.SKIRT);
            target = (int) Math.round(y + (ground - y) * t * t * (3 - 2 * t));
            if (cell == SoraRing.Cell.SKIRT && Math.abs(ground - target) <= 1) return;
        }
        level(canvas, c[0], c[1], ground, target, ceiling(ground, target, outer),
            cell == SoraRing.Cell.STREET ? canvas.floor : Blocks.GRASS_BLOCK.defaultBlockState());
        if (cell == SoraRing.Cell.WALL) {
            canvas.box(c[0], y, c[1], c[0], y + 6, c[1], canvas.wall);
            if (ring.crenel(dx, dz)) canvas.set(c[0], y + 7, c[1], canvas.wall);
        }
    }
    private static int ceiling(int ground, int target, boolean outer) { return outer ? Math.max(target, ground) + 4 : Math.max(target + CLEAR, ground + 12); }

    /** Fills below {@code target} (only air, fluids and plants), sets the top block, clears above up to {@code ceiling}. */
    static void level(PoiBuilder.Canvas canvas, int x, int z, int ground, int target, int ceiling, BlockState top) {
        var level = canvas.level;
        for (int fy = Math.max(level.getMinBuildHeight() + 1, Math.min(ground, target) - 12); fy < target - 1; fy++) {
            var current = level.getBlockState(new BlockPos(x, fy, z));
            if (current.isAir() || !current.getFluidState().isEmpty() || current.canBeReplaced())
                canvas.set(x, fy, z, fy < target - 4 ? Blocks.STONE.defaultBlockState() : Blocks.DIRT.defaultBlockState());
        }
        canvas.set(x, target - 1, z, top);
        for (int fy = target; fy <= ceiling; fy++) if (!level.getBlockState(new BlockPos(x, fy, z)).isAir()) canvas.set(x, fy, z, Blocks.AIR.defaultBlockState());
    }

    /** True when the column holds a block that is neither natural ground nor one the layout itself places. */
    static boolean foreign(ServerLevel level, PoiBuilder.Canvas canvas, int x, int z, int from, int to) { return firstForeign(level, canvas, x, z, from, to, false) != null; }
    static BlockState firstForeign(ServerLevel level, PoiBuilder.Canvas canvas, int x, int z, int from, int to, boolean road) {
        for (int fy = Math.max(level.getMinBuildHeight(), from); fy <= Math.min(level.getMaxBuildHeight() - 1, to); fy++) {
            var state = level.getBlockState(new BlockPos(x, fy, z));
            if (!replaceable(state, canvas, road)) return state;
        }
        return null;
    }
    /** Natural ground, the palette and lamps of the layout anywhere; road paving and bridge decks only on a main road. */
    static boolean replaceable(BlockState s, PoiBuilder.Canvas canvas, boolean road) {
        if (s.isAir() || !s.getFluidState().isEmpty() || s.canBeReplaced() || s.getBlock() instanceof BushBlock) return true;
        if (s.is(BlockTags.DIRT) || s.is(BlockTags.SAND) || s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES)
            || s.is(BlockTags.SNOW) || s.is(BlockTags.ICE) || s.is(BlockTags.TERRACOTTA) || s.is(BlockTags.FLOWERS) || s.is(BlockTags.SAPLINGS)
            || s.is(BlockTags.CORAL_BLOCKS) || s.is(BlockTags.CAVE_VINES) || s.is(BlockTags.REPLACEABLE_BY_TREES)
            || s.is(Tags.Blocks.STONES) || s.is(Tags.Blocks.ORES) || s.is(Tags.Blocks.SANDS) || s.is(Tags.Blocks.GRAVELS)) return true;
        var id = BuiltInRegistries.BLOCK.getKey(s.getBlock());
        if (id.getPath().endsWith("_ore") || "create".equals(id.getNamespace()) && CREATE_STONES.contains(id.getPath())) return true;
        var block = s.getBlock();
        if (NATURAL.contains(block) || OURS.contains(block) || road && ROAD.contains(block)) return true;
        return block == canvas.wall.getBlock() || block == canvas.floor.getBlock() || block == canvas.roof.getBlock() || block == canvas.accent.getBlock();
    }
    /** Create's natural stone layers (world generation, not player blocks). */
    private static final Set<String> CREATE_STONES = Set.of("limestone", "veridium", "asurine", "crimsite", "ochrum", "scorchia", "scoria");
    private static final Set<net.minecraft.world.level.block.Block> NATURAL = Set.of(Blocks.GRAVEL, Blocks.CLAY, Blocks.SANDSTONE, Blocks.RED_SANDSTONE,
        Blocks.CALCITE, Blocks.AMETHYST_BLOCK, Blocks.BUDDING_AMETHYST, Blocks.AMETHYST_CLUSTER, Blocks.SMOOTH_BASALT, Blocks.DRIPSTONE_BLOCK, Blocks.POINTED_DRIPSTONE,
        Blocks.CACTUS, Blocks.SUGAR_CANE, Blocks.BAMBOO, Blocks.PUMPKIN, Blocks.MELON, Blocks.BROWN_MUSHROOM_BLOCK, Blocks.RED_MUSHROOM_BLOCK, Blocks.MUSHROOM_STEM,
        Blocks.SWEET_BERRY_BUSH, Blocks.BEE_NEST, Blocks.COCOA, Blocks.MOSS_CARPET, Blocks.AZALEA, Blocks.FLOWERING_AZALEA, Blocks.BIG_DRIPLEAF, Blocks.BIG_DRIPLEAF_STEM,
        Blocks.SMALL_DRIPLEAF, Blocks.SPORE_BLOSSOM, Blocks.GLOW_LICHEN, Blocks.SCULK, Blocks.SCULK_VEIN, Blocks.OBSIDIAN, Blocks.MAGMA_BLOCK, Blocks.PACKED_MUD,
        Blocks.SUSPICIOUS_SAND, Blocks.SUSPICIOUS_GRAVEL, Blocks.INFESTED_STONE, Blocks.INFESTED_DEEPSLATE, Blocks.KELP, Blocks.KELP_PLANT, Blocks.SEAGRASS,
        Blocks.TALL_SEAGRASS, Blocks.SEA_PICKLE, Blocks.LILY_PAD, Blocks.BEDROCK);
    /** Lamp posts and gate-tower fittings of the layout (with the palette: streets, walls, towers). */
    private static final Set<net.minecraft.world.level.block.Block> OURS = Set.of(Blocks.SPRUCE_FENCE, Blocks.LANTERN, Blocks.LADDER);
    /** What RoadBuilder lays on a main road: paving, fill under it, bridge decks and pillars. */
    private static final Set<net.minecraft.world.level.block.Block> ROAD = Set.of(Blocks.STONE_BRICKS, Blocks.POLISHED_ANDESITE, Blocks.COBBLESTONE,
        Blocks.MOSSY_COBBLESTONE, Blocks.DIRT_PATH, Blocks.COARSE_DIRT, Blocks.SPRUCE_PLANKS);

    /**
     * The floor of Sora as the builder saved it; without a record, the median top of the old streets 90–105 blocks out.
     * Never the natural ground: the ring must meet the old city exactly.
     */
    static int cityFloor(ServerLevel level, WorldLayout.State layout, int cx, int cz) {
        var progress = LayoutJobs.progress(level.getServer());
        var floors = progress.getAsJsonObject("floors");
        boolean sameWorld = progress.has("world_id") && progress.get("world_id").getAsString().equals(layout.worldId());
        if (sameWorld && floors != null && floors.has("core.capital")) return floors.get("core.capital").getAsInt();
        var heights = new ArrayList<Integer>();
        var ring = SoraRing.of(layout, new WorldLayout.Poi("core.capital", "CAPITAL", "", cx, cz, 160, "core", null, 0, null, null));
        for (double b : ring.bearings) for (int r = 90; r <= 105; r += 3)
            heights.add(PoiBuilder.surface(level, cx + (int) Math.round(r * Math.sin(b)), cz - (int) Math.round(r * Math.cos(b))));
        if (heights.isEmpty()) return PoiBuilder.groundLevel(level, cx, cz, 40);
        heights.sort(Integer::compare);
        return heights.get(heights.size() / 2);
    }
}
