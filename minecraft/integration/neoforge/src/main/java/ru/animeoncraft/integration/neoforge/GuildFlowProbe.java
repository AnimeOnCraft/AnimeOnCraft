package ru.animeoncraft.integration.neoforge;

import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Disposable review client: plays the guild board in a copy of a layout-test world (singleplayer, survival) and takes
 * screenshots of every step. Board at x 0, z 149 (door faces north), floor 90.
 */
final class GuildFlowProbe {
    private int ticks, step, wait; private final List<String> log = new ArrayList<>();
    static void register() { new GuildFlowProbe(); }
    private GuildFlowProbe() { NeoForge.EVENT_BUS.addListener(this::tick); }
    private static boolean enabled() { return Boolean.getBoolean("animeoncraft.guildFlowProbe") && Minecraft.getInstance().gameDirectory.getAbsolutePath().contains("guild-flow-"); }

    private void tick(ClientTickEvent.Post event) {
        if (!enabled()) return;
        var mc = Minecraft.getInstance(); ticks++;
        if (mc.player == null) {
            if (mc.screen != null && ticks % 30 == 0) for (var child : mc.screen.children())
                if (child instanceof Button b && b.active && Set.of("Proceed", "I know what I'm doing!", "Yes").contains(b.getMessage().getString())) b.onPress();
            return;
        }
        if (ticks < 200 || ++wait < delay()) return;
        wait = 0; var server = Objects.requireNonNull(mc.getSingleplayerServer()); var id = mc.player.getUUID();
        switch (step++) {
            case 0 -> server(mc, p -> {
                p.setGameMode(GameType.SURVIVAL); var level = p.serverLevel();
                level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DAYLIGHT).set(false, server);
                level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_WEATHER_CYCLE).set(false, server);
                level.setDayTime(5000); level.setWeatherParameters(120000, 0, false, false); p.getInventory().clearContent();
                p.teleportTo(level, 0.5, 91, 128.5, 180, 5);
            });
            case 1 -> shot(mc, "01-approach-guild");
            case 2 -> server(mc, p -> p.teleportTo(p.serverLevel(), 0.5, 91, 138.5, 180, 0));
            case 3 -> { check(mc.screen instanceof GuildScreen s && s.welcome(), "welcome card opened on entering the hall"); shot(mc, "02-welcome-adventurer-card"); }
            case 4 -> { mc.setScreen(null); server(mc, p -> p.teleportTo(p.serverLevel(), 0.5, 91, 144.5, 0, -8)); }
            case 5 -> shot(mc, "03-board-sign");
            case 6 -> mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(new Vec3(0.5, 93.5, 149), Direction.NORTH, new BlockPos(0, 93, 149), false));
            case 7 -> { check(mc.screen instanceof GuildScreen s && !s.welcome(), "right click on the board opens it"); shot(mc, "04-board-rank-F"); }
            case 8 -> server(mc, p -> { // hand the player exactly what the first item quest of their board asks for
                var c = GuildStore.get(server).card(p.getUUID()); var offers = GuildServer.offers(server, c, 0);
                for (int i = 0; i < offers.size(); i++) if (offers.get(i).kind() == GuildCatalog.Kind.ITEM) {
                    var q = offers.get(i); var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemOf(q)));
                    for (int left = q.count(); left > 0; left -= 64) p.getInventory().add(new ItemStack(item, Math.min(64, left)));
                    itemOffer = i; System.out.println("AOC_GUILD_FLOW item quest " + q.code() + " x" + q.count()); break;
                }
            });
            case 9 -> ClientGuild.request("TAKE", 0, itemOffer);
            case 10 -> { check(ClientGuild.data().active() != null, "quest taken"); shot(mc, "05-item-quest-taken"); }
            case 11 -> ClientGuild.request("TURN_IN", 0, 0);
            case 12 -> { check(ClientGuild.data().active() == null && ClientGuild.data().completed() == 1 && ClientGuild.data().cooldown() > 0, "turned in, paid, cooldown started"); shot(mc, "06-turned-in-cooldown"); }
            case 13 -> mc.setScreen(new InventoryScreen(mc.player));
            case 14 -> shot(mc, "07-coins-in-inventory");
            case 15 -> { mc.setScreen(null); server(mc, p -> { var store = GuildStore.get(server); var c = store.card(p.getUUID()); c.cooldownUntil = 0; c.active = "f_zombies"; c.progress = 0; c.reward = 4; store.setDirty();
                p.teleportTo(p.serverLevel(), 0.5, 91, 140.5, 180, 15); }); }
            case 16 -> server(mc, p -> { spawnAndKill(p, EntityType.ZOMBIE, MobSpawnType.SPAWNER); });
            case 17 -> server(mc, p -> { check(GuildStore.get(server).card(p.getUUID()).progress == 0, "spawner zombie does not count"); spawnAndKill(p, EntityType.ZOMBIE, MobSpawnType.NATURAL); });
            case 18 -> { server(mc, p -> check(GuildStore.get(server).card(p.getUUID()).progress == 1, "natural zombie counts")); shot(mc, "08-kill-progress-actionbar"); }
            case 19 -> { server(mc, p -> GuildServer.send(p, GuildPayloads.SCREEN_BOARD, "")); }
            case 20 -> shot(mc, "09-kill-quest-in-progress");
            case 21 -> server(mc, p -> { var store = GuildStore.get(server); var c = store.card(p.getUUID()); c.abandon(System.currentTimeMillis()); c.cooldownUntil = 0; c.xp = 11; store.setDirty();
                p.getInventory().add(new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("lightmanscurrency:coin_gold")), 1)); GuildServer.send(p, GuildPayloads.SCREEN_BOARD, ""); });
            case 22 -> { check(ClientGuild.data().canRankUp(), "rank-up offered"); shot(mc, "10-rank-up-available"); }
            case 23 -> ClientGuild.request("RANK_UP", 0, 0);
            case 24 -> { check(ClientGuild.data().rank() == 1, "rank E after fee"); shot(mc, "11-rank-E-new-tab"); }
            case 25 -> server(mc, p -> { var store = GuildStore.get(server); var c = store.card(p.getUUID()); c.rank = 2; c.xp = 60; store.setDirty(); GuildServer.send(p, GuildPayloads.SCREEN_BOARD, ""); });
            case 26 -> shot(mc, "12-rank-D-cap-for-now");
            // Stage 2: rank C board, daily limit, exam, rank B.
            case 27 -> server(mc, p -> { var store = GuildStore.get(server); var c = store.card(p.getUUID()); c.rank = 3; c.xp = 0; c.doneToday = 0; c.cooldownUntil = 0; c.active = ""; store.setDirty(); GuildServer.send(p, GuildPayloads.SCREEN_BOARD, ""); });
            case 28 -> { check(ClientGuild.data().limit() == 8, "rank C shows the daily limit"); shot(mc, "13-rank-C-board"); }
            case 29 -> server(mc, p -> {
                var c = GuildStore.get(server).card(p.getUUID()); var offers = GuildServer.offers(server, c, 3);
                for (int i = 0; i < offers.size(); i++) if (offers.get(i).kind() == GuildCatalog.Kind.ITEM) {
                    var q = offers.get(i); var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemOf(q)));
                    for (int left = q.count(); left > 0; left -= 64) p.getInventory().add(new ItemStack(item, Math.min(64, left)));
                    itemOffer = i; System.out.println("AOC_GUILD_FLOW C quest " + q.code() + " x" + q.count()); break;
                }
            });
            case 30 -> ClientGuild.request("TAKE", 3, itemOffer);
            case 31 -> ClientGuild.request("TURN_IN", 0, 0);
            case 32 -> { check(ClientGuild.data().done() == 1 && ClientGuild.data().active() == null, "rank C quest counted for today"); shot(mc, "14-rank-C-quest-done"); }
            case 33 -> server(mc, p -> { var store = GuildStore.get(server); var c = store.card(p.getUUID()); c.doneToday = 8; c.cooldownUntil = 0; store.setDirty(); GuildServer.send(p, GuildPayloads.SCREEN_BOARD, ""); });
            case 34 -> ClientGuild.request("TAKE", 3, 0);
            case 35 -> { check(ClientGuild.data().active() == null && ClientGuild.message().startsWith("На сегодня"), "ninth quest of the day is refused"); shot(mc, "15-daily-limit"); }
            case 36 -> server(mc, p -> { var store = GuildStore.get(server); var c = store.card(p.getUUID()); c.xp = 125; c.doneToday = 0; c.cooldownUntil = System.currentTimeMillis() + 600_000; store.setDirty(); GuildServer.send(p, GuildPayloads.SCREEN_BOARD, ""); });
            case 37 -> { check(ClientGuild.data().exam() == 1 && !ClientGuild.data().canRankUp(), "rank B waits for the exam"); shot(mc, "16-exam-button"); }
            case 38 -> ClientGuild.request("EXAM", 0, 0);
            case 39 -> { var a = ClientGuild.data().active(); check(a != null && a.name().startsWith("Экзамен"), "exam taken despite cooldown"); shot(mc, "17-exam-active"); }
            case 40 -> server(mc, p -> { // real kills of the exam's target, as natural spawns
                var c = GuildStore.get(server).card(p.getUUID()); var q = GuildServer.catalog(server).find(c.active);
                var type = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse(q.id()));
                for (int i = 0; i < q.count(); i++) spawnAndKill(p, type, MobSpawnType.NATURAL);
                GuildServer.send(p, GuildPayloads.SCREEN_NONE, "");
            });
            case 41 -> shot(mc, "18-exam-progress");
            case 42 -> ClientGuild.request("TURN_IN", 0, 0);
            case 43 -> { check(ClientGuild.data().exam() == 2 && ClientGuild.data().canRankUp(), "exam passed, rank-up open"); shot(mc, "19-exam-passed"); }
            case 44 -> server(mc, p -> p.getInventory().add(new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("lightmanscurrency:coin_gold")), 3)));
            case 45 -> ClientGuild.request("RANK_UP", 0, 0);
            case 46 -> { check(ClientGuild.data().rank() == 4, "rank B after exam and fee"); shot(mc, "20-rank-B-team-tab"); }
            // Stage 3: a team of two (the second member is a stand-in player of rank C), one team quest of rank B.
            case 47 -> server(mc, p -> {
                var mate = net.neoforged.neoforge.common.util.FakePlayerFactory.get(p.serverLevel(), new com.mojang.authlib.GameProfile(MATE, "AocGuildMate"));
                GuildServer.PROBE_MEMBERS.put(MATE, mate); long now = System.currentTimeMillis();
                var store = GuildStore.get(server); var m = store.card(MATE); m.open(7); m.rank = 3; var c = store.card(p.getUUID()); c.cooldownUntil = 0; c.active = ""; store.setDirty();
                p.getInventory().add(new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("lightmanscurrency:coin_gold")), 1)); burnedBefore = store.ledger.burned; // the leader pays the contract entry
                check(GuildServer.parties.invite(p.getUUID(), MATE, now) == null && GuildServer.parties.accept(MATE, now) != null, "team of two formed");
                GuildServer.send(p, GuildPayloads.SCREEN_BOARD, "");
            });
            case 48 -> { check(ClientGuild.data().members().size() == 2 && ClientGuild.data().leader() && ClientGuild.data().team().size() >= 2, "board shows the team and the B set"); shot(mc, "21-team-tab"); }
            case 49 -> ClientGuild.request("TEAM_TAKE", 4, 0);
            case 50 -> { check(ClientGuild.data().teamActive() != null, "team quest taken"); shot(mc, "22-team-quest-taken");
                server(mc, p -> { var party = GuildServer.parties.of(p.getUUID()); long entry = GuildStore.get(server).ledger.burned - burnedBefore;
                    System.out.println("AOC_GUILD_FLOW team entry " + entry + " of reward " + party.reward);
                    check(entry > 0 && entry == GuildRules.teamEntry(party.reward, 2), "leader burned the contract entry: 10% of the reward per member"); }); }
            case 51 -> { mc.setScreen(null); server(mc, p -> {
                var party = GuildServer.parties.of(p.getUUID()); var q = GuildServer.catalog(server).find(party.active); teamReward = party.reward;
                for (int i = 0; i < q.count() - 1; i++) spawnAndKill(p, entityOf(q), MobSpawnType.NATURAL);
            }); }
            case 52 -> shot(mc, "23-team-progress");
            case 53 -> server(mc, p -> { var q = GuildServer.catalog(server).find(GuildServer.parties.of(p.getUUID()).active); spawnAndKill(p, entityOf(q), MobSpawnType.NATURAL); });
            case 54 -> server(mc, p -> {
                var store = GuildStore.get(server); var m = store.existing(MATE); var w = GuildRules.window(System.currentTimeMillis());
                check(GuildServer.parties.of(p.getUUID()).active.isEmpty() && m.earned == teamReward / 2 && m.xp == 4, "team quest paid: lower rank member got half");
                GuildServer.send(p, GuildPayloads.SCREEN_BOARD, "");
            });
            case 55 -> { check(ClientGuild.data().teamActive() == null && ClientGuild.data().team().stream().anyMatch(GuildPayloads.Quest::done), "quest marked done for this set"); shot(mc, "24-team-quest-done"); }
            case 56 -> ClientGuild.request("TEAM_TAKE", 4, 0);
            case 57 -> { check(ClientGuild.message().contains("уже выполнял"), "same set quest refused the second time"); shot(mc, "25-team-once-per-window"); }
            case 58 -> server(mc, p -> { GuildServer.leaveParty(server, MATE); GuildServer.send(p, GuildPayloads.SCREEN_NONE, ""); });
            case 59 -> check(ClientGuild.data().members().isEmpty(), "team of one dissolves when the mate leaves");
            // Bosses of the mods: a boss from a spawn egg does not count, one from a boss spawner does; only members near the boss are paid.
            case 60 -> server(mc, p -> {
                var store = GuildStore.get(p.server); var c = store.card(p.getUUID()); c.rank = GuildRules.Rank.A.ordinal(); c.cooldownUntil = 0;
                var mate = GuildServer.PROBE_MEMBERS.get(MATE); mate.moveTo(p.getX() + 3, p.getY(), p.getZ()); store.card(MATE).rank = GuildRules.Rank.B.ordinal(); store.setDirty();
                long now = System.currentTimeMillis();
                check(GuildServer.parties.invite(p.getUUID(), MATE, now) == null && GuildServer.parties.accept(MATE, now) != null, "boss team formed");
                var party = GuildServer.parties.of(p.getUUID()); party.active = "a_any_boss"; party.window = GuildRules.window(now); party.reward = 230; party.progress = 0;
                leadBefore = c.earned; mateBefore = store.card(MATE).earned;
                spawnAndKill(p, BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("twilightforest:naga")), MobSpawnType.SPAWN_EGG);
                check(party.progress == 0 && GuildServer.catalog(p.server).teamSet(party.window, GuildRules.Rank.A).get(1).boss(), "boss from a spawn egg does not count; the A set has a boss");
                GuildServer.send(p, GuildPayloads.SCREEN_BOARD, "");
            });
            case 61 -> shot(mc, "27-boss-quest-A");
            case 62 -> { mc.setScreen(null); server(mc, p -> {
                spawnAndKill(p, BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("twilightforest:naga")), MobSpawnType.SPAWNER);
                var store = GuildStore.get(p.server);
                check(GuildServer.parties.of(p.getUUID()).active.isEmpty() && store.card(p.getUUID()).earned - leadBefore == 230 && store.card(MATE).earned - mateBefore == 115,
                    "Twilight Forest naga from its boss spawner paid the team (B member half)");
            }); }
            case 63 -> server(mc, p -> {
                var store = GuildStore.get(p.server); store.card(p.getUUID()).rank = GuildRules.Rank.S.ordinal(); store.card(MATE).rank = GuildRules.Rank.A.ordinal();
                GuildServer.PROBE_MEMBERS.get(MATE).moveTo(p.getX() + 300, p.getY(), p.getZ());
                var party = GuildServer.parties.of(p.getUUID()); party.active = "s_cm_ignis"; party.window = GuildRules.window(System.currentTimeMillis()); party.reward = 380; party.progress = 0;
                leadBefore = store.card(p.getUUID()).earned; mateBefore = store.card(MATE).earned;
                spawnAndKill(p, BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("cataclysm:ignis")), MobSpawnType.SPAWNER);
                check(party.active.isEmpty() && store.card(p.getUUID()).earned - leadBefore == 380 && store.card(MATE).earned == mateBefore,
                    "Cataclysm Ignis from its altar paid the leader; the member 300 blocks away got nothing");
            });
            case 64 -> shot(mc, "28-boss-S-done");
            // Stage 4: the coin report the owner reads while tuning the quest file (/aocguild economy).
            case 65 -> { mc.setScreen(null); server(mc, p -> {
                var store = GuildStore.get(p.server); var lines = GuildServer.economyReport(store, 7);
                for (var line : lines) { System.out.println("AOC_GUILD_ECONOMY " + line); p.sendSystemMessage(net.minecraft.network.chat.Component.literal(line)); }
                check(lines.size() == 4 && store.ledger.team >= 2 && store.ledger.solo >= 1 && !store.ledger.players.isEmpty(), "economy report counts today's quests and players");
            }); }
            case 66 -> shot(mc, "26-economy-report");
            // Coin sinks and the card outside the hall: new quests for 5 copper, the rank next to the name, the Tab profile and guide.
            case 67 -> server(mc, p -> {
                var store = GuildStore.get(server); var c = store.card(p.getUUID()); c.rank = 3; c.cooldownUntil = 0; c.active = ""; store.setDirty();
                p.getInventory().add(new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("lightmanscurrency:coin_iron")), 1));
                boardBefore = GuildServer.offers(server, c, 3).stream().map(GuildCatalog.Quest::code).toList().toString(); burnedBefore = store.ledger.burned;
                p.teleportTo(p.serverLevel(), 0.5, 91, 144.5, 0, -8); GuildServer.send(p, GuildPayloads.SCREEN_BOARD, "");
            });
            case 68 -> ClientGuild.request("REROLL", 0, 0);
            case 69 -> { check(ClientGuild.message().startsWith("На доске новые задания"), "new quests on the board for a fee"); shot(mc, "29-board-new-quests");
                server(mc, p -> { var store = GuildStore.get(server); var now = GuildServer.offers(server, store.card(p.getUUID()), 3).stream().map(GuildCatalog.Quest::code).toList().toString();
                    var old = java.util.Arrays.asList(boardBefore.substring(1, boardBefore.length() - 1).split(", "));
                    check(java.util.Arrays.stream(now.substring(1, now.length() - 1).split(", ")).noneMatch(old::contains) && store.ledger.burned - burnedBefore == GuildRules.economy.rerollFee(), "all three quests replaced and 5 copper burned: " + boardBefore + " -> " + now); }); }
            case 70 -> { mc.setScreen(null); server(mc, p -> {
                var store = GuildStore.get(server); store.card(p.getUUID()).rank = 4; store.setDirty(); GuildServer.send(p, GuildPayloads.SCREEN_NONE, "");
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, new GuildPayloads.Badge(MATE, 5)); // what a nearby A-rank player would get
                p.teleportTo(p.serverLevel(), 0.5, 91, 140.5, 0, 2);
            }); }
            case 71 -> {
                mate = new net.minecraft.client.player.RemotePlayer(mc.level, new com.mojang.authlib.GameProfile(MATE, "AocGuildMate"));
                mate.moveTo(0.5, 91, 143.5, 180, 0); mate.setYHeadRot(180); mate.yBodyRot = 180; mc.level.addEntity(mate);
                check(ClientGuild.rank(id) == 4 && ClientGuild.rank(MATE) == 5, "ranks next to names reach the client (own B after rank change, nearby A)");
            }
            case 72 -> shot(mc, "30-nameplate-rank");
            case 73 -> { if (mate != null) mate.discard(); var s = new ServerMenuScreen(); s.show(ServerMenuLogic.Page.PROFILE, 0); mc.setScreen(s); }
            case 74 -> shot(mc, "31-tab-profile-rank");
            case 75 -> { if (mc.screen instanceof ServerMenuScreen s) s.show(ServerMenuLogic.Page.PROFILE, 3); check(ClientGuild.data().opened() && ClientGuild.data().rank() == 4, "Tab profile reads the adventurer card"); }
            case 76 -> shot(mc, "32-tab-guild-card");
            case 77 -> { if (mc.screen instanceof ServerMenuScreen s) s.show(ServerMenuLogic.Page.GUIDE, 4); }
            case 78 -> shot(mc, "33-guide-guild");
            // Coins only from the guild: the reset on login, a chest of an old chunk, mob drops.
            case 79 -> { mc.setScreen(null); server(mc, p -> {
                var store = CoinReset.Store.get(server); store.players.remove(p.getUUID()); p.getInventory().clearContent(); p.getEnderChestInventory().clearContent();
                p.getInventory().add(new ItemStack(item("lightmanscurrency:coin_gold"), 5)); p.getInventory().add(new ItemStack(item("lightmanscurrency:coinpile_iron"), 2));
                p.getInventory().add(new ItemStack(item("lightmanscurrency:coin_chocolate_gold"), 2));
                var box = new ItemStack(net.minecraft.world.item.Items.SHULKER_BOX);
                box.set(net.minecraft.core.component.DataComponents.CONTAINER, net.minecraft.world.item.component.ItemContainerContents.fromItems(List.of(new ItemStack(item("lightmanscurrency:coin_diamond"), 3), new ItemStack(net.minecraft.world.item.Items.DIRT, 4))));
                p.getInventory().add(box); p.getEnderChestInventory().setItem(0, new ItemStack(item("lightmanscurrency:coin_emerald"), 7));
                CoinReset.login(new net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent(p));
                long copper = 0, other = 0, chocolate = 0;
                for (var s : p.getInventory().items) { var key = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
                    if (key.equals("lightmanscurrency:coin_copper")) copper += s.getCount(); else if (GuildRules.coinId(key)) other += s.getCount(); else if (key.contains("chocolate")) chocolate += s.getCount();
                    if (s.is(net.minecraft.world.item.Items.SHULKER_BOX)) check(s.get(net.minecraft.core.component.DataComponents.CONTAINER).nonEmptyStream().noneMatch(CoinReset::coin), "coins taken out of the shulker box, dirt kept"); }
                check(copper == 64 && other == 0 && chocolate == 2 && p.getEnderChestInventory().isEmpty() && store.players.contains(p.getUUID()), "login reset: old coins gone, 64 copper given, chocolate coins kept");
                CoinReset.login(new net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent(p));
                check(p.getInventory().countItem(item("lightmanscurrency:coin_copper")) == 64, "the next login changes nothing");
            }); }
            case 80 -> server(mc, p -> {
                var level = p.serverLevel(); var pos = new BlockPos(3, 91, 141);
                level.setBlockAndUpdate(pos, net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState());
                var chest = (net.minecraft.world.level.block.entity.ChestBlockEntity) level.getBlockEntity(pos);
                chest.setItem(0, new ItemStack(item("lightmanscurrency:coin_gold"), 9)); chest.setItem(1, new ItemStack(net.minecraft.world.item.Items.BREAD, 3)); chest.setItem(2, new ItemStack(item("lightmanscurrency:coinblock_copper"), 1));
                int removed = CoinReset.cleanChunk(level, level.getChunkAt(pos));
                check(removed >= 10 && chest.countItem(net.minecraft.world.item.Items.BREAD) == 3 && !chest.hasAnyMatching(CoinReset::coin) && level.getChunkAt(pos).hasData(CoinReset.CHUNK_DONE), "old chest emptied of coins, bread kept, chunk marked");
                level.setBlockAndUpdate(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                var zombie = EntityType.ZOMBIE.create(level); zombie.moveTo(p.getX(), p.getY(), p.getZ());
                var drops = new ArrayList<net.minecraft.world.entity.item.ItemEntity>(List.of(new net.minecraft.world.entity.item.ItemEntity(level, p.getX(), p.getY(), p.getZ(), new ItemStack(item("lightmanscurrency:coin_iron"), 2)),
                    new net.minecraft.world.entity.item.ItemEntity(level, p.getX(), p.getY(), p.getZ(), new ItemStack(net.minecraft.world.item.Items.ROTTEN_FLESH, 1))));
                var dropped = new net.neoforged.neoforge.event.entity.living.LivingDropsEvent(zombie, p.damageSources().playerAttack(p), drops, true); CoinReset.drops(dropped);
                check(dropped.getDrops().size() == 1 && dropped.getDrops().iterator().next().getItem().is(net.minecraft.world.item.Items.ROTTEN_FLESH), "mobs drop no coins");
            });
            case 81 -> mc.setScreen(new InventoryScreen(mc.player));
            case 82 -> shot(mc, "34-coin-reset-start-purse");
            default -> { if (step == 84) { System.out.println("AOC_GUILD_FLOW_DONE " + (log.isEmpty() ? "ok" : String.join("; ", log))); mc.stop(); } }
        }
    }
    private int itemOffer; private volatile long teamReward, leadBefore, mateBefore, burnedBefore; private volatile String boardBefore = ""; private net.minecraft.client.player.RemotePlayer mate;
    private static final UUID MATE = UUID.fromString("00000000-0000-4000-8000-0000000000aa");
    private static net.minecraft.world.item.Item item(String id) { return BuiltInRegistries.ITEM.get(ResourceLocation.parse(id)); }
    private static EntityType<?> entityOf(GuildCatalog.Quest q) {
        var id = ResourceLocation.parse(q.id());
        if (!q.tag()) return BuiltInRegistries.ENTITY_TYPE.get(id);
        return BuiltInRegistries.ENTITY_TYPE.getTag(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ENTITY_TYPE, id)).<EntityType<?>>map(t -> t.get(0).value()).orElse(EntityType.ZOMBIE);
    }
    private int delay() { return step == 0 ? 300 : step == 3 ? 80 : step == 18 || step == 41 || step == 52 ? 6 : 60; }
    private void check(boolean ok, String what) { System.out.println("AOC_GUILD_FLOW " + (ok ? "PASS " : "FAIL ") + what); if (!ok) log.add(what); }
    private static String itemOf(GuildCatalog.Quest q) {
        if (!q.tag()) return q.id();
        return BuiltInRegistries.ITEM.getTag(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM, ResourceLocation.parse(q.id()))).map(t -> BuiltInRegistries.ITEM.getKey(t.get(0).value()).toString()).orElse("minecraft:air");
    }
    private static void spawnAndKill(ServerPlayer p, EntityType<?> entity, MobSpawnType type) {
        var level = p.serverLevel(); var z = (net.minecraft.world.entity.Mob) entity.create(level); z.moveTo(p.getX(), p.getY(), p.getZ() + 2.5, 0, 0);
        z.getPersistentData().putBoolean("aoc_spawn_existing_mob", true); // the Sora no-spawn zone would cancel a fresh mob
        net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(z, level, level.getCurrentDifficultyAt(z.blockPosition()), type, null);
        level.addFreshEntity(z); var hit = p.damageSources().playerAttack(p);
        for (int i = 0; i < 400 && z.isAlive(); i++) { z.invulnerableTime = 0; z.hurt(hit, 1000); } // bosses cap the damage of one hit
        if (z.isAlive()) { System.out.println("AOC_GUILD_FLOW forced death of " + BuiltInRegistries.ENTITY_TYPE.getKey(entity)); z.setHealth(0); z.die(hit); }
        System.out.println("AOC_GUILD_FLOW killed " + type + " dead=" + z.isDeadOrDying());
    }
    private static void server(Minecraft mc, java.util.function.Consumer<ServerPlayer> action) {
        var server = Objects.requireNonNull(mc.getSingleplayerServer()); var id = mc.player.getUUID();
        server.execute(() -> action.accept(server.getPlayerList().getPlayer(id)));
    }
    private static void shot(Minecraft mc, String name) { Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), ignored -> {}); System.out.println("AOC_GUILD_FLOW_SHOT " + name); }
}
