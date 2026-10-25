package ru.animeoncraft.integration.neoforge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Blocks;

/**
 * Opt-in fixture for resource mines (scripts/test-resource-mines.mjs, -Danimeoncraft.mineProbe=<dir>, world aoc-layout-test-*):
 * injects a capture view made from world-states.toml (-Danimeoncraft.mineView=<json>) before the layout jobs run, then
 * for every built mine checks the ore fill, a refresh after everything was mined (rubble cleared, a player's brick kept),
 * the protected shell, and dumps the blocks around it for scripts/world-design/render-mine.py.
 */
final class MineProbe {
    private static MinecraftServer server;
    private static Path out;
    private static int wait;
    private static boolean done;
    private MineProbe() {}

    static void started(net.neoforged.neoforge.event.server.ServerStartedEvent event) {
        var current = event.getServer();
        if (current.getPort() != 25569 || !"127.0.0.1".equals(current.getLocalIp()) || !current.getWorldData().getLevelName().startsWith("aoc-layout-test-"))
            throw new IllegalStateException("Mine probe requires its own loopback world");
        server = current; out = Path.of(System.getProperty("animeoncraft.mineProbe"));
        try { CaptureSites.accept(CaptureSites.parse(JsonParser.parseString(Files.readString(Path.of(System.getProperty("animeoncraft.mineView")))).getAsJsonObject())); }
        catch (Exception failure) { System.out.println("AOC_MINE_PROBE_FAILED view: " + failure); done = true; }
    }

    static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (done || server != event.getServer() || !LayoutJobs.idle()) return;
        if (++wait < 60) return;
        done = true;
        try { verify(); }
        catch (Exception failure) { System.out.println("AOC_MINE_PROBE_FAILED " + failure); failure.printStackTrace(System.out); }
    }

    private static void verify() throws Exception {
        var level = server.overworld();
        var dir = ResourcePits.directory(server).resolve("mines");
        Files.createDirectories(out.resolve("dumps"));
        var summary = new JsonArray();
        List<Path> files;
        try (var list = Files.list(dir)) { files = list.filter(f -> f.toString().endsWith(".json")).sorted().toList(); }
        if (files.isEmpty()) throw new IllegalStateException("No mine was built");
        for (var file : files) {
            var mine = ResourcePits.read(file); String site = mine.get("site").getAsString();
            var point = CaptureSites.view().points().get(site);
            int x = mine.get("x").getAsInt(), z = mine.get("z").getAsInt();
            for (int cx = (x - point.radius()) >> 4; cx <= (x + point.radius()) >> 4; cx++) for (int cz = (z - point.radius()) >> 4; cz <= (z + point.radius()) >> 4; cz++) level.getChunk(cx, cz);
            var pit = ResourcePits.read(ResourcePits.directory(server).resolve("pits").resolve(site + ".json"));
            check(pit != null && pit.has("mine"), site + ": pit not switched to the mine");
            var rows = pit.getAsJsonArray("blocks"); int ores = 0;
            var other = new java.util.TreeMap<String, Integer>();
            for (var value : rows) { var row = value.getAsJsonArray(); var found = level.getBlockState(pos(x, z, row));
                if (found.is(block(row.get(3).getAsString()))) ores++; else other.merge(BuiltInRegistries.BLOCK.getKey(found.getBlock()).toString(), 1, Integer::sum); }
            check(ores == rows.size(), site + ": " + ores + " of " + rows.size() + " slots hold ore after the build, others " + other);
            dump(level, mine, site + "-built");
            // Every slot is mined; rubble in the first, a player's brick in the second.
            for (var value : rows) level.setBlockAndUpdate(pos(x, z, value.getAsJsonArray()), Blocks.AIR.defaultBlockState());
            var rubble = pos(x, z, rows.get(0).getAsJsonArray()); var brick = pos(x, z, rows.get(1).getAsJsonArray());
            level.setBlockAndUpdate(rubble, Blocks.COBBLESTONE.defaultBlockState()); level.setBlockAndUpdate(brick, Blocks.BRICKS.defaultBlockState());
            int restored = ResourcePits.apply(server, List.of(), point, new CaptureSites.Refresh(site, Instant.now().plusSeconds(3600), 1));
            check(restored == rows.size() - 1, site + ": refresh restored " + restored + ", expected " + (rows.size() - 1));
            check(!level.getBlockState(rubble).is(Blocks.COBBLESTONE), site + ": rubble left in a slot");
            check(level.getBlockState(brick).is(Blocks.BRICKS), site + ": player block overwritten");
            level.setBlockAndUpdate(brick, block(rows.get(1).getAsJsonArray().get(3).getAsString()).defaultBlockState()); // leave the world ready for review shots
            // Shell: the chamber floor is protected, ore slots are not.
            int floor = mine.get("floor").getAsInt();
            check(MineShells.shell(level, new BlockPos(x, floor - 1, z)) || MineShells.shell(level, new BlockPos(x + 1, floor - 1, z)), site + ": floor not protected");
            check(!MineShells.shell(level, rubble), site + ": an ore slot is protected");
            var row = new JsonObject(); row.addProperty("site", site); row.addProperty("slots", mine.getAsJsonArray("slots").size());
            row.addProperty("ores", rows.size()); row.addProperty("restored", restored); row.addProperty("shell", mine.getAsJsonArray("shell").size());
            row.addProperty("floor", floor); row.addProperty("facing", mine.get("facing").getAsString()); summary.add(row);
            System.out.println("AOC_MINE_OK " + site + " ores=" + rows.size() + " restored=" + restored);
        }
        Files.writeString(out.resolve("mines-summary.json"), summary.toString());
        System.out.println("AOC_MINE_PROBE_PASS " + summary.size() + " mines");
    }

    private static BlockPos pos(int x, int z, JsonArray row) { return new BlockPos(x + row.get(0).getAsInt(), row.get(1).getAsInt(), z + row.get(2).getAsInt()); }
    private static net.minecraft.world.level.block.Block block(String id) { return BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.parse(id)); }
    private static void check(boolean valid, String message) { if (!valid) throw new IllegalStateException(message); }

    /** Block ids of the mine's surroundings, palette-coded, x fastest then z then y, for an isometric cutaway render. */
    private static void dump(net.minecraft.server.level.ServerLevel level, JsonObject mine, String name) throws java.io.IOException {
        int x = mine.get("x").getAsInt(), z = mine.get("z").getAsInt(), floor = mine.get("floor").getAsInt(), r = 26;
        int y0 = floor - 3, y1 = floor + 30;
        var palette = new LinkedHashMap<String, Integer>(); var cells = new StringBuilder();
        var pos = new BlockPos.MutableBlockPos();
        for (int y = y0; y <= y1; y++) for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) {
            var state = level.getBlockState(pos.set(x + dx, y, z + dz));
            String id = state.isAir() ? "air" : BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            if (state.hasProperty(net.minecraft.world.level.block.LanternBlock.HANGING)) id += state.getValue(net.minecraft.world.level.block.LanternBlock.HANGING) ? "#hanging" : "";
            int code = palette.computeIfAbsent(id, k -> palette.size());
            cells.append(code).append(',');
        }
        var json = new JsonObject(); json.addProperty("name", name); json.addProperty("x0", x - r); json.addProperty("z0", z - r); json.addProperty("y0", y0);
        json.addProperty("size", 2 * r + 1); json.addProperty("height", y1 - y0 + 1); json.addProperty("floor", floor); json.addProperty("facing", mine.get("facing").getAsString());
        var ids = new JsonArray(); palette.keySet().forEach(ids::add); json.add("palette", ids); json.addProperty("cells", cells.toString());
        var slots = new JsonArray(); mine.getAsJsonArray("slots").forEach(slots::add); json.add("slots", slots);
        Files.writeString(out.resolve("dumps").resolve(name + ".json"), json.toString());
    }
}
