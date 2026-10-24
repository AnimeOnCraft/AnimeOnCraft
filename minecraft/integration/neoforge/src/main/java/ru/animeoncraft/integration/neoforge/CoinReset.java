package ru.animeoncraft.integration.neoforge;

import com.mojang.serialization.Codec;
import java.util.*;
import java.util.function.Supplier;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * Coins come only from the guild. Reset v1 takes away every coin earned before, lazily as things load (a player on login, a chunk's
 * containers, dropped items and chest minecarts, bank accounts and shop cash once), and gives each player a starting purse of 64 copper.
 * What was wiped or created after the reset is marked and never touched again; mobs never drop coins.
 */
final class CoinReset {
    private CoinReset() {}
    static final String TAG = "aoc_coin_reset_v1";
    static final long START_COPPER = GuildRules.START_COPPER;
    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, "animeoncraft");
    /** Set on a chunk once its containers were wiped, or on a chunk generated after the reset. */
    static final Supplier<AttachmentType<Boolean>> CHUNK_DONE = ATTACHMENTS.register("coin_reset_v1", () -> AttachmentType.builder(() -> true).serialize(Codec.BOOL).build());
    private record Pending(ServerLevel level, ChunkPos pos) {}
    private static final ArrayDeque<Pending> chunks = new ArrayDeque<>();

    static void register(IEventBus bus) { ATTACHMENTS.register(bus); }
    private static boolean enabled() { return ModList.get().isLoaded("lightmanscurrency"); }
    static boolean coin(ItemStack s) { return !s.isEmpty() && GuildRules.coinId(BuiltInRegistries.ITEM.getKey(s.getItem()).toString()); }

    /** Removes coin stacks from a container and from the wallets, shulker boxes and bundles in it; returns the coin items removed. */
    static int clean(Container c) {
        int removed = 0;
        for (int i = 0; i < c.getContainerSize(); i++) {
            var s = c.getItem(i); if (s.isEmpty()) continue;
            if (coin(s)) { removed += s.getCount(); c.setItem(i, ItemStack.EMPTY); }
            else { int n = nested(s, 0); if (n > 0) { removed += n; c.setItem(i, s); } }
        }
        if (removed > 0) c.setChanged();
        return removed;
    }
    /** Coins inside one stack (it is changed in place). */
    static int nested(ItemStack s, int depth) {
        if (s.isEmpty() || depth > 3) return 0;
        int removed = 0;
        var box = s.get(DataComponents.CONTAINER);
        if (box != null) {
            var items = NonNullList.<ItemStack>create(); int n = 0;
            for (var item : box.stream().map(ItemStack::copy).toList()) { if (coin(item)) { n += item.getCount(); items.add(ItemStack.EMPTY); } else { n += nested(item, depth + 1); items.add(item); } }
            if (n > 0) { s.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items)); removed += n; }
        }
        var bundle = s.get(DataComponents.BUNDLE_CONTENTS);
        if (bundle != null) {
            var items = new ArrayList<ItemStack>(); int n = 0;
            for (var item : bundle.itemCopyStream().toList()) { if (coin(item)) n += item.getCount(); else { n += nested(item, depth + 1); items.add(item); } }
            if (n > 0) { s.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(items)); removed += n; }
        }
        if (enabled()) removed += CoinResetLightman.wallet(s);
        return removed;
    }

    /** Players and the one-time wipe of bank accounts and shop cash. */
    static final class Store extends SavedData {
        final Set<UUID> players = new HashSet<>(); boolean accounts;
        static Store get(MinecraftServer server) { return server.overworld().getDataStorage().computeIfAbsent(new Factory<>(Store::new, Store::load), "animeoncraft-coin-reset-v1"); }
        static Store load(CompoundTag tag, HolderLookup.Provider registries) {
            var s = new Store(); s.accounts = tag.getBoolean("accounts");
            for (var t : tag.getList("players", Tag.TAG_INT_ARRAY)) s.players.add(NbtUtils.loadUUID(t));
            return s;
        }
        @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            tag.putBoolean("accounts", accounts); var list = new ListTag(); for (var id : players) list.add(NbtUtils.createUUID(id)); tag.put("players", list); return tag;
        }
    }

    static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || p instanceof net.neoforged.neoforge.common.util.FakePlayer || !enabled()) return;
        var store = Store.get(p.server); if (store.players.contains(p.getUUID())) return;
        int removed = clean(p.getInventory()) + clean(p.getEnderChestInventory()) + CoinResetLightman.equippedWallet(p);
        var purse = new ItemStack(BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse("lightmanscurrency:coin_copper")), (int) START_COPPER);
        if (!p.getInventory().add(purse) && !purse.isEmpty()) p.drop(purse, false);
        store.players.add(p.getUUID()); store.setDirty();
        System.out.println("AOC_COIN_RESET player " + p.getGameProfile().getName() + " removed=" + removed + " start=" + START_COPPER);
        p.sendSystemMessage(Component.literal("Монеты теперь выдаёт только гильдия искателей приключений. Старые монеты обнулены, стартовый капитал — " + START_COPPER + " медных монет."));
    }

    static void chunkLoaded(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level && event.getChunk() instanceof LevelChunk chunk && !chunk.hasData(CHUNK_DONE)) {
            if (event.isNewChunk()) { chunk.setData(CHUNK_DONE, true); chunk.setUnsaved(true); }
            else if (chunks.size() < 1_000_000) chunks.add(new Pending(level, chunk.getPos()));
        }
    }
    /** Containers of one chunk saved before the reset: chests, barrels, money chests, modded storage with an item handler. */
    static int cleanChunk(ServerLevel level, LevelChunk chunk) {
        int removed = 0;
        for (var be : List.copyOf(chunk.getBlockEntities().values())) {
            int n = 0;
            if (be instanceof Container c) n = clean(c);
            else if (level.getCapability(Capabilities.ItemHandler.BLOCK, be.getBlockPos(), be.getBlockState(), be, null) instanceof IItemHandlerModifiable h)
                for (int i = 0; i < h.getSlots(); i++) {
                    var s = h.getStackInSlot(i); if (s.isEmpty()) continue;
                    if (coin(s)) { n += s.getCount(); h.setStackInSlot(i, ItemStack.EMPTY); }
                    else { var copy = s.copy(); int m = nested(copy, 0); if (m > 0) { n += m; h.setStackInSlot(i, copy); } }
                }
            if (n > 0) { removed += n; System.out.println("AOC_COIN_RESET block " + level.dimension().location() + " " + be.getBlockPos().toShortString() + " removed=" + n); }
        }
        chunk.setData(CHUNK_DONE, true); chunk.setUnsaved(true);
        return removed;
    }

    static void tick(ServerTickEvent.Post event) {
        if (!enabled()) { chunks.clear(); return; }
        var server = event.getServer(); var store = Store.get(server);
        if (!store.accounts) { System.out.println("AOC_COIN_RESET accounts " + CoinResetLightman.wipeAccounts()); store.accounts = true; store.setDirty(); }
        for (int budget = 32; budget > 0 && !chunks.isEmpty(); budget--) {
            var next = chunks.poll();
            if (next.level().getServer() != server || !next.level().hasChunk(next.pos().x, next.pos().z)) continue;
            var chunk = next.level().getChunk(next.pos().x, next.pos().z); if (!chunk.hasData(CHUNK_DONE)) cleanChunk(next.level(), chunk);
        }
    }

    /** Dropped items, chest minecarts and boats, item frames saved before the reset lose their coins; new ones are marked. */
    static void entityJoined(EntityJoinLevelEvent event) {
        var e = event.getEntity();
        if (event.getLevel().isClientSide() || !enabled() || !(e instanceof ItemEntity || e instanceof ItemFrame || e instanceof Container) || e instanceof Player) return;
        var data = e.getPersistentData(); if (data.getBoolean(TAG)) return;
        data.putBoolean(TAG, true); if (!event.loadedFromDisk()) return;
        int removed = 0;
        if (e instanceof ItemEntity item) {
            var s = item.getItem();
            if (coin(s)) { removed = s.getCount(); event.setCanceled(true); e.discard(); }
            else { var copy = s.copy(); removed = nested(copy, 0); if (removed > 0) item.setItem(copy); }
        } else if (e instanceof ItemFrame frame) {
            var s = frame.getItem(); if (coin(s)) { removed = s.getCount(); frame.setItem(ItemStack.EMPTY, false); }
        } else if (e instanceof Container c) removed = clean(c);
        if (removed > 0) System.out.println("AOC_COIN_RESET entity " + BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()) + " " + e.blockPosition().toShortString() + " removed=" + removed);
    }

    /** Coins come only from the guild: mobs drop none (a player's death drops stay). */
    static void drops(LivingDropsEvent event) {
        if (event.getEntity() instanceof Player) return;
        event.getDrops().removeIf(drop -> coin(drop.getItem()));
    }

    static void stopped(ServerStoppedEvent event) { chunks.clear(); }
}
