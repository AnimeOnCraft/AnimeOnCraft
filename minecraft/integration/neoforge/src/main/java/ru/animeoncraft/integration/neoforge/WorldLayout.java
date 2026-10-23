package ru.animeoncraft.integration.neoforge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Built objects of the states world (World Design stage 5): POIs, roads and the zones where random structures
 * must not start. Read from {@code <world>/datapacks/animeoncraft-states/aoc/layout.json}, generated from the
 * blueprint by scripts/world-design/build-world-profile.py. Pure data, no Minecraft types: unit-tested.
 */
final class WorldLayout {
    record Palette(String wall, String roof, String accent, String floor) {}
    record Poi(String code, String type, String name, int x, int z, int radius, String palette, String family, int tier, String kind, String road) {}
    record Road(String code, String kind, String style, int width, List<int[]> points) {}
    record Zone(String code, int x, int z, int radius) {
        boolean contains(double px, double pz) { double dx = px - x, dz = pz - z; return dx * dx + dz * dz <= (double) radius * radius; }
    }
    record State(String worldId, Map<String, Palette> palettes, Map<String, Poi> pois, Map<String, Road> roads, List<Zone> exclusions) {}

    private static volatile State state;
    private WorldLayout() {}
    static State state() { return state; }
    static boolean active() { return state != null; }
    static void clear() { state = null; }

    static State load(Path file) throws IOException {
        if (Files.size(file) > 2_000_000) throw new IOException("layout.json too large");
        state = parse(JsonParser.parseString(Files.readString(file)).getAsJsonObject());
        return state;
    }

    static State parse(JsonObject root) {
        if (root.get("format_version").getAsInt() != 1) throw new IllegalArgumentException("Unsupported layout format");
        var palettes = new LinkedHashMap<String, Palette>();
        for (var entry : root.getAsJsonObject("palettes").entrySet()) {
            var p = entry.getValue().getAsJsonObject();
            palettes.put(entry.getKey(), new Palette(id(p, "wall"), id(p, "roof"), id(p, "accent"), id(p, "floor")));
        }
        if (!palettes.containsKey("neutral")) throw new IllegalArgumentException("Layout needs a neutral palette");
        var pois = new LinkedHashMap<String, Poi>();
        for (var value : root.getAsJsonArray("pois")) {
            var p = value.getAsJsonObject();
            String code = code(p.get("code").getAsString()), palette = p.get("palette").getAsString();
            if (!palettes.containsKey(palette)) throw new IllegalArgumentException("Unknown palette " + palette + " for " + code);
            int radius = p.get("radius").getAsInt();
            if (radius < 4 || radius > 256) throw new IllegalArgumentException("Invalid radius for " + code);
            pois.put(code, new Poi(code, p.get("type").getAsString(), p.get("name").getAsString(), coordinate(p, "x"), coordinate(p, "z"), radius, palette,
                text(p, "family"), p.has("tier") ? p.get("tier").getAsInt() : 0, text(p, "kind"), text(p, "road")));
        }
        var roads = new LinkedHashMap<String, Road>();
        for (var value : root.getAsJsonArray("roads")) {
            var r = value.getAsJsonObject();
            var points = new ArrayList<int[]>();
            for (var point : r.getAsJsonArray("points")) {
                var pair = point.getAsJsonArray();
                int x = pair.get(0).getAsInt(), z = pair.get(1).getAsInt();
                if (Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000) throw new IllegalArgumentException("Road point out of the world");
                points.add(new int[] {x, z});
            }
            int width = r.get("width").getAsInt();
            if (points.size() < 2 || points.size() > 4096 || width < 1 || width > 9) throw new IllegalArgumentException("Invalid road " + r.get("code").getAsString());
            String code = code(r.get("code").getAsString());
            roads.put(code, new Road(code, r.get("kind").getAsString(), r.get("style").getAsString(), width, List.copyOf(points)));
        }
        var exclusions = new ArrayList<Zone>();
        for (var value : root.getAsJsonArray("exclusions")) {
            var z = value.getAsJsonObject();
            int radius = z.get("radius").getAsInt();
            if (radius < 8 || radius > 1024) throw new IllegalArgumentException("Invalid exclusion radius");
            exclusions.add(new Zone(code(z.get("code").getAsString()), coordinate(z, "x"), coordinate(z, "z"), radius));
        }
        return new State(root.get("world_id").getAsString(), Map.copyOf(palettes), java.util.Collections.unmodifiableMap(pois),
            java.util.Collections.unmodifiableMap(roads), List.copyOf(exclusions));
    }
    private static String id(JsonObject row, String key) {
        String value = row.get(key).getAsString();
        if (!value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("Invalid block id " + value);
        return value;
    }
    private static String code(String value) {
        if (!value.matches("[a-z0-9][a-z0-9._-]{1,63}")) throw new IllegalArgumentException("Invalid code " + value);
        return value;
    }
    private static int coordinate(JsonObject row, String key) {
        int value = row.get(key).getAsInt();
        if (Math.abs(value) > 30_000_000) throw new IllegalArgumentException("Coordinate out of the world");
        return value;
    }
    private static String text(JsonObject row, String key) { return row.has(key) && !row.get(key).isJsonNull() ? row.get(key).getAsString() : null; }

    /**
     * True when no random structure may start in this chunk: its centre lies in a POI zone (which already
     * includes a margin for structures that extend from their start chunk).
     */
    static boolean excludedChunk(int chunkX, int chunkZ) {
        var current = state;
        if (current == null) return false;
        double x = (chunkX << 4) + 8, z = (chunkZ << 4) + 8;
        for (var zone : current.exclusions()) if (zone.contains(x, z)) return true;
        return false;
    }

    /** Unit vector of the road direction nearest to (x, z), or null when no road passes within {@code reach}. */
    static double[] roadDirection(State layout, int x, int z, int reach) {
        double best = (double) reach * reach; double[] direction = null;
        for (var road : layout.roads().values()) {
            var points = road.points();
            for (int i = 0; i + 1 < points.size(); i++) {
                var a = points.get(i); var b = points.get(i + 1);
                double dx = b[0] - a[0], dz = b[1] - a[1], length = dx * dx + dz * dz;
                if (length == 0) continue;
                double t = Math.max(0, Math.min(1, ((x - a[0]) * dx + (z - a[1]) * dz) / length));
                double px = a[0] + t * dx - x, pz = a[1] + t * dz - z, distance = px * px + pz * pz;
                if (distance < best) { best = distance; double n = Math.sqrt(length); direction = new double[] {dx / n, dz / n}; }
            }
        }
        return direction;
    }
}
