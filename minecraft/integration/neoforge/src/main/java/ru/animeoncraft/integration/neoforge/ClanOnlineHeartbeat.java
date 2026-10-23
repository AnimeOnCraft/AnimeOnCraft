package ru.animeoncraft.integration.neoforge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.UUID;

/** Sends a bounded, server-owned presence snapshot after membership changes. */
final class ClanOnlineHeartbeat {
    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean();
    private static int nextTick;
    private static final java.util.concurrent.atomic.AtomicLong SNAPSHOT = new java.util.concurrent.atomic.AtomicLong();
    private ClanOnlineHeartbeat() {}

    static void send(MinecraftServer server) {
        if (!IN_FLIGHT.compareAndSet(false, true)) return;
        try {
            ServerSettings settings = ServerSettings.from(System.getenv());
            JsonArray players = new JsonArray();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                var identity = GameIdentity.PLAYERS.get(player.getUUID());
                if (identity == null) continue;
                JsonObject value = new JsonObject();
                value.addProperty("user_id", identity.userId().toString());
                value.addProperty("online", true);
                players.add(value);
            }
            JsonObject body = new JsonObject();
            body.addProperty("server_id", settings.serverId().toString());
            body.add("players", players);
            body.addProperty("snapshot_at",java.time.Instant.ofEpochMilli(SNAPSHOT.updateAndGet(previous->Math.max(previous+1,System.currentTimeMillis()))).toString());
            HttpJson.post(settings.heartbeatUrl(), settings.key(), body).thenAccept(reply->server.execute(()->{
                try{
                if(!reply.has("memberships")||!reply.get("memberships").isJsonArray()||reply.getAsJsonArray("memberships").size()>200)return;
                var memberships=new java.util.HashMap<UUID,String>();
                for(var value:reply.getAsJsonArray("memberships")){
                    var member=value.getAsJsonObject();String clan=member.get("clan_code").isJsonNull()?null:member.get("clan_code").getAsString();
                    if(clan!=null&&!ClanCodes.known(clan))throw new IllegalArgumentException("Invalid membership");
                    memberships.put(UUID.fromString(member.get("user_id").getAsString()),clan);
                }
                for(var player:server.getPlayerList().getPlayers()){
                    var identity=GameIdentity.PLAYERS.get(player.getUUID());
                    if(identity==null||!memberships.containsKey(identity.userId()))continue;
                    String clan=memberships.get(identity.userId());
                    if(java.util.Objects.equals(clan,identity.clan()))continue;
                    var updated=identity.withClan(clan);GameIdentity.PLAYERS.put(player.getUUID(),updated);
                    CapeAppearance.broadcast(player,updated);
                }
                }catch(RuntimeException invalid){System.getLogger("AnimeOnCraft").log(System.Logger.Level.WARNING,"Invalid clan membership snapshot ignored");}
            })).whenComplete((ignored, failure) -> IN_FLIGHT.set(false));
        } catch (RuntimeException failure) {
            IN_FLIGHT.set(false);
        }
    }

    static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() < nextTick) return;
        nextTick = event.getServer().getTickCount() + 400;
        send(event.getServer());
    }

    static void event(MinecraftServer server, String clan, UUID eventId, String kind, int xp, int reputation) {
        try {
            ServerSettings settings = ServerSettings.from(System.getenv());
            JsonObject body = new JsonObject();
            body.addProperty("server_id", settings.serverId().toString()); body.addProperty("event_id", eventId.toString());
            body.addProperty("clan_code", clan); body.addProperty("kind", kind); body.addProperty("xp_delta", xp); body.addProperty("reputation_delta", reputation);
            body.addProperty("occurred_at", java.time.Instant.now().toString());
            GameplayOutbox.store(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("animeoncraft-gameplay-outbox"), body);
        } catch (java.io.IOException | RuntimeException failure) {
            System.getLogger("AnimeOnCraft").log(System.Logger.Level.ERROR, "Could not persist gameplay reward event");
        }
    }
}
