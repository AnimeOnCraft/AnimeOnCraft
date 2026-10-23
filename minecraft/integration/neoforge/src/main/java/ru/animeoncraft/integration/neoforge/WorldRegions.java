package ru.animeoncraft.integration.neoforge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.server.MinecraftServer;

/**
 * Region catalog of a states world for protection (World Design stages 7–8): {@code POST /integration/v1/world/regions}
 * every 30 s. The backend is the source of truth — a region assigned to a new clan in the admin panel takes effect
 * here without a restart. The last accepted reply is kept in {@code config/animeoncraft-regions.json} so protection
 * survives a restart while the backend is down; a malformed reply is ignored and the previous policy stays.
 */
final class WorldRegions {
    static final Path CACHE = Path.of("config/animeoncraft-regions.json");
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean();
    private static volatile RegionPolicy current;
    private static long nextTick;
    private static final System.Logger LOG = System.getLogger("AnimeOnCraft");
    private WorldRegions() {}

    static RegionPolicy current() { return current; }
    static void reset() { current = null; nextTick = 0; }

    /** Cached catalog of the previous run, or null (protection then fails closed until the first sync). */
    static RegionPolicy loadCache() {
        current = null;
        try {
            if (!Files.isRegularFile(CACHE) || Files.size(CACHE) > MAX_BYTES) return null;
            var cached = RegionPolicy.parse(JsonParser.parseString(Files.readString(CACHE)).getAsJsonObject());
            if (!sameWorld(cached)) { LOG.log(System.Logger.Level.WARNING, "AOC region cache belongs to world " + cached.worldId() + "; ignored"); return null; }
            current = cached;
            LOG.log(System.Logger.Level.INFO, "AOC regions from cache: " + current.regions().size() + " (" + current.revision().substring(0, 12) + ")");
        } catch (IOException | RuntimeException invalid) { LOG.log(System.Logger.Level.WARNING, "AOC region cache unreadable; protection waits for the backend"); }
        return current;
    }

    static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        var server = event.getServer();
        if (!server.isDedicatedServer() || !TerritoryProtection.statesWorld() || server.getTickCount() < nextTick) return;
        nextTick = server.getTickCount() + 600;
        refresh(server);
    }

    static void refresh(MinecraftServer server) {
        if (!IN_FLIGHT.compareAndSet(false, true)) return;
        try {
            var settings = ServerSettings.from(System.getenv());
            var body = new JsonObject(); body.addProperty("server_id", settings.serverId().toString());
            var known = current; if (known != null) body.addProperty("known_revision", known.revision());
            HttpJson.post(settings.heartbeatUrl().resolve("../world/regions"), settings.key(), body, 1 << 20).thenAccept(reply -> {
                if (reply.has("unchanged") && reply.get("unchanged").getAsBoolean()) return;
                RegionPolicy parsed;
                try { parsed = RegionPolicy.parse(reply); }
                catch (RuntimeException invalid) { LOG.log(System.Logger.Level.ERROR, "AOC region catalog rejected; keeping the previous policy: " + invalid.getMessage()); return; }
                if (!sameWorld(parsed)) { LOG.log(System.Logger.Level.ERROR, "AOC region catalog is for world " + parsed.worldId() + ", not this one; keeping the previous policy"); return; }
                save(reply);
                server.execute(() -> { current = parsed; TerritoryProtection.regionsChanged(parsed); });
            }).whenComplete((ignored, failure) -> {
                IN_FLIGHT.set(false);
                if (failure != null) LOG.log(System.Logger.Level.DEBUG, "Region catalog unavailable; the previous policy stays");
            });
        } catch (RuntimeException unavailable) { IN_FLIGHT.set(false); }
    }

    /** The backend's active world must be the one this server runs (world_id of the states datapack layout). */
    static boolean sameWorld(RegionPolicy policy) {
        var layout = WorldLayout.state();
        return layout != null && policy.worldId().equals(layout.worldId());
    }

    private static void save(JsonObject reply) {
        try {
            var temporary = CACHE.resolveSibling(CACHE.getFileName() + ".pending");
            Files.writeString(temporary, reply.toString());
            Files.move(temporary, CACHE, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) { LOG.log(System.Logger.Level.WARNING, "AOC region cache not written"); }
    }
}
