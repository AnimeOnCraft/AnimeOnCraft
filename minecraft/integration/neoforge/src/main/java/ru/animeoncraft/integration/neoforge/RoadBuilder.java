package ru.animeoncraft.integration.neoforge;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Lays one road of the layout (World Design stage 5) in three resumable passes, a few dozen blocks per step:
 * sample the ground along the polyline, smooth it (at most 1 block of height per 2 blocks of length, both ways),
 * then pave a strip of the road width. Water is crossed on a plank bridge with pillars; hills are cut (a 4-block
 * corridor, so a high ridge becomes a tunnel). Nothing is placed above the corridor.
 */
final class RoadBuilder implements LayoutJobs.Job {
    private final WorldLayout.Road road;
    private final List<int[]> trace;
    private final int[] ground;
    private final boolean[] water;
    private int[] height;
    private int index;
    private int phase;

    RoadBuilder(WorldLayout.Road road) {
        this.road = road;
        this.trace = RoadGeometry.trace(road.points());
        this.ground = new int[trace.size()];
        this.water = new boolean[trace.size()];
    }
    @Override public String code() { return road.code(); }
    @Override public String progress() { return road.code() + " " + new String[] {"рельеф", "выравнивание", "мощение"}[Math.min(phase, 2)] + " " + index + "/" + trace.size(); }
    int length() { return trace.size(); }

    @Override public boolean step(ServerLevel level) {
        if (phase == 0) {
            for (int done = 0; done < 48 && index < trace.size(); done++, index++) {
                int[] p = trace.get(index);
                water[index] = PoiBuilder.water(level, p[0], p[1]);
                ground[index] = PoiBuilder.surface(level, p[0], p[1]);
            }
            if (index >= trace.size()) { phase = 1; index = 0; }
            return false;
        }
        if (phase == 1) { height = RoadGeometry.smooth(ground, water, PoiBuilder.sea(level)); phase = 2; return false; }
        for (int done = 0; done < 24 && index < trace.size(); done++, index++) pave(level, index);
        return index >= trace.size();
    }

    private BlockState surface(int w, int i) {
        int half = road.width() / 2;
        boolean edge = Math.abs(w) == half && road.width() > 2;
        return switch (road.style()) {
            case "main" -> edge ? Blocks.POLISHED_ANDESITE.defaultBlockState() : Blocks.STONE_BRICKS.defaultBlockState();
            case "cross" -> ((i * 31 + w * 17) & 7) == 0 ? Blocks.MOSSY_COBBLESTONE.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState();
            default -> edge ? Blocks.COARSE_DIRT.defaultBlockState() : Blocks.DIRT_PATH.defaultBlockState();
        };
    }

    private void pave(ServerLevel level, int i) {
        int[] p = trace.get(i), a = trace.get(Math.max(0, i - 3)), b = trace.get(Math.min(trace.size() - 1, i + 3));
        double dx = b[0] - a[0], dz = b[1] - a[1], length = Math.max(1e-6, Math.hypot(dx, dz));
        double px = -dz / length, pz = dx / length;
        int y = height[i], half = road.width() / 2;
        boolean bridge = water[i];
        for (int w = -half - 1; w <= half + 1; w++) {
            int x = p[0] + (int) Math.round(w * px), z = p[1] + (int) Math.round(w * pz);
            boolean rail = Math.abs(w) == half + 1;
            if (rail && !bridge) continue;
            if (rail) { set(level, x, y, z, Blocks.SPRUCE_FENCE.defaultBlockState()); continue; }
            set(level, x, y - 1, z, bridge ? Blocks.SPRUCE_PLANKS.defaultBlockState() : surface(w, i));
            if (!bridge) for (int fy = y - 2, depth = 0; depth < 10 && fy > level.getMinBuildHeight(); fy--, depth++) {
                var below = level.getBlockState(new BlockPos(x, fy, z));
                if (!below.isAir() && below.getFluidState().isEmpty() && !below.canBeReplaced()) break;
                set(level, x, fy, z, depth == 0 ? Blocks.DIRT.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState());
            }
            for (int fy = y; fy <= y + 3; fy++) {
                var above = level.getBlockState(new BlockPos(x, fy, z));
                if (!above.isAir()) set(level, x, fy, z, Blocks.AIR.defaultBlockState());
            }
        }
        if (bridge && i % 12 == 0) {
            for (int fy = y - 2; fy > level.getMinBuildHeight() + 1; fy--) {
                var below = level.getBlockState(new BlockPos(p[0], fy, p[1]));
                if (!below.isAir() && below.getFluidState().isEmpty() && !below.canBeReplaced()) break;
                set(level, p[0], fy, p[1], Blocks.STONE_BRICKS.defaultBlockState());
            }
        }
        if (i % 32 == 16 && ("main".equals(road.style()) || bridge)) {
            int x = p[0] + (int) Math.round((half + 1) * px), z = p[1] + (int) Math.round((half + 1) * pz);
            if (bridge) set(level, x, y + 1, z, Blocks.LANTERN.defaultBlockState());
            else if (level.getBlockState(new BlockPos(x, y, z)).isAir()) {
                set(level, x, y, z, Blocks.SPRUCE_FENCE.defaultBlockState()); set(level, x, y + 1, z, Blocks.LANTERN.defaultBlockState());
            }
        }
    }
    private static void set(ServerLevel level, int x, int y, int z, BlockState state) {
        var pos = new BlockPos(x, y, z);
        level.setBlock(pos, net.minecraft.world.level.block.Block.updateFromNeighbourShapes(state, level, pos), net.minecraft.world.level.block.Block.UPDATE_ALL);
    }
}
