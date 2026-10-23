package ru.animeoncraft.integration.neoforge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Runs the stage-5 build jobs (roads first, then POIs, so buildings sit on finished roads) on the server thread
 * within a time budget per tick. Finished objects are recorded in {@code <world>/animeoncraft-layout.json} and are
 * not built twice unless forced. Only active in a states world with a layout (datapack animeoncraft-states).
 *
 *   /aoc worldgen layout status
 *   /aoc worldgen layout build all|roads|pois|<code> [force]
 *   /aoc worldgen layout near <radius>       — everything with a point or road vertex within the radius
 *   /aoc worldgen layout budget <ms>         — work time per tick (default 30 ms; a pregeneration server uses more)
 *   /aoc worldgen map <radius> <step>        — top-down overview of a pregenerated world (stage 6, {@link MapRender})
 */
final class LayoutJobs {
    interface Job {
        String code(); boolean step(net.minecraft.server.level.ServerLevel level); String progress();
        /** Built objects are recorded in the progress file; helper jobs such as the map render are not. */
        default boolean recorded() { return true; }
    }
    private record PoiJob(WorldLayout.Poi poi) implements Job {
        @Override public String code() { return poi.code(); }
        @Override public boolean step(net.minecraft.server.level.ServerLevel level) {
            int y = PoiBuilder.build(level, WorldLayout.state(), poi);
            CaptureAnchors.record(level.getServer(), poi.code(), y);
            System.out.println("AOC_LAYOUT_BUILT " + poi.code() + " type=" + poi.type() + " y=" + y);
            return true;
        }
        @Override public String progress() { return poi.code(); }
    }
    private static final Deque<Job> QUEUE = new ArrayDeque<>();
    private static Job current;
    private static long budgetNanos = 30_000_000L;
    private static final System.Logger LOG = System.getLogger("AnimeOnCraft");
    private LayoutJobs() {}

    static boolean idle() { return current == null && QUEUE.isEmpty(); }
    static int pending() { return QUEUE.size() + (current == null ? 0 : 1); }
    static void budget(long millis) { budgetNanos = Math.max(5, millis) * 1_000_000L; }

    private static Path progressFile(MinecraftServer server) { return server.getWorldPath(LevelResource.ROOT).resolve("animeoncraft-layout.json"); }
    static JsonObject progress(MinecraftServer server) {
        try {
            var file = progressFile(server);
            if (Files.isRegularFile(file)) return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        } catch (IOException | RuntimeException invalid) { LOG.log(System.Logger.Level.WARNING, "Layout progress file unreadable; starting from scratch"); }
        var fresh = new JsonObject(); fresh.add("built", new JsonObject()); return fresh;
    }
    private static void record(MinecraftServer server, String code) {
        try {
            var state = progress(server);
            state.getAsJsonObject("built").addProperty(code, java.time.Instant.now().toString());
            if (WorldLayout.active()) state.addProperty("world_id", WorldLayout.state().worldId());
            var file = progressFile(server); var temporary = file.resolveSibling(file.getFileName() + ".pending");
            Files.writeString(temporary, state.toString());
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) { LOG.log(System.Logger.Level.ERROR, "Could not record layout progress for " + code); }
    }

    /** Queues the selection; returns how many objects were queued (already built ones are skipped unless forced). */
    static int enqueue(MinecraftServer server, String selection, boolean force, Integer nearRadius, double nearX, double nearZ) {
        var layout = WorldLayout.state();
        if (layout == null) return -1;
        var built = progress(server).getAsJsonObject("built");
        var jobs = new ArrayList<Job>();
        boolean all = "all".equals(selection);
        for (var road : layout.roads().values()) {
            boolean near = nearRadius != null && road.points().stream().anyMatch(p -> Math.hypot(p[0] - nearX, p[1] - nearZ) <= nearRadius);
            if (all || "roads".equals(selection) || road.code().equals(selection) || near) jobs.add(new RoadBuilder(road));
        }
        for (var poi : layout.pois().values()) {
            boolean near = nearRadius != null && Math.hypot(poi.x() - nearX, poi.z() - nearZ) <= nearRadius;
            if (all || "pois".equals(selection) || poi.code().equals(selection) || near) jobs.add(new PoiJob(poi));
        }
        int queued = 0;
        for (var job : jobs) {
            if (!force && built.has(job.code())) continue;
            if (QUEUE.stream().anyMatch(q -> q.code().equals(job.code())) || (current != null && current.code().equals(job.code()))) continue;
            QUEUE.add(job); queued++;
        }
        return queued;
    }

    static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (idle()) return;
        var server = event.getServer();
        long deadline = System.nanoTime() + budgetNanos;
        try {
            while (System.nanoTime() < deadline) {
                if (current == null) { current = QUEUE.poll(); if (current == null) return; }
                if (current.step(server.overworld())) {
                    if (current.recorded()) record(server, current.code());
                    System.out.println("AOC_LAYOUT_DONE " + current.code() + " left=" + QUEUE.size());
                    current = null;
                }
            }
        } catch (RuntimeException failure) {
            LOG.log(System.Logger.Level.ERROR, "AOC layout job failed: " + (current == null ? "?" : current.code()) + " — " + failure);
            System.out.println("AOC_LAYOUT_FAILED " + (current == null ? "?" : current.code()) + " " + failure);
            current = null;
        }
    }
    static void stopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) { QUEUE.clear(); current = null; }

    static void register(RegisterCommandsEvent event) {
        var layout = Commands.literal("layout").requires(source -> source.hasPermission(2))
            .then(Commands.literal("status").executes(context -> status(context.getSource())))
            .then(Commands.literal("build").then(Commands.argument("what", StringArgumentType.word())
                .suggests((c, b) -> SharedSuggestionProvider.suggest(suggestions(), b))
                .executes(context -> build(context.getSource(), StringArgumentType.getString(context, "what"), false))
                .then(Commands.literal("force").executes(context -> build(context.getSource(), StringArgumentType.getString(context, "what"), true)))))
            .then(Commands.literal("near").then(Commands.argument("radius", IntegerArgumentType.integer(16, 20000)).executes(context -> {
                var source = context.getSource(); var at = source.getPosition();
                int queued = enqueue(source.getServer(), "", false, IntegerArgumentType.getInteger(context, "radius"), at.x, at.z);
                return reply(source, queued);
            })))
            .then(Commands.literal("budget").then(Commands.argument("ms", IntegerArgumentType.integer(5, 2000)).executes(context -> {
                int ms = IntegerArgumentType.getInteger(context, "ms"); budget(ms);
                context.getSource().sendSuccess(() -> Component.literal("Время построек за тик: " + ms + " мс."), true);
                return ms;
            })));
        var map = Commands.literal("map").requires(source -> source.hasPermission(2))
            .then(Commands.argument("radius", IntegerArgumentType.integer(256, 10000)).then(Commands.argument("step", IntegerArgumentType.integer(1, 16)).executes(context -> {
                var source = context.getSource();
                if (!WorldLayout.active()) { source.sendFailure(Component.literal("Карта рисуется только в мире государств.")); return 0; }
                var job = new MapRender(IntegerArgumentType.getInteger(context, "radius"), IntegerArgumentType.getInteger(context, "step"));
                if (QUEUE.stream().anyMatch(q -> q.code().equals(job.code())) || (current != null && current.code().equals(job.code()))) {
                    source.sendFailure(Component.literal("Эта карта уже рисуется.")); return 0;
                }
                QUEUE.add(job);
                source.sendSuccess(() -> Component.literal("Карта " + job.code() + " в очереди после построек. Ход — /aoc worldgen layout status."), true);
                return 1;
            })));
        event.getDispatcher().register(Commands.literal("aoc").then(Commands.literal("worldgen").then(layout).then(map)));
    }
    private static List<String> suggestions() {
        var list = new ArrayList<>(List.of("all", "roads", "pois"));
        if (WorldLayout.active()) { list.addAll(WorldLayout.state().roads().keySet()); list.addAll(WorldLayout.state().pois().keySet()); }
        return list;
    }
    private static int build(CommandSourceStack source, String what, boolean force) { return reply(source, enqueue(source.getServer(), what, force, null, 0, 0)); }
    private static int reply(CommandSourceStack source, int queued) {
        if (queued < 0) { source.sendFailure(Component.literal("В этом мире нет раскладки построек (датапак animeoncraft-states с layout.json).")); return 0; }
        source.sendSuccess(() -> Component.literal("В очереди построек: " + queued + ". Ход — /aoc worldgen layout status."), true);
        return queued;
    }
    private static int status(CommandSourceStack source) {
        if (!WorldLayout.active()) { source.sendFailure(Component.literal("Раскладки построек нет.")); return 0; }
        var built = progress(source.getServer()).getAsJsonObject("built");
        var layout = WorldLayout.state();
        int total = layout.pois().size() + layout.roads().size();
        source.sendSuccess(() -> Component.literal("Построено " + built.size() + " из " + total + ". В очереди " + pending()
            + (current == null ? "." : ", сейчас: " + current.progress() + ".")), false);
        return built.size();
    }
}
