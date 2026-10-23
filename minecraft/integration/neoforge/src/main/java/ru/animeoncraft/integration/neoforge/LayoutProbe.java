package ru.animeoncraft.integration.neoforge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Opt-in fixture for stage 5 (scripts/test-layout.mjs, -Danimeoncraft.layoutProbe=<dir>, world aoc-layout-test-*):
 * builds the selected roads and POIs, checks that no random structure started inside their exclusion zones,
 * and writes top-down renders (map colour and height per column) for the owner's visual acceptance.
 */
final class LayoutProbe {
    private static MinecraftServer server;
    private static Path out;
    private static int stage, wait;
    private static long started;
    private LayoutProbe() {}

    static void started(net.neoforged.neoforge.event.server.ServerStartedEvent event) {
        String dir = System.getProperty("animeoncraft.layoutProbe");
        if (dir == null) return;
        var current = event.getServer();
        if (current.getPort() != 25569 || !"127.0.0.1".equals(current.getLocalIp()) || !current.getWorldData().getLevelName().startsWith("aoc-layout-test-"))
            throw new IllegalStateException("Layout probe requires its own loopback world");
        if (!WorldLayout.active()) { System.out.println("AOC_LAYOUT_PROBE_FAILED no layout.json in the world datapack"); return; }
        server = current; out = Path.of(dir); started = System.currentTimeMillis();
        LayoutJobs.budget(400); // a disposable server: favour throughput over tick time
        int queued = 0;
        for (String code : System.getProperty("animeoncraft.layoutCodes", "all").split(","))
            queued += Math.max(0, LayoutJobs.enqueue(server, code.trim(), true, null, 0, 0));
        System.out.println("AOC_LAYOUT_PROBE queued=" + queued);
    }

    static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (server != event.getServer() || stage > 0 || !LayoutJobs.idle()) return;
        if (++wait < 40) return; // let lighting and neighbour updates settle
        stage = 1;
        try { verify(server.overworld()); }
        catch (Exception failure) { System.out.println("AOC_LAYOUT_PROBE_FAILED " + failure); failure.printStackTrace(System.out); }
    }

    private static void verify(ServerLevel level) throws Exception {
        var layout = WorldLayout.state();
        var built = LayoutJobs.progress(server).getAsJsonObject("built");
        Files.createDirectories(out.resolve("renders"));
        var summary = new JsonObject(); var pois = new JsonArray(); var roads = new JsonArray();
        int insideStarts = 0, outsideStarts = 0, zoneChunks = 0;
        var checkedChunks = new java.util.HashSet<Long>();
        for (var poi : layout.pois().values()) {
            if (!built.has(poi.code())) continue;
            var zone = layout.exclusions().stream().filter(z -> z.code().equals(poi.code())).findFirst().orElseThrow();
            // Chunks of the built footprint are generated; all of them must be free of random structure starts.
            int reach = Math.min(zone.radius(), Math.max(poi.radius(), "CAPITAL".equals(poi.type()) ? 120 : 24) + 16);
            for (int cx = (poi.x() - reach) >> 4; cx <= (poi.x() + reach) >> 4; cx++) for (int cz = (poi.z() - reach) >> 4; cz <= (poi.z() + reach) >> 4; cz++) {
                if (!level.hasChunk(cx, cz) || !checkedChunks.add(net.minecraft.world.level.ChunkPos.asLong(cx, cz))) continue;
                if (!WorldLayout.excludedChunk(cx, cz)) continue;
                zoneChunks++;
                insideStarts += level.getChunk(cx, cz).getAllStarts().size();
            }
            int r = Math.max(40, Math.min(150, "CAPITAL".equals(poi.type()) ? 130 : poi.radius() * 2 + 16));
            render(level, poi.code(), poi.x() - r, poi.z() - r, 2 * r + 1);
            var row = new JsonObject(); row.addProperty("code", poi.code()); row.addProperty("type", poi.type()); row.addProperty("name", poi.name());
            row.addProperty("x", poi.x()); row.addProperty("z", poi.z()); pois.add(row);
        }
        for (var road : layout.roads().values()) {
            if (!built.has(road.code())) continue;
            var trace = RoadGeometry.trace(road.points());
            for (int i = trace.size() / 4; i < trace.size(); i += Math.max(1, trace.size() / 2)) {
                var p = trace.get(i);
                render(level, road.code() + "-" + i, p[0] - 48, p[1] - 48, 97);
            }
            var row = new JsonObject(); row.addProperty("code", road.code()); row.addProperty("blocks", trace.size()); roads.add(row);
        }
        // Control: loaded chunks outside every zone still get their structures.
        for (var holder : ((ru.animeoncraft.integration.neoforge.mixin.ChunkMapAccess) level.getChunkSource().chunkMap).animeoncraft$holders().values()) {
            var pos = holder.getPos();
            if (WorldLayout.excludedChunk(pos.x, pos.z)) continue;
            var full = holder.getLatestChunk();
            if (full != null) outsideStarts += full.getAllStarts().size();
        }
        summary.add("pois", pois); summary.add("roads", roads);
        summary.addProperty("zone_chunks_checked", zoneChunks); summary.addProperty("structure_starts_in_zones", insideStarts);
        summary.addProperty("structure_starts_elsewhere", outsideStarts);
        summary.addProperty("minutes", Math.round((System.currentTimeMillis() - started) / 600.0) / 100.0);
        Files.writeString(out.resolve("layout-summary.json"), summary.toString());
        if (insideStarts > 0) throw new IllegalStateException("Random structures started inside POI zones: " + insideStarts);
        System.out.println("AOC_LAYOUT_PROBE_PASS " + summary.get("pois").getAsJsonArray().size() + " pois, " + roads.size() + " roads, zone chunks " + zoneChunks
            + ", starts in zones 0, elsewhere " + outsideStarts);
    }

    /** Top-down map colour and surface height of a square, for scripts/world-design/render-layout.py. */
    static void render(ServerLevel level, String name, int x0, int z0, int size) throws java.io.IOException {
        var colors = new int[size * size]; var heights = new int[size * size];
        var pos = new BlockPos.MutableBlockPos();
        for (int dz = 0; dz < size; dz++) for (int dx = 0; dx < size; dx++) {
            int x = x0 + dx, z = z0 + dz; PoiBuilder.load(level, x, z); int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
            var state = level.getBlockState(pos.set(x, y, z));
            int depth = 0;
            while (!state.getFluidState().isEmpty() && depth < 12) { depth++; state = level.getBlockState(pos.set(x, y - depth, z)); }
            int color = level.getBlockState(pos.set(x, y, z)).getMapColor(level, pos).col;
            colors[dz * size + dx] = depth > 0 ? (color & 0xFFFFFF) | (Math.min(depth, 12) << 24) : color & 0xFFFFFF;
            heights[dz * size + dx] = y;
        }
        var json = new JsonObject();
        json.addProperty("name", name); json.addProperty("x0", x0); json.addProperty("z0", z0); json.addProperty("size", size);
        var c = new JsonArray(); for (int v : colors) c.add(v); json.add("colors", c);
        var h = new JsonArray(); for (int v : heights) h.add(v); json.add("heights", h);
        Files.writeString(out.resolve("renders").resolve(name.replaceAll("[^a-z0-9._-]", "_") + ".json"), json.toString());
    }
}
