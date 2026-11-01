package ru.animeoncraft.integration.neoforge;

import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.*;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityMobGriefingEvent;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Rift Gate on the server: the gate screen, solo starts and open teams (lobbies with ready checks, guild parties
 * join at once), fees, runs in the rift dimension and everything that keeps a run closed: no blocks, pearls, chorus,
 * waystones or /spawn; a death costs a share of the main inventory instead of a grave; leaving the game keeps the place
 * for a few minutes; a restart hands the fees back and sends everyone to the gate.
 */
final class DungeonServer {
    static final ResourceKey<Level> RIFT = ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath("animeoncraft", "rift"));
    static final int LOBBY_MINUTES = 10;
    private static DungeonCatalog.Catalog catalog;
    private static final Map<Long, DungeonRun> runs = new LinkedHashMap<>();
    private record Wiping(int slot, DungeonBuilder.Work work, List<long[]> chunks) {}
    private static final ArrayDeque<Wiping> wipes = new ArrayDeque<>();
    static final class Lobby {
        final long id; UUID leader; final List<UUID> members = new ArrayList<>(); final Set<UUID> ready = new HashSet<>(); int dungeon, difficulty; long touched;
        Lobby(long id, UUID leader, int dungeon, int difficulty, long now) { this.id = id; this.leader = leader; this.dungeon = dungeon; this.difficulty = difficulty; this.touched = now; members.add(leader); }
    }
    private static final List<Lobby> lobbies = new ArrayList<>();
    private static long lobbyIds;
    private static final Map<UUID, ArrayDeque<DungeonPayloads.Request>> pending = new HashMap<>();
    private static final Map<UUID, Long> requested = new HashMap<>();
    private static RiftGate.Site site; private static int siteChecked = -1;
    private static boolean allow;
    private DungeonServer() {}

    static void register(IEventBus bus) {
        DungeonItems.register(bus);
        var events = net.neoforged.neoforge.common.NeoForge.EVENT_BUS;
        events.addListener(DungeonServer::tick); events.addListener(DungeonServer::started); events.addListener(DungeonServer::stopped);
        events.addListener(DungeonServer::login); events.addListener(DungeonServer::logout); events.addListener(DungeonServer::commands);
        events.addListener(EventPriority.HIGHEST, false, DungeonServer::death);
        events.addListener(EventPriority.HIGHEST, false, DungeonServer::interact); events.addListener(EventPriority.HIGHEST, false, DungeonServer::useItem);
        events.addListener(EventPriority.HIGHEST, false, DungeonServer::breaking); events.addListener(EventPriority.HIGHEST, false, DungeonServer::placing);
        events.addListener(EventPriority.HIGHEST, false, DungeonServer::pearl); events.addListener(EventPriority.HIGHEST, false, DungeonServer::chorus);
        events.addListener(EventPriority.HIGHEST, false, DungeonServer::travel); events.addListener(EventPriority.HIGHEST, false, DungeonServer::command);
        events.addListener(DungeonServer::explosion); events.addListener(DungeonServer::griefing); events.addListener(DungeonServer::joined);
        events.addListener(DungeonServer::bloodsuckers); events.addListener(DungeonServer::riftBlast); events.addListener(EventPriority.HIGHEST, false, DungeonServer::noRegen);
    }

    // ------------------------------------------------------------------ catalog
    static DungeonCatalog.Catalog catalog(MinecraftServer server) { if (catalog == null) reload(server); return catalog; }
    static String reload(MinecraftServer server) {
        var skipped = new ArrayList<String>();
        try {
            catalog = DungeonCatalog.load(server.getWorldPath(LevelResource.ROOT), id -> known(BuiltInRegistries.ENTITY_TYPE.keySet(), id), id -> known(BuiltInRegistries.ITEM.keySet(), id), skipped);
            String line = "AOC_DUNGEON_CATALOG dungeons=" + catalog.dungeons().size() + " open=" + catalog.dungeons().stream().filter(DungeonCatalog.Dungeon::open).count() + " skipped=" + skipped;
            System.out.println(line); return line;
        } catch (Exception failure) {
            if (catalog == null) try { catalog = DungeonCatalog.builtIn(id -> true, id -> true, new ArrayList<>()); } catch (Exception ignored) {}
            String line = "AOC_DUNGEON_CATALOG_FAILED " + failure.getMessage(); System.out.println(line); return line;
        }
    }
    private static boolean known(Set<ResourceLocation> keys, String id) { var rl = ResourceLocation.tryParse(id); return rl != null && keys.contains(rl); }

    // ------------------------------------------------------------------ helpers
    static ServerLevel rift(MinecraftServer server) { return server.getLevel(RIFT); }
    static boolean inRift(ServerPlayer p) { return p.level().dimension().equals(RIFT); }
    private static boolean admitted(ServerPlayer p) { return !p.server.isDedicatedServer() || GameIdentity.PLAYERS.containsKey(p.getUUID()); }
    private static boolean staff(ServerPlayer p) { return p.hasPermissions(2) && (p.isCreative() || p.isSpectator()); }
    static DungeonRun runOf(UUID id) { for (var r : runs.values()) { var m = r.member(id); if (m != null && !m.out) return r; } return null; }
    /** Our own teleports between dimensions; any other way out of the rift is refused. */
    static void move(ServerPlayer p, ServerLevel level, double x, double y, double z, float yaw) {
        p.stopRiding(); allow = true;
        try { p.teleportTo(level, x, y, z, Set.of(), yaw, 0); } finally { allow = false; }
        p.setDeltaMovement(Vec3.ZERO); p.fallDistance = 0;
    }
    static int rank(MinecraftServer server, UUID id) { var c = GuildStore.get(server).existing(id); return c == null || !c.opened ? -1 : c.rank; }
    /** Probe only: look for the gate again at once (it was just built). */
    static void forgetSite() { siteChecked = -1; }
    static Map<Long, DungeonRun> runs() { return Collections.unmodifiableMap(runs); }
    static int wiping() { return wipes.size(); }
    private static RiftGate.Site site(MinecraftServer server) {
        int now = server.getTickCount();
        if (siteChecked < 0 || now - siteChecked >= 600) { siteChecked = now; site = RiftGate.site(server.overworld()); }
        return site;
    }
    /** At the gate: in its hall or on its path. Without a built gate (test worlds) operators may use it anywhere. */
    private static boolean atGate(ServerPlayer p) {
        var s = site(p.server);
        if (s == null) return p.hasPermissions(2);
        return p.level() == p.server.overworld() && s.near(p.getX(), p.getY(), p.getZ());
    }
    private static long copper(ServerPlayer p) { return GuildServer.coinsAvailable() ? GuildRules.value(GuildServer.coinsHeld(p)) : 0; }

    // ------------------------------------------------------------------ screen
    static void send(ServerPlayer p, int screen, String message) {
        if (p instanceof net.neoforged.neoforge.common.util.FakePlayer || !p.connection.hasChannel(DungeonPayloads.Snapshot.TYPE.id())) return;
        var c = catalog(p.server); var store = DungeonStore.get(p.server); var sp = store.player(p.getUUID()); long now = System.currentTimeMillis();
        if (sp.reset(now)) store.setDirty();
        int rank = rank(p.server, p.getUUID());
        var dungeons = new ArrayList<DungeonPayloads.Dungeon>();
        for (var d : c.dungeons()) {
            int cleared = 0; var tiers = new ArrayList<DungeonPayloads.Tier>();
            for (int i = 0; i < c.difficulties().size(); i++) {
                var f = c.difficulties().get(i); if (sp.cleared.contains(d.code() + ":" + f.code())) cleared |= 1 << i;
                var icons = new ArrayList<String>(); if (f.essenceMax() > 0) icons.add("animeoncraft:rift_essence");
                for (var l : d.loot().getOrDefault(f.code(), List.of())) if (icons.size() < 6 && !icons.contains(l.item())) icons.add(l.item());
                var boss = d.open() ? d.bossFor(c.difficulties(), f.code()) : null;
                tiers.add(new DungeonPayloads.Tier(boss == null ? "?" : boss.name(), icons));
            }
            dungeons.add(new DungeonPayloads.Dungeon(d.code(), d.name(), d.theme(), d.description(), d.rooms(), d.open(), cleared, tiers));
        }
        var difficulties = new ArrayList<DungeonPayloads.Difficulty>();
        for (var f : c.difficulties()) difficulties.add(new DungeonPayloads.Difficulty(f.name(), f.fee(), f.lossPercent(), f.minRank(), f.minPlayers(), (int) Math.round(f.health() * 10), (int) Math.round(f.damage() * 10),
            f.lives(), f.teamLives(), f.crystals(), DungeonRules.access(rank, f.minRank()).ordinal(), f.timeLimit(), f.affixes()));
        var affix = affix(p.server);
        var lobby = lobbyOf(p.getUUID()); var members = new ArrayList<DungeonPayloads.Member>();
        if (lobby != null) for (var id : lobby.members) { var m = p.server.getPlayerList().getPlayer(id); members.add(new DungeonPayloads.Member(m == null ? "игрок" : m.getGameProfile().getName(), rank(p.server, id), lobby.ready.contains(id))); }
        var open = new ArrayList<DungeonPayloads.Lobby>();
        for (int i = 0; i < lobbies.size() && open.size() < 16; i++) { var l = lobbies.get(i); var leader = p.server.getPlayerList().getPlayer(l.leader);
            open.add(new DungeonPayloads.Lobby(i, leader == null ? "игрок" : leader.getGameProfile().getName(), l.dungeon, l.difficulty, l.members.size(), c.maxPlayers())); }
        PacketDistributor.sendToPlayer(p, new DungeonPayloads.Snapshot(rank, sp.chests, c.dailyChests(), copper(p), runOf(p.getUUID()) != null, dungeons, difficulties, members,
            lobby != null && lobby.leader.equals(p.getUUID()), lobby == null ? -1 : lobby.dungeon, lobby == null ? -1 : lobby.difficulty, open, screen, message,
            affix == null ? "" : affix.name() + " — " + affix.description()));
    }
    static void request(DungeonPayloads.Request payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer p) || !admitted(p)) return;
            var queue = pending.computeIfAbsent(p.getUUID(), id -> new ArrayDeque<>());
            if (queue.size() < 6 && (queue.isEmpty() || !payload.action().equals("GET"))) queue.add(payload);
        });
    }
    private static String handle(ServerPlayer p, DungeonPayloads.Request r) {
        var c = catalog(p.server);
        switch (r.action()) {
            case "GET" -> { return ""; }
            case "EXIT" -> { var run = runOf(p.getUUID()); if (run == null) return "Ты не в забеге."; leave(p.server, run, p, "вышел из забега"); return "Ты покинул забег."; }
            default -> {}
        }
        if (runOf(p.getUUID()) != null) return "Ты уже в забеге.";
        if (!atGate(p)) return "Подойди к Вратам Разлома в Соре.";
        if (p.isSpectator() || p.isCreative() && !p.hasPermissions(2)) return "В этом режиме игры вход закрыт.";
        if (r.dungeon() >= c.dungeons().size() || r.difficulty() >= c.difficulties().size()) return "Подземелье не найдено.";
        long now = System.currentTimeMillis();
        switch (r.action()) {
            case "SOLO" -> { leaveLobby(p.server, p.getUUID()); return start(p.server, List.of(p), r.dungeon(), r.difficulty(), false); }
            case "CREATE" -> {
                var d = c.dungeons().get(r.dungeon()); if (!d.open()) return "«" + d.name() + "» ещё закрыто.";
                leaveLobby(p.server, p.getUUID());
                var lobby = new Lobby(++lobbyIds, p.getUUID(), r.dungeon(), r.difficulty(), now); lobbies.add(lobby);
                var party = GuildServer.parties.of(p.getUUID());
                if (party != null) for (var id : party.members) { var m = p.server.getPlayerList().getPlayer(id);
                    if (m != null && !id.equals(p.getUUID()) && lobbyOf(id) == null && runOf(id) == null && lobby.members.size() < c.maxPlayers()) { lobby.members.add(id); m.sendSystemMessage(Component.literal(p.getGameProfile().getName() + " собрал команду у Врат Разлома — нажми «Готов» на экране Врат.").withColor(0xf07aa6)); } }
                var f = c.difficulties().get(r.difficulty());
                var invite = Component.literal(p.getGameProfile().getName() + " набирает команду: «" + d.name() + "» · " + f.name() + " (" + lobby.members.size() + "/" + c.maxPlayers() + "). ").withColor(0xf07aa6)
                    .append(Component.literal("[Присоединиться]").withStyle(s -> s.withColor(0x7bea8a).withUnderlined(true).withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/aocdungeon join " + p.getGameProfile().getName()))));
                for (var o : p.server.getPlayerList().getPlayers()) if (o != p && admitted(o)) o.sendSystemMessage(invite);
                refreshLobby(p.server, lobby, p.getUUID());
                return "Команда создана. Позови игроков к Вратам: им придёт кнопка «Присоединиться».";
            }
            case "JOIN" -> { if (r.target() >= lobbies.size()) return "Эта команда уже ушла."; return join(p, lobbies.get(r.target())); }
            case "READY" -> { var l = lobbyOf(p.getUUID()); if (l == null) return "Ты не в команде."; if (!l.ready.remove(p.getUUID())) l.ready.add(p.getUUID()); l.touched = now; refreshLobby(p.server, l, p.getUUID()); return l.ready.contains(p.getUUID()) ? "Ты готов." : "Готовность снята."; }
            case "LEAVE" -> { if (lobbyOf(p.getUUID()) == null) return "Ты не в команде."; leaveLobby(p.server, p.getUUID()); return "Ты вышел из команды."; }
            case "START" -> {
                var l = lobbyOf(p.getUUID()); if (l == null) return "Сначала собери команду.";
                if (!l.leader.equals(p.getUUID())) return "Начинает лидер команды.";
                var players = new ArrayList<ServerPlayer>();
                for (var id : l.members) { var m = p.server.getPlayerList().getPlayer(id); if (m == null) return "Участник не в сети."; if (!l.ready.contains(id)) return m.getGameProfile().getName() + " ещё не готов.";
                    if (!atGate(m)) return m.getGameProfile().getName() + " далеко от Врат."; players.add(m); }
                var result = start(p.server, players, l.dungeon, l.difficulty, false);
                if (runOf(p.getUUID()) != null) lobbies.remove(l);
                return result;
            }
            default -> { return ""; }
        }
    }
    private static String join(ServerPlayer p, Lobby l) {
        var c = catalog(p.server);
        if (runOf(p.getUUID()) != null) return "Ты уже в забеге.";
        if (l.members.contains(p.getUUID())) return "Ты уже в этой команде.";
        if (l.members.size() >= c.maxPlayers()) return "Команда заполнена.";
        if (!atGate(p)) return "Подойди к Вратам Разлома в Соре.";
        leaveLobby(p.server, p.getUUID()); l.members.add(p.getUUID()); l.touched = System.currentTimeMillis(); refreshLobby(p.server, l, p.getUUID());
        return "Ты в команде. Нажми «Готов».";
    }
    static Lobby lobbyOf(UUID id) { for (var l : lobbies) if (l.members.contains(id)) return l; return null; }
    private static void leaveLobby(MinecraftServer server, UUID id) {
        var l = lobbyOf(id); if (l == null) return;
        l.members.remove(id); l.ready.remove(id);
        if (l.members.isEmpty()) { lobbies.remove(l); return; }
        if (l.leader.equals(id)) l.leader = l.members.getFirst();
        refreshLobby(server, l, null);
    }
    private static void refreshLobby(MinecraftServer server, Lobby l, UUID skip) {
        for (var id : l.members) { var m = server.getPlayerList().getPlayer(id); if (m != null && !id.equals(skip)) send(m, DungeonPayloads.SCREEN_NONE, ""); }
    }

    // ------------------------------------------------------------------ start
    /** Checks and starts a run; test runs (operators) skip rank and fee. Returns the answer for the starter. */
    static String start(MinecraftServer server, List<ServerPlayer> players, int dungeonIndex, int difficultyIndex, boolean test) {
        var c = catalog(server); var d = c.dungeons().get(dungeonIndex); var f = c.difficulties().get(difficultyIndex); var rift = rift(server);
        if (rift == null) return "Измерение Разлома не загружено. Сообщи администрации.";
        if (!d.open()) return "«" + d.name() + "» ещё закрыто.";
        for (var p : players) if (runOf(p.getUUID()) != null) return p.getGameProfile().getName() + " уже в забеге.";
        var ranks = new ArrayList<Integer>(); for (var p : players) ranks.add(rank(server, p.getUUID()));
        if (!test) { var error = DungeonRules.teamCheck(ranks, f.minRank(), f.minPlayers(), c.maxPlayers()); if (error != null) return error; }
        var busy = new HashSet<Integer>(); for (var r : runs.values()) busy.add(r.slot); for (var w : wipes) busy.add(w.slot());
        int slot = DungeonRules.freeSlot(busy); if (slot < 0) return "Все " + DungeonRules.SLOTS + " разломов заняты. Попробуй через пару минут.";
        long fee = test ? 0 : f.fee();
        if (fee > 0) {
            if (!GuildServer.coinsAvailable()) return "Монеты временно недоступны: мод валюты не загружен.";
            for (var p : players) if (copper(p) < fee) return p.getGameProfile().getName() + ": вход " + GuildRules.money(fee) + ", монеты нужны в инвентаре.";
            for (var p : players) if (!GuildServer.charge(p, fee)) return p.getGameProfile().getName() + ": не удалось списать вход.";
            var guild = GuildStore.get(server); guild.ledger.reset(System.currentTimeMillis()); guild.ledger.sink(fee * players.size()); guild.setDirty();
        }
        var store = DungeonStore.get(server); long id = store.nextId++;
        var run = new DungeonRun(id, slot, d, f, new Random().nextLong(), affix(server));
        var day = store.today(System.currentTimeMillis()); day[DungeonStore.RUNS]++; day[DungeonStore.BURNED] += fee * players.size();
        var fees = new LinkedHashMap<UUID, Long>();
        for (int i = 0; i < players.size(); i++) {
            var p = players.get(i); var access = test ? DungeonRules.Access.FULL : DungeonRules.access(ranks.get(i), f.minRank());
            var m = new DungeonRun.Member(p.getUUID(), p.getGameProfile().getName(), access, fee); m.lives = f.lives(); run.members.put(p.getUUID(), m); fees.put(p.getUUID(), fee);
            var sp = store.player(p.getUUID()); sp.run = id; sp.watcher = false;
            sp.returnLevel = p.level().dimension().location().toString(); sp.rx = p.getX(); sp.ry = p.getY(); sp.rz = p.getZ(); sp.ryRot = p.getYRot();
            leaveLobby(server, p.getUUID());
        }
        store.runs.put(id, new DungeonStore.Run(id, slot, d.code(), f.code(), fees)); store.dirtySlots.add(slot); store.burned += fee * players.size(); store.started++; store.setDirty();
        for (var ch : DungeonLayout.chunks(run.rooms, run.ox, run.oz)) rift.setChunkForced((int) ch[0], (int) ch[1], true);
        runs.put(id, run);
        System.out.println("AOC_DUNGEON_START run=" + id + " slot=" + slot + " " + run.key() + " players=" + players.stream().map(p -> p.getGameProfile().getName()).toList() + " fee=" + fee + (test ? " test" : ""));
        for (var p : players) { p.sendSystemMessage(Component.literal("Разлом открывается: «" + d.name() + "» · " + f.name() + (fee > 0 ? " · вход " + GuildRules.money(fee) + " сгорел" : "") + ". При смерти теряется " + f.lossPercent() + "% стаков инвентаря.").withColor(0xf07aa6));
            if (p.containerMenu != p.inventoryMenu) p.closeContainer(); }
        return "Разлом открывается…";
    }

    // ------------------------------------------------------------------ leaving and the way back
    /** Takes a member out of a run now and sends them to the gate with what their chest holds. */
    static void leave(MinecraftServer server, DungeonRun run, ServerPlayer p, String why) {
        var m = run.member(p.getUUID()); if (m == null) return;
        m.out = true; m.bar.removeAllPlayers(); if (run.bossBar != null) run.bossBar.removePlayer(p);
        sendHome(server, p, m);
        run.say(server, p.getGameProfile().getName() + " " + why + ".", 0xffa04a);
    }
    /** Back to the gate (or where the player stood before), their own game mode back, chest items and stored items handed over. */
    static void sendHome(MinecraftServer server, ServerPlayer p, DungeonRun.Member m) {
        var store = DungeonStore.get(server); var sp = store.player(p.getUUID());
        if (sp.watcher || m != null && m.watcher) p.setGameMode(m != null ? m.mode : GameType.SURVIVAL);
        if (p.isDeadOrDying()) p.setHealth(1);
        var s = site(server); var overworld = server.overworld();
        if (s != null) { var a = s.arrival(); move(p, overworld, a[0], a[1], a[2], (float) a[3]); }
        else {
            var level = sp.returnLevel.isEmpty() ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(sp.returnLevel)));
            if (level != null && level != rift(server)) move(p, level, sp.rx, sp.ry, sp.rz, sp.ryRot);
            else { var spawn = overworld.getSharedSpawnPos(); move(p, overworld, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, 0); }
        }
        if (m != null) for (int i = 0; i < m.chest.getContainerSize(); i++) { var stack = m.chest.removeItemNoUpdate(i); if (!stack.isEmpty()) give(p, stack); }
        for (int i = 0; i < sp.pending.size(); i++) { var stack = ItemStack.parse(server.registryAccess(), sp.pending.getCompound(i)); stack.ifPresent(st -> give(p, st)); }
        if (!sp.pending.isEmpty()) p.sendSystemMessage(Component.literal("Награда из сундука Разлома передана тебе.").withColor(0x7bea8a));
        sp.pending.clear(); sp.run = 0; sp.watcher = false; sp.returnLevel = ""; store.setDirty();
        if (sp.refund > 0 && GuildServer.coinsAvailable()) { GuildServer.give(p, sp.refund); p.sendSystemMessage(Component.literal("Забег прерван перезапуском сервера: вход " + GuildRules.money(sp.refund) + " возвращён.").withColor(0x7bea8a)); sp.refund = 0; }
        send(p, DungeonPayloads.SCREEN_NONE, "");
    }
    private static void give(ServerPlayer p, ItemStack stack) {
        if (!p.getInventory().add(stack) && !stack.isEmpty()) { var dropped = p.drop(stack, false); if (dropped != null) { dropped.setNoPickUpDelay(); dropped.setTarget(p.getUUID()); } }
    }
    /** The run is over: members go home (offline ones on their next login), the slot is wiped. */
    private static void finish(MinecraftServer server, DungeonRun run) {
        var store = DungeonStore.get(server); run.hideBars();
        for (var m : run.members.values()) {
            var p = server.getPlayerList().getPlayer(m.id); var sp = store.player(m.id);
            if (m.out) continue;
            if (p != null && inRift(p)) sendHome(server, p, m);
            else {
                for (int i = 0; i < m.chest.getContainerSize(); i++) { var stack = m.chest.removeItemNoUpdate(i); if (!stack.isEmpty() && sp.pending.size() < 64) sp.pending.add(stack.save(server.registryAccess())); }
                sp.run = 0; sp.watcher = m.watcher;
            }
        }
        store.runs.remove(run.id); store.setDirty();
        wipe(server, run.slot, new DungeonBuilder.Wipe(run.ox - 2, run.oz + DungeonLayout.bounds(run.rooms)[1] - 2, run.ox + DungeonLayout.bounds(run.rooms)[2] + 2, run.oz + DungeonLayout.bounds(run.rooms)[3] + 2, DungeonLayout.top(run.rooms) + 1),
            DungeonLayout.chunks(run.rooms, run.ox, run.oz));
        System.out.println("AOC_DUNGEON_END run=" + run.id + " state=" + run.state + " slot=" + run.slot);
    }
    private static void wipe(MinecraftServer server, int slot, DungeonBuilder.Work work, List<long[]> chunks) { wipes.add(new Wiping(slot, work, chunks)); }

    // ------------------------------------------------------------------ ticking
    static void tick(ServerTickEvent.Post event) {
        var server = event.getServer(); var rift = rift(server); if (rift == null) return;
        long now = System.currentTimeMillis(); int t = server.getTickCount();
        if (t % 10 == 0) for (var run : List.copyOf(runs.values())) {
            try { if (run.tick(server, rift, now)) { runs.remove(run.id); finish(server, run); } }
            catch (RuntimeException failure) { System.out.println("AOC_DUNGEON_RUN_FAILED run=" + run.id + " " + failure); run.fail(server, "сбой Разлома."); run.abort(); }
            // Members away longer than the reconnect window leave the run.
            for (var m : run.members.values()) if (m.leftAt > 0 && !m.out && !DungeonRules.reconnect(m.leftAt, now, catalog(server).reconnectSeconds())) { m.out = true; System.out.println("AOC_DUNGEON_GONE run=" + run.id + " " + m.name); }
        }
        var w = wipes.peek();
        if (w != null && w.work().step(rift, System.nanoTime() + 10_000_000L)) {
            wipes.poll(); for (var ch : w.chunks()) rift.setChunkForced((int) ch[0], (int) ch[1], false);
            var store = DungeonStore.get(server); if (runs.values().stream().noneMatch(r -> r.slot == w.slot())) { store.dirtySlots.remove(w.slot()); store.setDirty(); }
            System.out.println("AOC_DUNGEON_WIPED slot=" + w.slot());
        }
        if (t % 20 == 0) for (var p : server.getPlayerList().getPlayers())
            if (inRift(p) && runOf(p.getUUID()) == null && !staff(p)) { System.out.println("AOC_DUNGEON_STRAY " + p.getGameProfile().getName()); sendHome(server, p, null); }
        if (t % 200 == 0) lobbies.removeIf(l -> { l.members.removeIf(id -> server.getPlayerList().getPlayer(id) == null); return l.members.isEmpty() || now - l.touched > LOBBY_MINUTES * 60_000L; });
        long nano = System.nanoTime(); var it = pending.entrySet().iterator();
        while (it.hasNext()) {
            var item = it.next(); if (nano - requested.getOrDefault(item.getKey(), 0L) < 250_000_000L) continue;
            var request = item.getValue().poll(); if (item.getValue().isEmpty()) it.remove();
            var p = server.getPlayerList().getPlayer(item.getKey()); if (p == null || request == null) continue;
            requested.put(p.getUUID(), nano);
            send(p, DungeonPayloads.SCREEN_NONE, handle(p, request));
        }
    }
    /** After a restart no run is alive: fees go back on the next login, used slots are wiped. */
    static void started(ServerStartedEvent event) {
        var server = event.getServer(); var store = DungeonStore.get(server); reload(server);
        for (var run : store.runs.values()) { for (var f : run.fees().entrySet()) { store.player(f.getKey()).refund += f.getValue(); store.today(System.currentTimeMillis())[DungeonStore.BURNED] -= f.getValue(); }
            store.today(System.currentTimeMillis())[DungeonStore.INTERRUPTED]++; store.dirtySlots.add(run.slot()); System.out.println("AOC_DUNGEON_INTERRUPTED run=" + run.id() + " " + run.dungeon() + ":" + run.difficulty()); }
        store.runs.clear();
        for (var sp : store.players().values()) if (sp.run != 0) sp.run = -1; // -1: sent home at the next login
        store.setDirty();
        if (rift(server) != null) for (int slot : store.dirtySlots) wipe(server, slot, DungeonBuilder.Wipe.slot(slot, 20, 16), List.of());
    }
    static void stopped(ServerStoppedEvent event) {
        for (var run : runs.values()) run.hideBars();
        runs.clear(); wipes.clear(); lobbies.clear(); pending.clear(); requested.clear(); catalog = null; site = null; siteChecked = -1; allow = false;
    }
    static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        var store = DungeonStore.get(p.server); var sp = store.existing(p.getUUID());
        var run = runOf(p.getUUID());
        if (run != null) {
            var m = run.member(p.getUUID());
            if (m.leftAt > 0 && DungeonRules.reconnect(m.leftAt, System.currentTimeMillis(), catalog(p.server).reconnectSeconds())) { m.leftAt = 0; p.sendSystemMessage(Component.literal("Ты вернулся в забег.").withColor(0xf07aa6)); }
            return;
        }
        if (sp != null && (sp.run != 0 || inRift(p) || !sp.pending.isEmpty() || sp.refund > 0)) {
            if (inRift(p) || sp.run != 0) p.sendSystemMessage(Component.literal("Забег закончился, пока тебя не было. Ты у Врат Разлома.").withColor(0xffa04a));
            sendHome(p.server, p, null);
        } else if (inRift(p) && !staff(p)) sendHome(p.server, p, null);
        send(p, DungeonPayloads.SCREEN_NONE, "");
    }
    static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        pending.remove(p.getUUID()); requested.remove(p.getUUID()); leaveLobby(p.server, p.getUUID());
        var run = runOf(p.getUUID()); if (run != null) { var m = run.member(p.getUUID()); m.leftAt = System.currentTimeMillis(); m.bar.removePlayer(p); }
    }

    // ------------------------------------------------------------------ rules inside the rift
    /** A death in the rift never happens: the share of the inventory is gone, the player stands up at the room entrance. */
    static void death(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || !inRift(p)) return;
        event.setCanceled(true);
        p.setHealth(p.getMaxHealth()); p.clearFire(); p.removeAllEffects(); p.getFoodData().setFoodLevel(Math.max(p.getFoodData().getFoodLevel(), 14));
        var run = runOf(p.getUUID()); var rift = rift(p.server);
        if (run == null || rift == null) { sendHome(p.server, p, null); return; }
        var m = run.member(p.getUUID());
        if (run.state == DungeonRun.State.ACTIVE && !m.watcher) {
            int lost = loseItems(p, run.difficulty.lossPercent());
            if (lost > 0) p.sendSystemMessage(Component.literal("Разлом забрал " + lost + " " + (lost == 1 ? "стак" : lost < 5 ? "стака" : "стаков") + " из инвентаря.").withColor(0xff5a5a));
            if (!run.death(p.server, p, m)) p.setGameMode(GameType.SPECTATOR);
            DungeonStore.get(p.server).player(p.getUUID()).watcher = m.watcher; DungeonStore.get(p.server).setDirty();
            System.out.println("AOC_DUNGEON_DEATH run=" + run.id + " " + m.name + " lost=" + lost + " watcher=" + m.watcher + " source=" + event.getSource().getMsgId());
        }
        var at = run.respawn(); move(p, rift, at[0], at[1], at[2], -90); p.invulnerableTime = 60;
    }
    /** Removes the difficulty's share of occupied main-inventory stacks (backpacks and wallets stay). Returns how many. */
    static int loseItems(ServerPlayer p, int percent) {
        var items = p.getInventory().items; var occupied = new ArrayList<Integer>();
        for (int i = 0; i < Math.min(DungeonRules.MAIN_SLOTS, items.size()); i++) { var s = items.get(i); if (!s.isEmpty() && !DungeonRules.protectedItem(BuiltInRegistries.ITEM.getKey(s.getItem()).toString())) occupied.add(i); }
        var lost = DungeonRules.lostSlots(occupied, percent, new Random().nextLong());
        for (int i : lost) items.set(i, ItemStack.EMPTY);
        p.getInventory().setChanged(); p.inventoryMenu.broadcastChanges();
        return lost.size();
    }
    private static boolean guarded(net.minecraft.world.entity.Entity e) { return e != null && e.level().dimension().equals(RIFT) && !(e instanceof ServerPlayer p && staff(p)); }
    static void interact(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        if (!inRift(p)) {
            var s = site(p.server);
            if (s == null || event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND || p.level() != p.server.overworld() || !s.portal(event.getPos())) return;
            event.setCanceled(true); event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
            if (admitted(p)) send(p, DungeonPayloads.SCREEN_OPEN, "");
            return;
        }
        if (staff(p)) return;
        var run = runOf(p.getUUID()); var state = p.level().getBlockState(event.getPos());
        if (run != null && state.is(Blocks.CHEST)) {
            event.setCanceled(true); event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
            if (event.getHand() == net.minecraft.world.InteractionHand.MAIN_HAND) openChest(p, run, event.getPos());
            return;
        }
        event.setUseBlock(TriState.FALSE);
        var held = event.getItemStack().getItem();
        if (held instanceof BlockItem || held instanceof BucketItem || held instanceof SpawnEggItem || DungeonRules.blockedItem(BuiltInRegistries.ITEM.getKey(held).toString())) event.setUseItem(TriState.FALSE);
    }
    private static void openChest(ServerPlayer p, DungeonRun run, BlockPos pos) {
        var m = run.member(p.getUUID()); if (m == null || m.watcher) return;
        if (pos.equals(run.treasureChest) && !m.treasure) {
            var store = DungeonStore.get(p.server); var sp = store.player(p.getUUID()); sp.reset(System.currentTimeMillis());
            m.treasure = true;
            if (DungeonRules.chestsLeft(sp.chests, catalog(p.server).dailyChests()) > 0) run.fill(p.server, m, m.access == DungeonRules.Access.HALF ? 1 : 2, false);
            else p.sendSystemMessage(Component.literal("Награды на сегодня уже получены — сокровищница пуста.").withColor(0xffa04a));
        } else if (!pos.equals(run.treasureChest) && !run.chests.contains(pos)) return;
        p.openMenu(new SimpleMenuProvider((id, inventory, player) -> ChestMenu.threeRows(id, inventory, m.chest), Component.literal("Твой сундук Разлома")));
    }
    static void useItem(PlayerInteractEvent.RightClickItem event) {
        if (!guarded(event.getEntity())) return;
        var held = event.getItemStack().getItem();
        if (held instanceof BucketItem || held instanceof SpawnEggItem || DungeonRules.blockedItem(BuiltInRegistries.ITEM.getKey(held).toString())) {
            event.setCanceled(true); if (event.getEntity() instanceof ServerPlayer p) p.displayClientMessage(Component.literal("В Разломе это не работает.").withColor(0xffa04a), true);
        }
    }
    static void breaking(BlockEvent.BreakEvent event) { if (guarded(event.getPlayer())) event.setCanceled(true); }
    static void placing(BlockEvent.EntityPlaceEvent event) { if (event.getLevel() instanceof ServerLevel level && level.dimension().equals(RIFT) && !(event.getEntity() instanceof ServerPlayer p && staff(p))) event.setCanceled(true); }
    static void pearl(EntityTeleportEvent.EnderPearl event) { if (guarded(event.getPlayer())) event.setCanceled(true); }
    static void chorus(EntityTeleportEvent.ChorusFruit event) { if (guarded(event.getEntityLiving())) event.setCanceled(true); }
    /** Only our own teleports leave the rift; Waystones, portals, /spawn and the like are refused. */
    static void travel(EntityTravelToDimensionEvent event) {
        if (allow || !(event.getEntity() instanceof ServerPlayer p) || !inRift(p) || staff(p)) return;
        if (runOf(p.getUUID()) == null) return;
        event.setCanceled(true); p.displayClientMessage(Component.literal("Из Разлома выходят только через /aocdungeon leave.").withColor(0xffa04a), true);
    }
    static void command(CommandEvent event) {
        var source = event.getParseResults().getContext().getSource();
        if (!(source.getEntity() instanceof ServerPlayer p) || !inRift(p) || p.hasPermissions(2)) return;
        if (DungeonRules.blockedCommand(event.getParseResults().getReader().getString())) { event.setCanceled(true); p.sendSystemMessage(Component.literal("В Разломе эта команда недоступна. Выход — /aocdungeon leave.").withColor(0xffa04a)); }
    }
    static void explosion(ExplosionEvent.Detonate event) { if (event.getLevel().dimension().equals(RIFT)) event.getAffectedBlocks().clear(); }
    static void griefing(EntityMobGriefingEvent event) { if (event.getEntity().level().dimension().equals(RIFT)) event.setCanGrief(false); }
    /** Mobs of a run that no longer exists (they come back with their chunk after a restart) never return. */
    static void joined(EntityJoinLevelEvent event) {
        if (!event.getLevel().dimension().equals(RIFT) || !(event.getEntity() instanceof Mob mob)) return;
        var data = mob.getPersistentData(); if (data.contains(DungeonRun.TAG) && !runs.containsKey(data.getLong(DungeonRun.TAG))) event.setCanceled(true);
    }

    // ------------------------------------------------------------------ commands
    static String economyLine(MinecraftServer server) {
        var s = DungeonStore.get(server); var t = s.today(System.currentTimeMillis());
        return "Подземелья сегодня: сожжено входом " + GuildRules.money(t[DungeonStore.BURNED]) + " · забегов " + t[DungeonStore.RUNS] + " · всего с запуска сожжено " + GuildRules.money(s.burned)
            + ", забегов " + s.started + " (побед " + s.won + ", провалов " + s.failed + ") · сейчас идёт " + runs.size();
    }
    /** Today, the week and the last days of the Rift Gate: what the owner reads while tuning dungeons.json. */
    static List<String> economyReport(MinecraftServer server, int days) {
        var s = DungeonStore.get(server); s.today(System.currentTimeMillis()); var out = new ArrayList<String>(); var sum = new long[DungeonStore.FIELDS];
        var rows = new ArrayList<>(s.days.descendingMap().entrySet()); int n = 0;
        for (var e : rows) { if (n++ >= days) break; for (int i = 0; i < sum.length; i++) sum[i] += e.getValue()[i]; }
        var affix = affix(server);
        out.add("Подземелья за " + Math.min(days, rows.size()) + " дн.: забегов " + sum[DungeonStore.RUNS] + ", побед " + sum[DungeonStore.WINS] + ", провалов " + sum[DungeonStore.FAILS]
            + ", прервано перезапуском " + sum[DungeonStore.INTERRUPTED] + " · сожжено входом " + GuildRules.money(sum[DungeonStore.BURNED]) + " · сундуков " + sum[DungeonStore.CHESTS] + ", Эссенции " + sum[DungeonStore.ESSENCE]);
        out.add("Аффикс недели: " + (affix == null ? "нет" : affix.name() + (DungeonStore.get(server).affixOverride.isEmpty() ? "" : " (задан оператором)")) + " · " + economyLine(server));
        for (int i = 0; i < Math.min(14, rows.size()); i++) { var r = rows.get(i).getValue();
            out.add(rows.get(i).getKey() + ": забегов " + r[DungeonStore.RUNS] + " (+" + r[DungeonStore.WINS] + " −" + r[DungeonStore.FAILS] + ") · −" + GuildRules.money(r[DungeonStore.BURNED]) + " · сундуков " + r[DungeonStore.CHESTS] + " · Эссенции " + r[DungeonStore.ESSENCE]); }
        return out;
    }
    /** The affix of Nightmare and Mythic runs now: the operator's choice, otherwise the week's (catalog order, Monday in Yekaterinburg). */
    static DungeonCatalog.Affix affix(MinecraftServer server) {
        var c = catalog(server); var forced = DungeonStore.get(server).affixOverride;
        if (!forced.isEmpty()) for (var a : c.affixes()) if (a.code().equals(forced)) return a;
        return c.affix(DungeonRules.week(System.currentTimeMillis()));
    }
    /** A guild DUNGEON quest target the gate knows (any dungeon of the catalog or "any", a known difficulty). */
    static boolean knownQuest(String target) {
        var c = catalog; var order = c == null ? List.of("NORMAL", "HARD", "NIGHTMARE", "MYTHIC") : c.order();
        if (!target.matches("dungeon:[a-z0-9_]{3,32}/[a-z]{3,16}")) return false;
        var code = target.substring(8, target.indexOf('/'));
        return order.contains(target.substring(target.indexOf('/') + 1).toUpperCase(Locale.ROOT)) && (code.equals("any") || c == null || c.dungeon(code) != null);
    }
    private static DungeonRun runOfMob(net.minecraft.world.entity.Entity e) {
        if (e == null || !e.level().dimension().equals(RIFT) || !e.getPersistentData().contains(DungeonRun.TAG)) return null;
        return runs.get(e.getPersistentData().getLong(DungeonRun.TAG));
    }
    /** "Bloodsuckers": a run mob heals by a share of the damage it deals to a player. */
    static void bloodsuckers(net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer) || !(event.getSource().getEntity() instanceof Mob mob)) return;
        var run = runOfMob(mob); if (run == null || !run.has("BLOODSUCKERS")) return;
        mob.heal(event.getNewDamage() * run.affix.value() / 100f);
    }
    /** "Rift": a run mob bursts on death; the explosion hurts but never breaks blocks. */
    static void riftBlast(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof Mob mob) || event.isCanceled()) return;
        var run = runOfMob(mob); if (run == null || !run.has("RIFT") || run.state != DungeonRun.State.ACTIVE) return;
        mob.level().explode(mob, mob.getX(), mob.getY() + 0.5, mob.getZ(), Math.max(0.5f, run.affix.value() / 10f), Level.ExplosionInteraction.NONE);
    }
    /** "No regen": health of the players of such a run does not come back by itself (food, potions, spells). */
    static void noRegen(net.neoforged.neoforge.event.entity.living.LivingHealEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || !inRift(p)) return;
        var run = runOf(p.getUUID()); if (run != null && run.state == DungeonRun.State.ACTIVE && run.has("NO_REGEN")) event.setCanceled(true);
    }
    static void commands(RegisterCommandsEvent event) {
        var dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("aocdungeon")
            .then(Commands.literal("leave").requires(s -> s.getEntity() instanceof ServerPlayer).executes(ctx -> {
                var p = ctx.getSource().getPlayerOrException(); var run = runOf(p.getUUID());
                if (run == null) { ctx.getSource().sendFailure(Component.literal("Ты не в забеге.")); return 0; }
                if (run.state == DungeonRun.State.VICTORY) { leave(p.server, run, p, "вернулся к Вратам"); return 1; }
                leave(p.server, run, p, "покинул забег"); return 1;
            }))
            .then(Commands.literal("join").requires(s -> s.getEntity() instanceof ServerPlayer).then(Commands.argument("leader", StringArgumentType.word()).executes(ctx -> {
                var p = ctx.getSource().getPlayerOrException(); var name = StringArgumentType.getString(ctx, "leader");
                var leader = p.server.getPlayerList().getPlayerByName(name); var l = leader == null ? null : lobbyOf(leader.getUUID());
                String answer = l == null ? "Команда не найдена или уже ушла." : join(p, l);
                send(p, l == null ? DungeonPayloads.SCREEN_NONE : DungeonPayloads.SCREEN_OPEN, answer); ctx.getSource().sendSuccess(() -> Component.literal(answer), false); return 1;
            })))
            .then(Commands.literal("reload").requires(s -> s.hasPermission(2)).executes(ctx -> { var line = reload(ctx.getSource().getServer()); ctx.getSource().sendSuccess(() -> Component.literal(line), true); return 1; }))
            .then(Commands.literal("status").requires(s -> s.hasPermission(2)).executes(ctx -> {
                var server = ctx.getSource().getServer(); ctx.getSource().sendSuccess(() -> Component.literal(economyLine(server) + " · очистка слотов: " + wipes.size()), false);
                for (var r : runs.values()) ctx.getSource().sendSuccess(() -> Component.literal("#" + r.id + " слот " + r.slot + " " + r.key() + " " + r.state + " комната " + (r.room + 1) + "/" + r.rooms.size() + " · " + r.members.values().stream().map(m -> m.name).toList()), false);
                return runs.size();
            }))
            .then(Commands.literal("open").requires(s -> s.hasPermission(2) && s.getEntity() instanceof ServerPlayer).executes(ctx -> { send(ctx.getSource().getPlayerOrException(), DungeonPayloads.SCREEN_OPEN, ""); return 1; }))
            .then(Commands.literal("test").requires(s -> s.hasPermission(2) && s.getEntity() instanceof ServerPlayer)
                .then(Commands.argument("dungeon", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(catalog(c.getSource().getServer()).dungeons().stream().map(DungeonCatalog.Dungeon::code), b))
                .then(Commands.argument("difficulty", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(catalog(c.getSource().getServer()).difficulties().stream().map(DungeonCatalog.Difficulty::code), b)).executes(ctx -> {
                    var p = ctx.getSource().getPlayerOrException(); var c = catalog(p.server);
                    var d = c.dungeon(StringArgumentType.getString(ctx, "dungeon")); int f = c.index(StringArgumentType.getString(ctx, "difficulty").toUpperCase(Locale.ROOT));
                    if (d == null || f < 0) { ctx.getSource().sendFailure(Component.literal("Нет такого подземелья или сложности.")); return 0; }
                    var answer = start(p.server, List.of(p), c.dungeons().indexOf(d), f, true); ctx.getSource().sendSuccess(() -> Component.literal(answer), false); return 1;
                }))))
            .then(Commands.literal("economy").requires(s -> s.hasPermission(2)).executes(ctx -> {
                for (var line : economyReport(ctx.getSource().getServer(), 7)) ctx.getSource().sendSuccess(() -> Component.literal(line), false); return 1; }))
            .then(Commands.literal("affix").requires(s -> s.hasPermission(2)).then(Commands.argument("code", StringArgumentType.word())
                .suggests((c, b) -> SharedSuggestionProvider.suggest(java.util.stream.Stream.concat(java.util.stream.Stream.of("auto"), catalog(c.getSource().getServer()).affixes().stream().map(DungeonCatalog.Affix::code)), b))
                .executes(ctx -> {
                    var server = ctx.getSource().getServer(); var code = StringArgumentType.getString(ctx, "code").toUpperCase(Locale.ROOT); var store = DungeonStore.get(server);
                    if (!code.equals("AUTO") && catalog(server).affixes().stream().noneMatch(a -> a.code().equals(code))) { ctx.getSource().sendFailure(Component.literal("Нет такого аффикса.")); return 0; }
                    store.affixOverride = code.equals("AUTO") ? "" : code; store.setDirty(); var a = affix(server);
                    ctx.getSource().sendSuccess(() -> Component.literal("Аффикс новых забегов на Кошмаре и Мифической: " + (a == null ? "нет" : a.name())), true); return 1;
                })))
            .then(Commands.literal("stop").requires(s -> s.hasPermission(2)).executes(ctx -> {
                int n = runs.size(); for (var r : runs.values()) { r.fail(ctx.getSource().getServer(), "остановлен оператором."); r.abort(); }
                ctx.getSource().sendSuccess(() -> Component.literal("Остановлено забегов: " + n), true); return n;
            })));
    }
}
