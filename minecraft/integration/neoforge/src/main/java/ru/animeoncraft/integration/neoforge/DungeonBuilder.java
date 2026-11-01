package ru.animeoncraft.integration.neoforge;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Puts the rooms of a run into its rift slot and wipes the slot afterwards, a slice per call within a time budget
 * (like the layout jobs), so a busy server keeps ticking. Only the slot's own box is touched; the rift holds nothing else.
 * Sakura theme: stone-brick halls under a deepslate roof, cherry pillars, petals on the floor and hanging blossoms.
 */
final class DungeonBuilder {
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    static final BlockState AIR = Blocks.AIR.defaultBlockState(), BARS = Blocks.IRON_BARS.defaultBlockState();
    private DungeonBuilder() {}

    /** A resumable piece of block work; step returns true when finished. */
    interface Work { boolean step(ServerLevel level, long deadline); }

    /** Builds every room and corridor, one room (in column slices) per step. */
    static final class Build implements Work {
        private final List<DungeonLayout.Room> rooms; private final int ox, oz; private final long seed;
        private int room, column;
        Build(List<DungeonLayout.Room> rooms, int ox, int oz, long seed) { this.rooms = rooms; this.ox = ox; this.oz = oz; this.seed = seed; }
        @Override public boolean step(ServerLevel level, long deadline) {
            while (System.nanoTime() < deadline) {
                if (room >= rooms.size()) return true;
                var r = rooms.get(room);
                if (column <= r.x1() - r.x0()) { column(level, r, r.x0() + column++); continue; }
                decorate(level, r);
                if (room + 1 < rooms.size()) corridor(level, r, rooms.get(room + 1));
                room++; column = 0;
            }
            return false;
        }
        private void column(ServerLevel level, DungeonLayout.Room r, int lx) {
            var random = new SplittableRandom(seed ^ (long) lx * 0x632BE59BD9B4E019L);
            int y = DungeonRules.FLOOR, top = y + r.height();
            for (int lz = r.z0(); lz <= r.z1(); lz++) {
                boolean wall = lx == r.x0() || lx == r.x1() || lz == r.z0() || lz == r.z1();
                int x = ox + lx, z = oz + lz;
                if (!r.pit(lx, lz)) set(level, x, y - 1, z, Blocks.DEEPSLATE_BRICKS.defaultBlockState());
                set(level, x, y, z, r.pit(lx, lz) ? AIR : floor(r, lx, lz, random));
                for (int fy = y + 1; fy < top; fy++) set(level, x, fy, z, wall ? wallBlock(fy - y, random) : AIR);
                set(level, x, top, z, Blocks.POLISHED_DEEPSLATE.defaultBlockState());
                if (!wall && !r.pit(lx, lz) && random.nextInt(9) == 0) set(level, x, y + 1, z, Blocks.PINK_PETALS.defaultBlockState());
                if (!wall && random.nextInt(r.kind() == DungeonLayout.Kind.BOSS ? 5 : 7) == 0) set(level, x, top - 1, z, Blocks.CHERRY_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true));
            }
        }
        private static BlockState floor(DungeonLayout.Room r, int lx, int lz, SplittableRandom random) {
            if (r.kind() == DungeonLayout.Kind.BRIDGE && Math.abs(lz) <= DungeonLayout.DOOR_HALF) return Blocks.CHERRY_PLANKS.defaultBlockState();
            if (Math.abs(lz) <= DungeonLayout.DOOR_HALF && r.kind() != DungeonLayout.Kind.BOSS) return Blocks.POLISHED_ANDESITE.defaultBlockState();
            int n = random.nextInt(10);
            return n == 0 ? Blocks.CRACKED_STONE_BRICKS.defaultBlockState() : n == 1 ? Blocks.MOSSY_STONE_BRICKS.defaultBlockState() : Blocks.STONE_BRICKS.defaultBlockState();
        }
        private static BlockState wallBlock(int dy, SplittableRandom random) {
            if (dy == 1) return Blocks.CHISELED_STONE_BRICKS.defaultBlockState();
            int n = random.nextInt(8); return n == 0 ? Blocks.MOSSY_STONE_BRICKS.defaultBlockState() : n == 1 ? Blocks.CRACKED_STONE_BRICKS.defaultBlockState() : Blocks.STONE_BRICKS.defaultBlockState();
        }
        private void decorate(ServerLevel level, DungeonLayout.Room r) {
            int y = DungeonRules.FLOOR, top = y + r.height();
            // Cherry pillars at the corners and every six blocks along the long walls, lanterns between them.
            for (int lx = r.x0() + 1; lx < r.x1(); lx += 6) for (int lz : new int[] {r.z0() + 1, r.z1() - 1}) {
                if (Math.abs(lz) <= DungeonLayout.DOOR_HALF) continue;
                for (int fy = y + 1; fy < top; fy++) set(level, ox + lx, fy, oz + lz, Blocks.CHERRY_LOG.defaultBlockState());
                int lantern = lx + 3; if (lantern < r.x1() - 1) set(level, ox + lantern, y + 1, oz + lz, Blocks.LANTERN.defaultBlockState());
            }
            for (int lx : new int[] {r.x0() + 3, r.x1() - 3}) for (int lz : new int[] {r.z0() + 3, r.z1() - 3})
                if (lx > r.x0() + 1 && lz > r.z0() + 1 && !r.pit(lx, lz)) { set(level, ox + lx, top - 1, oz + lz, Blocks.CHAIN.defaultBlockState()); set(level, ox + lx, top - 2, oz + lz, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true)); }
            // Doorways: the entrance is open, the exit holds bars until the room is cleared.
            if (r.index() > 0) door(level, r.x0(), AIR);
            if (r.kind() != DungeonLayout.Kind.BOSS) door(level, r.x1(), BARS);
            switch (r.kind()) {
                case ENTRANCE -> { for (int lz : new int[] {-3, 3}) set(level, ox + r.x0() + 5, y + 1, oz + lz, Blocks.SOUL_LANTERN.defaultBlockState()); }
                case TRAP -> { for (int lx = r.x0() + 3; lx < r.x1() - 2; lx += 3) for (int lz : new int[] {r.z0(), r.z1()})
                    set(level, ox + lx, y + 2, oz + lz, Blocks.DISPENSER.defaultBlockState().setValue(net.minecraft.world.level.block.DispenserBlock.FACING, lz < 0 ? Direction.SOUTH : Direction.NORTH)); }
                case BRIDGE -> { for (int lx = r.x0() + 5; lx < r.x1() - 4; lx += 4) for (int lz : new int[] {-2, 2}) set(level, ox + lx, top - 1, oz + lz, Blocks.CHAIN.defaultBlockState()); }
                case TREASURE -> { for (int lz : new int[] {r.z0() + 1, r.z1() - 1}) for (int lx = r.x0() + 2; lx < r.x1() - 1; lx += 2) set(level, ox + lx, y + 1, oz + lz, Blocks.GOLD_BLOCK.defaultBlockState());
                    chest(level, ox + (r.x0() + r.x1()) / 2, y + 1, oz); }
                case BOSS -> { for (int lx : new int[] {r.x0() + 6, r.x1() - 6}) for (int lz : new int[] {r.z0() + 6, r.z1() - 6}) for (int fy = y + 1; fy < top; fy++) set(level, ox + lx, fy, oz + lz, Blocks.CHERRY_LOG.defaultBlockState());
                    for (int lz = -2; lz <= 2; lz++) set(level, ox + r.x1() - 2, y + 1, oz + lz, Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState()); }
                default -> {}
            }
        }
        private void door(ServerLevel level, int lx, BlockState state) {
            for (int lz = -DungeonLayout.DOOR_HALF; lz <= DungeonLayout.DOOR_HALF; lz++) for (int dy = 1; dy <= DungeonLayout.DOOR_HEIGHT; dy++) setShaped(level, ox + lx, DungeonRules.FLOOR + dy, oz + lz, state);
        }
        private void corridor(ServerLevel level, DungeonLayout.Room from, DungeonLayout.Room to) {
            int y = DungeonRules.FLOOR;
            for (int lx = from.x1() + 1; lx < to.x0(); lx++) for (int lz = -DungeonLayout.DOOR_HALF - 1; lz <= DungeonLayout.DOOR_HALF + 1; lz++) {
                boolean side = Math.abs(lz) > DungeonLayout.DOOR_HALF; int x = ox + lx, z = oz + lz;
                set(level, x, y - 1, z, Blocks.DEEPSLATE_BRICKS.defaultBlockState()); set(level, x, y, z, side ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.POLISHED_ANDESITE.defaultBlockState());
                for (int dy = 1; dy <= DungeonLayout.DOOR_HEIGHT; dy++) set(level, x, y + dy, z, side ? Blocks.STONE_BRICKS.defaultBlockState() : AIR);
                set(level, x, y + DungeonLayout.DOOR_HEIGHT + 1, z, Blocks.POLISHED_DEEPSLATE.defaultBlockState());
            }
        }
    }

    /** Opens (air) or closes (bars) the exit of a room. */
    static void exit(ServerLevel level, DungeonLayout.Room r, int ox, int oz, boolean open) {
        for (int lz = -DungeonLayout.DOOR_HALF; lz <= DungeonLayout.DOOR_HALF; lz++) for (int dy = 1; dy <= DungeonLayout.DOOR_HEIGHT; dy++)
            setShaped(level, ox + r.x1(), DungeonRules.FLOOR + dy, oz + lz, open ? AIR : BARS);
    }
    /** Personal reward chests in a row across the boss arena, one per member (decor: the chest opens each player's own loot). */
    static List<BlockPos> rewardChests(ServerLevel level, DungeonLayout.Room boss, int ox, int oz, int count) {
        var out = new ArrayList<BlockPos>(); int x = ox + (boss.x0() + boss.x1()) / 2;
        for (int i = 0; i < count; i++) { int z = oz + (i - (count - 1) / 2) * 2 - (count % 2 == 0 ? 1 : 0); chest(level, x, DungeonRules.FLOOR + 1, z); out.add(new BlockPos(x, DungeonRules.FLOOR + 1, z)); }
        return out;
    }
    static void chest(ServerLevel level, int x, int y, int z) { level.setBlock(new BlockPos(x, y, z), Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.WEST), Block.UPDATE_ALL); }

    /** Clears the slot's box (blocks and every non-player entity), a few columns per step. */
    static final class Wipe implements Work {
        private final int x0, z0, x1, z1, y1; private int x;
        Wipe(int x0, int z0, int x1, int z1, int top) { this.x0 = x0; this.z0 = z0; this.x1 = x1; this.z1 = z1; this.y1 = top; this.x = x0; }
        /** The whole cell of a slot, for a slot whose plan is unknown (after a restart). */
        static Wipe slot(int slot, int width, int height) { int ox = DungeonRules.slotX(slot), oz = DungeonRules.slotZ(slot); return new Wipe(ox - 4, oz - width, ox + 420, oz + width, height); }
        @Override public boolean step(ServerLevel level, long deadline) {
            if (x == x0) for (var e : level.getEntities((net.minecraft.world.entity.Entity) null, new net.minecraft.world.phys.AABB(x0, 0, z0, x1 + 1, 256, z1 + 1), e -> !(e instanceof net.minecraft.world.entity.player.Player)))
                e.discard();
            var pos = new BlockPos.MutableBlockPos();
            while (System.nanoTime() < deadline && x <= x1) {
                // A void chunk loads (or generates) cheaply; after a restart the slot's chunks are not loaded yet.
                for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) level.getChunk(x >> 4, cz);
                for (int z = z0; z <= z1; z++)
                    for (int y = DungeonRules.FLOOR - 2; y <= DungeonRules.FLOOR + y1; y++) { pos.set(x, y, z); if (!level.getBlockState(pos).isAir()) { level.removeBlockEntity(pos); level.setBlock(pos, AIR, FLAGS); } }
                x++;
            }
            return x > x1;
        }
    }
    private static void set(ServerLevel level, int x, int y, int z, BlockState state) { level.setBlock(new BlockPos(x, y, z), state, FLAGS); }
    private static void setShaped(ServerLevel level, int x, int y, int z, BlockState state) { var pos = new BlockPos(x, y, z); level.setBlock(pos, Block.updateFromNeighbourShapes(state, level, pos), Block.UPDATE_ALL); }
}
