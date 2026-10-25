package ru.animeoncraft.integration.neoforge;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDestroyBlockEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * Personal locks on storage blocks: only the owner, players the owner trusted, operators and — in an active war
 * window on the defender's land — raiders may open a locked block. Clan membership grants nothing here.
 * Sneak + right click with both hands empty toggles a lock; /lock (/замок) does the same and manages trust.
 * Automation reaches a locked block only from a block locked by the same owner (hoppers, Create funnels,
 * Sophisticated controllers); every other item-handler query for a locked block gets nothing.
 */
public final class ContainerLocks {
    static final int MAX_LOCKS = 400, MAX_TRUSTED = 32;
    record Lock(UUID owner, String name, Map<UUID, String> trusted) {
        boolean opens(UUID player) { return owner.equals(player) || trusted.containsKey(player); }
    }
    private static final ThreadLocal<int[]> BYPASS = ThreadLocal.withInitial(() -> new int[1]);
    private static volatile Store cache; private static volatile MinecraftServer cacheServer;
    private ContainerLocks() {}

    static void register(IEventBus events) {
        events.addListener(EventPriority.HIGHEST, ContainerLocks::use);
        events.addListener(EventPriority.HIGHEST, ContainerLocks::breaking);
        events.addListener(EventPriority.LOWEST, false, ContainerLocks::broken);
        events.addListener(EventPriority.LOW, false, ContainerLocks::placing);
        events.addListener(ContainerLocks::explosion);
        events.addListener(ContainerLocks::destroyed);
        events.addListener(ContainerLocks::commands);
        events.addListener(ContainerLocks::stopped);
    }

    // ---- queries (also used by mixins; cheap when nothing is locked) ----

    static Lock lock(LevelAccessor level, BlockPos pos) {
        if (!(level instanceof ServerLevel world)) return null;
        var store = store(world); var locks = store.byLevel.get(key(world)); if (locks == null || locks.isEmpty()) return null;
        var lock = locks.get(pos.asLong()); if (lock == null) return null;
        // A lock outlives its block only until somebody looks: removed blocks (commands, other mods) drop the lock.
        if (world.getServer().isSameThread() && world.isLoaded(pos) && world.getBlockEntity(pos) == null) { locks.remove(pos.asLong()); store.setDirty(); return null; }
        return lock;
    }
    static UUID owner(LevelAccessor level, BlockPos pos) { var lock = lock(level, pos); return lock == null ? null : lock.owner(); }
    /** True when an automation block at {@code actor} may touch {@code target}: the target is unlocked or locked by the actor's owner. */
    public static boolean canReach(LevelAccessor level, BlockPos actor, BlockPos target) {
        var locked = owner(level, target); return locked == null || locked.equals(owner(level, actor));
    }
    /** Item-handler capability of a locked block is hidden from every caller except the approved automation paths. */
    public static boolean capabilityHidden(Level level, BlockPos pos) { return BYPASS.get()[0] == 0 && owner(level, pos) != null; }
    /** Runs an approved automation path (it has checked {@link #canReach}) with locked capabilities visible. */
    public static <T> T trusted(Supplier<T> action) { var depth = BYPASS.get(); depth[0]++; try { return action.get(); } finally { depth[0]--; } }
    public static void trusted(Runnable action) { trusted(() -> { action.run(); return null; }); }
    /** Who may see or take the contents: owner, trusted players, operators, raiders in an active war window. */
    public static boolean canOpen(Player player, LevelAccessor level, BlockPos pos) {
        var lock = lock(level, pos); return lock == null || allowed(player, level, pos, lock);
    }
    private static boolean allowed(Player player, LevelAccessor level, BlockPos pos, Lock lock) {
        if (player == null) return false;
        if (lock.opens(player.getUUID())) return true;
        if (player instanceof ServerPlayer p && p.hasPermissions(2) && !(p instanceof net.neoforged.neoforge.common.util.FakePlayer)) return true;
        return TerritoryProtection.raidAccess(level, player, pos);
    }
    private static boolean admin(Player player) { return player instanceof ServerPlayer p && p.hasPermissions(2) && !(p instanceof net.neoforged.neoforge.common.util.FakePlayer); }

    /** Storage blocks: vanilla containers, anything exposing an item handler, and Create logistics blocks (so owners can lock their own funnels). */
    static boolean lockable(ServerLevel world, BlockPos pos) {
        var be = world.getBlockEntity(pos); if (be == null) return false;
        String namespace = BuiltInRegistries.BLOCK.getKey(world.getBlockState(pos).getBlock()).getNamespace();
        if (namespace.equals("lightmanscurrency")) return false; // shops have their own owners and must stay open to customers
        if (be instanceof Container || namespace.equals("create")) return true;
        return trusted(() -> world.getCapability(Capabilities.ItemHandler.BLOCK, pos, null)) != null;
    }
    private static BlockPos partner(BlockState state, BlockPos pos) {
        if (!(state.getBlock() instanceof ChestBlock) || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) return null;
        return pos.relative(ChestBlock.getConnectedDirection(state));
    }

    // ---- player actions ----

    static void use(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || !(event.getLevel() instanceof ServerLevel world)) return;
        var pos = event.getPos();
        if (p.isShiftKeyDown() && p.getMainHandItem().isEmpty() && p.getOffhandItem().isEmpty() && !(p instanceof net.neoforged.neoforge.common.util.FakePlayer)
            && (lock(world, pos) != null || lockable(world, pos))) {
            event.setCanceled(true); event.setCancellationResult(InteractionResult.SUCCESS);
            if (event.getHand() == InteractionHand.MAIN_HAND) toggle(p, world, pos);
            return;
        }
        var lock = lock(world, pos); if (lock == null) return;
        if (allowed(p, world, pos, lock)) {
            if (!lock.opens(p.getUUID()) && admin(p)) p.displayClientMessage(Component.literal("Замок игрока " + lock.name() + " открыт правами администратора."), true);
            else if (!lock.opens(p.getUUID())) p.displayClientMessage(Component.literal("Рейд: замок игрока " + lock.name() + " взломан."), true);
            return;
        }
        event.setCanceled(true); event.setCancellationResult(InteractionResult.FAIL);
        if (event.getHand() == InteractionHand.MAIN_HAND) {
            p.displayClientMessage(Component.literal("Заперто. Владелец: " + lock.name()), true);
            world.playSound(null, pos, SoundEvents.CHEST_LOCKED, SoundSource.BLOCKS, 1, 1);
        }
    }

    static int toggle(ServerPlayer p, ServerLevel world, BlockPos pos) {
        var store = store(world); var lock = lock(world, pos);
        if (lock != null) {
            if (!lock.owner().equals(p.getUUID()) && !admin(p)) { p.displayClientMessage(Component.literal("Это замок игрока " + lock.name() + ". Снять его может только владелец."), true); return 0; }
            store.remove(world, pos); var other = partner(world.getBlockState(pos), pos); if (other != null) store.remove(world, other);
            invalidate(world, pos, other);
            p.displayClientMessage(Component.literal("Замок снят" + (lock.owner().equals(p.getUUID()) ? "." : " (владелец " + lock.name() + ").")), true);
            world.playSound(null, pos, SoundEvents.IRON_TRAPDOOR_OPEN, SoundSource.BLOCKS, 1, 1);
            return 1;
        }
        if (!lockable(world, pos)) { p.displayClientMessage(Component.literal("Замок ставится только на сундуки, бочки и другие хранилища."), true); return 0; }
        if (p.server.isDedicatedServer() && !GameIdentity.PLAYERS.containsKey(p.getUUID())) { p.displayClientMessage(Component.literal("Войдите через лаунчер."), true); return 0; }
        if (!TerritoryProtection.lockAllowed(world, p, pos)) { p.displayClientMessage(Component.literal("Здесь замок не поставить: это чужая или защищённая земля."), true); return 0; }
        if (store.count(p.getUUID()) >= MAX_LOCKS && !admin(p)) { p.displayClientMessage(Component.literal("У вас уже " + MAX_LOCKS + " замков. Снимите ненужные."), true); return 0; }
        var created = new Lock(p.getUUID(), p.getGameProfile().getName(), new LinkedHashMap<>());
        store.put(world, pos, created); var other = partner(world.getBlockState(pos), pos); if (other != null) store.put(world, other, created);
        invalidate(world, pos, other);
        p.displayClientMessage(Component.literal("Заперто. Открыть можете только вы. Снять: присесть + пустая рука."), true);
        world.playSound(null, pos, SoundEvents.IRON_TRAPDOOR_CLOSE, SoundSource.BLOCKS, 1, 1);
        return 1;
    }
    /** Hands a lock to another owner (review probes). */
    static void reassign(ServerLevel world, BlockPos pos, Lock lock) { store(world).put(world, pos, lock); world.invalidateCapabilities(pos); }
    /** Cached capabilities (Create funnels, pipes) are recomputed after a lock changes. */
    private static void invalidate(ServerLevel world, BlockPos pos, BlockPos other) { world.invalidateCapabilities(pos); if (other != null) world.invalidateCapabilities(other); }

    static void breaking(BlockEvent.BreakEvent event) {
        var lock = lock(event.getLevel(), event.getPos()); if (lock == null) return;
        var player = event.getPlayer();
        if (player != null && (lock.owner().equals(player.getUUID()) || admin(player) || TerritoryProtection.raidAccess(event.getLevel(), player, event.getPos()))) return;
        event.setCanceled(true);
        if (player instanceof ServerPlayer p) p.displayClientMessage(Component.literal("Это хранилище заперто игроком " + lock.name() + "."), true);
    }
    static void broken(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof ServerLevel world && lock(world, event.getPos()) != null) store(world).remove(world, event.getPos());
    }
    /** A chest that would join a locked chest into a double chest takes the owner's lock or is refused. */
    static void placing(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel world)) return;
        var store = store(world); var pos = event.getPos();
        if (store.get(world, pos) != null) store.remove(world, pos); // a stale lock of a block that was replaced
        var other = partner(event.getPlacedBlock(), pos); if (other == null) return;
        var lock = lock(world, other); if (lock == null) return;
        if (event.getEntity() instanceof Player p && lock.owner().equals(p.getUUID())) { store.put(world, pos, lock); world.invalidateCapabilities(pos); return; }
        event.setCanceled(true);
        if (event.getEntity() instanceof ServerPlayer p) p.displayClientMessage(Component.literal("Нельзя пристроить сундук к чужому запертому сундуку."), true);
    }
    static void explosion(ExplosionEvent.Detonate event) { event.getAffectedBlocks().removeIf(pos -> lock(event.getLevel(), pos) != null); }
    static void destroyed(LivingDestroyBlockEvent event) { if (lock(event.getEntity().level(), event.getPos()) != null) event.setCanceled(true); }
    static void stopped(ServerStoppedEvent event) { cache = null; cacheServer = null; }

    // ---- commands: /lock, /lock info, /lock trust <player>, /lock untrust <player>, /lock list ----

    static void commands(RegisterCommandsEvent event) {
        for (String name : List.of("lock", "замок")) event.getDispatcher().register(Commands.literal(name)
            .executes(c -> target(c, (p, w, pos) -> toggle(p, w, pos)))
            .then(Commands.literal("info").executes(c -> target(c, ContainerLocks::info)))
            .then(Commands.literal("list").executes(ContainerLocks::list))
            .then(Commands.literal("trust").then(Commands.argument("player", EntityArgument.player()).executes(c -> trust(c, true))))
            .then(Commands.literal("untrust").then(Commands.argument("player", EntityArgument.player()).executes(c -> trust(c, false)))));
    }
    private interface Action { int run(ServerPlayer p, ServerLevel world, BlockPos pos) throws CommandSyntaxException; }
    private static int target(CommandContext<CommandSourceStack> c, Action action) throws CommandSyntaxException {
        var p = c.getSource().getPlayerOrException();
        var hit = p.pick(p.blockInteractionRange(), 1.0f, false);
        if (hit.getType() != HitResult.Type.BLOCK) { c.getSource().sendFailure(Component.literal("Посмотрите на хранилище.")); return 0; }
        return action.run(p, p.serverLevel(), ((BlockHitResult) hit).getBlockPos());
    }
    private static int info(ServerPlayer p, ServerLevel world, BlockPos pos) {
        var lock = lock(world, pos);
        if (lock == null) { p.sendSystemMessage(Component.literal(lockable(world, pos) ? "Не заперто. Запереть: присесть + ПКМ пустой рукой или /lock." : "Это не хранилище.")); return 1; }
        var names = String.join(", ", lock.trusted().values());
        p.sendSystemMessage(Component.literal("Владелец: " + lock.name() + (names.isEmpty() ? ". Доступ больше ни у кого." : ". Доверены: " + names + ".")));
        return 1;
    }
    private static int list(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        var p = c.getSource().getPlayerOrException(); var rows = store(p.serverLevel()).of(p.getUUID());
        p.sendSystemMessage(Component.literal("Ваших замков: " + rows.size() + " из " + MAX_LOCKS + "."));
        rows.stream().limit(10).forEach(row -> p.sendSystemMessage(Component.literal(" • " + row)));
        return rows.size();
    }
    private static int trust(CommandContext<CommandSourceStack> c, boolean add) throws CommandSyntaxException {
        var friend = EntityArgument.getPlayer(c, "player");
        return target(c, (p, world, pos) -> {
            var lock = lock(world, pos);
            if (lock == null) { p.sendSystemMessage(Component.literal("Это хранилище не заперто.")); return 0; }
            if (!lock.owner().equals(p.getUUID()) && !admin(p)) { p.sendSystemMessage(Component.literal("Доступ раздаёт только владелец: " + lock.name() + ".")); return 0; }
            if (add && lock.trusted().size() >= MAX_TRUSTED) { p.sendSystemMessage(Component.literal("Не больше " + MAX_TRUSTED + " доверенных на один замок.")); return 0; }
            var other = partner(world.getBlockState(pos), pos); var store = store(world);
            for (var at : other == null ? List.of(pos) : List.of(pos, other)) {
                var l = store.get(world, at); if (l == null) continue;
                if (add) l.trusted().put(friend.getUUID(), friend.getGameProfile().getName()); else l.trusted().remove(friend.getUUID());
            }
            store.setDirty();
            p.sendSystemMessage(Component.literal(add ? "" + friend.getGameProfile().getName() + " теперь может открывать это хранилище." : friend.getGameProfile().getName() + " больше не может открывать это хранилище."));
            return 1;
        });
    }

    // ---- storage: SavedData "animeoncraft-locks" in the overworld, part of the ordinary world backup ----

    private static String key(Level level) { return level.dimension().location().toString(); }
    private static Store store(ServerLevel world) {
        var server = world.getServer(); var current = cache;
        if (current != null && cacheServer == server) return current;
        current = server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(Store::new, Store::load), "animeoncraft-locks");
        cache = current; cacheServer = server; return current;
    }
    static final class Store extends SavedData {
        final Map<String, Map<Long, Lock>> byLevel = new ConcurrentHashMap<>();
        Lock get(Level level, BlockPos pos) { var locks = byLevel.get(key(level)); return locks == null ? null : locks.get(pos.asLong()); }
        void put(Level level, BlockPos pos, Lock lock) { byLevel.computeIfAbsent(key(level), k -> new ConcurrentHashMap<>()).put(pos.asLong(), lock); setDirty(); }
        void remove(Level level, BlockPos pos) { var locks = byLevel.get(key(level)); if (locks != null && locks.remove(pos.asLong()) != null) setDirty(); }
        int count(UUID owner) { int n = 0; for (var locks : byLevel.values()) for (var lock : locks.values()) if (lock.owner().equals(owner)) n++; return n; }
        List<String> of(UUID owner) {
            var rows = new ArrayList<String>();
            for (var e : byLevel.entrySet()) for (var l : e.getValue().entrySet()) if (l.getValue().owner().equals(owner)) {
                var pos = BlockPos.of(l.getKey()); rows.add(pos.getX() + " " + pos.getY() + " " + pos.getZ() + (e.getKey().equals("minecraft:overworld") ? "" : " (" + e.getKey() + ")"));
            }
            return rows;
        }
        static Store load(CompoundTag tag, HolderLookup.Provider registries) {
            var store = new Store(); var rows = tag.getList("locks", Tag.TAG_COMPOUND);
            for (int i = 0; i < rows.size(); i++) {
                var row = rows.getCompound(i); if (!row.hasUUID("owner")) continue;
                var trusted = new LinkedHashMap<UUID, String>(); var list = row.getList("trusted", Tag.TAG_COMPOUND);
                for (int t = 0; t < list.size() && t < MAX_TRUSTED; t++) { var f = list.getCompound(t); if (f.hasUUID("id")) trusted.put(f.getUUID("id"), f.getString("name")); }
                store.byLevel.computeIfAbsent(row.getString("level"), k -> new ConcurrentHashMap<>()).put(row.getLong("pos"), new Lock(row.getUUID("owner"), row.getString("name"), trusted));
            }
            return store;
        }
        @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            var rows = new ListTag();
            for (var e : byLevel.entrySet()) for (var l : e.getValue().entrySet()) {
                var row = new CompoundTag(); var lock = l.getValue();
                row.putString("level", e.getKey()); row.putLong("pos", l.getKey()); row.putUUID("owner", lock.owner()); row.putString("name", lock.name());
                var list = new ListTag(); for (var f : lock.trusted().entrySet()) { var t = new CompoundTag(); t.putUUID("id", f.getKey()); t.putString("name", f.getValue()); list.add(t); }
                row.put("trusted", list); rows.add(row);
            }
            tag.put("locks", rows); tag.putInt("version", 1);
            return tag;
        }
    }
}
