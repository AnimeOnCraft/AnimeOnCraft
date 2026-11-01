package ru.animeoncraft.integration.neoforge;

import java.util.*;

/**
 * Room chain of one run, from its seed (pure, unit-tested). Rooms stand in a row along +X, centred on z = 0 of the
 * slot, joined by short corridors; every room's exit is closed by bars until the room is cleared. Coordinates are
 * relative to the slot origin; the floor is at {@link DungeonRules#FLOOR}.
 */
final class DungeonLayout {
    /** Interior size (x, z), wall height; fight rooms run waves, trap and bridge rooms are crossed, treasure has a chest. */
    enum Kind {
        ENTRANCE(11, 11, 6), FIGHT(17, 17, 8), TRAP(25, 7, 6), BRIDGE(23, 13, 10), ELITE(19, 19, 9), TREASURE(9, 9, 6), BOSS(29, 29, 14);
        final int length, width, height;
        Kind(int length, int width, int height) { this.length = length; this.width = width; this.height = height; }
    }
    static final int CORRIDOR = 5, DOOR_HALF = 1, DOOR_HEIGHT = 3;
    /** x0..x1, z0..z1 are the outer walls (inclusive); waves: fight waves of the room (0 for none). */
    record Room(int index, Kind kind, int x0, int z0, int x1, int z1, int waves) {
        int height() { return kind.height; }
        boolean inside(double x, double z) { return x > x0 && x < x1 + 1 && z > z0 && z < z1 + 1; }
        /** Where a player of this room comes back after a death: just inside its entrance. */
        int[] entry() { return new int[] {x0 + 2, 0}; }
        /** Trap rooms are cleared by reaching this x. */
        int finish() { return x1 - 2; }
        /** Bridge rooms: the open pit between the two platforms, except the bridge itself. */
        boolean pit(int x, int z) { return kind == Kind.BRIDGE && x > x0 + 4 && x < x1 - 4 && Math.abs(z) > DOOR_HALF; }
    }
    private DungeonLayout() {}

    /**
     * The plan of the "how a run is put together" diagram: entrance, a fight, a trap or a bridge, two waves, the elite
     * room, sometimes a treasure room, the boss arena. {@code rooms}: rooms before the boss in the catalog (5 or more).
     */
    static List<Room> plan(long seed, int rooms) {
        var random = new SplittableRandom(seed); var kinds = new ArrayList<Kind>(); var waves = new ArrayList<Integer>();
        kinds.add(Kind.ENTRANCE); waves.add(0);
        kinds.add(Kind.FIGHT); waves.add(1);
        boolean trap = random.nextBoolean(); kinds.add(trap ? Kind.TRAP : Kind.BRIDGE); waves.add(trap ? 0 : 1);
        while (kinds.size() < Math.max(3, rooms - 1)) { kinds.add(Kind.FIGHT); waves.add(2); }
        kinds.add(Kind.ELITE); waves.add(1);
        if (random.nextInt(2) == 0) { kinds.add(Kind.TREASURE); waves.add(0); }
        kinds.add(Kind.BOSS); waves.add(1);
        var out = new ArrayList<Room>(); int x = 0;
        for (int i = 0; i < kinds.size(); i++) {
            var k = kinds.get(i); int half = k.width / 2 + 1;
            out.add(new Room(i, k, x, -half, x + k.length + 1, half, waves.get(i)));
            x += k.length + 2 + CORRIDOR;
        }
        return List.copyOf(out);
    }
    /** Outer extent of the whole chain: {minX, minZ, maxX, maxZ}. */
    static int[] bounds(List<Room> rooms) {
        int minZ = 0, maxZ = 0; for (var r : rooms) { minZ = Math.min(minZ, r.z0()); maxZ = Math.max(maxZ, r.z1()); }
        return new int[] {rooms.getFirst().x0(), minZ, rooms.getLast().x1(), maxZ};
    }
    /** Highest block the chain uses above the floor (walls and roofs). */
    static int top(List<Room> rooms) { int h = 0; for (var r : rooms) h = Math.max(h, r.height() + 1); return h; }
    /** Chunks (x, z) a run keeps loaded, for a slot origin. */
    static List<long[]> chunks(List<Room> rooms, int originX, int originZ) {
        var b = bounds(rooms); var out = new ArrayList<long[]>();
        for (int cx = (originX + b[0] - 2) >> 4; cx <= (originX + b[2] + 2) >> 4; cx++) for (int cz = (originZ + b[1] - 2) >> 4; cz <= (originZ + b[3] + 2) >> 4; cz++) out.add(new long[] {cx, cz});
        return out;
    }
    /** Room the column lies in, or null in a corridor. */
    static Room at(List<Room> rooms, double x, double z) { for (var r : rooms) if (r.inside(x, z)) return r; return null; }
    /** Spawn spots inside a room, two blocks clear of the walls, off the pit, on the far half for crossing rooms. */
    static List<int[]> spawnPoints(Room room, int count, long seed) {
        var random = new SplittableRandom(seed ^ room.index() * 0x9E3779B97F4A7C15L); var out = new ArrayList<int[]>();
        int from = room.kind() == Kind.BRIDGE ? room.x1() - 4 : room.x0() + 4, to = room.x1() - 2;
        for (int tries = 0; out.size() < count && tries < count * 50; tries++) {
            int x = from + random.nextInt(Math.max(1, to - from)), z = room.z0() + 2 + random.nextInt(Math.max(1, room.z1() - room.z0() - 3));
            if (!room.pit(x, z)) out.add(new int[] {x, z});
        }
        return out;
    }
}
