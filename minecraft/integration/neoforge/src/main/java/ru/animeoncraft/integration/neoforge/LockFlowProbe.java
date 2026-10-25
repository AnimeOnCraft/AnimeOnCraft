package ru.animeoncraft.integration.neoforge;

import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * Disposable review client for personal container locks: singleplayer copy of a layout-test world, survival, the real
 * player locks storages with sneak + empty hand, a stand-in clanmate (fake player) tries to open, break, extend and drain
 * them; screenshots of every visible step. Row of storages north of the guild square at z 126.
 */
final class LockFlowProbe {
    private int ticks, step, wait; private final List<String> log = new ArrayList<>();
    private static final BlockPos CHEST = new BlockPos(-2, 91, 126), EXTEND = new BlockPos(-3, 91, 126), HOPPER = new BlockPos(-2, 90, 126),
        BARREL = new BlockPos(0, 91, 126), STORAGE = new BlockPos(2, 91, 126), CONTROLLER = new BlockPos(3, 91, 126);
    private static final UUID MATE = UUID.fromString("00000000-0000-4000-8000-0000000000bb"), AKIRA = UUID.fromString("00000000-0000-4000-8000-0000000000cc");
    static void register() { new LockFlowProbe(); }
    private LockFlowProbe() { NeoForge.EVENT_BUS.addListener(this::tick); }
    private static boolean enabled() { return Boolean.getBoolean("animeoncraft.lockFlowProbe") && Minecraft.getInstance().gameDirectory.getAbsolutePath().contains("lock-flow-"); }

    private void tick(ClientTickEvent.Post event) {
        if (!enabled()) return;
        var mc = Minecraft.getInstance(); ticks++;
        if (mc.player == null) {
            if (mc.screen != null && ticks % 30 == 0) for (var child : mc.screen.children())
                if (child instanceof Button b && b.active && Set.of("Proceed", "I know what I'm doing!", "Yes").contains(b.getMessage().getString())) b.onPress();
            return;
        }
        if (ticks < 200 || ++wait < delay()) return;
        wait = 0;
        switch (step++) {
            case 0 -> server(mc, p -> {
                var level = p.serverLevel(); var server = p.server;
                level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DAYLIGHT).set(false, server);
                level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_WEATHER_CYCLE).set(false, server);
                level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING).set(false, server);
                level.setDayTime(5000); level.setWeatherParameters(120000, 0, false, false);
                noCheats(server); p.setGameMode(GameType.SURVIVAL); p.getInventory().clearContent();
                p.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE, 20 * 600, 4, false, false));
                for (int x = -6; x <= 6; x++) for (int z = 122; z <= 131; z++) {
                    level.setBlockAndUpdate(new BlockPos(x, 90, z), Blocks.STONE_BRICKS.defaultBlockState());
                    for (int y = 91; y <= 95; y++) level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                }
                level.setBlockAndUpdate(CHEST, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.SOUTH));
                var chest = (net.minecraft.world.level.block.entity.ChestBlockEntity) level.getBlockEntity(CHEST);
                chest.setItem(0, new ItemStack(Items.DIAMOND, 5)); chest.setItem(1, new ItemStack(Items.BREAD, 12)); chest.setItem(2, new ItemStack(Items.GOLD_INGOT, 9));
                level.setBlockAndUpdate(BARREL, Blocks.BARREL.defaultBlockState().setValue(net.minecraft.world.level.block.BarrelBlock.FACING, Direction.UP));
                ((net.minecraft.world.level.block.entity.BarrelBlockEntity) level.getBlockEntity(BARREL)).setItem(0, new ItemStack(Items.COAL, 32));
                level.setBlockAndUpdate(STORAGE, block("sophisticatedstorage:chest").defaultBlockState());
                var handler = level.getCapability(Capabilities.ItemHandler.BLOCK, STORAGE, null);
                check(handler != null && handler.insertItem(0, new ItemStack(Items.EMERALD, 7), false).isEmpty(), "emeralds put into the Sophisticated chest");
                level.setBlockAndUpdate(CONTROLLER, block("sophisticatedstorage:controller").defaultBlockState());
                try { var be = level.getBlockEntity(CONTROLLER); be.getClass().getMethod("searchAndAddBoundables").invoke(be); } catch (ReflectiveOperationException failure) { check(false, "controller search: " + failure); }
                p.teleportTo(level, 0.5, 91, 130.5, 180, 14);
                System.out.println("AOC_LOCK_FLOW op=" + p.hasPermissions(2));
            });
            case 1 -> server(mc, p -> {
                var level = p.serverLevel(); var mate = mate(level);
                check(!p.hasPermissions(2), "the player is not an operator (admin bypass off for the review)");
                check(opens(mate, CHEST), "before locking a clanmate opens the chest");
                check(controllerEmeralds(level) == 7, "before locking the controller sees the 7 emeralds");
            });
            case 2 -> shot(mc, "01-storages-unlocked");
            // the real player locks the chest, the barrel and the Sophisticated chest: sneak + right click with empty hands
            case 3 -> { look(mc, CHEST); mc.options.keyShift.setDown(true); }
            case 4 -> click(mc, CHEST);
            case 5 -> { server(mc, p -> { var lock = ContainerLocks.lock(p.serverLevel(), CHEST); check(lock != null && lock.owner().equals(p.getUUID()), "sneak + empty hand locked the chest"); }); check(mc.screen == null, "locking does not open the chest"); shot(mc, "02-chest-locked"); }
            case 6 -> look(mc, BARREL);
            case 7 -> click(mc, BARREL);
            case 8 -> look(mc, STORAGE);
            case 9 -> click(mc, STORAGE);
            case 10 -> { mc.options.keyShift.setDown(false); server(mc, p -> {
                check(ContainerLocks.lock(p.serverLevel(), BARREL) != null && ContainerLocks.lock(p.serverLevel(), STORAGE) != null, "barrel and Sophisticated chest locked");
            }); look(mc, CHEST); }
            case 11 -> click(mc, CHEST);
            case 12 -> { check(mc.screen instanceof net.minecraft.client.gui.screens.inventory.ContainerScreen, "the owner still opens the locked chest"); shot(mc, "03-owner-opens"); }
            case 13 -> { mc.player.closeContainer(); server(mc, p -> {
                var level = p.serverLevel(); var mate = mate(level);
                check(!opens(mate, CHEST) && !opens(mate, BARREL) && !opens(mate, STORAGE), "a clanmate opens none of the locked storages");
                mate.gameMode.destroyBlock(CHEST); mate.gameMode.destroyBlock(BARREL);
                check(level.getBlockState(CHEST).is(Blocks.CHEST) && level.getBlockState(BARREL).is(Blocks.BARREL), "a clanmate cannot break them");
                check(!place(mate, EXTEND) && level.getBlockState(EXTEND).isAir(), "a clanmate cannot extend the locked chest into a double chest");
                check(controllerEmeralds(level) == 0, "an unlocked controller no longer sees the locked Sophisticated chest");
                level.setBlockAndUpdate(HOPPER, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.DOWN));
            }); }
            case 14 -> server(mc, p -> {
                var level = p.serverLevel(); var hopper = (net.minecraft.world.level.block.entity.HopperBlockEntity) level.getBlockEntity(HOPPER);
                check(hopper.isEmpty() && ((net.minecraft.world.Container) level.getBlockEntity(CHEST)).countItem(Items.DIAMOND) == 5, "a foreign hopper under the locked chest pulls nothing");
                check(level.getCapability(Capabilities.ItemHandler.BLOCK, CHEST, null) == null, "pipes and other mods see no inventory on the locked chest");
                check(place(p, EXTEND) && ContainerLocks.lock(level, EXTEND) != null, "the owner extends the chest and the new half is locked too");
                ContainerLocks.toggle(p, level, HOPPER); ContainerLocks.toggle(p, level, CONTROLLER);
                check(ContainerLocks.trusted(() -> controllerEmeralds(level)) == 7, "the owner's own locked controller sees the owner's chest");
            });
            case 15 -> server(mc, p -> {
                var level = p.serverLevel(); var hopper = (net.minecraft.world.level.block.entity.HopperBlockEntity) level.getBlockEntity(HOPPER);
                check(!hopper.isEmpty(), "the owner's own locked hopper drains the owner's chest");
                var mate = mate(level); var at = List.of(CHEST, EXTEND);
                for (var pos : at) ContainerLocks.lock(level, pos).trusted().put(MATE, "AocClanmate");
                check(opens(mate, CHEST), "a trusted friend opens the chest");
                for (var pos : at) ContainerLocks.lock(level, pos).trusted().remove(MATE);
                check(!opens(mate, CHEST), "access is gone after untrust");
                // hand the chest to another player so the camera shows what a clanmate sees
                var akira = new ContainerLocks.Lock(AKIRA, "Akira", new LinkedHashMap<>());
                ContainerLocks.reassign(level, CHEST, akira); ContainerLocks.reassign(level, EXTEND, akira);
            });
            case 16 -> click(mc, CHEST);
            case 17 -> { check(mc.screen == null, "a chest locked by Akira does not open for the player"); shot(mc, "04-clanmate-sees-lock"); }
            case 18 -> mc.player.connection.sendCommand("lock info");
            case 19 -> shot(mc, "05-lock-info");
            case 20 -> server(mc, p -> {
                var level = p.serverLevel(); var mate = mate(level);
                level.explode(null, -0.5, 91.5, 124.5, 4f, Level.ExplosionInteraction.TNT);
                check(level.getBlockState(CHEST).is(Blocks.CHEST) && level.getBlockState(BARREL).is(Blocks.BARREL) && level.getBlockEntity(STORAGE) != null, "locked storages survive an explosion");
            });
            case 21 -> look(mc, BARREL);
            case 22 -> shot(mc, "06-after-explosion");
            default -> { if (step == 24) { System.out.println("AOC_LOCK_FLOW_DONE " + (log.isEmpty() ? "ok" : String.join("; ", log))); mc.stop(); } }
        }
    }

    private static int controllerEmeralds(ServerLevel level) {
        var handler = level.getCapability(Capabilities.ItemHandler.BLOCK, CONTROLLER, null); if (handler == null) return -1; int n = 0;
        for (int i = 0; i < handler.getSlots(); i++) if (handler.getStackInSlot(i).is(Items.EMERALD)) n += handler.getStackInSlot(i).getCount();
        return n;
    }
    private static FakePlayer mate(ServerLevel level) {
        var mate = net.neoforged.neoforge.common.util.FakePlayerFactory.get(level, new com.mojang.authlib.GameProfile(MATE, "AocClanmate"));
        mate.setGameMode(GameType.SURVIVAL); mate.moveTo(-2.5, 91, 129.5, 180, 20); mate.setShiftKeyDown(false); mate.getInventory().clearContent(); return mate;
    }
    /** The server-side right click a player sends; cancelled means the storage stays closed. */
    private static boolean opens(ServerPlayer player, BlockPos pos) {
        var click = new net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock(player, InteractionHand.MAIN_HAND, pos, new BlockHitResult(Vec3.atCenterOf(pos), Direction.SOUTH, pos, false));
        NeoForge.EVENT_BUS.post(click); return !click.isCanceled();
    }
    /** Places a chest on the floor next to the locked chest, facing like it (so the two would join). */
    private static boolean place(ServerPlayer player, BlockPos at) {
        var old = player.getYRot(); player.setYRot(180); var stack = new ItemStack(Items.CHEST); player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        var result = stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(at.below()).add(0, 0.5, 0), Direction.UP, at.below(), false)));
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY); player.setYRot(old);
        return result.consumesAction() && player.serverLevel().getBlockState(at).is(Blocks.CHEST);
    }
    /** Singleplayer owners are operators while cheats are on; the review needs an ordinary player. */
    private static void noCheats(net.minecraft.server.MinecraftServer server) {
        try {
            var data = (net.minecraft.world.level.storage.PrimaryLevelData) server.getWorldData(); var field = net.minecraft.world.level.storage.PrimaryLevelData.class.getDeclaredField("settings");
            field.setAccessible(true); var s = (net.minecraft.world.level.LevelSettings) field.get(data);
            field.set(data, new net.minecraft.world.level.LevelSettings(s.levelName(), s.gameType(), s.hardcore(), s.difficulty(), false, s.gameRules(), s.getDataConfiguration()));
        } catch (ReflectiveOperationException failure) { System.out.println("AOC_LOCK_FLOW cheats stay on: " + failure); }
    }
    private static net.minecraft.world.level.block.Block block(String id) { return BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id)); }
    private static void look(Minecraft mc, BlockPos pos) { server(mc, p -> p.teleportTo(p.serverLevel(), pos.getX() + 0.5, 91, 130.5, 180, 14)); }
    private static void click(Minecraft mc, BlockPos pos) { mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(pos).add(0, 0, 0.5), Direction.SOUTH, pos, false)); }
    /** Shots right after a click still show the action bar message; teleports settle before the next click. */
    private int delay() { return step == 0 ? 300 : Set.of(5, 12, 17, 19).contains(step) ? 15 : 40; }
    private void check(boolean ok, String what) { System.out.println("AOC_LOCK_FLOW " + (ok ? "PASS " : "FAIL ") + what); if (!ok) log.add(what); }
    private static void server(Minecraft mc, java.util.function.Consumer<ServerPlayer> action) {
        var server = Objects.requireNonNull(mc.getSingleplayerServer()); var id = mc.player.getUUID();
        server.execute(() -> action.accept(server.getPlayerList().getPlayer(id)));
    }
    private static void shot(Minecraft mc, String name) { Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), ignored -> {}); System.out.println("AOC_LOCK_FLOW_SHOT " + name); }
}
