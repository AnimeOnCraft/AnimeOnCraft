package ru.animeoncraft.integration.neoforge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;

/**
 * Decoration of a resource site without a mine (layout type SITE_DECOR, code {@code decor.<site code>}): an entrance arch
 * with lanterns and a sign where the marker ring crosses the flattest dry side, and over a cache's barrel a timber canopy.
 * Built into a live world like {@link MineBuilder}: planned in one step, refused as a whole when a block in the way is
 * neither natural ground, wild growth nor the layout's own. The blocks go to {@code animeoncraft-capture/decor/<site>.json}
 * and {@link MineShells} keeps them from being broken.
 */
final class SiteDecor implements LayoutJobs.Job {
    static final String TYPE = "SITE_DECOR";
    private static final int BATCH = 256;
    private record Op(BlockPos pos, BlockState state) {}
    private final WorldLayout.Poi poi;
    private final String site;
    private PoiBuilder.Canvas canvas;
    private final List<Op> ops = new ArrayList<>();
    private final Map<Long, BlockState> structure = new LinkedHashMap<>();
    private Direction forward;
    private int phase, index, ground;

    SiteDecor(WorldLayout.Poi poi) { this.poi = poi; this.site = poi.code().substring("decor.".length()); }
    @Override public String code() { return poi.code(); }
    @Override public String progress() { return poi.code() + (phase == 0 ? " план" : " " + index + "/" + ops.size()); }
    static Path file(MinecraftServer server, String site) { return ResourcePits.directory(server).resolve("decor").resolve(site + ".json"); }

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
        var post = canvas.wall.is(Blocks.QUARTZ_BRICKS) || canvas.wall.is(Blocks.QUARTZ_BLOCK) ? Blocks.STONE_BRICKS.defaultBlockState() : canvas.wall;
        forward = choose(level);
        int r = poi.radius();
        ground = PoiBuilder.surface(level, at(forward, r, 0, 0).getX(), at(forward, r, 0, 0).getZ());
        // The marker's lantern post stands right here; the ground is under it, not on its top.
        while (level.getBlockState(at(forward, r, 0, ground - 1)).is(Blocks.SPRUCE_FENCE) || level.getBlockState(at(forward, r, 0, ground - 1)).is(Blocks.LANTERN)) ground--;
        // Arch on the marker ring: posts footed on their own ground, a lintel, lanterns on top.
        for (int lat : new int[] {-2, 2}) {
            var foot = at(forward, r, lat, 0); int low = Math.min(ground, PoiBuilder.surface(level, foot.getX(), foot.getZ()));
            for (int y = low; y <= ground + 3; y++) structure.put(at(forward, r, lat, y).asLong(), post);
        }
        for (int lat = -2; lat <= 2; lat++) structure.put(at(forward, r, lat, ground + 4).asLong(), post);
        for (int lat : new int[] {-2, 2}) structure.put(at(forward, r, lat, ground + 5).asLong(), Blocks.LANTERN.defaultBlockState()); // after the lintel they stand on
        structure.put(at(forward, r, 0, ground + 3).asLong(), Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
        structure.put(at(forward, r + 1, -2, ground + 2).asLong(), Blocks.SPRUCE_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, forward));
        // The marker's own lantern post on this side stands in the opening; the arch's lanterns take its place.
        for (int y = ground + 3; y >= ground - 1; y--) { // top first, so the lantern does not drop as an item
            var pos = at(forward, r, 0, y); var state = level.getBlockState(pos);
            if (!structure.containsKey(pos.asLong()) && (state.is(Blocks.SPRUCE_FENCE) || state.is(Blocks.LANTERN))) ops.add(new Op(pos, Blocks.AIR.defaultBlockState()));
        }
        // A cache's barrel gets a canopy: four timber posts and a slab roof with a lantern under it.
        if ("CACHE".equals(poi.kind())) {
            int base = barrel(level);
            for (int dx : new int[] {-2, 2}) for (int dz : new int[] {-2, 2}) {
                int low = Math.min(base, PoiBuilder.surface(level, poi.x() + dx, poi.z() + dz));
                for (int y = low; y <= base + 2; y++) structure.put(BlockPos.asLong(poi.x() + dx, y, poi.z() + dz), Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState());
            }
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                boolean edge = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                structure.put(BlockPos.asLong(poi.x() + dx, base + 3, poi.z() + dz), edge ? Blocks.SPRUCE_PLANKS.defaultBlockState()
                    : Blocks.SPRUCE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
            }
            structure.put(BlockPos.asLong(poi.x(), base + 4, poi.z()), Blocks.SPRUCE_SLAB.defaultBlockState());
            structure.put(BlockPos.asLong(poi.x() + 1, base + 2, poi.z() + 1), Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int y = base + 1; y <= base + 2; y++) {
                var pos = new BlockPos(poi.x() + dx, y, poi.z() + dz);
                if (!structure.containsKey(pos.asLong()) && wild(level.getBlockState(pos))) ops.add(new Op(pos, Blocks.AIR.defaultBlockState()));
            }
        }
        // Refuse the whole build before any change when something in the way is not natural, wild or ours.
        for (long cell : structure.keySet()) {
            var pos = BlockPos.of(cell); var state = level.getBlockState(pos);
            if (!state.isAir() && !wild(state) && !SoraExpansion.replaceable(state, canvas, false) && !state.is(Blocks.SPRUCE_WALL_SIGN) && !state.is(post.getBlock()) && !state.is(Blocks.DIRT_PATH))
                throw new IllegalStateException(poi.code() + " site occupied at " + pos.toShortString() + " by " + BuiltInRegistries.BLOCK.getKey(state.getBlock()) + "; nothing was changed");
        }
        // The way through the arch and the view onto it: wild growth goes, a cut trunk goes whole.
        var cleared = new HashSet<Long>();
        for (int d = r - 3; d <= r + 6; d++) for (int lat = -3; lat <= 3; lat++) for (int y = ground; y <= ground + 12; y++) {
            var pos = at(forward, d, lat, y); long key = pos.asLong();
            if (structure.containsKey(key) || cleared.contains(key)) continue;
            var state = level.getBlockState(pos);
            if (!wild(state)) continue;
            cleared.add(key); ops.add(new Op(pos, Blocks.AIR.defaultBlockState()));
            if (state.is(BlockTags.LOGS)) for (var up = pos.above(); up.getY() < pos.getY() + 40 && level.getBlockState(up).is(BlockTags.LOGS) && wild(level.getBlockState(up)); up = up.above())
                if (cleared.add(up.asLong())) ops.add(new Op(up, Blocks.AIR.defaultBlockState()));
        }
        for (var e : structure.entrySet()) if (!e.getValue().is(Blocks.SPRUCE_WALL_SIGN)) ops.add(new Op(BlockPos.of(e.getKey()), e.getValue()));
        System.out.println("AOC_DECOR_PLAN " + poi.code() + " kind=" + poi.kind() + " facing=" + forward.getName() + " ground=" + ground + " changes=" + ops.size());
    }

    /** Side of the ring with dry ground closest to the site's own height; the side towards the capital wins ties. */
    private Direction choose(ServerLevel level) {
        int centre = PoiBuilder.surface(level, poi.x(), poi.z());
        Direction best = null; double bestScore = Double.MAX_VALUE;
        for (var dir : Direction.Plane.HORIZONTAL) {
            double score = 0;
            for (int lat = -3; lat <= 3; lat++) {
                var c = at(dir, poi.radius(), lat, 0);
                score += Math.abs(PoiBuilder.surface(level, c.getX(), c.getZ()) - centre) + (PoiBuilder.water(level, c.getX(), c.getZ()) ? 100 : 0);
            }
            double home = Math.hypot(poi.x(), poi.z());
            if (home > 0) score -= 0.5 * (-dir.getStepX() * poi.x() - dir.getStepZ() * poi.z()) / home;
            if (score < bestScore) { bestScore = score; best = dir; }
        }
        return best;
    }

    /** Height of the cache's barrel, or the ground at the centre when there is none (yet). */
    private int barrel(ServerLevel level) {
        int top = PoiBuilder.surface(level, poi.x(), poi.z());
        for (int y = top + 2; y >= top - 8; y--) if (level.getBlockState(new BlockPos(poi.x(), y, poi.z())).is(Blocks.BARREL)) return y;
        return top;
    }

    private BlockPos at(Direction dir, int d, int lat, int y) {
        var right = dir.getClockWise();
        return new BlockPos(poi.x() + dir.getStepX() * d + right.getStepX() * lat, y, poi.z() + dir.getStepZ() * d + right.getStepZ() * lat);
    }
    /** Growth nobody placed: leaves that decay, untouched tree trunks, plants and vines. */
    static boolean wild(BlockState state) {
        if (state.is(BlockTags.LEAVES)) return state.hasProperty(LeavesBlock.PERSISTENT) && !state.getValue(LeavesBlock.PERSISTENT);
        if (state.is(BlockTags.LOGS)) return !BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().startsWith("stripped_");
        return !state.isAir() && state.getFluidState().isEmpty() && state.canBeReplaced() || state.is(Blocks.VINE) || state.is(BlockTags.FLOWERS) || state.is(Blocks.SWEET_BERRY_BUSH);
    }

    // ------------------------------------------------------------------ finish
    private void finish(ServerLevel level) {
        var signPos = at(forward, poi.radius() + 1, -2, ground + 2);
        var signState = Blocks.SPRUCE_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, forward);
        level.setBlock(signPos, signState, Block.UPDATE_ALL);
        if (level.getBlockEntity(signPos) instanceof SignBlockEntity sign) {
            var lines = signLines(poi.name(), "CACHE".equals(poi.kind()) ? "Тайник" : "Угодья", MineBuilder.tier(poi));
            var text = sign.getFrontText();
            for (int i = 0; i < 4; i++) text = text.setMessage(i, Component.literal(lines[i]));
            sign.setText(text, true); sign.setWaxed(true); sign.setChanged();
            level.sendBlockUpdated(signPos, signState, signState, Block.UPDATE_ALL);
        }
        var json = new JsonObject();
        json.addProperty("format_version", 1); json.addProperty("site", site); json.addProperty("x", poi.x()); json.addProperty("z", poi.z());
        json.addProperty("kind", poi.kind()); json.addProperty("facing", forward.getName()); json.addProperty("ground", ground);
        json.addProperty("built_at", java.time.Instant.now().toString());
        var shell = new JsonArray();
        for (long cell : structure.keySet()) { var pos = BlockPos.of(cell); var row = new JsonArray(); row.add(pos.getX() - poi.x()); row.add(pos.getY()); row.add(pos.getZ() - poi.z()); shell.add(row); }
        json.add("shell", shell);
        try { ResourcePits.write(file(level.getServer(), site), json); }
        catch (IOException failure) { throw new IllegalStateException("Could not save decoration " + site, failure); }
        MineShells.add(level.getServer(), json);
        System.out.println("AOC_DECOR_BUILT " + poi.code() + " blocks=" + structure.size());
    }

    /** The kind, the name over two lines of at most 15 characters, the tier. */
    static String[] signLines(String name, String kind, int tier) {
        var lines = MineBuilder.signLines(name, tier); lines[0] = kind; return lines;
    }
}
