package ru.animeoncraft.integration.neoforge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The Rift Gate in Sora's outer ring (layout type RIFT_GATE, code core.rift_gate): a dark hall with its entrance
 * towards the city centre and a portal arch on the back wall. A right click on the arch opens the dungeon screen;
 * runs end in front of it. Built like the guild: the whole site is checked first and the build is refused, with
 * nothing changed, when it holds anything but natural ground or layout blocks.
 */
final class RiftGate {
    static final String TYPE = "RIFT_GATE", CODE = "core.rift_gate";
    static final int HALF_WIDTH = 8, DEPTH = 15, HEIGHT = 9, PATH = 9;
    private RiftGate() {}

    /** Same local frame as the guild: u across the facade, v inwards from the door wall. */
    record Frame(int cx, int cz, Direction door) {
        int x(int u, int v) { return cx + door.getStepX() * (DEPTH / 2 - v) + door.getClockWise().getStepX() * u; }
        int z(int u, int v) { return cz + door.getStepZ() * (DEPTH / 2 - v) + door.getClockWise().getStepZ() * u; }
        Direction right() { return door.getClockWise(); }
        Direction inward() { return door.getOpposite(); }
    }
    record Site(Frame frame, int y) {
        boolean inHall(double x, double py, double z) {
            double dx = x - 0.5 - frame.cx(), dz = z - 0.5 - frame.cz();
            double u = dx * frame.right().getStepX() + dz * frame.right().getStepZ(), v = DEPTH / 2 - (dx * frame.door().getStepX() + dz * frame.door().getStepZ());
            return Math.abs(u) <= HALF_WIDTH - 0.5 && v >= 0.5 && v <= DEPTH - 1.5 && py >= y + 1 && py <= y + HEIGHT;
        }
        /** Near the gate: inside the hall or on its path, where a lobby may gather. */
        boolean near(double x, double py, double z) { return inHall(x, py, z) || Math.hypot(x - frame.cx(), z - frame.cz()) < DEPTH + PATH && Math.abs(py - y) < 6; }
        /** The arch, its glass and the lectern in front of it. */
        boolean portal(BlockPos pos) {
            if (pos.getY() == y + 1 && pos.getX() == frame.x(0, DEPTH - 5) && pos.getZ() == frame.z(0, DEPTH - 5)) return true;
            for (int u = -3; u <= 3; u++) for (int dy = 1; dy <= 7; dy++) if (pos.getX() == frame.x(u, DEPTH - 2) && pos.getY() == y + dy && pos.getZ() == frame.z(u, DEPTH - 2)) return true;
            return false;
        }
        /** Where players stand after a run: between the lectern and the entrance, facing the door. */
        double[] arrival() { return new double[] {frame.x(0, DEPTH - 7) + 0.5, y + 1, frame.z(0, DEPTH - 7) + 0.5, frame.door().toYRot()}; }
    }
    /** The built gate of a states world, or null (no layout, not built yet, or another world's progress). */
    static Site site(net.minecraft.server.level.ServerLevel level) {
        var layout = WorldLayout.state(); if (layout == null) return null;
        var poi = layout.pois().get(CODE); if (poi == null) return null;
        var progress = LayoutJobs.progress(level.getServer()); var floors = progress.getAsJsonObject("floors");
        boolean sameWorld = progress.has("world_id") && progress.get("world_id").getAsString().equals(layout.worldId());
        if (!sameWorld || floors == null || !floors.has(CODE)) return null;
        return new Site(new Frame(poi.x(), poi.z(), AdventurerGuild.door(layout.pois().get("core.capital"), poi)), floors.get(CODE).getAsInt());
    }

    static int build(PoiBuilder.Canvas c, WorldLayout.State layout, WorldLayout.Poi poi) {
        var level = c.level; var capital = layout.pois().get("core.capital");
        int y = capital != null ? SoraExpansion.cityFloor(level, layout, capital.x(), capital.z()) : PoiBuilder.groundLevel(level, poi.x(), poi.z(), 8);
        var f = new Frame(poi.x(), poi.z(), AdventurerGuild.door(capital, poi));
        for (int u = -HALF_WIDTH - 2; u <= HALF_WIDTH + 2; u++) for (int v = -PATH; v <= DEPTH + 1; v++) {
            int x = f.x(u, v), z = f.z(u, v), ground = PoiBuilder.surface(level, x, z);
            if (SoraExpansion.foreign(level, c, x, z, Math.min(ground, y) - 4, Math.max(y + HEIGHT + 8, ground + 12)))
                throw new IllegalStateException("rift gate site occupied at " + x + ", " + z + "; nothing was changed");
        }
        for (int u = -HALF_WIDTH - 2; u <= HALF_WIDTH + 2; u++) for (int v = -PATH; v <= DEPTH + 1; v++) {
            int x = f.x(u, v), z = f.z(u, v), ground = PoiBuilder.surface(level, x, z);
            SoraExpansion.level(c, x, z, ground, y, Math.max(y + HEIGHT + 8, ground + 12), v < 0 && Math.abs(u) <= 2 ? c.floor : Blocks.GRASS_BLOCK.defaultBlockState());
        }
        if (PoiBuilder.template(c, poi, y)) return y;
        hall(c, f, y);
        System.out.println("AOC_RIFT_GATE built at " + poi.x() + ", " + y + ", " + poi.z() + " door=" + f.door().getName());
        return y;
    }

    private static void box(PoiBuilder.Canvas c, Frame f, int u1, int y1, int v1, int u2, int y2, int v2, BlockState s) { c.box(f.x(u1, v1), y1, f.z(u1, v1), f.x(u2, v2), y2, f.z(u2, v2), s); }
    private static void set(PoiBuilder.Canvas c, Frame f, int u, int y, int v, BlockState s) { c.set(f.x(u, v), y, f.z(u, v), s); }

    private static void hall(PoiBuilder.Canvas c, Frame f, int y) {
        int w = HALF_WIDTH, back = DEPTH - 1, top = y + HEIGHT; var air = Blocks.AIR.defaultBlockState();
        BlockState dark = Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState(), floor = Blocks.POLISHED_DEEPSLATE.defaultBlockState();
        box(c, f, -w, y, 0, w, top, back, dark);
        box(c, f, -w + 1, y + 1, 1, w - 1, top - 1, back - 1, air);
        box(c, f, -w + 1, y, 1, w - 1, y, back - 1, floor);
        box(c, f, -1, y, 1, 1, y, back - 2, Blocks.AMETHYST_BLOCK.defaultBlockState());
        for (int u : new int[] {-w, w}) for (int v : new int[] {0, back / 2, back}) box(c, f, u, y, v, u, top, v, Blocks.CRYING_OBSIDIAN.defaultBlockState());
        // Entrance: an open arch with steps and a sign; purple glass windows.
        box(c, f, -2, y + 1, 0, 2, y + 4, 0, air);
        box(c, f, -2, y - 1, -PATH, 2, y - 1, -1, c.floor);
        box(c, f, -2, y, -1, 2, y, -1, Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, f.inward()));
        sign(c, f.x(0, -1), y + 6, f.z(0, -1), f.door(), "Врата", "Разлома", "", "подземелья");
        for (int u : new int[] {-4, 4}) c.post(f.x(u, -PATH + 3), y, f.z(u, -PATH + 3), 3);
        var pane = Blocks.PURPLE_STAINED_GLASS_PANE.defaultBlockState();
        for (int v : new int[] {4, 10}) for (int u : new int[] {-w, w}) box(c, f, u, y + 2, v, u, y + 5, v, pane);
        for (int u : new int[] {-5, 5}) box(c, f, u, y + 2, 0, u, y + 5, 0, pane);
        // Portal arch on the back wall: crying obsidian frame, purple glass, amethyst at its feet, lectern in front.
        box(c, f, -3, y + 1, back - 1, 3, y + 7, back - 1, Blocks.CRYING_OBSIDIAN.defaultBlockState());
        box(c, f, -2, y + 1, back - 1, 2, y + 6, back - 1, Blocks.PURPLE_STAINED_GLASS.defaultBlockState());
        for (int u : new int[] {-4, 4}) { set(c, f, u, y + 1, back - 1, Blocks.AMETHYST_CLUSTER.defaultBlockState().setValue(AmethystClusterBlock.FACING, Direction.UP)); set(c, f, u, y + 3, back - 1, Blocks.SOUL_LANTERN.defaultBlockState()); }
        set(c, f, 0, y + 1, back - 4, Blocks.LECTERN.defaultBlockState().setValue(net.minecraft.world.level.block.LecternBlock.FACING, f.door()));
        sign(c, f.x(-5, back - 1), y + 3, f.z(-5, back - 1), f.door(), "ПКМ по порталу", "— выбор", "подземелья", "");
        sign(c, f.x(5, back - 1), y + 3, f.z(5, back - 1), f.door(), "Рекорды недели", "", "скоро", "");
        // Benches for waiting teams and hanging soul lanterns.
        for (int u : new int[] {-w + 2, w - 2}) for (int v = 3; v <= 9; v += 3) set(c, f, u, y + 1, v, Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, u < 0 ? f.right().getOpposite() : f.right()));
        for (int[] spot : new int[][] {{-4, 5}, {4, 5}, {0, 9}}) { set(c, f, spot[0], top - 1, spot[1], Blocks.CHAIN.defaultBlockState()); set(c, f, spot[0], top - 2, spot[1], Blocks.SOUL_LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true)); }
        // Stepped roof in the city colour with an amethyst crest.
        int k = 0;
        for (; k < 5; k++) {
            int u1 = -w - 1 + k, u2 = w + 1 - k, v1 = -1 + k, v2 = back + 1 - k, ry = top + 1 + k;
            box(c, f, u1, ry, v1, u2, ry, v1, c.roof); box(c, f, u1, ry, v2, u2, ry, v2, c.roof);
            box(c, f, u1, ry, v1, u1, ry, v2, c.roof); box(c, f, u2, ry, v1, u2, ry, v2, c.roof);
        }
        box(c, f, -w - 1 + k, top + k, -1 + k, w + 1 - k, top + k, back + 1 - k, c.roof);
        box(c, f, 0, top + k + 1, back / 2 - 2, 0, top + k + 1, back / 2 + 2, Blocks.AMETHYST_BLOCK.defaultBlockState());
    }
    private static void sign(PoiBuilder.Canvas c, int x, int y, int z, Direction facing, String... lines) {
        var pos = new BlockPos(x, y, z);
        c.level.setBlock(pos, Blocks.DARK_OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, facing), net.minecraft.world.level.block.Block.UPDATE_ALL);
        if (!(c.level.getBlockEntity(pos) instanceof SignBlockEntity sign)) return;
        var text = new SignText(); for (int i = 0; i < Math.min(4, lines.length); i++) text = text.setMessage(i, Component.literal(lines[i]));
        sign.setText(text.setHasGlowingText(true), true); sign.setWaxed(true); sign.setChanged();
        c.level.sendBlockUpdated(pos, sign.getBlockState(), sign.getBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
    }
}
