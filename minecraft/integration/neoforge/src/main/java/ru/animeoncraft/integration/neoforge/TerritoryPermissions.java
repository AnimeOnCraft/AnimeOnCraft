package ru.animeoncraft.integration.neoforge;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Server-owned policy; map payloads and client-supplied clan names never grant rights.
 * Two sources: the clan-world rectangles of the current world, or — in a states world — the region polygons
 * of the backend catalog ({@link RegionPolicy}). A states world without a loaded catalog fails closed:
 * every column belongs to {@link RegionPolicy#NOBODY} until the first sync (or the cached copy) arrives.
 */
record TerritoryPermissions(List<Territory> territories, List<Spawn> spawns, int spawnRadius, boolean states, RegionPolicy regions) {
    record Spawn(int x, int z) {}

    TerritoryPermissions {
        territories = List.copyOf(territories);
        spawns = List.copyOf(spawns);
        if (spawnRadius < 0 || spawnRadius > 64) throw new IllegalArgumentException("Invalid protected spawn radius");
        if (states && !territories.isEmpty()) throw new IllegalArgumentException("A states world has no rectangle territories");
    }
    TerritoryPermissions(List<Territory> territories, List<Spawn> spawns, int spawnRadius) { this(territories, spawns, spawnRadius, false, null); }

    /** Clan spawns follow region ownership; only non-clan spawns have an unconditional build lock. */
    static TerritoryPermissions states(RegionPolicy regions, int spawnRadius) {
        var spawns = regions == null ? List.<Spawn>of() : regions.spawns().stream().filter(s -> {
            String owner = regions.ownerAt(s.x(), s.z());
            return owner == null || RegionPolicy.NOBODY.equals(owner);
        }).map(s -> new Spawn(s.x(), s.z())).toList();
        return new TerritoryPermissions(List.of(), spawns, spawnRadius, true, regions);
    }

    static TerritoryPermissions read(JsonObject root, long seed) {
        var territories = TerritoryLayout.read(root, seed);
        var spawns = new ArrayList<Spawn>();
        for (String clan : ClanCodes.fromConfig(root)) {
            var point = root.getAsJsonObject("clans").getAsJsonObject(clan);
            int x = point.get("x").getAsInt(), z = point.get("z").getAsInt();
            if (Math.abs((long) x) > 100000 || Math.abs((long) z) > 100000) throw new IllegalArgumentException("Invalid clan spawn");
            // Do not expose an unclaimed spawn if an older config omits the territory layout.
            if (territories.stream().noneMatch(t -> t.clan().equals(clan) && t.contains(x, z))) spawns.add(new Spawn(x, z));
        }
        if (root.has("common")) {
            var point = root.getAsJsonObject("common");
            int x = point.get("x").getAsInt(), z = point.get("z").getAsInt();
            if (Math.abs((long) x) > 100000 || Math.abs((long) z) > 100000) throw new IllegalArgumentException("Invalid common spawn");
            spawns.add(new Spawn(x, z));
        }
        int radius = root.has("protection") ? root.getAsJsonObject("protection").get("spawn_radius_blocks").getAsInt() : 16;
        return new TerritoryPermissions(territories, spawns, radius);
    }

    String territory(int x, int z) {
        if (states) return regions == null ? RegionPolicy.NOBODY : regions.ownerAt(x, z);
        for (var region : territories) if (region.contains(x, z)) return region.clan();
        return null;
    }

    boolean spawn(int x, int z) {
        return spawnRadius > 0 && spawns.stream().anyMatch(point ->
            Math.abs((long) x - point.x()) <= spawnRadius && Math.abs((long) z - point.z()) <= spawnRadius);
    }

    boolean protectedBlock(int x, int z) { return spawn(x, z) || territory(x, z) != null; }

    boolean canBuild(boolean admitted, boolean operator, String clan, int x, int z) {
        if (!admitted) return false;
        if (operator) return true;
        if (spawn(x, z)) return false;
        String owner = territory(x, z);
        return owner == null || owner.equals(clan);
    }

    /** Automation may operate inside one region, but cannot move blocks across its boundary. */
    boolean canMove(int fromX, int fromZ, int toX, int toZ) {
        return !spawn(fromX, fromZ) && !spawn(toX, toZ)
            && Objects.equals(territory(fromX, fromZ), territory(toX, toZ));
    }
}
