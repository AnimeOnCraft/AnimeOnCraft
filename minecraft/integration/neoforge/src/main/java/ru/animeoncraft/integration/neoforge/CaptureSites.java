package ru.animeoncraft.integration.neoforge;

import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.server.MinecraftServer;

/**
 * Backend view of capturable points for the game server (POST /integration/v1/capture/sync every 5 s).
 * The backend is the source of truth for owners, states and timers; this class only caches its last
 * answer. A view older than {@link #FRESH_SECONDS} is stale: running battles pause until it is fresh again.
 */
final class CaptureSites {
    static final int FRESH_SECONDS = 20;
    record Resource(String family, int tier, String kind, Instant nextRefresh, double multiplier, JsonObject output) {
        Resource(String family, int tier, String kind, Instant nextRefresh, double multiplier) { this(family,tier,kind,nextRefresh,multiplier,new JsonObject()); }
        String label() { return output.has("label") ? output.get("label").getAsString() : family; }
    }
    record Point(String code, String name, String type, int x, Integer y, int z, int radius, String owner, String state,
                 Instant protectionUntil, Instant leaseUntil, Resource resource) {
        boolean contains(double px, double pz) { double dx = px - (x + 0.5), dz = pz - (z + 0.5); return dx * dx + dz * dz <= (double) radius * radius; }
        boolean contains(double px,double py,double pz,int floor,int height) {return contains(px,pz)&&Math.abs(py-floor)<=height;}
        boolean held() { return owner != null && List.of("CONTROLLED", "PROTECTED", "AVAILABLE", "CONTESTED", "CAPTURING").contains(state); }
        /** Pit blocks are the owner's while it holds the point (not while the lease has expired). */
        boolean pitLocked() { return owner != null && resource != null && "PIT".equals(resource.kind()) && List.of("CONTROLLED", "PROTECTED", "CONTESTED", "CAPTURING").contains(state); }
    }
    record Event(String id, String pointCode, String declaredBy, String state, Instant prepStart, Instant activeStart, Instant activeEnd,
                 String priorState, JsonObject snapshot) {}
    record Refresh(String pointCode, Instant dueAt, double multiplier) {}
    record View(Instant received, String worldId, JsonObject rules, Map<String, Point> points, Map<String, Event> events, List<Refresh> refreshes) {
        static final View EMPTY = new View(Instant.EPOCH, null, null, Map.of(), Map.of(), List.of());
        Point at(double x, double z) {
            Point best = null;
            for (var point : points.values()) if (point.contains(x, z) && (best == null || point.radius() < best.radius())) best = point;
            return best;
        }
        Event eventFor(String pointCode) {
            for (var event : events.values()) if (event.pointCode().equals(pointCode)) return event;
            return null;
        }
    }

    private static volatile View view = View.EMPTY;
    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean();
    private static long nextTick;
    private static final System.Logger LOG = System.getLogger("AnimeOnCraft");
    private CaptureSites() {}

    static View view() { return view; }
    static boolean fresh() { var v = view; return v.rules() != null && Instant.now().isBefore(v.received().plusSeconds(FRESH_SECONDS)); }
    static void reset() { view = View.EMPTY; nextTick = 0; }
    /** Test fixtures and the probe inject a view directly. */
    static void accept(View replacement) { view = replacement; }

    static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        var server = event.getServer();
        if (!server.isDedicatedServer() || server.getTickCount() < nextTick) return;
        nextTick = server.getTickCount() + 100;
        refresh(server);
    }

    static void refresh(MinecraftServer server) {
        if (!IN_FLIGHT.compareAndSet(false, true)) return;
        try {
            var settings = ServerSettings.from(System.getenv());
            var body = new JsonObject(); body.addProperty("server_id", settings.serverId().toString());
            body.add("finished", CaptureArena.finishedIds());
            HttpJson.post(settings.heartbeatUrl().resolve("../capture/sync"), settings.key(), body, 262144).thenAccept(reply -> {
                View parsed = parse(reply);
                server.execute(() -> { var previous = view; view = parsed; CaptureArena.viewChanged(server, previous, parsed); });
            }).whenComplete((ignored, failure) -> {
                IN_FLIGHT.set(false);
                if (failure != null) LOG.log(System.Logger.Level.DEBUG, "Capture sync unavailable; battles pause until it returns");
            });
        } catch (RuntimeException unavailable) { IN_FLIGHT.set(false); }
    }

    private static Instant instant(JsonObject row, String key) {
        return !row.has(key) || row.get(key).isJsonNull() ? null : Instant.parse(row.get(key).getAsString());
    }
    private static String text(JsonObject row, String key) { return !row.has(key) || row.get(key).isJsonNull() ? null : row.get(key).getAsString(); }

    /** Strict parse: an invalid answer is ignored as a whole (the previous view ages out instead). */
    static View parse(JsonObject reply) {
        if (!reply.has("rules") || reply.get("rules").isJsonNull()) return new View(Instant.now(), null, null, Map.of(), Map.of(), List.of());
        var points = new LinkedHashMap<String, Point>();
        var items = reply.getAsJsonArray("points");
        if (items.size() > 4096) throw new IllegalArgumentException("Too many points");
        for (var value : items) {
            var row = value.getAsJsonObject();
            String code = row.get("code").getAsString(), owner = text(row, "owner");
            if (!code.matches("[a-z0-9][a-z0-9._-]{1,63}") || (owner != null && !ClanCodes.valid(owner))) throw new IllegalArgumentException("Invalid point");
            int x = row.get("x").getAsInt(), z = row.get("z").getAsInt(), radius = row.get("radius").getAsInt();
            if (Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000 || radius < 4 || radius > 256) throw new IllegalArgumentException("Invalid point geometry");
            Resource resource = null;
            if (row.has("resource") && !row.get("resource").isJsonNull()) {
                var r = row.getAsJsonObject("resource");
                double multiplier = r.get("multiplier").getAsDouble();
                if (!(multiplier >= 0 && multiplier <= 2)) throw new IllegalArgumentException("Invalid multiplier");
                resource = new Resource(r.get("family").getAsString(), r.get("tier").getAsInt(), r.get("kind").getAsString(), instant(r, "next_refresh"), multiplier,
                    r.has("output") && r.get("output").isJsonObject() ? r.getAsJsonObject("output") : new JsonObject());
            }
            points.put(code, new Point(code, row.get("name").getAsString(), row.get("type").getAsString(), x,
                row.has("y") && !row.get("y").isJsonNull() ? row.get("y").getAsInt() : null, z, radius, owner, row.get("state").getAsString(),
                instant(row, "protection_until"), instant(row, "lease_until"), resource));
        }
        var events = new LinkedHashMap<String, Event>();
        for (var value : reply.getAsJsonArray("events")) {
            var row = value.getAsJsonObject();
            String id = java.util.UUID.fromString(row.get("id").getAsString()).toString(), declared = row.get("declared_by").getAsString();
            if (!ClanCodes.valid(declared) || !points.containsKey(row.get("point_code").getAsString())) throw new IllegalArgumentException("Invalid event");
            events.put(id, new Event(id, row.get("point_code").getAsString(), declared, row.get("state").getAsString(), instant(row, "prep_start"),
                instant(row, "active_start"), instant(row, "active_end"), text(row, "prior_state"),
                row.has("snapshot") && row.get("snapshot").isJsonObject() ? row.getAsJsonObject("snapshot") : null));
        }
        var refreshes = new ArrayList<Refresh>();
        for (var value : reply.getAsJsonArray("refreshes")) {
            var row = value.getAsJsonObject();
            refreshes.add(new Refresh(row.get("point_code").getAsString(), Instant.parse(row.get("due_at").getAsString()), row.get("multiplier").getAsDouble()));
        }
        var rules = reply.getAsJsonObject("rules");
        CaptureEngine.Settings.from(rules.getAsJsonObject("score"), rules.getAsJsonObject("timing")); // validates
        return new View(Instant.now(), reply.get("world_id").getAsString(), rules, Map.copyOf(points), Map.copyOf(events), List.copyOf(refreshes));
    }

    /** True when an entity may not change this block: inside a held resource pit of another clan. */
    static boolean pitDenied(net.minecraft.world.level.LevelAccessor level, net.minecraft.world.entity.Entity entity, net.minecraft.core.BlockPos pos) {
        if (!(level instanceof net.minecraft.server.level.ServerLevel world) || !world.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) return false;
        var point = view.at(pos.getX() + 0.5, pos.getZ() + 0.5);
        if (point == null || !point.pitLocked()) return false;
        if (entity instanceof net.minecraft.server.level.ServerPlayer player && player.hasPermissions(2)) return false;
        var identity = entity == null ? null : GameIdentity.PLAYERS.get(entity.getUUID());
        boolean allowed = identity != null && point.owner().equals(identity.clan());
        if (!allowed && entity instanceof net.minecraft.server.level.ServerPlayer player)
            player.displayClientMessage(net.minecraft.network.chat.Component.literal("Ресурсная точка «" + point.name() + "» принадлежит клану " + point.owner() + ". Копать здесь может только он."), true);
        return !allowed;
    }
    static boolean pitBlock(net.minecraft.core.BlockPos pos) {
        var point = view.at(pos.getX() + 0.5, pos.getZ() + 0.5);
        return point != null && point.pitLocked();
    }
}
