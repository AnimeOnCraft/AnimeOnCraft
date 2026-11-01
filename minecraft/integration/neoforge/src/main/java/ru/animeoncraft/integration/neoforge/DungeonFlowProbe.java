package ru.animeoncraft.integration.neoforge;

import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Disposable review client for the Rift Gate (scripts/shoot-dungeon-flow.mjs): in a copy of a layout-test world it builds
 * the gate, opens the screen, pays a Hard entry, plays the Sakura Crypt room by room (protections, a death, the trap,
 * the treasure, the boss, the personal chest) and returns. Phase 2 (second launch) checks a run interrupted by a stop.
 */
final class DungeonFlowProbe {
    private int ticks, step, wait, phase; private final List<String> log = new ArrayList<>();
    private volatile String state = ""; private volatile int kind = -1; private volatile boolean ready, home, done;
    private volatile long coinsBefore, coinsAfter; private volatile int stacksBefore;
    static void register() { new DungeonFlowProbe(); }
    private DungeonFlowProbe() { NeoForge.EVENT_BUS.addListener(this::tick); phase = Integer.getInteger("animeoncraft.dungeonFlowPhase", 1); }
    private static boolean enabled() { return Boolean.getBoolean("animeoncraft.dungeonFlowProbe") && Minecraft.getInstance().gameDirectory.getAbsolutePath().contains("dungeons-flow-"); }

    private void tick(ClientTickEvent.Post event) {
        if (!enabled()) return;
        var mc = Minecraft.getInstance(); ticks++;
        if (mc.player == null) {
            if (mc.screen != null && ticks % 30 == 0) for (var child : mc.screen.children())
                if (child instanceof Button b && b.active && Set.of("Proceed", "I know what I'm doing!", "Yes").contains(b.getMessage().getString())) b.onPress();
            return;
        }
        if (ticks < 200 || ++wait < (step == 0 ? 300 : 50)) return;
        wait = 0;
        if (phase == 2) { second(mc); return; }
        switch (step++) {
            case 0 -> server(mc, p -> {
                var server = p.server; var level = server.overworld();
                p.setGameMode(GameType.SURVIVAL); level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DAYLIGHT).set(false, server);
                level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_WEATHER_CYCLE).set(false, server); level.setDayTime(6000); level.setWeatherParameters(120000, 0, false, false);
                p.getInventory().clearContent(); var store = GuildStore.get(server); var c = store.card(p.getUUID()); c.open(7); c.rank = GuildRules.Rank.D.ordinal(); store.setDirty();
                p.getInventory().add(new ItemStack(item("lightmanscurrency:coin_iron"), 3));
                LayoutJobs.budget(400); int queued = LayoutJobs.enqueue(server, RiftGate.CODE, false, null, 0, 0);
                check(queued == 1, "gate queued for building");
                p.teleportTo(level, 120.5, 91, 0.5, -90, 5);
            });
            case 1 -> { server(mc, p -> { ready = LayoutJobs.idle() && RiftGate.site(p.server.overworld()) != null; DungeonServer.forgetSite(); }); if (!ready) step--; }
            case 2 -> { check(ready, "gate built in the ring"); server(mc, p -> p.teleportTo(p.serverLevel(), 122.5, 92, 0.5, -90, 4)); }
            case 3 -> shot(mc, "01-gate-outside");
            case 4 -> server(mc, p -> { var a = RiftGate.site(p.server.overworld()).arrival(); p.teleportTo(p.serverLevel(), a[0], a[1], a[2], (float) a[3] + 180, 0); });
            case 5 -> shot(mc, "02-gate-hall-portal");
            case 6 -> server(mc, p -> { var f = RiftGate.site(p.server.overworld()).frame(); var pos = new BlockPos(f.x(0, RiftGate.DEPTH - 2), RiftGate.site(p.server.overworld()).y() + 3, f.z(0, RiftGate.DEPTH - 2));
                mc.execute(() -> mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(pos), Direction.WEST, pos, false))); });
            case 7 -> {}
            case 8 -> { check(mc.screen instanceof DungeonScreen, "right click on the portal opens the gate screen"); shot(mc, "03-gate-screen"); }
            case 9 -> server(mc, p -> { coinsBefore = GuildRules.value(GuildServer.coinsHeld(p)); for (int i = 0; i < 10; i++) p.getInventory().add(new ItemStack(Items.DIRT, 64));
                p.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE)); p.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD)); stacksBefore = stacks(p); });
            case 10 -> ClientDungeon.request("SOLO", 0, 1, 0);
            case 11 -> { server(mc, p -> { coinsAfter = GuildRules.value(GuildServer.coinsHeld(p)); var r = DungeonServer.runOf(p.getUUID()); state = r == null ? "none" : r.state.name(); }); }
            case 12 -> { ready = false; check(coinsBefore - coinsAfter == 10, "Hard entry burned 10 copper: " + coinsBefore + " → " + coinsAfter); check(!state.equals("none"), "run started: " + state); shot(mc, "04-rift-opening"); }
            case 13 -> { server(mc, p -> { var r = DungeonServer.runOf(p.getUUID()); ready = r != null && r.state == DungeonRun.State.ACTIVE && DungeonServer.inRift(p); }); if (!ready) step--; }
            case 14 -> { mc.setScreen(null); shot(mc, "05-crypt-entrance"); }
            case 15 -> server(mc, p -> { // closed run: blocks, pearls, /spawn, other teleports
                var below = p.blockPosition().below(); var before = p.level().getBlockState(below);
                p.gameMode.destroyBlock(below); check(p.level().getBlockState(below).equals(before), "a floor block cannot be broken");
                p.server.getCommands().performPrefixedCommand(p.createCommandSourceStack(), "spawn"); check(DungeonServer.inRift(p), "/spawn refused inside");
                p.teleportTo(p.server.overworld(), 0, 100, 0, 0, 0); check(DungeonServer.inRift(p), "teleport to another dimension refused");
                var pearl = new ItemStack(Items.ENDER_PEARL); p.setItemInHand(InteractionHand.MAIN_HAND, pearl);
                var use = new net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickItem(p, InteractionHand.MAIN_HAND); NeoForge.EVENT_BUS.post(use); check(use.isCanceled(), "ender pearl refused");
                p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            });
            case 16 -> { room(mc); if (!done) step--; }
            case 17 -> { server(mc, p -> { var r = DungeonServer.runOf(p.getUUID()); check(r != null && r.state == DungeonRun.State.VICTORY, "the Sakura Crypt is cleared"); }); shot(mc, "11-victory-chests"); }
            case 18 -> server(mc, p -> { var r = DungeonServer.runOf(p.getUUID()); var pos = r.chests.getFirst(); p.teleportTo(p.serverLevel(), pos.getX() - 2.5, pos.getY(), pos.getZ() + 0.5, -90, 30);
                mc.execute(() -> mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(pos), Direction.WEST, pos, false))); });
            case 19 -> {}
            case 20 -> { shot(mc, "12-personal-chest"); server(mc, p -> { var m = DungeonServer.runOf(p.getUUID()).member(p.getUUID());
                check(m.chest.countItem(DungeonItems.ESSENCE.get()) >= 2, "Rift Essence in the personal chest"); check(!m.chest.isEmpty(), "loot rolled"); check(DungeonStore.get(p.server).player(p.getUUID()).chests == 1, "one reward chest of three used today");
                check(DungeonStore.get(p.server).player(p.getUUID()).cleared.contains("sakura_crypt:HARD"), "first clear recorded"); }); }
            case 21 -> { mc.setScreen(null); server(mc, p -> p.server.getCommands().performPrefixedCommand(p.createCommandSourceStack(), "aocdungeon leave")); }
            case 22 -> server(mc, p -> { var s = RiftGate.site(p.server.overworld()); check(!DungeonServer.inRift(p) && s.inHall(p.getX(), p.getY(), p.getZ()), "back in the gate hall");
                check(p.getInventory().countItem(DungeonItems.ESSENCE.get()) >= 2, "chest loot handed over on leaving"); });
            case 23 -> { ready = false; shot(mc, "13-back-at-gate"); }
            case 24 -> { server(mc, p -> ready = DungeonServer.runs().isEmpty() && DungeonServer.wiping() == 0); if (!ready) step--; }
            case 25 -> { check(ready, "slot wiped after the run"); server(mc, p -> { p.getInventory().add(new ItemStack(item("lightmanscurrency:coin_iron"), 1)); coinsBefore = GuildRules.value(GuildServer.coinsHeld(p)); }); ready = false; ClientDungeon.request("SOLO", 0, 1, 0); }
            case 26 -> { server(mc, p -> { var r = DungeonServer.runOf(p.getUUID()); ready = r != null && r.state == DungeonRun.State.ACTIVE && DungeonServer.inRift(p); if (ready) guard(p); }); if (!ready) step--; }
            case 27 -> { System.out.println("AOC_DUNGEON_FLOW_PHASE1 " + (log.isEmpty() ? "ok" : String.join("; ", log)) + " coins=" + coinsBefore); mc.stop(); }
            default -> {}
        }
    }
    /** Drives the current room: kill its mobs, cross the trap, open the treasure; one death in the first fight room. */
    private int fights, deathChecked;
    private void room(Minecraft mc) {
        server(mc, p -> {
            var r = DungeonServer.runOf(p.getUUID()); if (r == null || r.state != DungeonRun.State.ACTIVE) { done = true; return; }
            var room = r.current(); var rift = DungeonServer.rift(p.server);
            if (!room.inside(p.getX() - r.ox, p.getZ() - r.oz)) { p.teleportTo(rift, r.x(room.x0() + 3), DungeonRules.FLOOR + 1, r.z(0), -90, 10); return; }
            switch (room.kind()) {
                case TRAP -> { if (kind != 2) { kind = 2; shotLater(mc, "07-arrow-trap"); return; } p.teleportTo(rift, r.x(room.finish() + 1), DungeonRules.FLOOR + 1, r.z(0), -90, 0); }
                case TREASURE -> { if (kind != 3) { kind = 3; var pos = r.treasureChest; if (pos == null) return;
                    mc.execute(() -> mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(pos), Direction.WEST, pos, false))); shotLater(mc, "09-treasure-room"); } }
                default -> {
                    if (r.mobs.isEmpty()) return;
                    if (room.kind() == DungeonLayout.Kind.FIGHT && deathChecked == 0) { deathChecked = 1; shotLater(mc, "06-fight-wave");
                        int before = stacks(p); var m = r.member(p.getUUID()); int lives = m.lives;
                        p.hurt(p.damageSources().fellOutOfWorld(), 1000);
                        int lost = before - stacks(p);
                        check(p.isAlive() && DungeonServer.inRift(p), "a death in the rift is not a real death");
                        check(lost == DungeonRules.lossCount(before, 20), "death took 20% of stacks rounded up: " + lost + " of " + before);
                        check(p.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE) && p.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.SHIELD), "armour and off hand kept");
                        check(m.lives == lives - 1, "one life spent"); guard(p); return; }
                    if (room.kind() == DungeonLayout.Kind.BOSS && kind != 5) { kind = 5; shotLater(mc, "10-boss-arena"); return; }
                    if (room.kind() == DungeonLayout.Kind.ELITE && kind != 4) { kind = 4; shotLater(mc, "08-elite-room"); return; }
                    var hit = p.damageSources().playerAttack(p);
                    for (var u : List.copyOf(r.mobs)) if (rift.getEntity(u) instanceof Mob mob) for (int i = 0; i < 400 && mob.isAlive(); i++) { mob.invulnerableTime = 0; mob.hurt(hit, 1000); }
                    fights++;
                }
            }
        });
    }
    /** Phase 2: the client stopped inside a run; the world reopened as after a server restart. */
    private void second(Minecraft mc) {
        switch (step++) {
            case 0 -> server(mc, p -> { var s = RiftGate.site(p.server.overworld());
                check(!DungeonServer.inRift(p) && s != null && s.inHall(p.getX(), p.getY(), p.getZ()), "after a restart the player stands at the gate");
                check(DungeonServer.runs().isEmpty() && DungeonStore.get(p.server).runs.isEmpty(), "no run survives a restart");
                coinsAfter = GuildRules.value(GuildServer.coinsHeld(p)); check(coinsAfter == Long.getLong("animeoncraft.dungeonFlowCoins", -1) , "entry handed back: " + coinsAfter);
                check(p.gameMode.getGameModeForPlayer() == GameType.SURVIVAL, "own game mode"); });
            case 1 -> shot(mc, "14-after-restart-at-gate");
            case 2 -> { server(mc, p -> ready = DungeonServer.wiping() == 0 && DungeonStore.get(p.server).dirtySlots.isEmpty()); if (!ready) step--; }
            case 3 -> { check(ready, "used slot wiped at start"); System.out.println("AOC_DUNGEON_FLOW_DONE " + (log.isEmpty() ? "ok" : String.join("; ", log))); mc.stop(); }
            default -> {}
        }
    }
    /** The review player only watches the rooms after the death check: Hard has one life. */
    private static void guard(ServerPlayer p) {
        for (var e : List.of(net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE, net.minecraft.world.effect.MobEffects.REGENERATION, net.minecraft.world.effect.MobEffects.SATURATION, net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE))
            p.addEffect(new net.minecraft.world.effect.MobEffectInstance(e, 20 * 600, 4, false, false));
    }
    private static int stacks(ServerPlayer p) { int n = 0; for (int i = 0; i < DungeonRules.MAIN_SLOTS; i++) if (!p.getInventory().items.get(i).isEmpty()) n++; return n; }
    private static net.minecraft.world.item.Item item(String id) { return BuiltInRegistries.ITEM.get(ResourceLocation.parse(id)); }
    private void check(boolean ok, String what) { System.out.println("AOC_DUNGEON_FLOW " + (ok ? "PASS " : "FAIL ") + what); if (!ok) log.add(what); }
    private static void server(Minecraft mc, java.util.function.Consumer<ServerPlayer> action) {
        var server = Objects.requireNonNull(mc.getSingleplayerServer()); var id = mc.player.getUUID();
        server.execute(() -> action.accept(server.getPlayerList().getPlayer(id)));
    }
    private static void shotLater(Minecraft mc, String name) { mc.execute(() -> new Thread(() -> { try { Thread.sleep(1500); } catch (InterruptedException ignored) {} mc.execute(() -> shot(mc, name)); }).start()); }
    private static void shot(Minecraft mc, String name) { Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), ignored -> {}); System.out.println("AOC_DUNGEON_FLOW_SHOT " + name); }
}
