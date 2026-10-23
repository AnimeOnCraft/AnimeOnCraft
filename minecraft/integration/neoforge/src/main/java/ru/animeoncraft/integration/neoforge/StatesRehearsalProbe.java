package ru.animeoncraft.integration.neoforge;

import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * Stage 7 dress rehearsal (scripts/rehearse-states.mjs, -Danimeoncraft.rehearsal=true, world aoc-rehearsal-*):
 * a copy of the pregenerated states world with the real backend. Checks, in order: the region catalog arrives and
 * drives protection (real block breaks by FakePlayers), spawns and the boss arena, the capture view, the client map
 * payload, and a frontier slot assigned in the backend reaching the server without a restart.
 */
final class StatesRehearsalProbe {
    private static final UUID MEMBER = UUID.fromString("9be87aa0-bdbe-4430-a869-fba5590bec11");
    private static final UUID GUEST = UUID.fromString("9be87aa0-bdbe-4430-a869-fba5590bec12");
    private static MinecraftServer server;
    private static int stage, ticks;
    private static final JsonObject report = new JsonObject();
    private StatesRehearsalProbe() {}

    static void started(net.neoforged.neoforge.event.server.ServerStartedEvent event) {
        if (!Boolean.getBoolean("animeoncraft.rehearsal")) return;
        var current = event.getServer();
        if (current.getPort() != 25569 || !"127.0.0.1".equals(current.getLocalIp()) || !current.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString().startsWith("aoc-rehearsal-"))
            throw new IllegalStateException("Rehearsal probe requires its own loopback world");
        server = current; stage = 1; ticks = 0;
        System.out.println("AOC_REHEARSAL started");
    }

    static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (server != event.getServer() || stage == 0) return;
        ticks++;
        try {
            switch (stage) {
                case 1 -> { // region catalog from the backend
                    if (WorldRegions.current() != null && ClanControlSync.ready() && CaptureSites.fresh()) { protection(); stage = 2; }
                    else if (ticks > 20 * 120) fail("no region catalog, clan control or capture view from the backend within 120 s");
                }
                case 2 -> { System.out.println("AOC_REHEARSAL_ASSIGN_REQUEST"); stage = 3; ticks = 0; }
                case 3 -> { // the harness assigned frontier.slot.d to KITSUNE in the backend
                    var slot = WorldRegions.current().regions().stream().filter(r -> r.code().equals("frontier.slot.d")).findFirst().orElse(null);
                    if (slot != null && "KITSUNE".equals(slot.owner())) {
                        int x = (slot.minX() + slot.maxX()) / 2, z = (slot.minZ() + slot.maxZ()) / 2;
                        check("KITSUNE".equals(TerritoryProtection.ownerAt(x, z)), "assigned slot protects for KITSUNE");
                        check(RegionMapPayload.of(WorldRegions.current()).regions().size() == 6, "client map shows six clan regions");
                        report.addProperty("slot_assigned_after_seconds", ticks / 20);
                        stage = 0;
                        System.out.println("AOC_REHEARSAL_PASS " + report);
                    } else if (ticks > 20 * 90) fail("assigned slot did not reach the server within 90 s");
                }
                default -> { }
            }
        } catch (Exception failure) { fail(failure.toString()); failure.printStackTrace(System.out); }
    }

    private static void protection() {
        var regions = WorldRegions.current();
        report.addProperty("regions", regions.regions().size());
        check(TerritoryProtection.statesWorld(), "states-world protection active");
        check(RegionPolicy.NOBODY.equals(TerritoryProtection.ownerAt(0, 0)), "Sora: nobody builds");
        check("SCAM".equals(TerritoryProtection.ownerAt(0, -2704)) && "NYA".equals(TerritoryProtection.ownerAt(2560, -832)), "capitals belong to their clans");
        check(TerritoryProtection.ownerAt(0, -1248) == null, "ring outpost is open land");

        var level = server.overworld();
        var member = new FakePlayer(level, new GameProfile(MEMBER, "AocRehearsalScam"));
        var guest = new FakePlayer(level, new GameProfile(GUEST, "AocRehearsalNya"));
        member.gameMode.changeGameModeForPlayer(GameType.SURVIVAL); guest.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        GameIdentity.PLAYERS.put(MEMBER, new GameIdentity("SCAM", new byte[0], false, "", UUID.randomUUID(), UUID.randomUUID()));
        GameIdentity.PLAYERS.put(GUEST, new GameIdentity("NYA", new byte[0], false, "", UUID.randomUUID(), UUID.randomUUID()));
        try {
            // High above the buildings, so the world copy keeps its castles intact.
            var scam = new BlockPos(64, 300, -2704 + 64);
            level.setBlockAndUpdate(scam, Blocks.STONE.defaultBlockState());
            check(!guest.gameMode.destroyBlock(scam) && level.getBlockState(scam).is(Blocks.STONE), "NYA cannot break in SCAM land");
            check(member.gameMode.destroyBlock(scam) && level.getBlockState(scam).isAir(), "SCAM breaks at home");
            var sora = new BlockPos(40, 300, 40);
            level.setBlockAndUpdate(sora, Blocks.STONE.defaultBlockState());
            check(!member.gameMode.destroyBlock(sora) && !guest.gameMode.destroyBlock(sora), "nobody breaks in Sora");
            level.setBlockAndUpdate(sora, Blocks.AIR.defaultBlockState());
            var ring = new BlockPos(0, 300, -1248 + 40);
            level.setBlockAndUpdate(ring, Blocks.STONE.defaultBlockState());
            check(guest.gameMode.destroyBlock(ring), "anyone breaks in the open ring");
        } finally { GameIdentity.PLAYERS.remove(MEMBER); GameIdentity.PLAYERS.remove(GUEST); }
        report.addProperty("protection", true);

        var spawns = ClanSpawns.read(server);
        check(spawns.size() == 6 && spawns.containsKey("COMMON"), "five clan spawns and the common spawn");
        var common = spawns.get("COMMON");
        check(Math.hypot(common.getX(), common.getZ()) < 64, "common spawn is in Sora");
        report.addProperty("world_spawn", level.getSharedSpawnPos().toShortString());
        check(Math.hypot(level.getSharedSpawnPos().getX(), level.getSharedSpawnPos().getZ()) < 64, "world spawn moved to Sora");

        var arena = WorldBossEncounter.layoutArena(server);
        check(Math.hypot(arena.getX(), arena.getZ() - 800) < 1 && WorldBossEncounter.statesArena(arena.getX(), arena.getZ()), "boss arena from the layout, no clan land near it");
        report.addProperty("boss_arena", arena.toShortString());

        var view = CaptureSites.view();
        check(view.points().size() >= 50, "capture view from the backend");
        report.addProperty("capture_points", view.points().size());
        check(RegionMapPayload.of(regions).regions().size() == 5, "client map shows five clan regions");
        System.out.println("AOC_REHEARSAL_PROTECTION " + report);
    }

    private static void check(boolean condition, String what) { if (!condition) throw new IllegalStateException("Rehearsal check failed: " + what); }
    private static void fail(String reason) { stage = 0; System.out.println("AOC_REHEARSAL_FAILED " + reason); }
}
