package ru.animeoncraft.integration.neoforge;

import java.nio.file.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Disposable two-client review of a Rift Gate team (scripts/shoot-dungeon-team.mjs). The host is a singleplayer copy
 * of a layout-test world opened to LAN and plays the leader; the guest is a second real client on the LAN port. The
 * host drives the scenario and checks the server state; the guest performs client actions it is told to through
 * numbered files (chat button, ready, screen, chest click, disconnect, reconnect, screenshots).
 */
final class DungeonTeamProbe {
    static final String LEADER = "AocLeader", GUEST = "AocGuest";
    private final boolean host = "host".equals(System.getProperty("aoc.dungeonTeamRole"));
    private final Path root = Path.of(System.getProperty("aoc.dungeonTeamRoot", "."));
    private final int port = Integer.getInteger("aoc.dungeonTeamPort", 25573);
    private int ticks, step, wait, sent, done; private long waitUntil;
    private final List<String> log = new ArrayList<>();
    private volatile boolean ok, answer; private volatile int asked, answered; private volatile String joinCommand;
    private volatile long leaderCoins, guestCoins; private volatile int leaderRolls, guestRolls;
    static void register() { new DungeonTeamProbe(); }
    private DungeonTeamProbe() {
        if (!root.toAbsolutePath().toString().replace('\\', '/').contains("/.tools/dungeons-team-")) throw new IllegalStateException("Disposable directory required");
        NeoForge.EVENT_BUS.addListener(this::tick);
        if (!host) NeoForge.EVENT_BUS.addListener((ClientChatReceivedEvent.System event) -> event.getMessage().visit((style, text) -> {
            var click = style.getClickEvent();
            if (click != null && click.getAction() == ClickEvent.Action.RUN_COMMAND && click.getValue().startsWith("/aocdungeon join")) joinCommand = click.getValue();
            return Optional.empty();
        }, Style.EMPTY));
    }

    // ------------------------------------------------------------------ plumbing
    private void check(boolean value, String what) { System.out.println("AOC_DUNGEON_TEAM " + (value ? "PASS " : "FAIL ") + what); if (!value) log.add(what); }
    private void shot(String name) { var mc = Minecraft.getInstance(); Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), ignored -> {}); System.out.println("AOC_DUNGEON_TEAM_SHOT " + name); }
    private void file(String name, String text) { try { Files.writeString(root.resolve(name), text); } catch (java.io.IOException e) { throw new IllegalStateException(e); } }
    private boolean exists(String name) { return Files.exists(root.resolve(name)); }
    /** Host: ask the guest to do something; the next steps wait for its acknowledgement. */
    private void guest(String command) { sent++; file("cmd-" + sent + ".txt", command); System.out.println("AOC_DUNGEON_TEAM_GUEST " + sent + " " + command); }
    private boolean guestDone() { return exists("ack-" + sent + ".txt"); }
    private void server(java.util.function.Consumer<MinecraftServer> action) { var s = Objects.requireNonNull(Minecraft.getInstance().getSingleplayerServer()); s.execute(() -> action.accept(s)); }
    /** Asks the server thread; true once a fresh answer (not one from an earlier step) is true. */
    private boolean until(java.util.function.Function<MinecraftServer, Boolean> question) {
        if (asked > 0 && answered == asked && answer) { asked = 0; answered = 0; return true; }
        if (answered == asked) { int id = ++asked; server(s -> { answer = question.apply(s); answered = id; }); }
        return false;
    }
    private static ServerPlayer player(MinecraftServer s, String name) { return s.getPlayerList().getPlayerByName(name); }
    private static net.minecraft.world.item.Item item(String id) { return BuiltInRegistries.ITEM.get(ResourceLocation.parse(id)); }
    private static void click(Minecraft mc, BlockPos pos) { mc.execute(() -> mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(pos), Direction.WEST, pos, false))); }
    private static void guard(ServerPlayer p) {
        for (var e : List.of(net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE, net.minecraft.world.effect.MobEffects.REGENERATION, net.minecraft.world.effect.MobEffects.SATURATION, net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE))
            p.addEffect(new net.minecraft.world.effect.MobEffectInstance(e, 20 * 900, 4, false, false));
    }
    private static long coins(ServerPlayer p) { return GuildRules.value(GuildServer.coinsHeld(p)); }
    private static void rank(ServerPlayer p, GuildRules.Rank rank) { var store = GuildStore.get(p.server); var c = store.card(p.getUUID()); c.open(7); c.rank = rank.ordinal(); store.setDirty(); }
    private static BlockPos portal(MinecraftServer s) { var site = RiftGate.site(s.overworld()); var f = site.frame(); return new BlockPos(f.x(0, RiftGate.DEPTH - 2), site.y() + 3, f.z(0, RiftGate.DEPTH - 2)); }
    private static void toGate(ServerPlayer p, double side) { var a = RiftGate.site(p.server.overworld()).arrival(); p.teleportTo(p.server.overworld(), a[0], a[1], a[2] + side, (float) a[3] + 180, 0); }

    private void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance(); ticks++;
        if (mc.player == null) {
            if (mc.screen != null && ticks % 30 == 0) for (var child : mc.screen.children())
                if (child instanceof Button b && b.active && Set.of("Proceed", "I know what I'm doing!", "Yes").contains(b.getMessage().getString())) b.onPress();
            if (!host && ticks % 20 == 0) guestTick(mc);
            return;
        }
        if (!host) { if (ticks % 20 == 0) guestTick(mc); return; }
        if (ticks < 200 || ++wait < (step == 0 ? 300 : 40) || System.currentTimeMillis() < waitUntil) return;
        wait = 0;
        try { hostStep(mc); } catch (RuntimeException failure) { check(false, "host step " + step + " threw " + failure); finish(mc); }
    }

    // ------------------------------------------------------------------ guest
    private void guestTick(Minecraft mc) {
        if (!exists("cmd-" + (done + 1) + ".txt")) return;
        String command; try { command = Files.readString(root.resolve("cmd-" + (done + 1) + ".txt")).trim(); } catch (java.io.IOException e) { return; }
        boolean online = mc.player != null && mc.getConnection() != null;
        String[] part = command.split(" ", 2);
        switch (part[0]) {
            case "chatjoin" -> { if (!online) return; System.out.println("AOC_DUNGEON_TEAM_GUEST_JOIN " + joinCommand); if (joinCommand == null) return; mc.player.connection.sendCommand(joinCommand.substring(1)); }
            case "cmd" -> { if (!online) return; mc.player.connection.sendCommand(part[1]); }
            case "request" -> { if (!online) return; var a = part[1].split(" "); ClientDungeon.request(a[0], Integer.parseInt(a[1]), Integer.parseInt(a[2]), 0); }
            case "click" -> { if (!online) return; var a = part[1].split(" "); click(mc, new BlockPos(Integer.parseInt(a[0]), Integer.parseInt(a[1]), Integer.parseInt(a[2]))); }
            case "close" -> { if (online) mc.setScreen(null); }
            case "shot" -> { if (!online) return; shot(part[1]); }
            case "disconnect" -> { if (!online) return; mc.level.disconnect(); mc.disconnect(new TitleScreen()); }
            case "reconnect" -> { if (online) break; var address = "127.0.0.1:" + port;
                ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(address), new ServerData("aoc-team", address, ServerData.Type.OTHER), false, null); }
            case "quit" -> { file("ack-" + (done + 1) + ".txt", "ok"); mc.stop(); return; }
            default -> {}
        }
        done++; file("ack-" + done + ".txt", "ok");
    }

    // ------------------------------------------------------------------ host
    private void hostStep(Minecraft mc) {
        switch (step++) {
            case 0 -> server(s -> {
                var level = s.overworld(); var p = player(s, LEADER);
                level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DAYLIGHT).set(false, s); level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_WEATHER_CYCLE).set(false, s);
                level.setDayTime(6000); level.setWeatherParameters(120000, 0, false, false);
                p.setGameMode(GameType.SURVIVAL); p.getInventory().clearContent(); rank(p, GuildRules.Rank.B); p.getInventory().add(new ItemStack(item("lightmanscurrency:coin_gold"), 1));
                LayoutJobs.budget(400); check(LayoutJobs.enqueue(s, RiftGate.CODE, false, null, 0, 0) == 1, "gate queued");
                s.setUsesAuthentication(false); check(s.publishServer(GameType.SURVIVAL, false, port), "LAN opened on " + port);
            });
            case 1 -> { if (!until(s -> LayoutJobs.idle() && RiftGate.site(s.overworld()) != null)) { step--; return; } DungeonServer.forgetSite(); file("host-ready.ok", "ok"); }
            case 2 -> { if (!until(s -> player(s, GUEST) != null)) step--; }
            case 3 -> server(s -> { // both at the gate: leader rank B, guest rank E (Hard needs D), a gold coin each
                var l = player(s, LEADER); var g = player(s, GUEST); g.setGameMode(GameType.SURVIVAL); g.getInventory().clearContent(); rank(g, GuildRules.Rank.E);
                g.getInventory().add(new ItemStack(item("lightmanscurrency:coin_gold"), 1)); toGate(l, -2); toGate(g, 2); ok = false;
            });
            case 4 -> guest("request SOLO 0 1");
            case 5 -> { if (!guestDone()) { step--; return; } waitUntil = System.currentTimeMillis() + 2000; }
            case 6 -> server(s -> check(DungeonServer.runOf(player(s, GUEST).getUUID()) == null, "a rank below Hard cannot go alone"));
            case 7 -> ClientDungeon.request("CREATE", 0, 1, 0);
            case 8 -> guest("chatjoin");
            case 9 -> { if (!guestDone()) { step--; return; } waitUntil = System.currentTimeMillis() + 2000; }
            case 10 -> server(s -> { var l = DungeonServer.lobbyOf(player(s, LEADER).getUUID()); check(l != null && l.members.size() == 2 && l.members.contains(player(s, GUEST).getUUID()), "the guest joined through the chat button"); });
            case 11 -> server(s -> guest("click " + portal(s).getX() + " " + portal(s).getY() + " " + portal(s).getZ()));
            case 12 -> { if (!guestDone()) { step--; return; } waitUntil = System.currentTimeMillis() + 2500; }
            case 13 -> guest("request READY 0 1");
            case 14 -> { if (!guestDone()) { step--; return; } waitUntil = System.currentTimeMillis() + 2000; }
            case 15 -> guest("shot team-01-guest-lobby");
            case 16 -> { if (!guestDone()) { step--; return; } guest("close"); ClientDungeon.request("READY", 0, 1, 0); }
            case 17 -> server(s -> click(mc, portal(s)));
            case 18 -> { waitUntil = System.currentTimeMillis() + 2500; }
            case 19 -> { check(mc.screen instanceof DungeonScreen, "leader sees the gate screen"); shot("team-02-leader-lobby-ready"); }
            case 20 -> server(s -> { var l = DungeonServer.lobbyOf(player(s, LEADER).getUUID()); check(l != null && l.ready.size() == 2, "both pressed Ready");
                leaderCoins = coins(player(s, LEADER)); guestCoins = coins(player(s, GUEST)); });
            case 21 -> ClientDungeon.request("START", 0, 1, 0);
            case 22 -> { if (!until(s -> { var r = DungeonServer.runOf(player(s, LEADER).getUUID()); return r != null && r.state == DungeonRun.State.ACTIVE && DungeonServer.inRift(player(s, GUEST)); })) step--; }
            case 23 -> server(s -> {
                var l = player(s, LEADER); var g = player(s, GUEST); var r = DungeonServer.runOf(l.getUUID());
                check(leaderCoins - coins(l) == 10 && guestCoins - coins(g) == 10, "Hard entry 10 copper from each: " + (leaderCoins - coins(l)) + ", " + (guestCoins - coins(g)));
                check(r.member(l.getUUID()).access == DungeonRules.Access.FULL && r.member(g.getUUID()).access == DungeonRules.Access.HALF, "leader full loot, rank-below guest half");
                check(r.members.size() == 2 && DungeonServer.runOf(g.getUUID()) == r, "both in one run");
                guard(l); guard(g); // Hard has one life each: the review players must not fall while the guest reconnects
            });
            case 24 -> { mc.setScreen(null); shot("team-03-leader-in-crypt"); guest("shot team-04-guest-in-crypt"); }
            case 25 -> { if (!guestDone()) { step--; return; } }
            case 26 -> { if (!until(s -> drive(s, true))) step--; } // until the first fight wave is up
            case 27 -> server(s -> { var r = DungeonServer.runOf(player(s, LEADER).getUUID()); var rift = DungeonServer.rift(s); double expected = 1.8 * DungeonRules.teamHealth(2, 0.6) - 1; boolean all = !r.mobs.isEmpty();
                for (var u : r.mobs) if (rift.getEntity(u) instanceof Mob mob) { var mod = mob.getAttribute(Attributes.MAX_HEALTH).getModifier(ResourceLocation.fromNamespaceAndPath("animeoncraft", "dungeon_health")); all &= mod != null && Math.abs(mod.amount() - expected) < 1e-6; }
                check(all, "mob health ×1.8 for Hard × 1.6 for two players"); shotLater("team-05-two-player-wave"); });
            case 28 -> guest("disconnect");
            case 29 -> { if (!guestDone()) { step--; return; } waitUntil = System.currentTimeMillis() + 20_000; }
            case 30 -> server(s -> { var r = DungeonServer.runOf(player(s, LEADER).getUUID()); var m = r.members.values().stream().filter(x -> x.name.equals(GUEST)).findFirst().orElseThrow();
                check(player(s, GUEST) == null && m.leftAt > 0 && !m.out, "guest left the game, place kept"); });
            case 31 -> guest("reconnect");
            case 32 -> { if (!until(s -> player(s, GUEST) != null)) { step--; return; } waitUntil = System.currentTimeMillis() + 3000; }
            case 33 -> server(s -> { var g = player(s, GUEST); var r = DungeonServer.runOf(g.getUUID());
                check(r != null && DungeonServer.inRift(g) && r.member(g.getUUID()).leftAt == 0, "back within 3 minutes: still in the run"); guard(g); guard(player(s, LEADER)); });
            case 34 -> guest("shot team-06-guest-back-in-run");
            case 35 -> { if (!guestDone()) { step--; return; } }
            case 36 -> { if (!until(s -> drive(s, false))) step--; } // until the boss falls
            case 37 -> server(s -> { var l = player(s, LEADER); var g = player(s, GUEST); var r = DungeonServer.runOf(l.getUUID());
                var ml = r.member(l.getUUID()); var mg = r.member(g.getUUID()); leaderRolls = ml.rolled; guestRolls = mg.rolled;
                check(r.state == DungeonRun.State.VICTORY && r.chests.size() == 2, "victory: a reward chest for each member");
                check(!ml.chest.isEmpty() && !mg.chest.isEmpty() && ml.chest != mg.chest, "each member has an own personal chest");
                check(guestRolls == (leaderRolls + 1) / 2, "half loot for the rank below: " + guestRolls + " of " + leaderRolls + " rolls");
                check(mg.chest.countItem(DungeonItems.ESSENCE.get()) <= 2 && ml.chest.countItem(DungeonItems.ESSENCE.get()) >= 2, "half essence: " + mg.chest.countItem(DungeonItems.ESSENCE.get()) + " vs " + ml.chest.countItem(DungeonItems.ESSENCE.get()));
                var a = r.chests.get(0); var b = r.chests.get(1);
                l.teleportTo(l.serverLevel(), a.getX() - 2.5, a.getY(), a.getZ() + 0.5, -90, 30); g.teleportTo(g.serverLevel(), b.getX() - 2.5, b.getY(), b.getZ() + 0.5, -90, 30);
                guest("click " + b.getX() + " " + b.getY() + " " + b.getZ()); click(mc, a); });
            case 38 -> { if (!guestDone()) { step--; return; } waitUntil = System.currentTimeMillis() + 2500; }
            case 39 -> { shot("team-07-leader-chest"); guest("shot team-08-guest-chest"); }
            case 40 -> { if (!guestDone()) { step--; return; } mc.setScreen(null); guest("close"); }
            case 41 -> { server(s -> { s.getCommands().performPrefixedCommand(player(s, LEADER).createCommandSourceStack(), "aocdungeon leave"); }); guest("cmd aocdungeon leave"); }
            case 42 -> { if (!guestDone()) { step--; return; } waitUntil = System.currentTimeMillis() + 3000; }
            case 43 -> server(s -> { var l = player(s, LEADER); var g = player(s, GUEST); var site = RiftGate.site(s.overworld());
                check(!DungeonServer.inRift(l) && !DungeonServer.inRift(g) && site.inHall(l.getX(), l.getY(), l.getZ()) && site.inHall(g.getX(), g.getY(), g.getZ()), "both back in the gate hall");
                check(l.getInventory().countItem(DungeonItems.ESSENCE.get()) > g.getInventory().countItem(DungeonItems.ESSENCE.get()) || g.getInventory().countItem(DungeonItems.ESSENCE.get()) <= 2, "each took their own loot home");
                // Run 2: a guild party (/aocparty) goes in as one, Nightmare with shared lives; the guest is rank C (Nightmare needs B).
                rank(g, GuildRules.Rank.C); toGate(l, -2); toGate(g, 2); });
            case 44 -> { if (!until(s -> DungeonServer.runs().isEmpty())) { step--; return; } server(s -> s.getCommands().performPrefixedCommand(player(s, LEADER).createCommandSourceStack(), "aocparty invite " + GUEST)); }
            case 45 -> guest("cmd aocparty accept");
            case 46 -> { if (!guestDone()) { step--; return; } waitUntil = System.currentTimeMillis() + 2000; }
            case 47 -> server(s -> { var party = GuildServer.parties.of(player(s, LEADER).getUUID()); check(party != null && party.members.size() == 2, "guild party of two"); leaderCoins = coins(player(s, LEADER)); guestCoins = coins(player(s, GUEST)); });
            case 48 -> ClientDungeon.request("CREATE", 0, 2, 0);
            case 49 -> { waitUntil = System.currentTimeMillis() + 2000; }
            case 50 -> server(s -> { var l = DungeonServer.lobbyOf(player(s, LEADER).getUUID()); check(l != null && l.members.contains(player(s, GUEST).getUUID()), "the party joined the team at once"); });
            case 51 -> { guest("request READY 0 2"); ClientDungeon.request("READY", 0, 2, 0); }
            case 52 -> { if (!guestDone()) { step--; return; } waitUntil = System.currentTimeMillis() + 2000; }
            case 53 -> ClientDungeon.request("START", 0, 2, 0);
            case 54 -> { if (!until(s -> { var r = DungeonServer.runOf(player(s, LEADER).getUUID()); return r != null && r.state == DungeonRun.State.ACTIVE && DungeonServer.inRift(player(s, GUEST)); })) step--; }
            case 55 -> server(s -> { var l = player(s, LEADER); var g = player(s, GUEST); var r = DungeonServer.runOf(l.getUUID());
                check(leaderCoins - coins(l) == 30 && guestCoins - coins(g) == 30, "Nightmare entry 30 copper from each: " + (leaderCoins - coins(l)) + ", " + (guestCoins - coins(g)));
                check(r.teamLives == 2, "Nightmare: 2 lives for the whole team"); guard(l);
                g.hurt(g.damageSources().fellOutOfWorld(), 1000);
                check(g.isAlive() && DungeonServer.inRift(g) && r.teamLives == 1 && !r.member(g.getUUID()).watcher, "a guest death spends a shared life: " + r.teamLives); guard(g); });
            case 56 -> { mc.setScreen(null); guest("shot team-09-shared-lives"); }
            case 57 -> { if (!guestDone()) { step--; return; } guest("disconnect"); }
            case 58 -> { if (!guestDone()) { step--; return; } waitUntil = System.currentTimeMillis() + 190_000; System.out.println("AOC_DUNGEON_TEAM waiting 190 s"); }
            case 59 -> server(s -> { var r = DungeonServer.runOf(player(s, LEADER).getUUID()); var m = r.members.values().stream().filter(x -> x.name.equals(GUEST)).findFirst().orElseThrow();
                check(m.out, "away longer than 3 minutes: the guest is out of the run"); check(r.state == DungeonRun.State.ACTIVE, "the leader's run goes on"); });
            case 60 -> guest("reconnect");
            case 61 -> { if (!until(s -> player(s, GUEST) != null)) { step--; return; } waitUntil = System.currentTimeMillis() + 4000; }
            case 62 -> server(s -> { var g = player(s, GUEST); var site = RiftGate.site(s.overworld());
                check(!DungeonServer.inRift(g) && site.inHall(g.getX(), g.getY(), g.getZ()) && DungeonServer.runOf(g.getUUID()) == null, "back after 3 minutes: at the gate, not in the run"); });
            case 63 -> guest("shot team-10-guest-at-gate-after-timeout");
            case 64 -> { if (!guestDone()) { step--; return; } server(s -> s.getCommands().performPrefixedCommand(player(s, LEADER).createCommandSourceStack(), "aocdungeon leave")); }
            case 65 -> { guest("quit"); waitUntil = System.currentTimeMillis() + 3000; }
            default -> finish(mc);
        }
    }
    private void finish(Minecraft mc) { System.out.println("AOC_DUNGEON_TEAM_DONE " + (log.isEmpty() ? "ok" : String.join("; ", log))); file("host-done.ok", "ok"); mc.stop(); }
    private static void shotLater(String name) { var mc = Minecraft.getInstance(); mc.execute(() -> new Thread(() -> { try { Thread.sleep(1500); } catch (InterruptedException ignored) {} mc.execute(() -> { Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), ignored -> {}); System.out.println("AOC_DUNGEON_TEAM_SHOT " + name); }); }).start()); }
    /** Leader plays the rooms: into the current room, its mobs die, past the trap. firstWave: stop once the first fight wave is up. */
    private static boolean drive(MinecraftServer s, boolean firstWave) {
        {
            var l = player(s, LEADER); var r = DungeonServer.runOf(l.getUUID()); var rift = DungeonServer.rift(s);
            if (r == null || r.state != DungeonRun.State.ACTIVE) return r != null && r.state == DungeonRun.State.VICTORY;
            var room = r.current();
            if (!room.inside(l.getX() - r.ox, l.getZ() - r.oz)) { l.teleportTo(rift, r.x(room.x0() + 3), DungeonRules.FLOOR + 1, r.z(0), -90, 10); return false; }
            if (room.kind() == DungeonLayout.Kind.TRAP) { l.teleportTo(rift, r.x(room.finish() + 1), DungeonRules.FLOOR + 1, r.z(0), -90, 0); return false; }
            if (r.mobs.isEmpty()) return false;
            if (firstWave) return true;
            var hit = l.damageSources().playerAttack(l);
            for (var u : List.copyOf(r.mobs)) if (rift.getEntity(u) instanceof Mob mob) for (int i = 0; i < 400 && mob.isAlive(); i++) { mob.invulnerableTime = 0; mob.hurt(hit, 1000); }
            return false;
        }
    }
}
