package ru.animeoncraft.integration.neoforge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * Opt-in E2E probe of the capture engine on a disposable loopback server against a real backend and a
 * disposable database (scripts/test-capture.mjs, -Danimeoncraft.captureProbe=true, world aoc-capture-probe-*).
 * FakePlayers of three clans walk through capture, protection, defence bonus lock, kills, pit refresh,
 * cache collection, a real process restart (resume from snapshot) and an API outage (pause).
 */
final class CapturePointsProbe {
    private record Step(String name, BooleanSupplier done, int timeoutSeconds) {}
    private static final List<Step> STEPS = new ArrayList<>();
    private static int index, deadlineTick = -1;
    private static boolean failed, started;
    private static MinecraftServer server;
    private static final Map<String, FakePlayer> PLAYERS = new LinkedHashMap<>();
    private static JsonObject config, state;
    private static CompletableFuture<JsonObject> pending;
    private static JsonObject reply;
    private static final Map<String, Object> facts = new LinkedHashMap<>();
    static final String BATTLE = "site.ring.324", PIT = "site.ring.108", CACHE = "site.frontier.280", RESTART = "rim.watchtower.scam";
    private CapturePointsProbe() {}

    static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        var current = event.getServer();
        if (failed || current.getTickCount() % 10 != 0) return;
        try {
            if (!started) { start(current); return; }
            if (index >= STEPS.size()) return;
            var step = STEPS.get(index);
            if (deadlineTick < 0) deadlineTick = current.getTickCount() + step.timeoutSeconds * 20;
            if (step.done.getAsBoolean()) {
                System.out.println("AOC_CAPTURE_STEP " + (index + 1) + "/" + STEPS.size() + " " + step.name);
                index++; deadlineTick = -1;
            } else if (current.getTickCount() > deadlineTick) throw new IllegalStateException("Timed out: " + step.name);
        } catch (Exception failure) {
            failed = true;
            System.out.println("AOC_CAPTURE_PROBE_FAILED " + failure.getMessage());
            failure.printStackTrace(System.out);
        }
    }

    private static void start(MinecraftServer current) throws Exception {
        if (current.getPort() != 25569 || !"127.0.0.1".equals(current.getLocalIp()) || !current.getWorldData().getLevelName().startsWith("aoc-capture-probe-"))
            throw new IllegalStateException("Capture probe requires its own loopback world");
        server = current; started = true;
        config = JsonParser.parseString(Files.readString(Path.of("config/aoc-capture-probe.json"))).getAsJsonObject();
        Path saved = statePath();
        state = Files.isRegularFile(saved) ? JsonParser.parseString(Files.readString(saved)).getAsJsonObject() : new JsonObject();
        for (var clan : List.of("SCAM", "NYA", "AKUMA")) for (int i = 0; i < 2; i++) {
            String name = clan.charAt(0) + "" + (i + 1);
            var player = new FakePlayer(server.overworld(), new GameProfile(UUID.nameUUIDFromBytes(("aoc-capture-" + name).getBytes()), "AocCap" + name));
            player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
            GameIdentity.PLAYERS.put(player.getUUID(), new GameIdentity(clan, new byte[0], false, "", UUID.randomUUID(),
                UUID.fromString(config.getAsJsonObject("users").getAsJsonArray(clan).get(i).getAsString())));
            player.setPos(10000.5, -60, 10000.5 + PLAYERS.size() * 3); // outside every catalog zone
            PLAYERS.put(name, player);
            CaptureArena.PROBE_PLAYERS.add(player);
        }
        if (state.has("phase") && state.get("phase").getAsInt() == 2) phaseTwo(); else phaseOne();
    }
    private static Path statePath() { return server.getWorldPath(LevelResource.ROOT).resolve("aoc-capture-probe-state.json"); }

    // ------------------------------------------------------------------ helpers
    private static CaptureSites.Point point(String code) { return CaptureSites.view().points().get(code); }
    private static int surface(CaptureSites.Point p) { return server.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.x(), p.z()); }
    private static void load(CaptureSites.Point p) {
        for (int dx = -p.radius() - 32; dx <= p.radius() + 32; dx += 16) for (int dz = -p.radius() - 32; dz <= p.radius() + 32; dz += 16)
            server.overworld().setChunkForced((p.x() + dx) >> 4, (p.z() + dz) >> 4, true);
    }
    private static void put(String name, CaptureSites.Point p, double dx, double dz) {
        var player = PLAYERS.get(name);
        player.setPos(p.x() + 0.5 + dx, CaptureAnchors.height(server,p), p.z() + 0.5 + dz);
        CaptureArena.clearGrace(player);
    }
    private static void park(String... names) { for (var name : names) PLAYERS.get(name).setPos(10000.5, -60, 10000.5 + List.copyOf(PLAYERS.keySet()).indexOf(name) * 3); }
    private static void post(String route, JsonObject body) {
        var settings = ServerSettings.from(System.getenv());
        body.addProperty("server_id", settings.serverId().toString());
        reply = null;
        pending = HttpJson.post(settings.heartbeatUrl().resolve("../capture/" + route), settings.key(), body).whenComplete((value, failure) -> reply = value);
    }
    private static boolean answered() { return pending != null && pending.isDone(); }
    private static void declare(String player, String clan, String code) {
        var body = new JsonObject();
        body.addProperty("point_code", code); body.addProperty("actor_user_id", GameIdentity.PLAYERS.get(PLAYERS.get(player).getUUID()).userId().toString());
        body.addProperty("clan_code", clan); body.addProperty("players_in_zone", 2); body.addProperty("defenders_online", 2);
        post("declare", body);
    }
    private static void admin(String action, String code, String clan) {
        var body = new JsonObject();
        body.addProperty("action", action); body.addProperty("actor_user_id", config.get("owner").getAsString()); body.addProperty("point_code", code);
        if (clan != null) body.addProperty("clan_code", clan);
        body.addProperty("reason", "capture probe");
        post("admin", body);
    }
    private static String declined() { return reply != null && !reply.get("accepted").getAsBoolean() ? reply.get("reason").getAsString() : null; }
    private static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private static void step(String name, int timeout, BooleanSupplier done) { STEPS.add(new Step(name, done, timeout)); }
    private static void once(String name, Runnable action) { STEPS.add(new Step(name, () -> { action.run(); return true; }, 5)); }
    private static CaptureSites.Event event(String code) { return CaptureSites.view().eventFor(code); }
    private static CaptureEngine battle(String code) { var e = event(code); return e == null ? null : CaptureArena.battle(e.id()); }
    private static boolean ownedBy(String code, String clan, String stateName) { var p = point(code); return p != null && clan.equals(p.owner()) && (stateName == null || stateName.equals(p.state())); }
    private static final long[] mark = new long[1];
    private static boolean waited(int seconds) { long now = Instant.now().getEpochSecond(); if (mark[0] == 0) mark[0] = now; if (now - mark[0] < seconds) return false; mark[0] = 0; return true; }

    // ------------------------------------------------------------------ phase one
    private static void phaseOne() {
        step("backend view with the states world", 60, () -> CaptureSites.fresh() && point(BATTLE) != null && point(PIT) != null && point(CACHE) != null && point(RESTART) != null);
        if(Boolean.getBoolean("animeoncraft.entryOnly")) {
            once("enter and check menu target guard",()->{
                var p=point(BATTLE);load(p);put("S1",p,1,0);put("S2",p,-1,0);
                var player=PLAYERS.get("S1");ServerMenuSync.rememberCapture(player);check(ServerMenuSync.captureMatches(player),"Current card refused");
                park("S1");check(!ServerMenuSync.captureMatches(player),"Stale menu target accepted");
                server.getCommands().performPrefixedCommand(player.createCommandSourceStack(),"aoc capture menu-declare");put("S1",p,1,0);
            });
            step("entry automatically creates one capture",30,()->event(BATTLE)!=null);
            once("entry and stale menu passed",()->System.out.println("AOC_CAPTURE_ENTRY_PROBE_PASS"));return;
        }
        if(Boolean.getBoolean("animeoncraft.allResourceSites")) {
            var specs=com.google.gson.JsonParser.parseString(readSpecs()).getAsJsonObject().getAsJsonObject("points");
            for(var code:specs.keySet())if(specs.getAsJsonObject(code).has("output"))once("all resource sites: "+code,()->ResourceSitesProbe.verify(server,point(code)));
        }
        if(Boolean.getBoolean("animeoncraft.resourcesOnly")) {once("all 28 resources passed",()->System.out.println("AOC_CAPTURE_RESOURCE_PROBE_PASS"));return;}
        once("load zones and lay ore in the pit", () -> {
            for (var code : List.of(BATTLE, PIT, CACHE, RESTART)) load(point(code));
            var pit = point(PIT); int y = surface(pit) - 1;
            for (int i = 0; i < 6; i++) server.overworld().setBlockAndUpdate(new BlockPos(pit.x() + 2 + i, y, pit.z()), Blocks.IRON_ORE.defaultBlockState());
            if(Boolean.getBoolean("animeoncraft.allResourceSites")) {
                try {var empty=new JsonObject();empty.add("blocks",new com.google.gson.JsonArray());ResourcePits.write(ResourcePits.directory(server).resolve("pits/"+PIT+".json"),empty);}
                catch(java.io.IOException failure){throw new IllegalStateException(failure);}
                admin("REFRESH",PIT,null);
            }
            facts.put("pit_ore_laid", 6);
        });
        step("first refresh records the pit template", 60, () -> {
            var p = point(PIT);
            try {var state=ResourcePits.read(ResourcePits.directory(server).resolve("pits/"+PIT+".json"));return p.resource().nextRefresh()!=null&&p.resource().nextRefresh().isAfter(Instant.now())&&state!=null&&!state.getAsJsonArray("blocks").isEmpty();}
            catch(java.io.IOException invalid){return false;}
        });
        once("mine one ore while a player stands in the pit", () -> {
            var p = point(PIT);
            server.overworld().setBlockAndUpdate(new BlockPos(p.x() + 2, surface(p) - 1, p.z()), Blocks.AIR.defaultBlockState());
            put("S1", p, 0, 0); admin("REFRESH", PIT, null);
            PLAYERS.get("S1").setGameMode(GameType.CREATIVE); // occupies the pit, cannot auto-declare during the resource test
        });
        step("refresh waits while the zone is occupied", 30, () -> {
            if (!answered() || !waited(8)) return false;
            var p = point(PIT);
            check(!server.overworld().getBlockState(new BlockPos(p.x() + 2, surface(p) - 1, p.z())).is(Blocks.IRON_ORE), "Pit refilled with a player inside");
            park("S1"); PLAYERS.get("S1").setGameMode(GameType.SURVIVAL);return true;
        });
        step("pit restored once the zone is empty", 60, () -> {
            var p = point(PIT);
            if (!server.overworld().getBlockState(new BlockPos(p.x() + 2, surface(p) - 1, p.z())).is(Blocks.IRON_ORE)) return false;
            facts.put("pit_restored", true); return true;
        });

        once("SCAM enters the neutral T1 site (automatic declaration)", () -> {
            var p = point(BATTLE); load(p); put("S1", p, 1, 0); put("S2", p, -1, 0);
            var player=PLAYERS.get("S1");ServerMenuSync.rememberCapture(player);check(ServerMenuSync.captureMatches(player),"Current menu point missing");
            park("S1");check(!ServerMenuSync.captureMatches(player),"Stale card could capture a different point");put("S1",p,1,0);
            facts.put("menu_point_guard",true);
        });
        step("entry declaration visible in the backend view", 30, () -> { if(event(BATTLE)==null)return false;facts.put("automatic_neutral",true);return true; });
        once("NYA cannot declare the same point", () -> declare("N1", "NYA", BATTLE));
        step("second declaration refused: RUNNING", 15, () -> { if (!answered()) return false; check("RUNNING".equals(declined()), "Expected RUNNING, got " + reply); return true; });
        step("battle active", 30, () -> battle(BATTLE) != null);
        once("NYA teleports in: join grace", () -> { var p = point(BATTLE); PLAYERS.get("N1").setPos(p.x() + 0.5, surface(p), p.z() + 2.5); });
        step("teleported player does not count for 10 s", 20, () -> {
            if (!waited(4)) return false;
            var engine = battle(BATTLE);
            check(engine == null || !engine.scores().containsKey("NYA") || engine.scores().get("NYA").presenceSeconds == 0, "A just-teleported player counted");
            facts.put("join_grace", true); park("N1"); return true;
        });
        step("SCAM owns the site, PROTECTED", 90, () -> ownedBy(BATTLE, "SCAM", "PROTECTED"));
        once("NYA declares during protection", () -> declare("N1", "NYA", BATTLE));
        step("refused: PROTECTED", 15, () -> { if (!answered()) return false; check("PROTECTED".equals(declined()), "Expected PROTECTED, got " + reply); return true; });
        once("pit of the owner: foreign break refused, owner break allowed", () -> {
            var p = point(BATTLE); var pos = new BlockPos(p.x() + 3, surface(p), p.z() + 3);
            server.overworld().setBlockAndUpdate(pos, Blocks.OAK_LOG.defaultBlockState());
            check(!PLAYERS.get("N1").gameMode.destroyBlock(pos) && server.overworld().getBlockState(pos).is(Blocks.OAK_LOG), "Foreign clan broke a held pit block");
            check(PLAYERS.get("S1").gameMode.destroyBlock(pos) && server.overworld().getBlockState(pos).isAir(), "Owner could not break its pit block");
            facts.put("pit_protection", true);
        });
        step("protection ends: CONTROLLED", 75, () -> ownedBy(BATTLE, "SCAM", "CONTROLLED"));
        once("NYA attacks with two players against two SCAM defenders", () -> {
            var p = point(BATTLE); put("N1", p, 0, 2); put("N2", p, 0, -2); declare("N1", "NYA", BATTLE);
        });
        step("attack accepted", 15, () -> { if (!answered()) return false; check(reply != null && reply.get("accepted").getAsBoolean(), "Attack refused: " + reply); return true; });
        step("battle active with the defence bonus", 30, () -> battle(BATTLE) != null);
        step("2 vs 2 with the owner bonus is a lock", 20, () -> {
            if (!waited(4)) return false;
            var engine = battle(BATTLE);
            check(engine.locked() && engine.progress("NYA") == 0, "Expected a lock, got " + engine.summary());
            facts.put("defence_lock", true);
            park("S2"); var p = point(BATTLE); PLAYERS.get("S1").setPos(p.x() + 0.5 + p.radius() + 8, surface(p), p.z() + 0.5); // just outside, within kill range
            return true;
        });
        step("a kill near the zone counts once", 10, () -> {
            long now = Instant.now().getEpochSecond();
            check(CaptureArena.kill(server, PLAYERS.get("N1"), PLAYERS.get("S1"), now), "Kill near the zone not counted");
            check(!CaptureArena.kill(server, PLAYERS.get("N1"), PLAYERS.get("S1"), now + 1), "Repeat kill counted");
            facts.put("kills", true); park("S1"); return true;
        });
        step("NYA captures the defended site", 120, () -> ownedBy(BATTLE, "NYA", null));

        once("AKUMA declares the watchtower", () -> { var p = point(RESTART); put("A1", p, 1, 0); put("A2", p, -1, 0); declare("A1", "AKUMA", RESTART); });
        step("watchtower declaration accepted", 15, () -> { if (!answered()) return false; check(reply.get("accepted").getAsBoolean(), "Refused: " + reply); return true; });
        step("watchtower battle reaches 30 % and is snapshotted", 60, () -> {
            var engine = battle(RESTART);
            if (engine == null || engine.progress("AKUMA") < 30 || !waited(4)) return false;
            park("A1", "A2");
            return true;
        });
        once("request a real process restart", () -> {
            state.addProperty("phase", 2);
            state.addProperty("progress_before_restart", battle(RESTART).progress("AKUMA"));
            state.add("facts", new com.google.gson.Gson().toJsonTree(facts));
            try { Files.writeString(statePath(), state.toString()); } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
            System.out.println("AOC_CAPTURE_RESTART_REQUEST");
        });
    }
    private static String readSpecs(){try{return Files.readString(Path.of("config/capture-sites-states.json"));}catch(java.io.IOException failure){throw new IllegalStateException(failure);}}

    // ------------------------------------------------------------------ phase two (after the restart)
    private static void phaseTwo() {
        double before = state.get("progress_before_restart").getAsDouble();
        step("backend view after restart", 60, () -> CaptureSites.fresh() && point(RESTART) != null);
        once("reload zones", () -> { for (var code : List.of(BATTLE, CACHE, RESTART)) load(point(code)); });
        step("battle resumed from the snapshot", 30, () -> {
            var engine = battle(RESTART);
            if (engine == null) return false;
            check(engine.progress("AKUMA") >= before - 12, "Snapshot not restored: " + engine.summary() + " before " + before);
            facts.put("restart_resume", Math.round(engine.progress("AKUMA")));
            return true;
        });
        once("API outage", () -> System.out.println("AOC_CAPTURE_STOP_API"));
        step("view becomes stale", 60, () -> !CaptureSites.fresh());
        once("players return during the outage", () -> { var p = point(RESTART); put("A1", p, 1, 0); put("A2", p, -1, 0); facts.put("outage_progress", battle(RESTART).progress("AKUMA")); });
        step("battle paused while the API is down", 20, () -> {
            if (!waited(4)) return false;
            check(battle(RESTART).progress("AKUMA") == (double) facts.get("outage_progress"), "Battle advanced without the backend");
            facts.put("outage_pause", true);
            System.out.println("AOC_CAPTURE_START_API"); return true;
        });
        step("API back", 90, CaptureSites::fresh);
        step("AKUMA captures the watchtower after restart and outage", 120, () -> ownedBy(RESTART, "AKUMA", "PROTECTED"));

        once("cache site given to SCAM and refreshed", () -> { park("A1", "A2"); admin("TRANSFER", CACHE, "SCAM"); });
        step("transfer done", 15, () -> answered() && reply != null);
        once("refresh the cache", () -> admin("REFRESH", CACHE, null));
        step("cache filled", 60, () -> {
            try { var cache = ResourcePits.read(ResourcePits.directory(server).resolve("caches/" + CACHE + ".json")); return cache != null && cache.getAsJsonArray("items").size() > 0; }
            catch (java.io.IOException failure) { return false; }
        });
        step("view shows SCAM owning the cache", 30, () -> ownedBy(CACHE, "SCAM", "CONTROLLED"));
        once("only the owner collects", () -> {
            var p = point(CACHE); put("N1", p, 0, 0); put("S1", p, 1, 1);
            server.getCommands().performPrefixedCommand(PLAYERS.get("N1").createCommandSourceStack(), "aoc resource collect");
            server.getCommands().performPrefixedCommand(PLAYERS.get("S1").createCommandSourceStack(), "aoc resource collect");
            check(PLAYERS.get("N1").getInventory().countItem(Items.COD) == 0, "A foreign clan opened the cache");
            int cod = PLAYERS.get("S1").getInventory().countItem(Items.COD);
            check(cod > 0, "Owner received nothing from the cache");
            facts.put("cache_cod", cod);
        });
        once("pass", () -> {
            var all = new JsonObject();
            var restored = state.getAsJsonObject("facts");
            for (var key : restored.keySet()) all.add(key, restored.get(key));
            for (var entry : facts.entrySet()) all.add(entry.getKey(), new com.google.gson.Gson().toJsonTree(entry.getValue()));
            all.addProperty("block_registry", BuiltInRegistries.BLOCK.size() > 0);
            System.out.println("AOC_CAPTURE_PROBE_PASS " + all);
        });
    }
}
