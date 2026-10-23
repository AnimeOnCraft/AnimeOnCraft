package ru.animeoncraft.integration.neoforge;

import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Runs the capture battles the backend has started (World Design stage 4): once per second it counts
 * eligible clan players inside each point cylinder, advances {@link CaptureEngine}, shows boss bars,
 * sends snapshots every few seconds and hands the result to the durable gameplay outbox.
 * It never changes an owner itself; the next sync shows what the backend decided.
 */
final class CaptureArena {
    private static final class Live {
        final CaptureEngine engine; final ServerBossEvent bar; long lastSnapshot; boolean resultSent; Integer centerY;
        Live(CaptureEngine engine, ServerBossEvent bar) { this.engine = engine; this.bar = bar; }
    }
    private record Seen(double x, double y, double z, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> level) {}
    private static final Map<String, Live> BATTLES = new HashMap<>();
    private static final Map<String, ServerBossEvent> PREPARING = new HashMap<>();
    private static final Map<UUID, Long> GRACE = new HashMap<>();
    private static final Map<UUID, Seen> SEEN = new HashMap<>();
    private static final System.Logger LOG = System.getLogger("AnimeOnCraft");
    private CaptureArena() {}

    /** FakePlayers of the disposable capture probe (empty on real servers). */
    static final List<ServerPlayer> PROBE_PLAYERS = new java.util.concurrent.CopyOnWriteArrayList<>();
    static List<ServerPlayer> players(MinecraftServer server) {
        if (PROBE_PLAYERS.isEmpty()) return server.getPlayerList().getPlayers();
        var all = new java.util.ArrayList<ServerPlayer>(server.getPlayerList().getPlayers()); all.addAll(PROBE_PLAYERS); return all;
    }
    static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        var server = event.getServer();
        if (!server.isDedicatedServer() || server.getTickCount() % 20 != 0) return;
        second(server, players(server), Instant.now().getEpochSecond());
    }
    static void login(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        var rules = CaptureSites.view().rules();
        int grace = rules == null ? 10 : rules.getAsJsonObject("presence").get("join_grace_seconds").getAsInt();
        GRACE.put(event.getEntity().getUUID(), Instant.now().getEpochSecond() + grace);
    }
    static void logout(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        GRACE.remove(event.getEntity().getUUID()); SEEN.remove(event.getEntity().getUUID());
        if (event.getEntity() instanceof ServerPlayer player) { for (var live : BATTLES.values()) live.bar.removePlayer(player); for (var bar : PREPARING.values()) bar.removePlayer(player); }
    }
    static void stopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        for (var live : BATTLES.values()) live.bar.removeAllPlayers();
        for (var bar : PREPARING.values()) bar.removeAllPlayers();
        BATTLES.clear(); PREPARING.clear(); GRACE.clear(); SEEN.clear(); CaptureEntry.reset(); ResourcePits.reset(event.getServer()); CaptureSites.reset();
    }
    /** Battles whose result waits in the outbox; the sync tells the backend not to void them as stale. */
    static com.google.gson.JsonArray finishedIds() {
        var ids = new com.google.gson.JsonArray();
        for (var entry : BATTLES.entrySet()) if (entry.getValue().resultSent) ids.add(entry.getKey());
        return ids;
    }
    /** Probe access: the progress of a running battle. */
    static CaptureEngine battle(String eventId) { var live = BATTLES.get(eventId); return live == null ? null : live.engine; }
    /** Probe only: a FakePlayer placed by the fixture counts at once (no teleport grace). */
    static void clearGrace(ServerPlayer player) {
        GRACE.remove(player.getUUID());
        SEEN.put(player.getUUID(), new Seen(player.getX(), player.getY(), player.getZ(), player.level().dimension()));
    }

    static boolean eligible(ServerPlayer player, MinecraftServer server, long now) {
        if (player.serverLevel() != server.overworld() || !player.isAlive() || player.isSpectator() || player.isCreative()) return false;
        var identity = GameIdentity.PLAYERS.get(player.getUUID());
        return identity != null && identity.clan() != null && ClanCodes.known(identity.clan()) && now >= GRACE.getOrDefault(player.getUUID(), 0L);
    }
    static int centerY(MinecraftServer server, CaptureSites.Point point, Live live) {
        if (point.y() != null) return point.y();
        if (live != null && live.centerY != null) return live.centerY;
        var level = server.overworld();
        // Unloaded chunk: nobody can stand there; do not cache the bottom-of-the-world answer of getHeight.
        if (!level.hasChunk(point.x() >> 4, point.z() >> 4)) return level.getMinBuildHeight() - 1000;
        int y = CaptureAnchors.height(server, point);
        if (live != null) live.centerY = y;
        return y;
    }
    static boolean inZone(ServerPlayer player, CaptureSites.Point point, int centerY, int height) {
        return point.contains(player.getX(),player.getY(),player.getZ(),centerY,height);
    }
    /** Eligible members of each clan inside the point cylinder. */
    static Map<String, Integer> present(MinecraftServer server, List<ServerPlayer> players, CaptureSites.Point point, int centerY, int height, long now) {
        var counts = new HashMap<String, Integer>();
        for (var player : players) if (eligible(player, server, now) && inZone(player, point, centerY, height) && point.equals(CaptureEntry.here(server,player,CaptureSites.view())))
            counts.merge(GameIdentity.PLAYERS.get(player.getUUID()).clan(), 1, Integer::sum);
        return counts;
    }

    static void second(MinecraftServer server, List<ServerPlayer> players, long now) {
        var view = CaptureSites.view();
        if (view.rules() == null) return;
        var rules = view.rules();
        var presence = rules.getAsJsonObject("presence");
        int height = presence.get("height").getAsInt(), grace = presence.get("join_grace_seconds").getAsInt();
        double teleport = presence.get("teleport_blocks").getAsDouble();
        for (var player : players) {
            // A sudden jump (Waystone, pearl chain, /tp, dimension change) counts as arriving just now.
            var seen = SEEN.get(player.getUUID());
            var level = player.level().dimension();
            if (seen != null && (seen.level() != level || Math.sqrt(player.distanceToSqr(seen.x(), seen.y(), seen.z())) > teleport))
                GRACE.put(player.getUUID(), now + grace);
            SEEN.put(player.getUUID(), new Seen(player.getX(), player.getY(), player.getZ(), level));
        }
        boolean fresh = CaptureSites.fresh();
        var settings = CaptureEngine.Settings.from(rules.getAsJsonObject("score"), rules.getAsJsonObject("timing"));
        int snapshotEvery = rules.getAsJsonObject("score").get("snapshot_seconds").getAsInt();
        var running = new HashSet<String>();
        for (var event : view.events().values()) {
            var point = view.points().get(event.pointCode());
            if (point == null) continue;
            if ("PREPARING".equals(event.state())) { preparing(server, players, event, point, now); continue; }
            if (!"ACTIVE".equals(event.state()) && !"OVERTIME".equals(event.state())) continue;
            running.add(event.id());
            var bar = PREPARING.remove(event.id());
            if (bar != null) bar.removeAllPlayers();
            var live = BATTLES.computeIfAbsent(event.id(), id -> start(event, point, settings));
            if (live.resultSent) {viewers(server,players,point,live.bar);continue;}
            int y = centerY(server, point, live);
            if (!fresh) { live.bar.setName(Component.literal(CaptureEntry.compact("«" + point.name() + "»: пауза · нет связи с API",62))); viewers(server,players,point,live.bar); continue; }
            var status = live.engine.second(present(server, players, point, y, height, now), now);
            showBar(server, players, point, live, status);
            if (live.engine.finished()) sendResult(server, event, point, live);
            else if (now - live.lastSnapshot >= snapshotEvery) { live.lastSnapshot = now; snapshot(event, live, status); }
        }
        for (var iterator = BATTLES.entrySet().iterator(); iterator.hasNext(); ) {
            var entry = iterator.next();
            if (!running.contains(entry.getKey())) { entry.getValue().bar.removeAllPlayers(); iterator.remove(); }
        }
        for (var iterator = PREPARING.entrySet().iterator(); iterator.hasNext(); ) {
            var entry = iterator.next();
            var event = view.events().get(entry.getKey());
            if (event == null || !"PREPARING".equals(event.state())) { entry.getValue().removeAllPlayers(); iterator.remove(); }
        }
        CaptureEntry.second(server, players, view, now);
        if (fresh) ResourcePits.second(server, players, view, now);
    }

    private static Live start(CaptureSites.Event event, CaptureSites.Point point, CaptureEngine.Settings settings) {
        var engine = new CaptureEngine(event.id(), point.owner(), "CONTROLLED".equals(event.priorState()), event.activeEnd().getEpochSecond(), settings);
        if (event.snapshot() != null && event.snapshot().has("scores")) {
            try { engine.restore(event.snapshot().getAsJsonArray("scores"));
                if(event.snapshot().has("kill_limits"))engine.restoreKillLimits(event.snapshot().getAsJsonArray("kill_limits"));
                LOG.log(System.Logger.Level.INFO, "Capture battle resumed from snapshot: " + event.id()); }
            catch (RuntimeException invalid) { LOG.log(System.Logger.Level.WARNING, "Ignoring an invalid capture snapshot"); }
        }
        var bar = new ServerBossEvent(Component.literal("«" + point.name() + "»"), BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.NOTCHED_10);
        var live = new Live(engine, bar);
        live.lastSnapshot = Instant.now().getEpochSecond();
        return live;
    }

    private static void viewers(MinecraftServer server, List<ServerPlayer> players, CaptureSites.Point point, ServerBossEvent bar) {
        for (var player : players) {
            boolean near = CaptureEntry.here(server, player, CaptureSites.view()) == point;
            if (near && !bar.getPlayers().contains(player)) bar.addPlayer(player);
            else if (!near && bar.getPlayers().contains(player)) bar.removePlayer(player);
        }
    }
    private static void preparing(MinecraftServer server, List<ServerPlayer> players, CaptureSites.Event event, CaptureSites.Point point, long now) {
        var bar = PREPARING.computeIfAbsent(event.id(), id -> new ServerBossEvent(Component.empty(), BossEvent.BossBarColor.YELLOW, BossEvent.BossBarOverlay.PROGRESS));
        long total = Math.max(1, event.activeStart().getEpochSecond() - event.prepStart().getEpochSecond()), left = Math.max(0, event.activeStart().getEpochSecond() - now);
        bar.setProgress(Math.max(0, Math.min(1, (float) left / total)));
        bar.setName(Component.literal(CaptureEntry.compact("«" + point.name() + "»: " + event.declaredBy() + ", бой через " + CaptureEntry.clock(left),62)));
        if(!CaptureSites.fresh())bar.setName(Component.literal(CaptureEntry.compact("«"+point.name()+"»: подготовка · нет связи с API",62)));
        viewers(server, players, point, bar);
    }
    private static void showBar(MinecraftServer server, List<ServerPlayer> players, CaptureSites.Point point, Live live, CaptureEngine.Status status) {
        var engine = live.engine;
        String leader = engine.leader();
        String suffix = status == CaptureEngine.Status.OVERTIME ? " · овертайм" : engine.locked() ? " · точка оспаривается" : "";
        var event=CaptureSites.view().eventFor(point.code());
        long left=event==null?0:Math.max(0,event.activeEnd().getEpochSecond()-Instant.now().getEpochSecond());
        live.bar.setName(Component.literal(CaptureEntry.compact("«" + CaptureEntry.compact(point.name(),24) + "»: " + CaptureEntry.compact(engine.summary()+suffix,24)+" · "+CaptureEntry.clock(left),62)));
        double top = 0; for (var score : engine.scores().values()) top = Math.max(top, score.progress);
        live.bar.setProgress((float) Math.max(0, Math.min(1, top / 100)));
        live.bar.setColor(engine.locked() ? BossEvent.BossBarColor.YELLOW : leader != null && leader.equals(point.owner()) ? BossEvent.BossBarColor.BLUE : BossEvent.BossBarColor.RED);
        viewers(server, players, point, live.bar);
    }

    private static void snapshot(CaptureSites.Event event, Live live, CaptureEngine.Status status) {
        try {
            var settings = ServerSettings.from(System.getenv());
            var body = new JsonObject();
            body.addProperty("server_id", settings.serverId().toString()); body.addProperty("event_id", event.id());
            body.addProperty("phase", status == CaptureEngine.Status.OVERTIME ? "OVERTIME" : "ACTIVE");
            body.add("scores", live.engine.scoresJson());
            body.add("kill_limits",live.engine.killLimitsJson());
            HttpJson.post(settings.heartbeatUrl().resolve("../capture/snapshot"), settings.key(), body)
                .exceptionally(failure -> { LOG.log(System.Logger.Level.DEBUG, "Capture snapshot not delivered"); return null; });
        } catch (RuntimeException unavailable) { LOG.log(System.Logger.Level.DEBUG, "Capture snapshot skipped"); }
    }

    /** The result goes through the durable outbox: a crash or API outage after the battle cannot lose it. */
    private static void sendResult(MinecraftServer server, CaptureSites.Event event, CaptureSites.Point point, Live live) {
        try {
            var settings = ServerSettings.from(System.getenv());
            var rules = CaptureSites.view().rules().getAsJsonObject("timing");
            var body = new JsonObject();
            body.addProperty("server_id", settings.serverId().toString());
            body.addProperty("event_id", event.id());
            body.addProperty("capture_finish", true);
            if (live.engine.winner() == null) body.add("winner_clan_code", com.google.gson.JsonNull.INSTANCE);
            else body.addProperty("winner_clan_code", live.engine.winner());
            body.add("scores", live.engine.scoresJson());
            body.addProperty("expires_at", event.activeEnd().plusSeconds(rules.get("overtime_seconds").getAsLong() + rules.get("resume_grace_seconds").getAsLong()).toString());
            GameplayOutbox.store(server.getWorldPath(LevelResource.ROOT).resolve("animeoncraft-gameplay-outbox"), body);
            live.resultSent = true;
            live.bar.setName(Component.literal("«" + point.name() + "»: " + (live.engine.winner() == null ? "победителя нет" : "победил " + live.engine.winner()) + " — ждём подтверждение"));
            LOG.log(System.Logger.Level.INFO, "Capture battle finished: event=" + event.id() + " winner=" + live.engine.winner());
        } catch (java.io.IOException | RuntimeException failure) {
            LOG.log(System.Logger.Level.ERROR, "Could not persist a capture result; the battle continues and will retry");
        }
    }

    /** PvP kill in or near a running battle (LivingDeathEvent, observed after other mods could cancel it). */
    static void death(net.neoforged.neoforge.event.entity.living.LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer victim) || !(event.getSource().getEntity() instanceof ServerPlayer killer) || killer == victim) return;
        kill(victim.server, killer, victim, Instant.now().getEpochSecond());
    }
    static boolean kill(MinecraftServer server, ServerPlayer killer, ServerPlayer victim, long now) {
        var view = CaptureSites.view();
        if (view.rules() == null || !CaptureSites.fresh() || victim.serverLevel() != server.overworld() || !eligible(killer,server,now) || victim.isCreative() || victim.isSpectator()) return false;
        var a = GameIdentity.PLAYERS.get(killer.getUUID()); var b = GameIdentity.PLAYERS.get(victim.getUUID());
        if (a == null || b == null || a.clan() == null || b.clan() == null) return false;
        int extra = view.rules().getAsJsonObject("score").get("kill_radius_extra").getAsInt();
        boolean counted = false;
        for (var entry : BATTLES.entrySet()) {
            var event = view.events().get(entry.getKey());
            var point = event == null ? null : view.points().get(event.pointCode());
            if (point == null || entry.getValue().resultSent) continue;
            double dx = victim.getX() - (point.x() + 0.5), dz = victim.getZ() - (point.z() + 0.5), reach = point.radius() + extra;
            int height=view.rules().getAsJsonObject("presence").get("height").getAsInt();
            if (dx * dx + dz * dz <= reach * reach && Math.abs(victim.getY()-CaptureAnchors.height(server,point))<=height) counted |= entry.getValue().engine.kill(a.clan(), b.clan(), victim.getUUID(), now);
        }
        return counted;
    }

    // ---------------------------------------------------------------------------------------------
    // Announcements when the backend view changes (declaration, battle start, new owner)
    // ---------------------------------------------------------------------------------------------
    static void viewChanged(MinecraftServer server, CaptureSites.View previous, CaptureSites.View next) {
        if (previous.rules() == null || next.rules() == null) return; // first sync after start: nothing to announce
        var list = server.getPlayerList();
        for (var event : next.events().values()) {
            var point = next.points().get(event.pointCode());
            var before = previous.events().get(event.id());
            if (before == null) {
                long minutes = Math.max(0, (event.activeStart().getEpochSecond() - Instant.now().getEpochSecond() + 59) / 60);
                list.broadcastSystemMessage(Component.literal("⚔ Клан " + event.declaredBy() + " объявил захват «" + point.name() + "» (" + point.x() + ", " + point.z()
                    + "). Бой начнётся через " + minutes + " мин."), false);
            }
            if ((before == null || "PREPARING".equals(before.state())) && "ACTIVE".equals(event.state())) {
                list.broadcastSystemMessage(Component.literal("⚔ Начался бой за «" + point.name() + "»!"), false);
                for (var player : list.getPlayers()) {
                    var identity = GameIdentity.PLAYERS.get(player.getUUID());
                    if (identity == null || identity.clan() == null || !(identity.clan().equals(event.declaredBy()) || identity.clan().equals(point.owner()))) continue;
                    player.connection.send(new ClientboundSetTitleTextPacket(Component.literal("Бой за «" + point.name() + "»")));
                    player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(point.x() + ", " + point.z())));
                }
            }
        }
        for (var point : next.points().values()) {
            var old = previous.points().get(point.code());
            if (old == null || java.util.Objects.equals(old.owner(), point.owner())) {
                var ended = previous.eventFor(point.code());
                if (old != null && ended != null && next.events().get(ended.id()) == null)
                    list.broadcastSystemMessage(Component.literal("Бой за «" + point.name() + "» окончен: " + (point.owner() == null ? "точка остаётся ничьей." : "точка остаётся у " + point.owner() + ".")), false);
                continue;
            }
            list.broadcastSystemMessage(Component.literal(point.owner() == null ? "«" + point.name() + "» снова ничья."
                : "🏴 «" + point.name() + "» теперь под контролем клана " + point.owner() + "."), false);
        }
    }
}
