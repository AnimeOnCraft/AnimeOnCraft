package ru.animeoncraft.integration.neoforge;

import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.*;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Adventurers' Guild on the server: the card opens on the first visit to the hall, the board opens with a click on it,
 * quests are taken and handed in only inside the hall, coins come from the guild ledger with its caps.
 */
final class GuildServer {
    private static final String UNNATURAL = "animeoncraft_guild_unnatural";
    private static final Set<String> UNNATURAL_SPAWNS = Set.of("SPAWNER", "TRIAL_SPAWNER", "SPAWN_EGG", "COMMAND", "DISPENSER", "BUCKET", "BREEDING", "SPAWN_ITEM_USE");
    private static final List<TagKey<EntityType<?>>> BOSSES = List.of(TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath("c", "bosses")),
        TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath("animeoncraft", "guild_bosses_a")), TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath("animeoncraft", "guild_bosses_s")));
    /** A one-kill team quest (a boss) pays only members this close to the one who landed the last hit. */
    static final int BOSS_NEAR = 128;
    private static GuildCatalog.Catalog catalog;
    private static AdventurerGuild.Site site;
    private static int siteChecked = -1; private static boolean signDone;
    private static final Map<UUID, ArrayDeque<GuildPayloads.Request>> pending = new HashMap<>();
    private static final Map<UUID, Long> requested = new HashMap<>();
    static final GuildParty parties = new GuildParty();
    /** Only the disposable review client puts stand-in team members here. */
    static final Map<UUID, ServerPlayer> PROBE_MEMBERS = new HashMap<>();
    private GuildServer() {}
    private static ServerPlayer member(MinecraftServer server, UUID id) { var p = server.getPlayerList().getPlayer(id); return p != null ? p : PROBE_MEMBERS.get(id); }

    private static boolean admitted(ServerPlayer p) { return !p.server.isDedicatedServer() || GameIdentity.PLAYERS.containsKey(p.getUUID()); }
    private static boolean playing(ServerPlayer p) { return admitted(p) && !p.isCreative() && !p.isSpectator(); }
    static AdventurerGuild.Site site() { return site; }

    static GuildCatalog.Catalog catalog(MinecraftServer server) {
        if (catalog == null) reload(server);
        return catalog;
    }
    static String reload(MinecraftServer server) {
        var skipped = new ArrayList<String>();
        try {
            catalog = GuildCatalog.load(server.getWorldPath(LevelResource.ROOT), GuildServer::known, skipped); GuildRules.economy = catalog.economy();
            String line = "AOC_GUILD_CATALOG quests=" + catalog.quests().size() + " skipped=" + skipped + " economy " + catalog.economy();
            System.out.println(line); return line;
        } catch (Exception failure) {
            if (catalog == null) catalog = new GuildCatalog.Catalog(List.of());
            String line = "AOC_GUILD_CATALOG_FAILED " + failure.getMessage(); System.out.println(line); return line;
        }
    }
    private static boolean known(GuildCatalog.Quest q) {
        var id = ResourceLocation.tryParse(q.id()); if (id == null) return false;
        if (q.kind() == GuildCatalog.Kind.ITEM) return q.tag() ? BuiltInRegistries.ITEM.getTag(TagKey.create(Registries.ITEM, id)).map(t -> t.size() > 0).orElse(false) : BuiltInRegistries.ITEM.containsKey(id);
        return q.tag() ? BuiltInRegistries.ENTITY_TYPE.getTag(TagKey.create(Registries.ENTITY_TYPE, id)).map(t -> t.size() > 0).orElse(false) : BuiltInRegistries.ENTITY_TYPE.containsKey(id);
    }
    private static String icon(GuildCatalog.Quest q) {
        var id = ResourceLocation.parse(q.id()); Item item = Items.IRON_SWORD;
        if (q.kind() == GuildCatalog.Kind.ITEM) item = q.tag() ? BuiltInRegistries.ITEM.getTag(TagKey.create(Registries.ITEM, id)).map(t -> t.get(0).value()).orElse(Items.CHEST) : BuiltInRegistries.ITEM.get(id);
        else {
            EntityType<?> type = q.tag() ? BuiltInRegistries.ENTITY_TYPE.getTag(TagKey.create(Registries.ENTITY_TYPE, id)).map(t -> (EntityType<?>) t.get(0).value()).orElse(null) : BuiltInRegistries.ENTITY_TYPE.get(id);
            var egg = type == null ? null : SpawnEggItem.byId(type); if (egg != null) item = egg;
        }
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }
    private static boolean matches(ItemStack stack, GuildCatalog.Quest q) {
        if (stack.isEmpty()) return false;
        var id = ResourceLocation.parse(q.id());
        return q.tag() ? stack.is(TagKey.create(Registries.ITEM, id)) : BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(id);
    }
    private static int held(ServerPlayer p, GuildCatalog.Quest q) { int n = 0; for (var s : p.getInventory().items) if (matches(s, q)) n += s.getCount(); return n; }
    private static void take(ServerPlayer p, GuildCatalog.Quest q, int count) {
        int left = count; for (var s : p.getInventory().items) if (left > 0 && matches(s, q)) { int n = Math.min(left, s.getCount()); s.shrink(n); left -= n; }
        p.getInventory().setChanged();
    }

    // Coins: Lightman's Currency items of the main chain.
    private static Item coin(int index) { return BuiltInRegistries.ITEM.get(ResourceLocation.parse(GuildRules.COIN_IDS[index])); }
    private static boolean coinsAvailable() { for (int i = 0; i < GuildRules.COINS.length; i++) if (coin(i) == Items.AIR) return false; return true; }
    static void give(ServerPlayer p, long copper) {
        var counts = GuildRules.coins(copper);
        for (int i = 0; i < counts.length; i++) for (long left = counts[i]; left > 0; ) {
            int n = (int) Math.min(left, 64); left -= n; var stack = new ItemStack(coin(i), n);
            if (!p.getInventory().add(stack) && !stack.isEmpty()) { var dropped = p.drop(stack, false); if (dropped != null) { dropped.setNoPickUpDelay(); dropped.setTarget(p.getUUID()); } }
        }
        p.getInventory().setChanged();
    }
    private static long[] coinsHeld(ServerPlayer p) {
        var held = new long[GuildRules.COINS.length];
        for (var s : p.getInventory().items) for (int i = 0; i < held.length; i++) if (!s.isEmpty() && s.is(coin(i))) held[i] += s.getCount();
        return held;
    }
    private static boolean charge(ServerPlayer p, long fee) {
        var payment = GuildRules.pay(coinsHeld(p), fee); if (payment == null) return false;
        for (int i = 0; i < payment.take().length; i++) { long left = payment.take()[i]; for (var s : p.getInventory().items) if (left > 0 && s.is(coin(i))) { int n = (int) Math.min(left, s.getCount()); s.shrink(n); left -= n; } }
        give(p, payment.change()); return true;
    }

    private static GuildPayloads.Quest view(GuildCatalog.Quest q, GuildRules.Rank player) {
        return new GuildPayloads.Quest(q.code(), q.rank().ordinal(), q.kind() == GuildCatalog.Kind.KILL, icon(q), q.count(), q.name(), q.task(), q.min(), q.max(), GuildRules.xpFor(player, q.rank()), 0, false);
    }
    private static GuildPayloads.Quest teamView(GuildCatalog.Quest q, GuildStore.Card c, long window) {
        return new GuildPayloads.Quest(q.code(), q.rank().ordinal(), true, icon(q), q.count(), q.name(), q.task(), GuildRules.teamShare(q.min(), c.rank(), q.rank()), GuildRules.teamShare(q.max(), c.rank(), q.rank()),
            GuildRules.teamXp(c.rank(), q.rank()), q.party(), c.teamDone(window, q.code()));
    }
    /** The team set of one rank for a two-hour window: the same for every player. */
    static List<GuildCatalog.Quest> teamSet(MinecraftServer server, long window, int rank) { return catalog(server).teamSet(window, GuildRules.Rank.of(rank)); }
    static List<GuildCatalog.Quest> offers(MinecraftServer server, GuildStore.Card c, int rank) {
        var pool = catalog(server).rank(GuildRules.Rank.of(rank));
        return GuildRules.offers(pool.size(), c.seed, rank).stream().map(pool::get).toList();
    }
    static void send(ServerPlayer p, int screen, String message) {
        if (p instanceof net.neoforged.neoforge.common.util.FakePlayer) return;
        broadcastBadge(p); if (!p.connection.hasChannel(GuildPayloads.Snapshot.TYPE.id())) return;
        var store = GuildStore.get(p.server); var c = store.card(p.getUUID()); long now = System.currentTimeMillis();
        if (c.reset(now)) store.setDirty();
        var offers = new ArrayList<GuildPayloads.Quest>();
        if (c.opened) for (int r = 0; r <= Math.min(c.rank, GuildRules.BOARD_UP_TO.ordinal()); r++) for (var q : offers(p.server, c, r)) offers.add(view(q, c.rank()));
        var active = c.active.isEmpty() ? null : catalog(p.server).find(c.active);
        var activeView = active == null ? null : new GuildPayloads.Quest(active.code(), active.rank().ordinal(), active.kind() == GuildCatalog.Kind.KILL, icon(active), active.count(), active.name(), active.task(), c.reward, c.reward, GuildRules.xpFor(c.rank(), active.rank()), 0, false);
        int progress = active == null ? 0 : active.kind() == GuildCatalog.Kind.KILL ? c.progress : held(p, active);
        long window = GuildRules.window(now); var team = new ArrayList<GuildPayloads.Quest>();
        if (c.opened) for (int r = GuildRules.TEAM_FROM.ordinal(); r <= Math.min(c.rank + 1, GuildRules.Rank.S.ordinal()); r++) for (var q : teamSet(p.server, window, r)) team.add(teamView(q, c, window));
        var party = parties.of(p.getUUID()); var members = new ArrayList<String>(); GuildPayloads.Quest teamActive = null; int teamProgress = 0;
        if (party != null) {
            for (var id : party.members) { var m = member(p.server, id); var mc = store.existing(id);
                members.add((id.equals(party.leader) ? "★ " : "") + (m == null ? "игрок" : m.getGameProfile().getName()) + " · " + (mc == null ? "?" : mc.rank().name())); }
            var q = party.active.isEmpty() ? null : catalog(p.server).find(party.active);
            if (q != null) { long share = GuildRules.teamShare(party.reward, c.rank(), q.rank());
                teamActive = new GuildPayloads.Quest(q.code(), q.rank().ordinal(), true, icon(q), q.count(), q.name(), q.task(), share, share, GuildRules.teamXp(c.rank(), q.rank()), q.party(), false); teamProgress = party.progress; }
        }
        var up = c.rank().up();
        PacketDistributor.sendToPlayer(p, new GuildPayloads.Snapshot(c.opened, c.rank, c.xp, c.rank().next, c.completed, (int) ((c.cooldown(now) + 999) / 1000), c.earned, GuildRules.cap(c.rank()),
            c.canRankUp() == GuildStore.Result.OK, up == null ? 0 : GuildRules.fee(up), c.canRankUp() == GuildStore.Result.MAX_RANK,
            c.doneToday, GuildRules.dailyQuests(c.rank()), c.rank() != GuildRules.EXAM_RANK ? 0 : c.exam ? 2 : 1, offers, activeView, progress, screen, message,
            team, members, party != null && party.leader.equals(p.getUUID()), teamActive, teamProgress, (int) ((GuildRules.windowEnds(now) - now) / 1000), GuildRules.economy.rerollFee(), GuildRules.economy.teamEntryPercent()));
    }

    static void request(GuildPayloads.Request payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer p) || !admitted(p)) return;
            var queue = pending.computeIfAbsent(p.getUUID(), id -> new ArrayDeque<>());
            if (queue.size() < 6 && (queue.isEmpty() || !payload.action().equals("GET"))) queue.add(payload);
        });
    }
    private static String handle(ServerPlayer p, GuildPayloads.Request r) {
        var store = GuildStore.get(p.server); var c = store.card(p.getUUID()); long now = System.currentTimeMillis();
        if (r.action().equals("GET")) return "";
        if (!c.opened) return "Карточку выдают в зале гильдии.";
        if (!playing(p)) return "В творческом режиме и наблюдении задания недоступны.";
        if (site == null || p.level() != p.server.overworld() || !site.inHall(p.getX(), p.getY(), p.getZ())) return "Подойди к доске заданий в гильдии.";
        switch (r.action()) {
            case "TAKE" -> {
                if (r.rank() > Math.min(c.rank, GuildRules.BOARD_UP_TO.ordinal())) return "Это задание выше твоего ранга.";
                var list = offers(p.server, c, r.rank()); if (r.index() >= list.size()) return "Задание уже сменилось.";
                var q = list.get(r.index());
                return switch (c.take(q, now)) {
                    case OK -> { store.setDirty(); System.out.println("AOC_GUILD_TAKE " + p.getGameProfile().getName() + " " + q.code() + " reward=" + c.reward); yield "Задание принято: «" + q.name() + "»."; }
                    case BUSY -> "Сначала сдай или отмени текущее задание.";
                    case COOLDOWN -> "Новое задание через " + seconds(c.cooldown(now)) + ".";
                    case LIMIT -> "На сегодня всё: " + GuildRules.economy.dailyQuests() + " заданий. Новые — после полуночи по ЕКБ.";
                    default -> "Задание недоступно.";
                };
            }
            case "EXAM" -> {
                var exams = catalog(p.server).exams(); if (exams.isEmpty()) return "Экзамен пока не назначен.";
                var q = exams.get((int) Math.floorMod(c.seed, (long) exams.size()));
                return switch (c.exam(q)) {
                    case OK -> { store.setDirty(); System.out.println("AOC_GUILD_EXAM_START " + p.getGameProfile().getName() + " " + q.code()); yield "Экзамен: «" + q.name() + "». Сдай его на этой доске."; }
                    case BUSY -> "Сначала сдай или отмени текущее задание.";
                    case XP -> "Нужно ещё " + (c.rank().next - c.xp) + " опыта карточки.";
                    default -> "Экзамен сейчас не нужен.";
                };
            }
            case "TEAM_TAKE" -> {
                if (r.rank() < GuildRules.TEAM_FROM.ordinal()) return "Это не командное задание.";
                var party = parties.of(p.getUUID()); if (party == null) return "Нужна команда: /aocparty invite <ник>.";
                if (!party.leader.equals(p.getUUID())) return "Командное задание берёт лидер команды.";
                if (!party.active.isEmpty()) return "У команды уже есть задание.";
                long window = GuildRules.window(now); var list = teamSet(p.server, window, r.rank()); if (r.index() >= list.size()) return "Набор заданий сменился.";
                var q = list.get(r.index());
                if (c.rank < q.rank().ordinal()) return "Лидер команды должен быть ранга " + q.rank() + ".";
                if (party.members.size() < q.party()) return "Нужна команда от " + q.party() + " игроков.";
                for (var id : party.members) {
                    var m = member(p.server, id); var mc = store.existing(id); String name = m == null ? "Участник" : m.getGameProfile().getName();
                    if (m == null) return name + " не в сети.";
                    if (mc == null || !mc.opened) return name + ": нет карточки гильдии.";
                    if (GuildRules.teamShare(1000, mc.rank(), q.rank()) == 0) return name + ": ранг ниже " + GuildRules.Rank.of(q.rank().ordinal() - 1) + ".";
                    if (mc.teamDone(window, q.code())) return name + " уже выполнял это задание в этом наборе.";
                }
                long reward = GuildRules.roll(q.min(), q.max(), c.seed ^ now), entry = GuildRules.teamEntry(reward, party.members.size());
                if (entry > 0 && (!coinsAvailable() || !charge(p, entry))) return "Взнос за контракт " + GuildRules.money(entry) + ": положи монеты в инвентарь.";
                party.active = q.code(); party.window = window; party.progress = 0; party.reward = reward; store.ledger.reset(now); store.ledger.sink(entry); store.setDirty();
                System.out.println("AOC_GUILD_TEAM_TAKE " + p.getGameProfile().getName() + " " + q.code() + " members=" + party.members.size() + " reward=" + party.reward + " entry=" + entry);
                tell(p.server, party, "Команда взяла задание «" + q.name() + "»: " + q.task().toLowerCase(Locale.ROOT) + ".", p.getUUID());
                return "Команда взяла «" + q.name() + "»" + (entry > 0 ? " · взнос " + GuildRules.money(entry) + " сгорел." : ".");
            }
            case "TEAM_ABANDON" -> {
                var party = parties.of(p.getUUID());
                if (party == null || party.active.isEmpty()) return "У команды нет задания.";
                if (!party.leader.equals(p.getUUID())) return "Отменить может только лидер команды.";
                party.clear(); tell(p.server, party, "Лидер отменил командное задание.", p.getUUID()); return "Командное задание отменено.";
            }
            case "REROLL" -> {
                long fee = GuildRules.economy.rerollFee();
                if (fee > 0 && (!coinsAvailable() || !charge(p, fee))) return "Новые задания стоят " + GuildRules.money(fee) + ": положи монеты в инвентарь.";
                var sizes = new int[Math.min(c.rank, GuildRules.BOARD_UP_TO.ordinal()) + 1]; for (int i = 0; i < sizes.length; i++) sizes[i] = catalog(p.server).rank(GuildRules.Rank.of(i)).size();
                c.seed = GuildRules.freshSeed(c.seed, sizes); store.ledger.reset(now); store.ledger.sink(fee); store.setDirty();
                System.out.println("AOC_GUILD_REROLL " + p.getGameProfile().getName() + " fee=" + fee);
                return "На доске новые задания" + (fee > 0 ? " · взнос " + GuildRules.money(fee) + " сгорел." : ".");
            }
            case "ABANDON" -> {
                if (c.abandon(now) != GuildStore.Result.OK) return "Активного задания нет.";
                store.setDirty(); return "Задание отменено. Новое — через " + c.rank().cooldownMinutes + " мин.";
            }
            case "TURN_IN" -> {
                var q = c.active.isEmpty() ? null : catalog(p.server).find(c.active);
                if (q == null) { c.active = ""; store.setDirty(); return "Активного задания нет."; }
                if (!q.exam() && !coinsAvailable()) return "Монеты временно недоступны: мод валюты не загружен.";
                if (q.kind() == GuildCatalog.Kind.ITEM) { int have = held(p, q); if (have < q.count()) return "Не хватает: " + have + " из " + q.count() + "."; take(p, q, q.count()); }
                else if (c.progress < q.count()) return "Выполнено " + c.progress + " из " + q.count() + ".";
                if (q.exam()) { c.finish(q, now, store.ledger); store.setDirty(); System.out.println("AOC_GUILD_EXAM_PASSED " + p.getGameProfile().getName() + " " + q.code());
                    return "Экзамен сдан! Теперь можно повысить ранг до " + c.rank().up() + "."; }
                int xp = GuildRules.xpFor(c.rank(), q.rank()); long paid = c.finish(q, now, store.ledger); store.ledger.player(p.getUUID()); store.setDirty(); give(p, paid);
                System.out.println("AOC_GUILD_DONE " + p.getGameProfile().getName() + " " + q.code() + " paid=" + paid + " xp=" + xp + " issued_today=" + store.ledger.issued);
                return "Готово! +" + GuildRules.money(paid) + ", +" + xp + " опыта карточки.";
            }
            case "RANK_UP" -> {
                var check = c.canRankUp();
                if (check == GuildStore.Result.MAX_RANK) return "Ранг " + GuildRules.Rank.of(c.rank + 1) + " откроется в следующем обновлении гильдии.";
                if (check == GuildStore.Result.EXAM) return "Перед рангом " + c.rank().up() + " нужно сдать экзамен гильдии.";
                if (check != GuildStore.Result.OK) return "Нужно ещё " + (c.rank().next - c.xp) + " опыта карточки.";
                long fee = GuildRules.fee(c.rank().up());
                if (!coinsAvailable() || !charge(p, fee)) return "Взнос " + GuildRules.money(fee) + ": положи монеты в инвентарь.";
                c.rankUp(store.ledger, now); store.setDirty();
                System.out.println("AOC_GUILD_RANK " + p.getGameProfile().getName() + " " + c.rank() + " fee=" + fee);
                return "Новый ранг: " + c.rank() + "! Взнос " + GuildRules.money(fee) + " принят.";
            }
            default -> { return ""; }
        }
    }
    /** Today, the last week and where the coins went: what the owner reads while tuning the quest file. */
    static List<String> economyReport(GuildStore store, int days) {
        var l = store.ledger; l.reset(System.currentTimeMillis()); var e = GuildRules.economy; var out = new ArrayList<String>();
        var cards = new int[GuildRules.Rank.values().length]; for (var c : store.cards().values()) if (c.opened) cards[c.rank]++;
        out.add("Гильдия сегодня: выдано " + GuildRules.money(l.issued) + " из " + GuildRules.money(e.budget()) + ", сожжено " + GuildRules.money(l.burned)
            + ", удержано потолками " + GuildRules.money(l.withheld) + " · заданий " + l.solo + " + командных " + l.team + " · игроков " + l.players.size());
        var week = l.last(days); long issued = 0, burned = 0, withheld = 0; int quests = 0; var byRank = new long[cards.length];
        for (var d : week) { issued += d.issued(); burned += d.burned(); withheld += d.withheld(); quests += d.solo() + d.team(); for (int i = 0; i < byRank.length; i++) byRank[i] += d.byRank()[i]; }
        out.add("За " + week.size() + " дн.: выдано " + GuildRules.money(issued) + ", сожжено " + GuildRules.money(burned) + ", прирост монет " + (issued >= burned ? "" : "−") + GuildRules.money(Math.abs(issued - burned))
            + ", удержано " + GuildRules.money(withheld) + ", заданий " + quests);
        var ranks = new ArrayList<String>(); for (var r : GuildRules.Rank.values()) ranks.add(r + ": " + cards[r.ordinal()] + " карт., " + GuildRules.money(byRank[r.ordinal()]));
        out.add("По рангам за " + week.size() + " дн.: " + String.join(" · ", ranks));
        out.add("Всего с запуска: выдано " + GuildRules.money(l.totalIssued) + ", сожжено " + GuildRules.money(l.totalBurned) + " · настройки: " + e);
        return out;
    }
    static List<String> economyDays(GuildStore store) {
        var out = new ArrayList<String>();
        for (var d : store.ledger.last(14)) out.add(d.day() + ": +" + GuildRules.money(d.issued()) + " −" + GuildRules.money(d.burned()) + " · удержано " + GuildRules.money(d.withheld())
            + " · " + d.solo() + "+" + d.team() + " заданий · повышений " + d.rankUps() + " · игроков " + d.players());
        return out;
    }
    private static String seconds(long millis) { long s = (millis + 999) / 1000; return s >= 60 ? (s / 60) + " мин " + (s % 60) + " с" : s + " с"; }

    static void interact(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND || site == null || p.level() != p.server.overworld() || !site.board(event.getPos())) return;
        event.setCanceled(true); event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
        if (!admitted(p)) return;
        var store = GuildStore.get(p.server); var c = store.card(p.getUUID());
        if (c.open(new Random().nextLong())) { store.setDirty(); welcome(p); return; }
        send(p, GuildPayloads.SCREEN_BOARD, "");
    }
    private static void welcome(ServerPlayer p) {
        System.out.println("AOC_GUILD_CARD_OPENED " + p.getGameProfile().getName());
        p.sendSystemMessage(Component.literal("Гильдия искателей приключений выдала тебе карточку авантюриста. Ранг F. Задания — на доске у дальней стены.").withColor(0xffd27a));
        send(p, GuildPayloads.SCREEN_WELCOME, "");
    }
    /** Tells every online member of a team and refreshes their boards; skip is the player who gets the board answer anyway. */
    private static void tell(MinecraftServer server, GuildParty.Party party, String text, UUID skip) {
        for (var id : List.copyOf(party.members)) { var m = member(server, id); if (m == null) continue;
            m.sendSystemMessage(Component.literal(text).withColor(0xffd27a)); if (!id.equals(skip)) send(m, GuildPayloads.SCREEN_NONE, ""); }
    }
    private static boolean target(GuildCatalog.Quest q, EntityType<?> type) {
        var id = ResourceLocation.parse(q.id());
        return q.tag() ? type.is(TagKey.create(Registries.ENTITY_TYPE, id)) : BuiltInRegistries.ENTITY_TYPE.getKey(type).equals(id);
    }
    private static void teamKill(ServerPlayer p, GuildParty.Party party, EntityType<?> type) {
        var q = catalog(p.server).find(party.active); if (q == null) { party.clear(); return; }
        if (!target(q, type) || party.progress >= q.count()) return;
        party.progress++;
        for (var id : party.members) { var m = member(p.server, id); if (m != null) m.displayClientMessage(Component.literal(q.name() + " (команда): " + party.progress + "/" + q.count()).withColor(party.progress >= q.count() ? 0x7bea8a : 0xffd27a), true); }
        if (party.progress >= q.count()) completeTeam(p.server, party, q, p);
    }
    /** Pays every member still in the team: full share from the quest's rank, half one rank below; caps as for solo quests. */
    private static void completeTeam(MinecraftServer server, GuildParty.Party party, GuildCatalog.Quest q, ServerPlayer killer) {
        var store = GuildStore.get(server); long now = System.currentTimeMillis();
        for (var id : List.copyOf(party.members)) {
            var m = member(server, id); var card = store.existing(id); if (m == null || card == null) continue;
            if (q.count() == 1 && (m.level() != killer.level() || m.distanceToSqr(killer) > (double) BOSS_NEAR * BOSS_NEAR)) {
                m.sendSystemMessage(Component.literal("Команда победила босса «" + q.name() + "», но ты был дальше " + BOSS_NEAR + " блоков — награды нет.").withColor(0xffa04a));
                System.out.println("AOC_GUILD_TEAM_FAR " + m.getGameProfile().getName() + " " + q.code()); continue;
            }
            int xp = GuildRules.teamXp(card.rank(), q.rank()); long paid = card.teamFinish(q, party.window, GuildRules.teamShare(party.reward, card.rank(), q.rank()), now, store.ledger); store.ledger.player(id);
            if (coinsAvailable()) give(m, paid);
            m.sendSystemMessage(Component.literal("Командное задание «" + q.name() + "» выполнено! +" + GuildRules.money(paid) + ", +" + xp + " опыта карточки.").withColor(0x7bea8a));
            System.out.println("AOC_GUILD_TEAM_DONE " + m.getGameProfile().getName() + " " + q.code() + " rank=" + card.rank() + " paid=" + paid + " xp=" + xp + " issued_today=" + store.ledger.issued);
        }
        store.setDirty(); party.clear();
        for (var id : party.members) { var m = member(server, id); if (m != null) send(m, GuildPayloads.SCREEN_NONE, ""); }
    }
    static void leaveParty(MinecraftServer server, UUID id) {
        var party = parties.leave(id); if (party == null) return;
        var q = party.active.isEmpty() ? null : catalog(server).find(party.active);
        if (q != null && party.members.size() < q.party()) { party.clear(); tell(server, party, "Игрок вышел — в команде меньше " + q.party() + ", задание отменено.", null); }
        else tell(server, party, party.members.size() <= 1 ? "Команда распалась." : "Игрок вышел из команды.", null);
    }
    static void kill(LivingDeathEvent event) {
        // a boss often dies from a pet, fire or a fall: the kill credit (the last player who hit it) still counts
        var p = event.getSource().getEntity() instanceof ServerPlayer direct ? direct : event.getEntity().getKillCredit() instanceof ServerPlayer credit ? credit : null;
        if (event.isCanceled() || p == null || !playing(p) || event.getEntity().getPersistentData().getBoolean(UNNATURAL)) return;
        var party = parties.of(p.getUUID()); if (party != null && !party.active.isEmpty()) teamKill(p, party, event.getEntity().getType());
        var store = GuildStore.get(p.server); var c = store.existing(p.getUUID()); if (c == null || c.active.isEmpty()) return;
        var q = catalog(p.server).find(c.active); if (q == null || q.kind() != GuildCatalog.Kind.KILL) return;
        if (!target(q, event.getEntity().getType()) || c.progress >= q.count()) return;
        c.kill(q); store.setDirty();
        p.displayClientMessage(Component.literal(q.name() + ": " + c.progress + "/" + q.count()).withColor(c.progress >= q.count() ? 0x7bea8a : 0xffd27a), true);
    }
    /** Boss altars and boss spawners of the mods (Cataclysm, Twilight Forest) spawn as SPAWNER; such a boss still counts. */
    static void spawned(FinalizeSpawnEvent event) {
        var type = event.getSpawnType().name(); if (!UNNATURAL_SPAWNS.contains(type)) return;
        if ((type.equals("SPAWNER") || type.equals("TRIAL_SPAWNER")) && boss(event.getEntity().getType())) return;
        event.getEntity().getPersistentData().putBoolean(UNNATURAL, true);
    }
    static boolean boss(EntityType<?> type) { return BOSSES.stream().anyMatch(type::is); }

    static void tick(ServerTickEvent.Post event) {
        var server = event.getServer(); int now = server.getTickCount();
        if (siteChecked < 0 || now - siteChecked >= 600) { siteChecked = now; refreshSite(server); }
        if (site != null && !signDone && now % 100 == 0) sign(server);
        if (site != null && now % 20 == 0) for (var p : server.getPlayerList().getPlayers())
            if (playing(p) && p.level() == server.overworld() && site.inHall(p.getX(), p.getY(), p.getZ())) {
                var store = GuildStore.get(server); if (store.card(p.getUUID()).open(new Random().nextLong())) { store.setDirty(); welcome(p); }
            }
        long nano = System.nanoTime(); var it = pending.entrySet().iterator();
        while (it.hasNext()) {
            var item = it.next(); if (nano - requested.getOrDefault(item.getKey(), 0L) < 250_000_000L) continue;
            var request = item.getValue().poll(); if (item.getValue().isEmpty()) it.remove();
            var p = server.getPlayerList().getPlayer(item.getKey()); if (p == null || request == null || !admitted(p)) continue;
            requested.put(p.getUUID(), nano);
            send(p, GuildPayloads.SCREEN_NONE, handle(p, request));
        }
    }
    private static void refreshSite(MinecraftServer server) {
        site = AdventurerGuild.site(server.overworld());
    }
    /** The hall was built with "скоро" on the board sign; switch it once its chunk is loaded. */
    private static void sign(MinecraftServer server) {
        var level = server.overworld(); var pos = site.sign(); if (!level.isLoaded(pos)) return;
        signDone = true;
        if (level.getBlockEntity(pos) instanceof SignBlockEntity sign && sign.getFrontText().getMessage(2, false).getString().equals("скоро")) {
            var text = sign.getFrontText().setMessage(2, Component.literal("ПКМ — открыть")); sign.setText(text, true); sign.setChanged();
            level.sendBlockUpdated(pos, sign.getBlockState(), sign.getBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
            System.out.println("AOC_GUILD_SIGN updated");
        }
    }
    private static final Map<UUID, Integer> badges = new HashMap<>();
    static GuildPayloads.Badge badge(ServerPlayer p) { var c = GuildStore.get(p.server).existing(p.getUUID()); return new GuildPayloads.Badge(p.getUUID(), c == null || !c.opened ? -1 : c.rank); }
    /** The rank next to the name: sent to the player and everyone within 128 blocks whenever it changes, and to whoever starts tracking them. */
    static void broadcastBadge(ServerPlayer p) {
        var badge = badge(p); if (Objects.equals(badges.put(p.getUUID(), badge.rank()), badge.rank())) return;
        for (var o : p.server.getPlayerList().getPlayers())
            if (o.connection.hasChannel(GuildPayloads.Badge.TYPE.id()) && (o == p || o.serverLevel() == p.serverLevel() && o.distanceToSqr(p) < 128 * 128)) PacketDistributor.sendToPlayer(o, badge);
    }
    static void tracking(PlayerEvent.StartTracking event) {
        if (event.getEntity() instanceof ServerPlayer o && event.getTarget() instanceof ServerPlayer t && !(t instanceof net.neoforged.neoforge.common.util.FakePlayer) && o.connection.hasChannel(GuildPayloads.Badge.TYPE.id()))
            PacketDistributor.sendToPlayer(o, badge(t));
    }
    static void login(PlayerEvent.PlayerLoggedInEvent event) { if (event.getEntity() instanceof ServerPlayer p && admitted(p)) send(p, GuildPayloads.SCREEN_NONE, ""); }
    static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        pending.remove(event.getEntity().getUUID()); requested.remove(event.getEntity().getUUID()); badges.remove(event.getEntity().getUUID());
        if (event.getEntity() instanceof ServerPlayer p) leaveParty(p.server, p.getUUID());
    }
    static void stopped(ServerStoppedEvent event) { pending.clear(); requested.clear(); badges.clear(); parties.clear(); PROBE_MEMBERS.clear(); catalog = null; site = null; siteChecked = -1; signDone = false; }

    static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("aocparty").requires(s -> s.getEntity() instanceof ServerPlayer sp && admitted(sp))
            .then(Commands.literal("invite").then(Commands.argument("player", EntityArgument.player()).executes(ctx -> {
                var p = ctx.getSource().getPlayerOrException(); var t = EntityArgument.getPlayer(ctx, "player"); var store = GuildStore.get(p.server);
                var pc = store.existing(p.getUUID()); var tc = store.existing(t.getUUID());
                if (pc == null || !pc.opened || tc == null || !tc.opened) { ctx.getSource().sendFailure(Component.literal("У обоих игроков должна быть карточка гильдии.")); return 0; }
                var error = parties.invite(p.getUUID(), t.getUUID(), System.currentTimeMillis());
                if (error != null) { ctx.getSource().sendFailure(Component.literal(error)); return 0; }
                t.sendSystemMessage(Component.literal(p.getGameProfile().getName() + " зовёт тебя в команду гильдии. ").withColor(0xffd27a)
                    .append(Component.literal("[Вступить]").withStyle(s -> s.withColor(0x7bea8a).withUnderlined(true).withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/aocparty accept")))));
                ctx.getSource().sendSuccess(() -> Component.literal("Приглашение отправлено: " + t.getGameProfile().getName() + " (60 секунд)."), false); return 1;
            })))
            .then(Commands.literal("accept").executes(ctx -> {
                var p = ctx.getSource().getPlayerOrException(); var party = parties.accept(p.getUUID(), System.currentTimeMillis());
                if (party == null) { ctx.getSource().sendFailure(Component.literal("Приглашения нет, оно истекло или команда заполнена.")); return 0; }
                tell(p.server, party, p.getGameProfile().getName() + " вступил в команду (" + party.members.size() + "/" + GuildRules.MAX_PARTY + ").", null); return 1;
            }))
            .then(Commands.literal("leave").executes(ctx -> {
                var p = ctx.getSource().getPlayerOrException(); if (parties.of(p.getUUID()) == null) { ctx.getSource().sendFailure(Component.literal("Ты не в команде.")); return 0; }
                leaveParty(p.server, p.getUUID()); send(p, GuildPayloads.SCREEN_NONE, ""); ctx.getSource().sendSuccess(() -> Component.literal("Ты вышел из команды."), false); return 1;
            })));
        event.getDispatcher().register(Commands.literal("aocguild").requires(s -> s.hasPermission(2))
            .then(Commands.literal("reload").executes(ctx -> { var line = reload(ctx.getSource().getServer()); ctx.getSource().sendSuccess(() -> Component.literal(line), true); return 1; }))
            .then(Commands.literal("economy").executes(ctx -> { for (var line : economyReport(GuildStore.get(ctx.getSource().getServer()), 7)) ctx.getSource().sendSuccess(() -> Component.literal(line), false); return 1; })
                .then(Commands.literal("days").executes(ctx -> { for (var line : economyDays(GuildStore.get(ctx.getSource().getServer()))) ctx.getSource().sendSuccess(() -> Component.literal(line), false); return 1; })))
            .then(Commands.literal("rank").then(Commands.argument("player", EntityArgument.player()).then(Commands.argument("rank", StringArgumentType.word()).executes(ctx -> {
                var p = EntityArgument.getPlayer(ctx, "player"); var rank = GuildRules.Rank.valueOf(StringArgumentType.getString(ctx, "rank").toUpperCase(Locale.ROOT));
                var store = GuildStore.get(p.server); var c = store.card(p.getUUID()); c.open(new Random().nextLong()); c.rank = rank.ordinal(); c.xp = 0; c.seed++; store.setDirty(); send(p, GuildPayloads.SCREEN_NONE, "");
                ctx.getSource().sendSuccess(() -> Component.literal("Ранг " + p.getGameProfile().getName() + ": " + rank), true); return 1;
            }))))
            .then(Commands.literal("reset-cooldown").then(Commands.argument("player", EntityArgument.player()).executes(ctx -> {
                var p = EntityArgument.getPlayer(ctx, "player"); var store = GuildStore.get(p.server); store.card(p.getUUID()).cooldownUntil = 0; store.setDirty(); send(p, GuildPayloads.SCREEN_NONE, "");
                ctx.getSource().sendSuccess(() -> Component.literal("Перерыв снят: " + p.getGameProfile().getName()), true); return 1;
            }))));
    }
}
