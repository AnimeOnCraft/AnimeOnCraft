package ru.animeoncraft.integration.neoforge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The Adventurers' Guild in Sora's outer ring (layout type ADVENTURER_GUILD): a two-storey hall with its entrance
 * towards the city centre, a reception counter, tables, a gallery and an empty quest board on the back wall, kept
 * free for the coming quest feature. Built on the city floor; refuses (and stays unrecorded) when the site holds
 * anything but natural ground or layout blocks, so nothing a player made is replaced.
 */
final class AdventurerGuild {
    static final int HALF_WIDTH = 12, DEPTH = 17, HEIGHT = 10, PATH = 9;
    private AdventurerGuild() {}

    /** Local frame: u across the facade (to the right when looking at the entrance), v inwards from the door wall. */
    record Frame(int cx, int cz, Direction door) {
        int x(int u, int v) { return cx + door.getStepX() * (DEPTH / 2 - v) + door.getClockWise().getStepX() * u; }
        int z(int u, int v) { return cz + door.getStepZ() * (DEPTH / 2 - v) + door.getClockWise().getStepZ() * u; }
        Direction right() { return door.getClockWise(); }
        Direction left() { return door.getCounterClockWise(); }
        Direction inward() { return door.getOpposite(); }
    }
    /** Quest board centre (the block the coming board feature will use), for a guild at the point. */
    static int[] board(Frame f, int y) { return new int[] {f.x(0, DEPTH - 2), y + 3, f.z(0, DEPTH - 2)}; }

    /** The entrance faces the city centre. */
    static Direction door(WorldLayout.Poi capital, WorldLayout.Poi poi) {
        int tx = capital != null ? capital.x() - poi.x() : 0, tz = capital != null ? capital.z() - poi.z() : -1;
        return Math.abs(tx) > Math.abs(tz) ? (tx > 0 ? Direction.EAST : Direction.WEST) : (tz > 0 ? Direction.SOUTH : Direction.NORTH);
    }

    /** A built guild as the quest board sees it: the hall's inner volume, the board on the back wall and its sign. */
    record Site(Frame frame, int y) {
        boolean inHall(double x, double py, double z) {
            double dx = x - 0.5 - frame.cx(), dz = z - 0.5 - frame.cz();
            double u = dx * frame.right().getStepX() + dz * frame.right().getStepZ(), v = DEPTH / 2 - (dx * frame.door().getStepX() + dz * frame.door().getStepZ());
            return Math.abs(u) <= HALF_WIDTH - 0.5 && v >= 0.5 && v <= DEPTH - 1.5 && py >= y + 1 && py <= y + HEIGHT;
        }
        BlockPos sign() { return new BlockPos(frame.x(0, DEPTH - 3), y + 3, frame.z(0, DEPTH - 3)); }
        /** The framed board on the back wall or its sign. */
        boolean board(BlockPos pos) {
            if (pos.equals(sign())) return true;
            for (int u = -4; u <= 4; u++) for (int dy = 1; dy <= 5; dy++) if (pos.getX() == frame.x(u, DEPTH - 2) && pos.getY() == y + dy && pos.getZ() == frame.z(u, DEPTH - 2)) return true;
            return false;
        }
    }
    /** The guild of a states world, or null when the layout has none. Uses the floor the builder recorded. */
    static Site site(net.minecraft.server.level.ServerLevel level) {
        var layout = WorldLayout.state(); if (layout == null) return null;
        var poi = layout.pois().get("core.guild"); var capital = layout.pois().get("core.capital"); if (poi == null) return null;
        var progress = LayoutJobs.progress(level.getServer()); var floors = progress.getAsJsonObject("floors");
        boolean sameWorld = progress.has("world_id") && progress.get("world_id").getAsString().equals(layout.worldId());
        if (!sameWorld || floors == null || !floors.has("core.guild")) return null;
        return new Site(new Frame(poi.x(), poi.z(), door(capital, poi)), floors.get("core.guild").getAsInt());
    }

    static int build(PoiBuilder.Canvas c, WorldLayout.State layout, WorldLayout.Poi poi) {
        var level = c.level;
        var capital = layout.pois().get("core.capital");
        int y = capital != null ? SoraExpansion.cityFloor(level, layout, capital.x(), capital.z()) : PoiBuilder.groundLevel(level, poi.x(), poi.z(), 10);
        var f = new Frame(poi.x(), poi.z(), door(capital, poi));
        // Site check first: the whole footprint, its margin and the path to the ring street.
        for (int u = -HALF_WIDTH - 2; u <= HALF_WIDTH + 2; u++) for (int v = -PATH; v <= DEPTH + 1; v++) {
            int x = f.x(u, v), z = f.z(u, v), ground = PoiBuilder.surface(level, x, z);
            if (SoraExpansion.foreign(level, c, x, z, Math.min(ground, y) - 4, Math.max(y + HEIGHT + 8, ground + 12)))
                throw new IllegalStateException("guild site occupied at " + x + ", " + z + "; nothing was changed");
        }
        for (int u = -HALF_WIDTH - 2; u <= HALF_WIDTH + 2; u++) for (int v = -PATH; v <= DEPTH + 1; v++) {
            int x = f.x(u, v), z = f.z(u, v), ground = PoiBuilder.surface(level, x, z);
            boolean path = v < 0 && Math.abs(u) <= 2;
            SoraExpansion.level(c, x, z, ground, y, Math.max(y + HEIGHT + 8, ground + 12), path ? c.floor : Blocks.GRASS_BLOCK.defaultBlockState());
        }
        if (PoiBuilder.template(c, poi, y)) return y;
        hall(c, f, y);
        int[] b = board(f, y);
        System.out.println("AOC_GUILD built at " + poi.x() + ", " + y + ", " + poi.z() + " door=" + f.door().getName() + " board=" + b[0] + "," + b[1] + "," + b[2]);
        return y;
    }

    private static void box(PoiBuilder.Canvas c, Frame f, int u1, int y1, int v1, int u2, int y2, int v2, BlockState state) {
        c.box(f.x(u1, v1), y1, f.z(u1, v1), f.x(u2, v2), y2, f.z(u2, v2), state);
    }
    private static void set(PoiBuilder.Canvas c, Frame f, int u, int y, int v, BlockState state) { c.set(f.x(u, v), y, f.z(u, v), state); }

    private static void hall(PoiBuilder.Canvas c, Frame f, int y) {
        int w = HALF_WIDTH, back = DEPTH - 1, top = y + HEIGHT;
        var air = Blocks.AIR.defaultBlockState();
        var planks = Blocks.SPRUCE_PLANKS.defaultBlockState();
        var fence = Blocks.SPRUCE_FENCE.defaultBlockState();
        // Shell, floor, pillars.
        box(c, f, -w, y, 0, w, top, back, c.wall);
        box(c, f, -w + 1, y + 1, 1, w - 1, top - 1, back - 1, air);
        box(c, f, -w + 1, y, 1, w - 1, y, back - 1, planks);
        for (int u : new int[] {-w, -4, 4, w}) for (int v : new int[] {0, back}) box(c, f, u, y, v, u, top, v, Blocks.QUARTZ_PILLAR.defaultBlockState());
        for (int u : new int[] {-w, w}) box(c, f, u, y, back / 2, u, top, back / 2, Blocks.QUARTZ_PILLAR.defaultBlockState());
        // Entrance: open arch (no door to lock in the protected city), steps, sign and flags.
        box(c, f, -2, y + 1, 0, 2, y + 4, 0, air);
        box(c, f, -2, y - 1, -PATH, 2, y - 1, -1, c.floor);
        box(c, f, -2, y, -1, 2, y, -1, Blocks.QUARTZ_STAIRS.defaultBlockState().setValue(StairBlock.FACING, f.inward()));
        box(c, f, -1, y + 1, 1, 1, y + 1, back - 4, Blocks.LIGHT_BLUE_CARPET.defaultBlockState());
        sign(c, f.x(0, -1), y + 6, f.z(0, -1), f.door(), "Гильдия", "искателей", "приключений", "");
        PoiBuilder.flag(c, f.x(-7, -3), y, f.z(-7, -3), 7);
        PoiBuilder.flag(c, f.x(5, -3), y, f.z(5, -3), 7);
        for (int u : new int[] {-4, 4}) c.post(f.x(u, -PATH + 3), y, f.z(u, -PATH + 3), 3);
        // Windows on both storeys.
        var pane = Blocks.LIGHT_BLUE_STAINED_GLASS_PANE.defaultBlockState();
        for (int v : new int[] {3, 6, 10, 13}) for (int u : new int[] {-w, w}) { box(c, f, u, y + 2, v, u, y + 3, v, pane); box(c, f, u, y + 7, v, u, y + 8, v, pane); }
        for (int u : new int[] {-8, 8}) { box(c, f, u, y + 2, 0, u, y + 3, 0, pane); box(c, f, u, y + 7, 0, u, y + 8, 0, pane); box(c, f, u, y + 2, back, u, y + 3, back, pane); }
        for (int u : new int[] {-2, 0, 2}) box(c, f, u, y + 7, 0, u, y + 8, 0, pane);
        // Gallery over the entrance with a railing and a ladder on the left wall.
        box(c, f, -w + 1, y + 5, 1, w - 1, y + 5, 5, planks);
        box(c, f, -w + 1, y + 6, 6, w - 1, y + 6, 6, fence);
        box(c, f, -w + 1, y + 5, 6, w - 1, y + 5, 6, planks);
        set(c, f, -w + 1, y + 5, 5, air); set(c, f, -w + 1, y + 6, 6, air);
        box(c, f, -w + 1, y + 1, 5, -w + 1, y + 5, 5, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, f.right()));
        box(c, f, -w + 3, y + 6, 1, -5, y + 6, 1, Blocks.BOOKSHELF.defaultBlockState());
        box(c, f, 5, y + 6, 1, w - 1, y + 6, 1, Blocks.BOOKSHELF.defaultBlockState());
        table(c, f, -2, y + 6, 3); table(c, f, 3, y + 6, 3);
        // Reception counter on the right, shelves behind it.
        box(c, f, 7, y + 1, 7, 7, y + 1, 11, planks);
        set(c, f, 7, y + 2, 7, Blocks.LANTERN.defaultBlockState()); set(c, f, 7, y + 2, 11, Blocks.LANTERN.defaultBlockState());
        box(c, f, w - 1, y + 1, 7, w - 1, y + 3, 11, Blocks.BOOKSHELF.defaultBlockState());
        set(c, f, 9, y + 1, 9, Blocks.BARREL.defaultBlockState());
        // Tables for parties on the left.
        table(c, f, -7, y + 1, 8); table(c, f, -7, y + 1, 12);
        // Quest board on the back wall: framed, empty, signed; the space in front stays clear for the coming feature.
        var frame = Blocks.STRIPPED_DARK_OAK_WOOD.defaultBlockState();
        box(c, f, -4, y + 1, back - 1, 4, y + 5, back - 1, frame);
        box(c, f, -3, y + 2, back - 1, 3, y + 4, back - 1, Blocks.SPRUCE_PLANKS.defaultBlockState());
        sign(c, f.x(0, back - 2), y + 3, f.z(0, back - 2), f.door(), "Доска заданий", "", "скоро", "");
        for (int u : new int[] {-3, 3}) set(c, f, u, y + 6, back - 1, Blocks.LANTERN.defaultBlockState());
        // Hanging lights.
        for (int[] spot : new int[][] {{-6, 10}, {0, 9}, {6, 10}}) {
            set(c, f, spot[0], top - 1, spot[1], Blocks.CHAIN.defaultBlockState());
            set(c, f, spot[0], top - 2, spot[1], Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
        }
        // Hip roof in the city colour.
        int k = 0;
        for (; k < 6; k++) {
            int u1 = -w - 1 + k, u2 = w + 1 - k, v1 = -1 + k, v2 = back + 1 - k, ry = top + 1 + k;
            box(c, f, u1, ry, v1, u2, ry, v1, c.roof); box(c, f, u1, ry, v2, u2, ry, v2, c.roof);
            box(c, f, u1, ry, v1, u1, ry, v2, c.roof); box(c, f, u2, ry, v1, u2, ry, v2, c.roof);
        }
        box(c, f, -w - 1 + k, top + k, -1 + k, w + 1 - k, top + k, back + 1 - k, c.roof);
        set(c, f, 0, top + k + 1, back / 2, Blocks.LANTERN.defaultBlockState());
    }

    private static void table(PoiBuilder.Canvas c, Frame f, int u, int y, int v) {
        set(c, f, u, y, v, Blocks.SPRUCE_FENCE.defaultBlockState());
        set(c, f, u, y + 1, v, Blocks.SPRUCE_PRESSURE_PLATE.defaultBlockState());
        set(c, f, u - 1, y, v, Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, f.left()));
        set(c, f, u + 1, y, v, Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, f.right()));
    }

    /** Waxed wall sign facing {@code facing}, attached to the block behind it. */
    private static void sign(PoiBuilder.Canvas c, int x, int y, int z, Direction facing, String... lines) {
        var pos = new BlockPos(x, y, z);
        c.level.setBlock(pos, Blocks.SPRUCE_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, facing), net.minecraft.world.level.block.Block.UPDATE_ALL);
        if (!(c.level.getBlockEntity(pos) instanceof SignBlockEntity sign)) return;
        var text = new SignText();
        for (int i = 0; i < Math.min(4, lines.length); i++) text = text.setMessage(i, Component.literal(lines[i]));
        sign.setText(text, true);
        sign.setWaxed(true);
        sign.setChanged();
        c.level.sendBlockUpdated(pos, sign.getBlockState(), sign.getBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
    }
}
