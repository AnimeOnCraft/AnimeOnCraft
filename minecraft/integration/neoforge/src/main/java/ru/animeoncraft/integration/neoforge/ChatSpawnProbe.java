package ru.animeoncraft.integration.neoforge;

import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/** Opt-in runtime checks in an isolated loopback world; never admitted on the public server. */
final class ChatSpawnProbe {
    private ChatSpawnProbe() {}
    private static final class Listener extends FakePlayer {
        final List<String> inbox = new ArrayList<>();
        ChatVisiblity visibility = ChatVisiblity.FULL;
        Listener(ServerLevel level, String name) { super(level, new GameProfile(UUID.randomUUID(), name)); }
        @Override public void sendSystemMessage(Component message) { inbox.add(message.getString()); }
        @Override public ChatVisiblity getChatVisibility() { return visibility; }
    }
    static void started(ServerStartedEvent event) {
        var server = event.getServer();
        if (server.getPort() != 25569 || !"127.0.0.1".equals(server.getLocalIp())
            || !server.getWorldData().getLevelName().startsWith("aoc-chat-spawn-probe-"))
            throw new IllegalStateException("Chat/spawn probe requires its own loopback world");
        var level = server.overworld();
        var nether = server.getLevel(Level.NETHER);
        var players = List.of(new Listener(level, "AocSender"), new Listener(level, "AocNearby"),
            new Listener(level, "AocFar"), new Listener(nether, "AocNether"), new Listener(level, "AocOutsider"));
        var sender = players.get(0);
        try {
            for (int i = 0; i < players.size(); i++) {
                var player = players.get(i);
                player.setPos(i == 2 ? 1000 : i == 4 ? 20 : 0, 200, 0);
                String clan = i == 4 ? null : i == 1 ? "SCAM" : "NYA";
                GameIdentity.PLAYERS.put(player.getUUID(), new GameIdentity(clan, new byte[0], false, "", UUID.randomUUID(), UUID.randomUUID()));
            }
            var recipients = new ArrayList<ServerPlayer>(players);
            PlayerChat.deliver(sender, "рядом", recipients);
            check(players.get(0).inbox.size() == 1 && players.get(1).inbox.size() == 1 && players.get(4).inbox.size() == 1, "nearby players receive local");
            check(players.get(2).inbox.isEmpty() && players.get(3).inbox.isEmpty(), "local excludes far and other dimension");
            clear(players);
            PlayerChat.deliver(sender, "! всем", recipients);
            check(players.stream().allMatch(p -> p.inbox.size() == 1 && p.inbox.getFirst().equals("[Общий] AocSender: всем")), "global reaches all dimensions");
            clear(players);
            PlayerChat.deliver(sender, "!! секрет", recipients);
            check(players.get(0).inbox.size() == 1 && players.get(2).inbox.size() == 1 && players.get(3).inbox.size() == 1, "clan reaches same clan across dimensions");
            check(players.get(1).inbox.isEmpty() && players.get(4).inbox.isEmpty(), "clan does not leak to other or absent clans");
            clear(players);
            GameIdentity.PLAYERS.put(players.get(2).getUUID(), GameIdentity.PLAYERS.get(players.get(2).getUUID()).withClan("SCAM"));
            PlayerChat.deliver(sender, "!! после смены клана", recipients);
            check(players.get(2).inbox.isEmpty(), "membership update takes effect without reconnect");
            clear(players);
            PlayerChat.deliver(players.get(4), "!! без клана", recipients);
            check(players.get(4).inbox.size() == 1 && players.get(4).inbox.getFirst().contains("не состоите"), "clanless sender gets only an error");
            check(players.subList(0, 4).stream().allMatch(p -> p.inbox.isEmpty()), "clanless chat never broadcasts");
            clear(players);
            PlayerChat.deliver(sender, "!!   ", recipients);
            check(sender.inbox.size() == 1 && players.subList(1, 5).stream().allMatch(p -> p.inbox.isEmpty()), "empty prefix never broadcasts");
            clear(players);
            var removed = GameIdentity.PLAYERS.remove(players.get(4).getUUID());
            PlayerChat.deliver(players.get(4), "! без допуска", recipients);
            check(players.get(4).inbox.size() == 1 && players.subList(0, 4).stream().allMatch(p -> p.inbox.isEmpty()), "unadmitted sender cannot broadcast");
            GameIdentity.PLAYERS.put(players.get(4).getUUID(), removed);
            clear(players);
            players.get(1).visibility = ChatVisiblity.SYSTEM;
            PlayerChat.deliver(sender, "! скрытый чат", recipients);
            check(players.get(1).inbox.isEmpty(), "chat visibility respected");
            var chat = new ServerChatEvent(sender, "!! secret", Component.literal("!! secret"));
            NeoForge.EVENT_BUS.post(chat);
            check(chat.isCanceled(), "registered event cancels vanilla global broadcast");
            var area = CentralSpawn.zone();
            check(area != null && area.contains(0, 0) && !area.contains(0, 800), "central zone excludes shared boss arena");
            for (var type : List.of(EntityType.ZOMBIE, EntityType.COW, EntityType.VILLAGER)) {
                Mob mob = type.create(level); check(mob != null, "mob factory"); mob.setPos(0, 200, 0);
                check(!level.addFreshEntity(mob), "new hostile/passive/NPC denied at center: " + type);
                check(level.getEntity(mob.getUUID()) == null, "denied mob absent from actual world");
            }
            for (var reason : List.of(MobSpawnType.NATURAL, MobSpawnType.SPAWNER, MobSpawnType.SPAWN_EGG, MobSpawnType.COMMAND, MobSpawnType.BREEDING)) {
                var spawned = EntityType.COW.spawn(level, new BlockPos(0, 200, 0), reason);
                check(spawned == null || level.getEntity(spawned.getUUID()) == null, "spawn pipeline denied: " + reason);
            }
            var check = new MobSpawnEvent.SpawnPlacementCheck(EntityType.ZOMBIE, level, MobSpawnType.NATURAL, new BlockPos(10, 200, 10), level.random, true);
            NeoForge.EVENT_BUS.post(check);
            check(!check.getPlacementCheckResult(), "natural placement denied before entity allocation");
            var cow = EntityType.COW.create(level); check(cow != null, "cow factory"); cow.setPos(0, 200, 0);
            var loaded = new EntityJoinLevelEvent(cow, level, true); NeoForge.EVENT_BUS.post(loaded);
            check(!loaded.isCanceled(), "saved pets and NPCs preserved");
            var moved = new EntityJoinLevelEvent(cow, level, false); NeoForge.EVENT_BUS.post(moved);
            check(!moved.isCanceled(), "existing transported mob preserved");
            var saved = new net.minecraft.nbt.CompoundTag(); cow.saveWithoutId(saved);
            var transported = EntityType.COW.create(level); check(transported != null, "transported mob factory"); transported.load(saved);
            var restoredJoin = new EntityJoinLevelEvent(transported, level, false); NeoForge.EVENT_BUS.post(restoredJoin);
            check(!restoredJoin.isCanceled(), "existing marker survives NBT roundtrip used by dimension transfer");
            var arena = EntityType.ZOMBIE.create(level); check(arena != null, "arena mob factory"); arena.setPos(0, 200, 800);
            check(level.addFreshEntity(arena), "mob can spawn outside city at boss arena"); arena.discard();
            var otherDimension = EntityType.ZOMBIE.create(nether); check(otherDimension != null, "nether mob factory"); otherDimension.setPos(0, 100, 0);
            var otherJoin = new EntityJoinLevelEvent(otherDimension, nether, false); NeoForge.EVENT_BUS.post(otherJoin);
            check(!otherJoin.isCanceled(), "nether same coordinates unaffected");
            System.out.println("AOC_CHAT_SPAWN_PROBE_PASS channels=3 local_radius=100 central_radius=128 clan_privacy=true saved_mobs_preserved=true boss_arena_unaffected=true");
        } catch (RuntimeException failure) {
            System.out.println("AOC_CHAT_SPAWN_PROBE_FAILED " + failure); throw failure;
        } finally { for (var player : players) GameIdentity.PLAYERS.remove(player.getUUID()); }
    }
    private static void clear(List<Listener> players) { players.forEach(p -> p.inbox.clear()); }
    private static void check(boolean condition, String description) { if (!condition) throw new IllegalStateException(description); }
}
