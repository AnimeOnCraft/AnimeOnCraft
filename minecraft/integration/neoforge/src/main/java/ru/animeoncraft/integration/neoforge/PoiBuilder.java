package ru.animeoncraft.integration.neoforge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;

/**
 * Builds the POIs of the states world (World Design stage 5) from the layout: Sora, clan capitals, forts,
 * outposts, watchtowers, passes, bridges, ports, villages, arenas and resource-site markers.
 *
 * Every object is procedural, in the palette of its region, on prepared ground (filled below, cleared above).
 * A hand-built NBT template replaces the procedural one when the world datapack contains
 * {@code data/animeoncraft/structure/poi/<code>.nbt} (one point) or {@code poi/<type>.nbt} (every point of a type).
 */
final class PoiBuilder {
    private PoiBuilder() {}
    private static final System.Logger LOG = System.getLogger("AnimeOnCraft");

    // ------------------------------------------------------------------ small canvas over the level
    static final class Canvas {
        final ServerLevel level; final WorldLayout.Palette palette;
        final BlockState wall, roof, accent, floor;
        Canvas(ServerLevel level, WorldLayout.Palette palette) {
            this.level = level; this.palette = palette;
            wall = state(palette.wall(), Blocks.STONE_BRICKS); roof = state(palette.roof(), Blocks.DARK_OAK_PLANKS);
            accent = state(palette.accent(), Blocks.WHITE_WOOL); floor = state(palette.floor(), Blocks.POLISHED_ANDESITE);
        }
        /** Places a block with its shape fitted to the neighbours (fences, panes, walls connect). */
        void set(int x, int y, int z, BlockState state) {
            var pos = new BlockPos(x, y, z);
            level.setBlock(pos, Block.updateFromNeighbourShapes(state, level, pos), Block.UPDATE_ALL);
        }
        void box(int x1, int y1, int z1, int x2, int y2, int z2, BlockState state) {
            for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++)
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) set(x, y, z, state);
        }
        void hollow(int x1, int y1, int z1, int x2, int y2, int z2, BlockState shell) {
            box(x1, y1, z1, x2, y2, z2, shell);
            box(x1 + 1, y1 + 1, z1 + 1, x2 - 1, y2 - 1, z2 - 1, Blocks.AIR.defaultBlockState());
        }
        void lantern(int x, int y, int z) { set(x, y, z, Blocks.LANTERN.defaultBlockState()); }
        void post(int x, int y, int z, int height) {
            box(x, y, z, x, y + height - 1, z, Blocks.SPRUCE_FENCE.defaultBlockState());
            set(x, y + height, z, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, false));
        }
    }
    static BlockState state(String id, Block fallback) {
        var location = ResourceLocation.tryParse(id);
        if (location == null || !BuiltInRegistries.BLOCK.containsKey(location)) return fallback.defaultBlockState();
        return BuiltInRegistries.BLOCK.get(location).defaultBlockState();
    }

    // ------------------------------------------------------------------ ground
    static int sea(ServerLevel level) { return level.getChunkSource().getGenerator().getSeaLevel(); }
    /** Level.getHeight answers the bottom of the world for an unloaded chunk: load (generate) it first. */
    static void load(ServerLevel level, int x, int z) { level.getChunk(x >> 4, z >> 4); }
    static int surface(ServerLevel level, int x, int z) {
        load(level, x, z);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        // Trunks are not ground.
        while (y > level.getMinBuildHeight() + 1 && level.getBlockState(new BlockPos(x, y - 1, z)).is(BlockTags.LOGS)) y--;
        return y;
    }
    static boolean water(ServerLevel level, int x, int z) {
        load(level, x, z);
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        return level.getBlockState(new BlockPos(x, y, z)).getFluidState().isSource();
    }
    /** Median ground height of the footprint, at least one block above sea level. */
    static int groundLevel(ServerLevel level, int cx, int cz, int r) {
        var heights = new ArrayList<Integer>();
        for (int dx = -r; dx <= r; dx += Math.max(1, r / 6)) for (int dz = -r; dz <= r; dz += Math.max(1, r / 6))
            heights.add(water(level, cx + dx, cz + dz) ? sea(level) : surface(level, cx + dx, cz + dz));
        heights.sort(Comparator.naturalOrder());
        return Math.max(sea(level) + 1, heights.get(heights.size() / 2));
    }
    /**
     * Levels the footprint (circle or square) to {@code y}: ground below is filled with dirt/stone up to {@code y-1}
     * (topped with {@code top}), everything above is cleared for {@code clear} blocks. A 6-block skirt blends the edge.
     */
    static void prepare(Canvas canvas, int cx, int cz, int r, int y, boolean round, int clear, BlockState top) {
        var level = canvas.level;
        int skirt = 6;
        for (int dx = -r - skirt; dx <= r + skirt; dx++) for (int dz = -r - skirt; dz <= r + skirt; dz++) {
            double distance = round ? Math.sqrt(dx * dx + dz * dz) : Math.max(Math.abs(dx), Math.abs(dz));
            if (distance > r + skirt) continue;
            int x = cx + dx, z = cz + dz, ground = surface(level, x, z);
            int target = y;
            if (distance > r) { // skirt: ease towards the natural ground
                double t = (distance - r) / skirt;
                target = (int) Math.round(y + (ground - y) * t);
                if (Math.abs(ground - target) <= 1) continue;
            }
            for (int fy = Math.max(level.getMinBuildHeight() + 1, Math.min(ground, target) - 12); fy < target - 1; fy++) {
                var current = level.getBlockState(new BlockPos(x, fy, z));
                if (current.isAir() || !current.getFluidState().isEmpty() || current.canBeReplaced())
                    canvas.set(x, fy, z, fy < target - 4 ? Blocks.STONE.defaultBlockState() : Blocks.DIRT.defaultBlockState());
            }
            canvas.set(x, target - 1, z, distance > r ? Blocks.GRASS_BLOCK.defaultBlockState() : top);
            int ceiling = distance > r ? Math.max(target, ground) + 4 : target + clear;
            for (int fy = target; fy <= ceiling; fy++) {
                var current = level.getBlockState(new BlockPos(x, fy, z));
                if (!current.isAir()) canvas.set(x, fy, z, Blocks.AIR.defaultBlockState());
            }
        }
    }

    // ------------------------------------------------------------------ entry point
    /** Builds one POI; returns the floor level it was built at. */
    static int build(ServerLevel level, WorldLayout.State layout, WorldLayout.Poi poi) {
        var palette = layout.palettes().getOrDefault(poi.palette(), layout.palettes().get("neutral"));
        var canvas = new Canvas(level, palette);
        int y = switch (poi.type()) {
            case "CAPITAL" -> sora(canvas, layout, poi);
            case "CLAN_CAPITAL" -> capital(canvas, poi);
            case "FORT" -> fort(canvas, layout, poi);
            case "OUTPOST" -> outpost(canvas, layout, poi);
            case "WATCHTOWER" -> watchtower(canvas, poi);
            case "PASS" -> pass(canvas, layout, poi);
            case "BRIDGE" -> bridge(canvas, layout, poi);
            case "PORT" -> port(canvas, poi);
            case "VILLAGE" -> village(canvas, poi);
            case "EVENT_ZONE", "BOSS_LAIR" -> arena(canvas, poi, "BOSS_LAIR".equals(poi.type()));
            case "RESOURCE_SITE", "MINE" -> site(canvas, poi);
            default -> groundLevel(level, poi.x(), poi.z(), 4);
        };
        return y;
    }

    /** A hand-built template for this point or type, centred on the point, replaces the procedural build. */
    static boolean template(Canvas canvas, WorldLayout.Poi poi, int y) {
        var manager = canvas.level.getStructureManager();
        for (String name : List.of("poi/" + poi.code(), "poi/" + poi.type().toLowerCase(java.util.Locale.ROOT))) {
            var found = manager.get(ResourceLocation.fromNamespaceAndPath("animeoncraft", name));
            if (found.isEmpty()) continue;
            var template = found.get(); var size = template.getSize();
            var origin = new BlockPos(poi.x() - size.getX() / 2, y, poi.z() - size.getZ() / 2);
            template.placeInWorld(canvas.level, origin, origin, new StructurePlaceSettings(), canvas.level.getRandom(), Block.UPDATE_CLIENTS);
            LOG.log(System.Logger.Level.INFO, "AOC POI " + poi.code() + " placed from template animeoncraft:" + name);
            return true;
        }
        return false;
    }

    private static Direction facing(WorldLayout.State layout, WorldLayout.Poi poi, int reach) {
        var road = WorldLayout.roadDirection(layout, poi.x(), poi.z(), reach);
        double dx = road != null ? road[0] : -poi.x(), dz = road != null ? road[1] : -poi.z();
        return Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
    }

    /** Small house with a door on {@code door} side, windows, a lantern and a pitched slab-free roof layer. */
    static void house(Canvas c, int cx, int y, int cz, int halfX, int halfZ, int height, Direction door, BlockState roof) {
        c.hollow(cx - halfX, y, cz - halfZ, cx + halfX, y + height, cz + halfZ, c.wall);
        c.box(cx - halfX + 1, y, cz - halfZ + 1, cx + halfX - 1, y, cz + halfZ - 1, c.floor);
        c.box(cx - halfX - 1, y + height + 1, cz - halfZ - 1, cx + halfX + 1, y + height + 1, cz + halfZ + 1, roof);
        c.box(cx - halfX + 1, y + height + 2, cz - halfZ + 1, cx + halfX - 1, y + height + 2, cz + halfZ - 1, roof);
        int dx = door.getStepX(), dz = door.getStepZ();
        int doorX = cx + dx * halfX, doorZ = cz + dz * halfZ;
        c.box(doorX, y + 1, doorZ, doorX, y + 2, doorZ, Blocks.AIR.defaultBlockState());
        for (var side : new Direction[] {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            if (side == door) continue;
            int wx = cx + side.getStepX() * halfX, wz = cz + side.getStepZ() * halfZ;
            c.set(wx, y + 2, wz, Blocks.GLASS_PANE.defaultBlockState());
        }
        c.lantern(cx, y + 1, cz);
    }
    static void tower(Canvas c, int cx, int y, int cz, int half, int height) {
        c.hollow(cx - half, y, cz - half, cx + half, y + height, cz + half, c.wall);
        c.box(cx - half + 1, y, cz - half + 1, cx + half - 1, y, cz + half - 1, c.floor);
        c.box(cx - half - 1, y + height + 1, cz - half - 1, cx + half + 1, y + height + 1, cz + half + 1, c.floor);
        for (int i = -half - 1; i <= half + 1; i += 2) {
            c.set(cx + i, y + height + 2, cz - half - 1, c.wall); c.set(cx + i, y + height + 2, cz + half + 1, c.wall);
            c.set(cx - half - 1, y + height + 2, cz + i, c.wall); c.set(cx + half + 1, y + height + 2, cz + i, c.wall);
        }
        for (int ly = y + 1; ly <= y + height; ly++) c.set(cx, ly, cz - half + 1, Blocks.LADDER.defaultBlockState());
        c.lantern(cx + 1, y + 1, cz + 1);
    }
    /** Square curtain wall with crenellations and a gate gap on one side. */
    static void walls(Canvas c, int cx, int y, int cz, int half, int height, Direction gate, int gateHalf) {
        for (int i = -half; i <= half; i++) for (int[] side : new int[][] {{i, -half}, {i, half}, {-half, i}, {half, i}}) {
            c.box(cx + side[0], y, cz + side[1], cx + side[0], y + height - 1, cz + side[1], c.wall);
            if ((i & 1) == 0) c.set(cx + side[0], y + height, cz + side[1], c.wall);
        }
        int gx = cx + gate.getStepX() * half, gz = cz + gate.getStepZ() * half;
        if (gate.getAxis() == Direction.Axis.Z) c.box(gx - gateHalf, y, gz, gx + gateHalf, y + height, gz, Blocks.AIR.defaultBlockState());
        else c.box(gx, y, gz - gateHalf, gx, y + height, gz + gateHalf, Blocks.AIR.defaultBlockState());
    }
    static void flag(Canvas c, int x, int y, int z, int height) {
        c.box(x, y, z, x, y + height, z, Blocks.SPRUCE_FENCE.defaultBlockState());
        c.box(x + 1, y + height - 3, z, x + 3, y + height, z, c.accent);
    }

    // ------------------------------------------------------------------ Sora — the capital of One Sky
    static final int SORA_WALL = 110;
    private static int sora(Canvas c, WorldLayout.State layout, WorldLayout.Poi poi) {
        int cx = poi.x(), cz = poi.z(), y = groundLevel(c.level, cx, cz, 40);
        prepare(c, cx, cz, SORA_WALL + 4, y, true, 24, Blocks.GRASS_BLOCK.defaultBlockState());
        if (template(c, poi, y)) return y;
        var street = c.floor;
        // Plaza of One Sky: rings of floor and wall blocks, a beacon of light-blue sky in the middle.
        for (int dx = -36; dx <= 36; dx++) for (int dz = -36; dz <= 36; dz++) {
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d <= 36) c.set(cx + dx, y - 1, cz + dz, (int) d % 8 == 0 ? c.wall : street);
        }
        c.box(cx - 2, y - 1, cz - 2, cx + 2, y - 1, cz + 2, Blocks.IRON_BLOCK.defaultBlockState());
        c.box(cx - 1, y, cz - 1, cx + 1, y, cz + 1, Blocks.IRON_BLOCK.defaultBlockState());
        c.set(cx, y + 1, cz, Blocks.BEACON.defaultBlockState());
        c.set(cx, y + 2, cz, Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState());
        for (int[] corner : new int[][] {{-4, -4}, {4, -4}, {-4, 4}, {4, 4}}) c.box(cx + corner[0], y, cz + corner[1], cx + corner[0], y + 6, cz + corner[1], c.wall);
        // City wall with five gates where the main roads leave towards the clan states.
        var bearings = new ArrayList<Double>();
        for (var road : layout.roads().values()) if (road.code().startsWith("road.main.")) {
            var first = road.points().getFirst();
            bearings.add(Math.atan2(first[0] - cx, -(first[1] - cz)));
        }
        for (int step = 0; step < 720; step++) {
            double angle = step * Math.PI / 360;
            boolean gate = bearings.stream().anyMatch(b -> Math.abs(Math.atan2(Math.sin(angle - b), Math.cos(angle - b))) < 4.5 / SORA_WALL);
            int x = cx + (int) Math.round(SORA_WALL * Math.sin(angle)), z = cz - (int) Math.round(SORA_WALL * Math.cos(angle));
            if (gate) { c.set(x, y - 1, z, street); continue; }
            c.box(x, y, z, x, y + 6, z, c.wall);
            if (step % 4 < 2) c.set(x, y + 7, z, c.wall);
        }
        for (double b : bearings) {
            // Streets from the plaza to the gates, gate towers on both sides.
            for (int r = 36; r <= SORA_WALL + 2; r++) for (int w = -2; w <= 2; w++) {
                int x = cx + (int) Math.round(r * Math.sin(b) + w * Math.cos(b)), z = cz + (int) Math.round(-r * Math.cos(b) + w * Math.sin(b));
                c.set(x, y - 1, z, street);
            }
            for (int side : new int[] {-1, 1}) {
                int tx = cx + (int) Math.round(SORA_WALL * Math.sin(b) + side * 6 * Math.cos(b)), tz = cz + (int) Math.round(-SORA_WALL * Math.cos(b) + side * 6 * Math.sin(b));
                tower(c, tx, y, tz, 2, 10);
            }
            for (int r = 44; r <= 100; r += 14) for (int side : new int[] {-1, 1}) {
                int lx = cx + (int) Math.round(r * Math.sin(b) + side * 3.5 * Math.cos(b)), lz = cz + (int) Math.round(-r * Math.cos(b) + side * 3.5 * Math.sin(b));
                c.post(lx, y, lz, 3);
            }
        }
        // Market: one stall per clan between the streets; embassies beside each street; the hall of gates in the south.
        var clans = new ArrayList<String>();
        for (var name : layout.palettes().keySet()) if (name.matches("[A-Z0-9]{2,12}")) clans.add(name);
        for (int i = 0; i < bearings.size(); i++) {
            double b = bearings.get(i), between = b + Math.PI / Math.max(1, bearings.size());
            var clanPalette = i < clans.size() ? layout.palettes().get(clans.get(i)) : c.palette;
            var clanCanvas = new Canvas(c.level, clanPalette);
            int sx = cx + (int) Math.round(60 * Math.sin(between)), sz = cz - (int) Math.round(60 * Math.cos(between));
            clanCanvas.box(sx - 3, y, sz - 2, sx - 3, y + 3, sz - 2, Blocks.SPRUCE_FENCE.defaultBlockState());
            clanCanvas.box(sx + 3, y, sz - 2, sx + 3, y + 3, sz - 2, Blocks.SPRUCE_FENCE.defaultBlockState());
            clanCanvas.box(sx - 3, y, sz + 2, sx - 3, y + 3, sz + 2, Blocks.SPRUCE_FENCE.defaultBlockState());
            clanCanvas.box(sx + 3, y, sz + 2, sx + 3, y + 3, sz + 2, Blocks.SPRUCE_FENCE.defaultBlockState());
            clanCanvas.box(sx - 3, y + 4, sz - 2, sx + 3, y + 4, sz + 2, clanCanvas.accent);
            clanCanvas.box(sx - 2, y, sz - 1, sx + 2, y, sz - 1, Blocks.BARREL.defaultBlockState());
            int ex = cx + (int) Math.round(82 * Math.sin(b) + 13 * Math.cos(b)), ez = cz + (int) Math.round(-82 * Math.cos(b) + 13 * Math.sin(b));
            house(clanCanvas, ex, y, ez, 4, 4, 5, Math.abs(Math.sin(b)) > Math.abs(Math.cos(b)) ? (Math.cos(b) > 0 ? Direction.WEST : Direction.EAST) : (Math.sin(b) > 0 ? Direction.SOUTH : Direction.NORTH), clanCanvas.roof);
            flag(clanCanvas, ex, y + 8, ez, 4);
        }
        // Hall of gates: a nether portal frame (unlit) and room for Waystones and a Create station.
        int hx = cx, hz = cz + 52;
        house(c, hx, y, hz, 8, 6, 8, Direction.NORTH, c.roof);
        c.box(hx - 2, y + 1, hz + 2, hx + 1, y + 5, hz + 2, Blocks.OBSIDIAN.defaultBlockState());
        c.box(hx - 1, y + 2, hz + 2, hx, y + 4, hz + 2, Blocks.AIR.defaultBlockState());
        return y;
    }

    private static int capital(Canvas c, WorldLayout.Poi poi) {
        int y = groundLevel(c.level, poi.x(), poi.z(), 20);
        prepare(c, poi.x(), poi.z(), 24, y, false, 20, c.floor);
        if (template(c, poi, y)) return y;
        ClanCastles.build(c.level, poi.x(), y, poi.z(), new ClanCastles.Palette(c.wall.getBlock(), c.roof.getBlock(), c.accent.getBlock(), c.floor.getBlock()));
        flag(c, poi.x(), y + 13, poi.z(), 6);
        return y;
    }

    private static int fort(Canvas c, WorldLayout.State layout, WorldLayout.Poi poi) {
        int cx = poi.x(), cz = poi.z(), y = groundLevel(c.level, cx, cz, 15);
        prepare(c, cx, cz, 18, y, false, 16, c.floor);
        if (template(c, poi, y)) return y;
        var gate = facing(layout, poi, 120);
        c.box(cx - 15, y - 1, cz - 15, cx + 15, y - 1, cz + 15, c.floor);
        walls(c, cx, y, cz, 15, 6, gate, 2);
        for (int[] corner : new int[][] {{-15, -15}, {15, -15}, {-15, 15}, {15, 15}}) tower(c, cx + corner[0], y, cz + corner[1], 2, 10);
        house(c, cx - 7, y, cz - 7, 3, 4, 4, Direction.EAST, c.roof);
        house(c, cx + 7, y, cz - 7, 3, 4, 4, Direction.WEST, c.roof);
        flag(c, cx, y, cz + 4, 9);
        c.post(cx - 4, y, cz + 8, 3); c.post(cx + 4, y, cz + 8, 3);
        return y;
    }

    private static int outpost(Canvas c, WorldLayout.State layout, WorldLayout.Poi poi) {
        int cx = poi.x(), cz = poi.z(), y = groundLevel(c.level, cx, cz, 7);
        prepare(c, cx, cz, 9, y, false, 14, c.floor);
        if (template(c, poi, y)) return y;
        walls(c, cx, y, cz, 7, 2, facing(layout, poi, 60), 2);
        tower(c, cx, y, cz, 2, 12);
        flag(c, cx + 3, y, cz + 3, 6);
        return y;
    }

    private static int watchtower(Canvas c, WorldLayout.Poi poi) {
        int cx = poi.x(), cz = poi.z(), y = groundLevel(c.level, cx, cz, 5);
        prepare(c, cx, cz, 6, y, true, 26, c.floor);
        if (template(c, poi, y)) return y;
        tower(c, cx, y, cz, 3, 20);
        c.set(cx, y + 22, cz, Blocks.CAMPFIRE.defaultBlockState());
        return y;
    }

    private static int pass(Canvas c, WorldLayout.State layout, WorldLayout.Poi poi) {
        int cx = poi.x(), cz = poi.z(), y = groundLevel(c.level, cx, cz, 6);
        prepare(c, cx, cz, 14, y, true, 14, c.floor);
        if (template(c, poi, y)) return y;
        var direction = WorldLayout.roadDirection(layout, cx, cz, 200);
        double dx = direction == null ? 1 : direction[0], dz = direction == null ? 0 : direction[1];
        double px = -dz, pz = dx; // across the road
        for (int side : new int[] {-1, 1}) {
            int tx = cx + (int) Math.round(side * 6 * px), tz = cz + (int) Math.round(side * 6 * pz);
            tower(c, tx, y, tz, 2, 11);
            for (int w = 9; w <= 15; w++) {
                int wx = cx + (int) Math.round(side * w * px), wz = cz + (int) Math.round(side * w * pz);
                c.box(wx, y, wz, wx, y + 5, wz, c.wall);
            }
        }
        for (int w = -4; w <= 4; w++) {
            int ax = cx + (int) Math.round(w * px), az = cz + (int) Math.round(w * pz);
            c.box(ax, y + 7, az, ax, y + 8, az, c.wall);
        }
        c.lantern(cx, y + 6, cz);
        return y;
    }

    private static int bridge(Canvas c, WorldLayout.State layout, WorldLayout.Poi poi) {
        int cx = poi.x(), cz = poi.z();
        var direction = WorldLayout.roadDirection(layout, cx, cz, 200);
        double dx = direction == null ? 1 : direction[0], dz = direction == null ? 0 : direction[1], px = -dz, pz = dx;
        int half = poi.radius();
        int ends = Math.max(surface(c.level, cx + (int) Math.round(-half * dx), cz + (int) Math.round(-half * dz)),
            surface(c.level, cx + (int) Math.round(half * dx), cz + (int) Math.round(half * dz)));
        int y = Math.max(sea(c.level) + 3, ends);
        if (template(c, poi, y)) return y;
        var fence = Blocks.SPRUCE_FENCE.defaultBlockState();
        for (int t = -half; t <= half; t++) {
            for (int w = -3; w <= 3; w++) {
                int x = cx + (int) Math.round(t * dx + w * px), z = cz + (int) Math.round(t * dz + w * pz);
                if (Math.abs(w) <= 2) { c.set(x, y - 1, z, c.roof); for (int h = 0; h < 4; h++) c.set(x, y + h, z, Blocks.AIR.defaultBlockState()); }
                else c.set(x, y, z, fence);
            }
            if (t % 8 == 0) for (int w : new int[] {-3, 3}) {
                int x = cx + (int) Math.round(t * dx + w * px), z = cz + (int) Math.round(t * dz + w * pz);
                c.set(x, y + 1, z, Blocks.LANTERN.defaultBlockState());
                for (int py = y - 2; py > c.level.getMinBuildHeight() + 1; py--) {
                    var below = c.level.getBlockState(new BlockPos(x, py, z));
                    if (!below.isAir() && below.getFluidState().isEmpty() && !below.canBeReplaced()) break;
                    c.set(x, py, z, c.wall);
                }
            }
        }
        for (int side : new int[] {-1, 1}) tower(c, cx + (int) Math.round(side * (half + 3) * dx + 5 * px), y, cz + (int) Math.round(side * (half + 3) * dz + 5 * pz), 1, 6);
        return y;
    }

    private static int port(Canvas c, WorldLayout.Poi poi) {
        int cx = poi.x(), cz = poi.z(), y = sea(c.level) + 1;
        if (template(c, poi, y)) return y;
        // Piers run towards the open water: the direction with most water 20 blocks out.
        Direction sea = Direction.NORTH; int best = -1;
        for (var d : new Direction[] {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            int count = 0;
            for (int r = 8; r <= 24; r += 4) if (water(c.level, cx + d.getStepX() * r, cz + d.getStepZ() * r)) count++;
            if (count > best) { best = count; sea = d; }
        }
        prepare(c, cx - sea.getStepX() * 8, cz - sea.getStepZ() * 8, 6, Math.max(y, groundLevel(c.level, cx - sea.getStepX() * 8, cz - sea.getStepZ() * 8, 5)), false, 10, c.floor);
        c.box(cx - 7, y - 1, cz - 7, cx + 7, y - 1, cz + 7, c.roof);
        for (int pier = -6; pier <= 6; pier += 6) for (int r = 7; r <= 22; r++) {
            int x = cx + sea.getStepX() * r + (sea.getAxis() == Direction.Axis.Z ? pier : 0), z = cz + sea.getStepZ() * r + (sea.getAxis() == Direction.Axis.X ? pier : 0);
            c.box(x - (sea.getAxis() == Direction.Axis.Z ? 1 : 0), y - 1, z - (sea.getAxis() == Direction.Axis.X ? 1 : 0),
                x + (sea.getAxis() == Direction.Axis.Z ? 1 : 0), y - 1, z + (sea.getAxis() == Direction.Axis.X ? 1 : 0), c.roof);
            if (r % 5 == 0) c.post(x, y, z, 2);
        }
        house(c, cx - sea.getStepX() * 8, Math.max(y, sea(c.level) + 1), cz - sea.getStepZ() * 8, 3, 3, 4, sea, c.roof);
        return y;
    }

    /** A real vanilla village of the local biome type (jigsaw, as /place structure does); procedural huts as fallback. */
    private static int village(Canvas c, WorldLayout.Poi poi) {
        var level = c.level; var pos = new BlockPos(poi.x(), 64, poi.z());
        var biome = level.getBiome(pos);
        ResourceKey<Structure> key = biome.is(BiomeTags.HAS_VILLAGE_DESERT) ? BuiltinStructures.VILLAGE_DESERT
            : biome.is(BiomeTags.HAS_VILLAGE_SAVANNA) ? BuiltinStructures.VILLAGE_SAVANNA
            : biome.is(BiomeTags.HAS_VILLAGE_SNOWY) ? BuiltinStructures.VILLAGE_SNOWY
            : biome.is(BiomeTags.HAS_VILLAGE_TAIGA) ? BuiltinStructures.VILLAGE_TAIGA : BuiltinStructures.VILLAGE_PLAINS;
        int y = groundLevel(level, poi.x(), poi.z(), 12);
        if (template(c, poi, y)) return y;
        try {
            var structure = level.registryAccess().registryOrThrow(Registries.STRUCTURE).getOrThrow(key);
            var generator = level.getChunkSource().getGenerator();
            var start = structure.generate(level.registryAccess(), generator, generator.getBiomeSource(), level.getChunkSource().randomState(),
                level.getStructureManager(), level.getSeed(), new ChunkPos(pos), 0, level, holder -> true);
            if (start.isValid()) {
                BoundingBox box = start.getBoundingBox();
                var min = new ChunkPos(SectionPos.blockToSectionCoord(box.minX()), SectionPos.blockToSectionCoord(box.minZ()));
                var max = new ChunkPos(SectionPos.blockToSectionCoord(box.maxX()), SectionPos.blockToSectionCoord(box.maxZ()));
                ChunkPos.rangeClosed(min, max).forEach(chunk -> level.getChunk(chunk.x, chunk.z));
                ChunkPos.rangeClosed(min, max).forEach(chunk -> start.placeInChunk(level, level.structureManager(), generator, level.getRandom(),
                    new BoundingBox(chunk.getMinBlockX(), level.getMinBuildHeight(), chunk.getMinBlockZ(), chunk.getMaxBlockX(), level.getMaxBuildHeight(), chunk.getMaxBlockZ()), chunk));
                LOG.log(System.Logger.Level.INFO, "AOC POI " + poi.code() + " village " + key.location() + " pieces=" + start.getPieces().size());
                return y;
            }
        } catch (RuntimeException failure) { LOG.log(System.Logger.Level.WARNING, "Village structure failed at " + poi.code() + "; procedural huts instead"); }
        prepare(c, poi.x(), poi.z(), 14, y, true, 10, Blocks.DIRT_PATH.defaultBlockState());
        for (int[] spot : new int[][] {{-8, -8}, {8, -8}, {-8, 8}, {8, 8}}) house(c, poi.x() + spot[0], y, poi.z() + spot[1], 3, 3, 4, spot[1] < 0 ? Direction.SOUTH : Direction.NORTH, c.roof);
        c.hollow(poi.x() - 1, y - 1, poi.z() - 1, poi.x() + 1, y, poi.z() + 1, Blocks.COBBLESTONE.defaultBlockState());
        c.set(poi.x(), y - 1, poi.z(), Blocks.WATER.defaultBlockState());
        return y;
    }

    private static int arena(Canvas c, WorldLayout.Poi poi, boolean lair) {
        int cx = poi.x(), cz = poi.z(), r = poi.radius(), y = groundLevel(c.level, cx, cz, r / 2);
        prepare(c, cx, cz, r, y, true, 24, lair ? Blocks.MOSSY_COBBLESTONE.defaultBlockState() : Blocks.SMOOTH_STONE.defaultBlockState());
        if (template(c, poi, y)) return y;
        var ring = lair ? Blocks.MOSSY_STONE_BRICKS.defaultBlockState() : c.wall;
        for (int step = 0; step < 360; step++) {
            double a = Math.toRadians(step);
            int x = cx + (int) Math.round(r * Math.sin(a)), z = cz - (int) Math.round(r * Math.cos(a));
            c.box(x, y, z, x, y + 1, z, ring);
            if (step % 45 == 0) { c.box(x, y, z, x, y + 6, z, ring); c.lantern(x, y + 7, z); }
        }
        return y;
    }

    /** Resource site marker: a ring of path blocks and four lantern posts around the (untouched) pit. */
    private static int site(Canvas c, WorldLayout.Poi poi) {
        int cx = poi.x(), cz = poi.z(), r = poi.radius();
        int y = surface(c.level, cx, cz);
        if (template(c, poi, y)) return y;
        for (int step = 0; step < 720; step++) {
            double a = Math.toRadians(step / 2.0);
            int x = cx + (int) Math.round(r * Math.sin(a)), z = cz - (int) Math.round(r * Math.cos(a)), s = surface(c.level, x, z);
            var top = c.level.getBlockState(new BlockPos(x, s - 1, z));
            if (top.is(BlockTags.DIRT) || top.is(Blocks.SAND) || top.is(Blocks.GRAVEL) || top.is(Blocks.SNOW_BLOCK)) {
                c.set(x, s - 1, z, Blocks.DIRT_PATH.defaultBlockState());
                if (c.level.getBlockState(new BlockPos(x, s, z)).is(Blocks.SNOW)) c.set(x, s, z, Blocks.AIR.defaultBlockState()); // a snow layer would hide the ring
            }
        }
        for (var d : new Direction[] {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            int x = cx + d.getStepX() * r, z = cz + d.getStepZ() * r;
            c.post(x, surface(c.level, x, z), z, 2);
        }
        if ("CACHE".equals(poi.kind())) { int s = surface(c.level, cx, cz); c.set(cx, s, cz, Blocks.BARREL.defaultBlockState()); }
        return y;
    }
}
