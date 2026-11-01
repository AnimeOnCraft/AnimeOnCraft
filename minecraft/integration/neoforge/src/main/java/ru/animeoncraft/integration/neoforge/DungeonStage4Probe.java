package ru.animeoncraft.integration.neoforge;

import java.nio.file.Files;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ExplosionEvent;

/**
 * Disposable review client for Rift Gate stage 4 (scripts/shoot-dungeon-stage4.mjs), singleplayer copy of a layout-test world:
 * catalog reload with a server override, the Mythic screen, timer and watcher, each weekly affix in a real run,
 * a guild DUNGEON quest from the clear to the coins, and the dungeon economy journal.
 */
final class DungeonStage4Probe {
    private int ticks, step, wait; private long waitUntil; private final List<String> log = new ArrayList<>();
    private volatile boolean answer; private volatile int asked, answered; private volatile long coins; private volatile int explosions;
    static void register() { new DungeonStage4Probe(); }
    private DungeonStage4Probe() {
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener((ExplosionEvent.Detonate e) -> { if (e.getLevel().dimension().equals(DungeonServer.RIFT)) { explosions++; check(e.getAffectedBlocks().isEmpty(), "a rift explosion breaks no blocks"); } });
    }
    private static boolean enabled() { return Boolean.getBoolean("animeoncraft.dungeonStage4Probe") && Minecraft.getInstance().gameDirectory.getAbsolutePath().contains("dungeons-stage4-"); }
    private void check(boolean ok, String what) { System.out.println("AOC_DUNGEON_STAGE4 " + (ok ? "PASS " : "FAIL ") + what); if (!ok) log.add(what); }
    private static void shot(String name) { var mc = Minecraft.getInstance(); Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), ignored -> {}); System.out.println("AOC_DUNGEON_STAGE4_SHOT " + name); }
    private void server(java.util.function.Consumer<ServerPlayer> action) { var mc = Minecraft.getInstance(); var s = Objects.requireNonNull(mc.getSingleplayerServer()); var id = mc.player.getUUID(); s.execute(() -> action.accept(s.getPlayerList().getPlayer(id))); }
    private boolean until(java.util.function.Function<ServerPlayer, Boolean> question) {
        if (asked > 0 && answered == asked && answer) { asked = 0; answered = 0; return true; }
        if (answered == asked) { int id = ++asked; server(p -> { answer = question.apply(p); answered = id; }); }
        return false;
    }
    private static void command(ServerPlayer p, String command) { p.server.getCommands().performPrefixedCommand(p.createCommandSourceStack().withPermission(4), command); }
    private static void guard(ServerPlayer p) {
        for (var e : List.of(net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE, net.minecraft.world.effect.MobEffects.SATURATION, net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE))
            p.addEffect(new net.minecraft.world.effect.MobEffectInstance(e, 20 * 600, 4, false, false));
    }
    private static boolean active(ServerPlayer p) { var r = DungeonServer.runOf(p.getUUID()); return r != null && r.state == DungeonRun.State.ACTIVE && DungeonServer.inRift(p); }
    private static boolean home(ServerPlayer p) { var s = RiftGate.site(p.server.overworld()); return DungeonServer.runOf(p.getUUID()) == null && !DungeonServer.inRift(p) && s != null && s.inHall(p.getX(), p.getY(), p.getZ()); }
    /** Into the current room until its first mobs are up (or until victory when the clear argument is set). */
    private static boolean drive(ServerPlayer p, boolean clear) {
        var r = DungeonServer.runOf(p.getUUID()); var rift = DungeonServer.rift(p.server); if (r == null) return false;
        if (r.state != DungeonRun.State.ACTIVE) return r.state == DungeonRun.State.VICTORY;
        var room = r.current();
        if (!room.inside(p.getX() - r.ox, p.getZ() - r.oz)) { p.teleportTo(rift, r.x(room.x0() + 3), DungeonRules.FLOOR + 1, r.z(0), -90, 10); return false; }
        if (room.kind() == DungeonLayout.Kind.TRAP) { p.teleportTo(rift, r.x(room.finish() + 1), DungeonRules.FLOOR + 1, r.z(0), -90, 0); return false; }
        if (r.mobs.isEmpty()) return false;
        if (!clear) return true;
        var hit = p.damageSources().playerAttack(p);
        for (var u : List.copyOf(r.mobs)) if (rift.getEntity(u) instanceof Mob mob) for (int i = 0; i < 400 && mob.isAlive(); i++) { mob.invulnerableTime = 0; mob.hurt(hit, 1000); }
        return false;
    }
    private static Mob firstMob(ServerPlayer p) { var r = DungeonServer.runOf(p.getUUID()); for (var u : r.mobs) if (DungeonServer.rift(p.server).getEntity(u) instanceof Mob m && m.isAlive()) return m; return null; }

    private void tick(ClientTickEvent.Post event) {
        if (!enabled()) return;
        var mc = Minecraft.getInstance(); ticks++;
        if (mc.player == null) {
            if (mc.screen != null && ticks % 30 == 0) for (var child : mc.screen.children())
                if (child instanceof Button b && b.active && Set.of("Proceed", "I know what I'm doing!", "Yes").contains(b.getMessage().getString())) b.onPress();
            return;
        }
        if (ticks < 200 || ++wait < (step == 0 ? 300 : 30) || System.currentTimeMillis() < waitUntil) return;
        wait = 0;
        try { run(mc); } catch (RuntimeException failure) { check(false, "step " + step + " threw " + failure); finish(mc); }
    }
    private void run(Minecraft mc) {
        switch (step++) {
            case 0 -> server(p -> {
                var s = p.server; var level = s.overworld();
                level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DAYLIGHT).set(false, s); level.setDayTime(6000);
                p.setGameMode(GameType.SURVIVAL); p.getInventory().clearContent(); var store = GuildStore.get(s); var c = store.card(p.getUUID()); c.open(7); c.rank = GuildRules.Rank.A.ordinal(); store.setDirty();
                LayoutJobs.budget(400); LayoutJobs.enqueue(s, RiftGate.CODE, false, null, 0, 0);
                // A server override with a short Mythic clock, applied by /aocdungeon reload.
                try (var in = DungeonCatalog.class.getResourceAsStream(DungeonCatalog.DEFAULT)) {
                    var json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).replace("\"timeLimitSeconds\": 1200", "\"timeLimitSeconds\": 45");
                    var file = s.getWorldPath(LevelResource.ROOT).resolve(DungeonCatalog.OVERRIDE); Files.createDirectories(file.getParent()); Files.writeString(file, json);
                } catch (java.io.IOException e) { throw new IllegalStateException(e); }
                command(p, "aocdungeon reload");
                var mythic = DungeonServer.catalog(s).difficulty("MYTHIC");
                check(mythic.timeLimit() == 45, "/aocdungeon reload takes the server override (Mythic clock 45 s)");
            });
            case 1 -> { if (!until(p -> LayoutJobs.idle() && RiftGate.site(p.server.overworld()) != null)) { step--; return; } DungeonServer.forgetSite(); }
            case 2 -> server(p -> { command(p, "aocdungeon affix bloodsuckers"); var a = RiftGate.site(p.server.overworld()).arrival(); p.teleportTo(p.serverLevel(), a[0], a[1], a[2], (float) a[3] + 180, 0); DungeonServer.send(p, DungeonPayloads.SCREEN_OPEN, ""); });
            case 3 -> { waitUntil = System.currentTimeMillis() + 2500; }
            case 4 -> { // click the Mythic tab (virtual 965, 128 of 1100×620)
                var sc = mc.screen; check(sc instanceof DungeonScreen, "gate screen open");
                if (sc != null) { double scale = Math.min(sc.width / 1100d, sc.height / 620d), left = (sc.width - 1100 * scale) / 2, top = (sc.height - 620 * scale) / 2; sc.mouseClicked(left + 965 * scale, top + 128 * scale, 0); }
                check(ClientDungeon.data() != null && ClientDungeon.data().affix().startsWith("Кровопийцы"), "the screen shows the week's affix: " + (ClientDungeon.data() == null ? "?" : ClientDungeon.data().affix()));
            }
            case 5 -> shot("stage4-01-mythic-screen-affix");
            case 6 -> { mc.setScreen(null); server(p -> command(p, "aocdungeon test sakura_crypt mythic")); }
            case 7 -> { if (!until(DungeonStage4Probe::active)) step--; }
            case 8 -> server(p -> { var r = DungeonServer.runOf(p.getUUID());
                check(r.affix != null && r.affix.code().equals("BLOODSUCKERS") && r.difficulty.code().equals("MYTHIC"), "Mythic run carries the affix");
                var bar = r.member(p.getUUID()).bar.getName().getString(); check(bar.contains("Кровопийцы") && bar.contains("⌛"), "HUD: affix and the Mythic clock: " + bar); guard(p); });
            case 9 -> shot("stage4-02-mythic-hud-clock");
            case 10 -> { if (!until(p -> drive(p, false))) step--; }
            case 11 -> server(p -> { var mob = firstMob(p); mob.setHealth(mob.getMaxHealth() / 2); float before = mob.getHealth();
                p.removeEffect(net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE); p.invulnerableTime = 0; p.setHealth(p.getMaxHealth());
                p.hurt(p.damageSources().mobAttack(mob), 6); guard(p); check(mob.getHealth() > before, "Bloodsuckers: the mob healed from its hit: " + before + " → " + mob.getHealth()); });
            case 12 -> server(p -> { var r = DungeonServer.runOf(p.getUUID()); p.removeAllEffects(); p.hurt(p.damageSources().fellOutOfWorld(), 1000);
                check(p.isSpectator() && r.member(p.getUUID()).watcher && DungeonServer.inRift(p), "Mythic: the fallen player watches as a spectator"); });
            case 13 -> shot("stage4-03-mythic-watcher");
            case 14 -> { if (!until(DungeonStage4Probe::home)) step--; }
            case 15 -> server(p -> check(p.gameMode.getGameModeForPlayer() == GameType.SURVIVAL, "nobody left: the run fails, the watcher is back at the gate in survival"));
            // Rift affix, then the Mythic clock (45 s override) ends the run.
            case 16 -> server(p -> { command(p, "aocdungeon affix rift"); command(p, "aocdungeon test sakura_crypt mythic"); explosions = 0; });
            case 17 -> { if (!until(DungeonStage4Probe::active)) step--; }
            case 18 -> server(p -> guard(p));
            case 19 -> { if (!until(p -> drive(p, false))) step--; }
            case 20 -> server(p -> { var mob = firstMob(p); var below = mob.blockPosition().below(); var floor = mob.level().getBlockState(below); mob.hurt(p.damageSources().playerAttack(p), 10_000);
                check(explosions > 0, "Rift: the mob burst on death"); check(mob.level().getBlockState(below).equals(floor), "the floor under it is intact"); });
            case 21 -> { if (!until(p -> DungeonServer.runOf(p.getUUID()) == null)) step--; }
            case 22 -> server(p -> check(home(p), "the Mythic clock ran out: run over, player at the gate"));
            // Shadows: an elite far away is invisible, next to it visible.
            case 23 -> server(p -> { command(p, "aocdungeon affix shadows"); command(p, "aocdungeon test sakura_crypt nightmare"); });
            case 24 -> { if (!until(DungeonStage4Probe::active)) step--; }
            case 25 -> server(p -> { guard(p); var r = DungeonServer.runOf(p.getUUID()); var room = r.current();
                var elite = r.spawnAt(p.server, DungeonServer.rift(p.server), "minecraft:zombie", r.x(room.x1() - 2), DungeonRules.FLOOR + 1, r.z(0), null, true, 1); elite.setNoAi(true); });
            case 26 -> server(p -> { var r = DungeonServer.runOf(p.getUUID()); var elite = firstMob(p);
                check(elite != null && elite.hasEffect(net.minecraft.world.effect.MobEffects.INVISIBILITY), "Shadows: a far elite is invisible");
                if (elite != null) p.teleportTo(p.serverLevel(), elite.getX() - 3, elite.getY(), elite.getZ(), -90, 0); });
            case 27 -> { waitUntil = System.currentTimeMillis() + 1500; }
            case 28 -> server(p -> { var elite = firstMob(p); check(elite != null && !elite.hasEffect(net.minecraft.world.effect.MobEffects.INVISIBILITY), "Shadows: within 6 blocks it shows"); });
            case 29 -> shot("stage4-04-shadows-elite-near");
            case 30 -> server(p -> command(p, "aocdungeon leave"));
            // No regen.
            case 31 -> { if (!until(p -> DungeonServer.runs().isEmpty())) step--; }
            case 32 -> server(p -> { command(p, "aocdungeon affix no_regen"); command(p, "aocdungeon test sakura_crypt nightmare"); });
            case 33 -> { if (!until(DungeonStage4Probe::active)) step--; }
            case 34 -> server(p -> { p.setHealth(8); p.heal(6); check(p.getHealth() == 8, "No regen: healing does nothing inside: " + p.getHealth()); command(p, "aocdungeon leave"); });
            case 35 -> server(p -> { p.heal(4); check(p.getHealth() > 8, "outside the run healing works again"); command(p, "aocdungeon affix auto"); });
            // Guild DUNGEON quest: C card with "clear the Sakura Crypt on Hard"; the clear counts, coins come at the board.
            case 36 -> { if (!until(p -> DungeonServer.runs().isEmpty())) step--; }
            case 37 -> server(p -> { var store = GuildStore.get(p.server); var c = store.card(p.getUUID()); c.rank = GuildRules.Rank.C.ordinal(); c.active = "c_rift_sakura"; c.progress = 0; c.reward = 55; store.setDirty();
                check(GuildServer.catalog(p.server).find("c_rift_sakura") != null, "the guild board knows the dungeon quest"); command(p, "aocdungeon test sakura_crypt hard"); });
            case 38 -> { if (!until(DungeonStage4Probe::active)) step--; }
            case 39 -> server(DungeonStage4Probe::guard);
            case 40 -> { if (!until(p -> drive(p, true))) step--; }
            case 41 -> server(p -> { var c = GuildStore.get(p.server).card(p.getUUID()); check(c.progress == 1, "the clear advanced the guild quest: " + c.progress + "/1"); command(p, "aocdungeon leave"); });
            case 42 -> { waitUntil = System.currentTimeMillis() + 2000; }
            case 43 -> server(p -> { var g = GuildServer.site(); p.teleportTo(p.server.overworld(), g.frame().x(0, AdventurerGuild.DEPTH - 4) + 0.5, g.y() + 1, g.frame().z(0, AdventurerGuild.DEPTH - 4) + 0.5, 0, 0);
                coins = GuildRules.value(GuildServer.coinsHeld(p)); GuildServer.send(p, GuildPayloads.SCREEN_BOARD, ""); });
            case 44 -> { waitUntil = System.currentTimeMillis() + 2500; }
            case 45 -> shot("stage4-05-guild-dungeon-quest");
            case 46 -> ClientGuild.request("TURN_IN", 0, 0);
            case 47 -> { waitUntil = System.currentTimeMillis() + 2500; }
            case 48 -> server(p -> { var c = GuildStore.get(p.server).card(p.getUUID()); long now = GuildRules.value(GuildServer.coinsHeld(p));
                check(c.active.isEmpty() && now > coins, "turned in at the board: +" + (now - coins) + " copper from the guild"); });
            // Economy journal.
            case 49 -> { mc.setScreen(null); server(p -> { var lines = DungeonServer.economyReport(p.server, 7); lines.forEach(l -> System.out.println("AOC_DUNGEON_STAGE4_ECONOMY " + l));
                var today = DungeonStore.get(p.server).today(System.currentTimeMillis());
                check(today[DungeonStore.RUNS] >= 5 && today[DungeonStore.FAILS] >= 2 && today[DungeonStore.WINS] >= 1 && today[DungeonStore.CHESTS] >= 1, "economy journal counts runs, wins, fails and chests: " + Arrays.toString(today));
                command(p, "aocdungeon economy"); command(p, "aocguild economy"); }); }
            case 50 -> mc.setScreen(new ChatScreen(""));
            case 51 -> { waitUntil = System.currentTimeMillis() + 1500; }
            case 52 -> shot("stage4-06-economy-journal");
            case 53 -> server(p -> { try { Files.deleteIfExists(p.server.getWorldPath(LevelResource.ROOT).resolve(DungeonCatalog.OVERRIDE)); } catch (java.io.IOException ignored) {} command(p, "aocdungeon reload");
                check(DungeonServer.catalog(p.server).difficulty("MYTHIC").timeLimit() == 1200, "without the override the shipped Mythic clock (20 min) is back"); });
            default -> finish(mc);
        }
    }
    private void finish(Minecraft mc) { System.out.println("AOC_DUNGEON_STAGE4_DONE " + (log.isEmpty() ? "ok" : String.join("; ", log))); mc.stop(); }
}
