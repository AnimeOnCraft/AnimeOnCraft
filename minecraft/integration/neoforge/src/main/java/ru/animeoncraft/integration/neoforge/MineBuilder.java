package ru.animeoncraft.integration.neoforge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Mine entrance of a resource pit (layout type MINE_ENTRANCE, code {@code mine.<site code>}, docs/operations/RESOURCE_MINES.md):
 * a portal where a stair corridor leaves the ground, and a lit chamber {@link #DEPTH} blocks under the site floor whose
 * walls, ceiling and pillars hold the ore slots. Built into a live world like {@link SoraExpansion}: planned in one step,
 * then a few hundred blocks per step; refused as a whole, before any change, when the volume or its shell holds a block
 * that is neither natural ground nor the layout's own. Slots and the protected shell go to
 * {@code animeoncraft-capture/mines/<site>.json}; {@link ResourcePits} then fills the slots instead of seeding veins.
 */
final class MineBuilder implements LayoutJobs.Job {
    static final String TYPE = "MINE_ENTRANCE";
    static final int DEPTH = 8;
    private static final int[] HALF = {4, 6, 7, 10}, HEIGHT = {4, 4, 5, 6};
    private static final int GALLERY = 6, BATCH = 384;
    private static final System.Logger LOG = System.getLogger("AnimeOnCraft");
    private record Op(BlockPos pos, BlockState state) {}
    private final WorldLayout.Poi poi;
    private final String site;
    private PoiBuilder.Canvas canvas;
    private final List<Op> ops = new ArrayList<>();
    /** Air of the chamber and galleries (ore faces), of the corridor, and every block the mine owns (protected). */
    private final Set<Long> hall = new LinkedHashSet<>(), corridor = new HashSet<>(), owned = new LinkedHashSet<>(), lamps = new HashSet<>();
    private final List<BlockPos> slots = new ArrayList<>();
    private Direction forward;
    private int phase, index, floor, exit, exitY;

    MineBuilder(WorldLayout.Poi poi) { this.poi = poi; this.site = poi.code().substring("mine.".length()); }
    @Override public String code() { return poi.code(); }
    @Override public String progress() { return poi.code() + (phase == 0 ? " план" : " " + index + "/" + ops.size()); }

    static int tier(WorldLayout.Poi poi) {
        if (poi.tier() >= 1 && poi.tier() <= 4) return poi.tier();
        return poi.radius() >= 32 ? 4 : poi.radius() >= 24 ? 3 : poi.radius() >= 20 ? 2 : 1;
    }
    static Path file(MinecraftServer server, String site) { return ResourcePits.directory(server).resolve("mines").resolve(site + ".json"); }

    @Override public boolean step(ServerLevel level) {
        if (phase == 0) { plan(level); phase = 1; return false; }
        for (int done = 0; done < BATCH && index < ops.size(); done++, index++) {
            var op = ops.get(index); canvas.set(op.pos().getX(), op.pos().getY(), op.pos().getZ(), op.state());
        }
        if (index < ops.size()) return false;
        finish(level);
        return true;
    }

    // ------------------------------------------------------------------ plan
    private void plan(ServerLevel level) {
        var layout = WorldLayout.state();
        canvas = new PoiBuilder.Canvas(level, layout.palettes().getOrDefault(poi.palette(), layout.palettes().get("neutral")));
        int t = tier(poi), half = HALF[t - 1], height = HEIGHT[t - 1];
        floor = siteFloor(level, layout) - DEPTH;
        forward = choose(level, layout, half);
        var stairs = new LinkedHashSet<Long>(); var structure = new java.util.LinkedHashMap<Long, BlockState>();
        // Chamber with pillars, then galleries.
        int p = Math.max(2, half / 2);
        for (int dx = -half; dx <= half; dx++) for (int dz = -half; dz <= half; dz++) {
            boolean pillar = t == 1 ? dx == 0 && dz == 0 : Math.abs(dx) == p && Math.abs(dz) == p || t == 4 && (dx == 0 && Math.abs(dz) == p || dz == 0 && Math.abs(dx) == p);
            if (pillar) continue;
            for (int y = floor; y < floor + height; y++) hall.add(BlockPos.asLong(poi.x() + dx, y, poi.z() + dz));
        }
        var galleries = new ArrayList<Direction>();
        if (t == 2 || t == 4) galleries.add(forward.getOpposite());
        if (t >= 3) { galleries.add(forward.getClockWise()); galleries.add(forward.getCounterClockWise()); }
        for (var g : galleries) for (int d = half + 1; d <= half + GALLERY; d++) for (int lat = -1; lat <= 1; lat++) for (int y = floor; y < floor + 3; y++)
            hall.add(at(g, d, lat, y).asLong());
        // Corridor: one stair per block up to where it meets the ground, three wide, three high above the treads.
        for (int d = half + 1; d <= exit; d++) {
            int s = floor + d - half - 1;
            for (int lat = -1; lat <= 1; lat++) {
                var tread = at(forward, d, lat, s);
                stairs.add(tread.asLong()); structure.put(tread.asLong(), Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, forward));
                for (int y = s + 1; y <= s + 3; y++) corridor.add(at(forward, d, lat, y).asLong());
            }
            // Timber sets every third step while the corridor is under ground.
            if ((d - half) % 3 == 1 && s + 4 < PoiBuilder.surface(level, at(forward, d, 0, s).getX(), at(forward, d, 0, s).getZ())) {
                for (int y = s + 1; y <= s + 3; y++) for (int lat : new int[] {-2, 2}) structure.put(at(forward, d, lat, y).asLong(), Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState());
                for (int lat = -2; lat <= 2; lat++) structure.put(at(forward, d, lat, s + 4).asLong(), Blocks.SPRUCE_PLANKS.defaultBlockState());
            }
        }
        // Portal: two posts and a lintel of the region's wall (stone bricks where the region builds in quartz), lanterns on top, and a landing in front.
        var post = canvas.wall.is(Blocks.QUARTZ_BRICKS) || canvas.wall.is(Blocks.QUARTZ_BLOCK) ? Blocks.STONE_BRICKS.defaultBlockState() : canvas.wall;
        for (int y = exitY + 1; y <= exitY + 4; y++) for (int lat : new int[] {-2, 2}) structure.put(at(forward, exit, lat, y).asLong(), post);
        for (int lat = -2; lat <= 2; lat++) structure.put(at(forward, exit, lat, exitY + 4).asLong(), post);
        for (int lat : new int[] {-2, 2}) structure.put(at(forward, exit, lat, exitY + 5).asLong(), Blocks.LANTERN.defaultBlockState());
        for (int d = exit + 1; d <= exit + 2; d++) for (int lat = -1; lat <= 1; lat++) {
            structure.put(at(forward, d, lat, exitY).asLong(), canvas.floor);
            for (int y = exitY + 1; y <= exitY + 3; y++) corridor.add(at(forward, d, lat, y).asLong());
        }
        structure.put(at(forward, exit + 1, -2, exitY + 2).asLong(), Blocks.SPRUCE_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, forward));
        // Lanterns hang from the chamber ceiling on a 4-block grid, and one at the end of each gallery.
        for (int dx = -half + 2; dx <= half - 2; dx += 4) for (int dz = -half + 2; dz <= half - 2; dz += 4) {
            long lamp = BlockPos.asLong(poi.x() + dx, floor + height - 1, poi.z() + dz);
            if (hall.contains(lamp)) lamps.add(lamp);
        }
        for (var g : galleries) lamps.add(at(g, half + GALLERY, 0, floor + 2).asLong());
        var air = new HashSet<Long>(hall); air.addAll(corridor);
        // Shell: every neighbour of the air. Under ground, holes, fluids and falling blocks become stone; the floor is paved.
        var seals = new java.util.LinkedHashMap<Long, BlockState>(); var shell = new LinkedHashSet<Long>();
        for (long cell : air) {
            var pos = BlockPos.of(cell);
            for (var dir : Direction.values()) {
                var next = pos.relative(dir); long key = next.asLong();
                if (air.contains(key) || structure.containsKey(key)) continue;
                boolean underground = next.getY() < PoiBuilder.surface(level, next.getX(), next.getZ());
                if (!underground && dir != Direction.DOWN) continue;
                shell.add(key);
                if (hall.contains(cell) && dir == Direction.DOWN) { seals.put(key, canvas.floor); continue; }
                var around = level.getBlockState(next);
                if (!solid(around) || wild(around)) seals.put(key, Blocks.STONE.defaultBlockState()); // trees are no wall
            }
        }
        for (long tread : stairs) { var below = BlockPos.of(tread).below(); if (!air.contains(below.asLong()) && !structure.containsKey(below.asLong())) {
            shell.add(below.asLong()); if (!solid(level.getBlockState(below))) seals.put(below.asLong(), Blocks.STONE.defaultBlockState()); } }
        // Refuse the whole build before any change when something here is not natural or ours.
        var all = new LinkedHashSet<Long>(air); all.addAll(structure.keySet()); all.addAll(shell);
        for (long cell : all) {
            var pos = BlockPos.of(cell); var state = level.getBlockState(pos);
            if (!SoraExpansion.replaceable(state, canvas, false) && !state.is(Blocks.STONE_BRICK_STAIRS) && !state.is(Blocks.SPRUCE_PLANKS) && !state.is(Blocks.SPRUCE_WALL_SIGN))
                throw new IllegalStateException(poi.code() + " site occupied at " + pos.toShortString() + " by " + BuiltInRegistries.BLOCK.getKey(state.getBlock()) + "; nothing was changed");
        }
        // Ore slots: wall and ceiling faces of the chamber and galleries, inside the site radius, not holding a lantern.
        var candidates = new LinkedHashSet<Long>();
        for (long cell : hall) {
            var pos = BlockPos.of(cell);
            for (var dir : Direction.values()) {
                if (dir == Direction.DOWN) continue;
                var next = pos.relative(dir); long key = next.asLong();
                if (air.contains(key) || structure.containsKey(key) || !shell.contains(key)) continue;
                if (dir == Direction.UP && lamps.contains(cell)) continue;
                if (Math.hypot(next.getX() - poi.x(), next.getZ() - poi.z()) > poi.radius() - 1) continue;
                candidates.add(key);
            }
        }
        var order = new ArrayList<>(candidates); Collections.shuffle(order, new Random(site.hashCode() * 31L + poi.x() * 7L + poi.z()));
        for (long key : order) slots.add(BlockPos.of(key));
        owned.addAll(shell); owned.addAll(structure.keySet()); owned.addAll(lamps);
        for (var slot : slots) owned.remove(slot.asLong());
        // Seal first so no water reaches the air, then dig, then fit out.
        for (var e : seals.entrySet()) ops.add(new Op(BlockPos.of(e.getKey()), e.getValue()));
        for (long cell : air) if (!level.getBlockState(BlockPos.of(cell)).isAir()) ops.add(new Op(BlockPos.of(cell), Blocks.AIR.defaultBlockState()));
        for (var e : structure.entrySet()) if (!e.getValue().is(Blocks.SPRUCE_WALL_SIGN)) ops.add(new Op(BlockPos.of(e.getKey()), e.getValue()));
        for (long lamp : lamps) ops.add(new Op(BlockPos.of(lamp), Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true)));
        // Open the view: wild leaves, tree trunks and plants in front of and over the portal go, so the entrance is seen from the path.
        // A trunk that is cut goes whole, so no log is left floating; its leaves decay on their own.
        var cleared = new HashSet<Long>();
        for (int d = exit - 2; d <= exit + 7; d++) for (int lat = -4; lat <= 4; lat++) for (int y = exitY + 1; y <= exitY + 16; y++) {
            var pos = at(forward, d, lat, y); long key = pos.asLong();
            if (air.contains(key) || structure.containsKey(key) || shell.contains(key) || cleared.contains(key)) continue;
            var state = level.getBlockState(pos);
            if (!wild(state)) continue;
            cleared.add(key); ops.add(new Op(pos, Blocks.AIR.defaultBlockState()));
            if (state.is(net.minecraft.tags.BlockTags.LOGS)) for (var up = pos.above(); up.getY() < pos.getY() + 40 && level.getBlockState(up).is(net.minecraft.tags.BlockTags.LOGS) && wild(level.getBlockState(up)); up = up.above())
                if (cleared.add(up.asLong())) ops.add(new Op(up, Blocks.AIR.defaultBlockState()));
        }
        System.out.println("AOC_MINE_PLAN " + poi.code() + " tier=" + t + " floor=" + floor + " facing=" + forward.getName() + " corridor=" + (exit - half)
            + " exit_y=" + exitY + " slots=" + slots.size() + " changes=" + ops.size());
    }

    private BlockPos at(Direction dir, int d, int lat, int y) {
        var right = dir.getClockWise();
        return new BlockPos(poi.x() + dir.getStepX() * d + right.getStepX() * lat, y, poi.z() + dir.getStepZ() * d + right.getStepZ() * lat);
    }
    /** Growth nobody placed: leaves that decay, untouched tree trunks, plants and vines. */
    private static boolean wild(BlockState state) {
        if (state.is(net.minecraft.tags.BlockTags.LEAVES)) return state.hasProperty(net.minecraft.world.level.block.LeavesBlock.PERSISTENT) && !state.getValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT);
        if (state.is(net.minecraft.tags.BlockTags.LOGS)) return !BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().startsWith("stripped_");
        return !state.isAir() && state.getFluidState().isEmpty() && state.canBeReplaced() || state.is(Blocks.VINE) || state.is(net.minecraft.tags.BlockTags.FLOWERS) || state.is(Blocks.SWEET_BERRY_BUSH);
    }
    private static boolean solid(BlockState state) {
        return !state.isAir() && state.getFluidState().isEmpty() && !state.canBeReplaced() && !(state.getBlock() instanceof FallingBlock);
    }

    /** The floor the site marker was built at; without a record, the ground at the centre. */
    private int siteFloor(ServerLevel level, WorldLayout.State layout) {
        var progress = LayoutJobs.progress(level.getServer());
        var floors = progress.getAsJsonObject("floors");
        if (progress.has("world_id") && progress.get("world_id").getAsString().equals(layout.worldId()) && floors != null && floors.has(site)) return floors.get(site).getAsInt();
        return PoiBuilder.surface(level, poi.x(), poi.z());
    }

    /** Side whose corridor reaches the ground soonest; water and leaving the site radius cost extra, the road side wins ties. */
    private Direction choose(ServerLevel level, WorldLayout.State layout, int half) {
        var road = WorldLayout.roadDirection(layout, poi.x(), poi.z(), 96);
        Direction best = null; double bestScore = Double.MAX_VALUE;
        for (var dir : Direction.Plane.HORIZONTAL) {
            int d = half + 1, s = floor;
            for (; d <= poi.radius() + 12; d++, s++) {
                var c = at(dir, d, 0, s);
                if (s >= PoiBuilder.surface(level, c.getX(), c.getZ()) - 1) break;
            }
            var c = at(dir, d, 0, s);
            double score = d + (PoiBuilder.water(level, c.getX(), c.getZ()) ? 1000 : 0) + (d > poi.radius() - 3 ? 200 : 0);
            if (road != null) score -= 0.5 * (dir.getStepX() * road[0] + dir.getStepZ() * road[1]) / Math.max(1e-6, Math.hypot(road[0], road[1]));
            if (score < bestScore) { bestScore = score; best = dir; exit = d; exitY = s; }
        }
        return best;
    }

    // ------------------------------------------------------------------ finish
    private void finish(ServerLevel level) {
        var signPos = at(forward, exit + 1, -2, exitY + 2);
        var signState = Blocks.SPRUCE_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, forward);
        level.setBlock(signPos, signState, net.minecraft.world.level.block.Block.UPDATE_ALL);
        if (level.getBlockEntity(signPos) instanceof SignBlockEntity sign) {
            var lines = signLines(poi.name(), tier(poi));
            var text = sign.getFrontText();
            for (int i = 0; i < 4; i++) text = text.setMessage(i, Component.literal(lines[i]));
            sign.setText(text, true); sign.setWaxed(true); sign.setChanged();
            level.sendBlockUpdated(signPos, signState, signState, net.minecraft.world.level.block.Block.UPDATE_ALL);
        }
        var json = new JsonObject();
        json.addProperty("format_version", 1); json.addProperty("site", site); json.addProperty("x", poi.x()); json.addProperty("z", poi.z());
        json.addProperty("tier", tier(poi)); json.addProperty("floor", floor); json.addProperty("facing", forward.getName());
        json.addProperty("half", HALF[tier(poi) - 1]); json.addProperty("exit", exit); json.addProperty("exit_y", exitY);
        json.addProperty("built_at", java.time.Instant.now().toString());
        var slotRows = new JsonArray(); for (var slot : slots) slotRows.add(row(slot)); json.add("slots", slotRows);
        var shellRows = new JsonArray(); for (long cell : owned) shellRows.add(row(BlockPos.of(cell))); json.add("shell", shellRows);
        try { ResourcePits.write(file(level.getServer(), site), json); }
        catch (IOException failure) { throw new IllegalStateException("Could not save mine " + site, failure); }
        MineShells.add(level.getServer(), json);
        CaptureAnchors.record(level.getServer(), poi.code(), floor);
        int filled = 0;
        var view = CaptureSites.view(); var point = view.points().get(site);
        if (view.rules() != null && point != null && point.resource() != null && "PIT".equals(point.resource().kind())) {
            try { filled = ResourcePits.adoptMine(level.getServer(), point); }
            catch (IOException | RuntimeException failure) { LOG.log(System.Logger.Level.WARNING, "Mine " + site + " built, ore fill deferred to the next refresh: " + failure.getMessage()); }
        }
        System.out.println("AOC_MINE_BUILT " + poi.code() + " floor=" + floor + " slots=" + slots.size() + " shell=" + owned.size() + " filled=" + filled);
    }
    private JsonArray row(BlockPos pos) { var row = new JsonArray(); row.add(pos.getX() - poi.x()); row.add(pos.getY()); row.add(pos.getZ() - poi.z()); return row; }

    /** "Шахта", the name over two lines of at most 15 characters, the tier. */
    static String[] signLines(String name, int tier) {
        var lines = new String[] {"Шахта", "", "", "T" + tier};
        int line = 1;
        for (var word : name.split(" ")) {
            if (!lines[line].isEmpty() && lines[line].length() + 1 + word.length() > 15) { if (line == 2) break; line++; }
            lines[line] = lines[line].isEmpty() ? word : lines[line] + " " + word;
        }
        return lines;
    }
}
