package ru.animeoncraft.integration.neoforge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Build policy of a states world (World Design stages 7–8): the region polygons of the backend catalog
 * ({@code POST /integration/v1/world/regions}) turned into the "owner" that {@link TerritoryPermissions} enforces.
 *
 * <ul>
 *   <li>{@code MEMBERS} with a clan — that clan builds (and the war/raid rules apply as on the old rectangles);</li>
 *   <li>{@code NONE}, or {@code MEMBERS} without a clan — nobody but operators ({@link #NOBODY}); PvP is denied there too;</li>
 *   <li>{@code OPEN}, reserved slots and land outside every region — unprotected (null).</li>
 * </ul>
 * Lookups are cached per chunk: a chunk no region border crosses has one answer for all its columns.
 * Pure Java: no Minecraft classes, unit tested in {@code RegionPolicyTest}.
 */
final class RegionPolicy {
    /** Owner of a region nobody may build in; never equal to a clan code (those are [A-Z0-9]{2,12}). */
    static final String NOBODY = "#NONE";
    private static final String OPEN = "";
    private static final String MIXED = "#MIXED";
    private static final int LIMIT = 100_000;

    /** {@code clan} is the region's clan (null for neutral land), {@code color} its map colour 0xRRGGBB or -1. */
    record Region(String code, String owner, String clan, int color, int[] xs, int[] zs, int minX, int minZ, int maxX, int maxZ, Spawn spawn) {
        /** Even-odd rule at the column centre (x + 0.5, z + 0.5). */
        boolean contains(int x, int z) {
            if (x < minX || x > maxX || z < minZ || z > maxZ) return false;
            double px = x + 0.5, pz = z + 0.5; boolean inside = false;
            for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
                if ((zs[i] > pz) != (zs[j] > pz) && px < (double) (xs[j] - xs[i]) * (pz - zs[i]) / (zs[j] - zs[i]) + xs[i]) inside = !inside;
            }
            return inside;
        }
        /** True when one of the polygon edges passes through the column square [x0, x1) × [z0, z1). */
        boolean crosses(int x0, int z0, int x1, int z1) {
            if (x1 <= minX || x0 > maxX || z1 <= minZ || z0 > maxZ) return false;
            for (int i = 0, j = xs.length - 1; i < xs.length; j = i++)
                if (segmentHitsBox(xs[j], zs[j], xs[i], zs[i], x0, z0, x1, z1)) return true;
            return false;
        }
    }
    record Spawn(int x, int z) {}

    private final String revision;
    private final List<Region> regions;
    private final ConcurrentHashMap<Long, String> chunks = new ConcurrentHashMap<>();

    private final String worldId;
    private RegionPolicy(String revision, String worldId, List<Region> regions) { this.revision = revision; this.worldId = worldId; this.regions = List.copyOf(regions); }

    String revision() { return revision; }
    String worldId() { return worldId; }
    List<Region> regions() { return regions; }
    List<Spawn> spawns() { return regions.stream().map(Region::spawn).filter(java.util.Objects::nonNull).toList(); }

    /** Clan code, {@link #NOBODY}, or null for unprotected land. */
    String ownerAt(int x, int z) {
        long key = ((long) Math.floorDiv(x, 16) << 32) ^ (Math.floorDiv(z, 16) & 0xFFFFFFFFL);
        String cached = chunks.get(key);
        if (cached == null) {
            int x0 = Math.floorDiv(x, 16) * 16, z0 = Math.floorDiv(z, 16) * 16;
            boolean mixed = false;
            for (var region : regions) if (region.crosses(x0, z0, x0 + 16, z0 + 16)) { mixed = true; break; }
            cached = mixed ? MIXED : exact(x0 + 8, z0 + 8);
            if (chunks.size() < 1_000_000) chunks.put(key, cached);
        }
        String owner = MIXED.equals(cached) ? exact(x, z) : cached;
        return OPEN.equals(owner) ? null : owner;
    }
    /** Regions do not overlap (the catalog import rejects it); if they ever do, the most restrictive owner wins. */
    private String exact(int x, int z) {
        String found = OPEN;
        for (var region : regions) if (region.contains(x, z)) {
            if (!OPEN.equals(region.owner())) return region.owner();
            found = region.owner();
        }
        return found;
    }

    /** Parses the backend reply; anything malformed is rejected as a whole so a broken reply never relaxes protection. */
    static RegionPolicy parse(JsonObject reply) {
        String revision = reply.get("revision").getAsString();
        if (!revision.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid region revision");
        String worldId = reply.has("world_id") ? reply.get("world_id").getAsString() : null;
        if (worldId == null || !worldId.matches("[a-z0-9][a-z0-9-]{1,63}")) throw new IllegalArgumentException("Invalid world id");
        JsonArray rows = reply.getAsJsonArray("regions");
        // An empty catalog would leave the whole world open: never accept it in place of a real one.
        if (rows.isEmpty() || rows.size() > 512) throw new IllegalArgumentException("Region count out of range");
        var regions = new ArrayList<Region>();
        for (var element : rows) {
            var row = element.getAsJsonObject();
            String code = row.get("code").getAsString();
            if (!code.matches("[a-z0-9][a-z0-9._-]{1,63}")) throw new IllegalArgumentException("Invalid region code");
            String build = row.get("build_policy").getAsString();
            String clan = row.has("clan_code") && !row.get("clan_code").isJsonNull() ? row.get("clan_code").getAsString() : null;
            if (clan != null && !ClanCodes.valid(clan)) throw new IllegalArgumentException("Invalid region clan " + clan);
            String owner = switch (build) {
                case "OPEN" -> OPEN;
                case "NONE" -> NOBODY;
                case "MEMBERS", "OWNER_ZONE" -> clan == null ? NOBODY : clan;
                default -> throw new IllegalArgumentException("Invalid build policy " + build);
            };
            var polygon = row.getAsJsonArray("polygon");
            if (polygon.size() < 3 || polygon.size() > 4096) throw new IllegalArgumentException("Invalid polygon of " + code);
            int[] xs = new int[polygon.size()], zs = new int[polygon.size()];
            int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (int i = 0; i < polygon.size(); i++) {
                var vertex = polygon.get(i).getAsJsonArray();
                if (vertex.size() != 2) throw new IllegalArgumentException("Invalid vertex of " + code);
                xs[i] = coordinate(vertex.get(0).getAsInt()); zs[i] = coordinate(vertex.get(1).getAsInt());
                minX = Math.min(minX, xs[i]); maxX = Math.max(maxX, xs[i]); minZ = Math.min(minZ, zs[i]); maxZ = Math.max(maxZ, zs[i]);
            }
            Spawn spawn = null;
            if (row.has("spawn") && !row.get("spawn").isJsonNull()) {
                var s = row.getAsJsonObject("spawn");
                spawn = new Spawn(coordinate(s.get("x").getAsInt()), coordinate(s.get("z").getAsInt()));
            }
            int color = -1;
            if (row.has("color") && !row.get("color").isJsonNull()) {
                String hex = row.get("color").getAsString();
                if (!hex.matches("[0-9A-Fa-f]{6}")) throw new IllegalArgumentException("Invalid colour of " + code);
                color = Integer.parseInt(hex, 16);
            }
            regions.add(new Region(code, owner, clan, color, xs, zs, minX, minZ, maxX, maxZ, spawn));
        }
        if (regions.stream().allMatch(r -> OPEN.equals(r.owner()))) throw new IllegalArgumentException("Catalog protects nothing");
        return new RegionPolicy(revision, worldId, regions);
    }
    private static int coordinate(int value) {
        if (Math.abs(value) > LIMIT) throw new IllegalArgumentException("Coordinate out of range");
        return value;
    }

    /** Liang–Barsky clip of the segment against the box [x0, x1) × [z0, z1). */
    static boolean segmentHitsBox(double ax, double az, double bx, double bz, double x0, double z0, double x1, double z1) {
        double t0 = 0, t1 = 1, dx = bx - ax, dz = bz - az;
        double[] p = {-dx, dx, -dz, dz}, q = {ax - x0, x1 - ax, az - z0, z1 - az};
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0) { if (q[i] < 0) return false; continue; }
            double r = q[i] / p[i];
            if (p[i] < 0) { if (r > t1) return false; if (r > t0) t0 = r; }
            else { if (r < t0) return false; if (r < t1) t1 = r; }
        }
        return true;
    }
}
