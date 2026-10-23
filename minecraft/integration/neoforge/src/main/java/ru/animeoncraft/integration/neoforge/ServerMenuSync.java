package ru.animeoncraft.integration.neoforge;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** On-demand shared cache. HTTP never blocks the game thread; clients cannot query as another player. */
final class ServerMenuSync {
    private static final Map<UUID,Integer> requested=new HashMap<>();
    private record Offered(String code,int tick){}
    private static final Map<UUID,Offered> captureOffered=new HashMap<>();
    private static final java.util.Set<UUID> waiting=new HashSet<>();
    private static ClanAchievementPayload achievements;private static ServerMenuData cached;private static int cachedAt,nextAttempt;private static boolean inFlight;private static long generation;
    private ServerMenuSync(){}
    static void request(ServerMenuPayloads.Request payload,IPayloadContext context){context.enqueueWork(()->{
        if(!(context.player() instanceof ServerPlayer player)||!player.connection.hasChannel(ServerMenuPayloads.Snapshot.TYPE.id()))return;
        var server=player.server;if(server.isDedicatedServer()&&!GameIdentity.PLAYERS.containsKey(player.getUUID()))return;
        int now=server.getTickCount(),last=requested.getOrDefault(player.getUUID(),Integer.MIN_VALUE/2);if(now-last<40)return;requested.put(player.getUUID(),now);
        if(player.connection.hasChannel(ServerMenuPayloads.CaptureSummary.TYPE.id())){rememberCapture(player);PacketDistributor.sendToPlayer(player,CaptureEntry.summary(server,player));}
        if(cached!=null&&now-cachedAt<200){send(player,cached);return;}
        if(now<nextAttempt&&!inFlight){send(player,ServerMenuData.unavailable(server.getMaxPlayers()));return;}
        waiting.add(player.getUUID());if(inFlight)return;
        inFlight=true;long token=generation;
        try{var settings=ServerSettings.from(System.getenv());var body=new com.google.gson.JsonObject();body.addProperty("server_id",settings.serverId().toString());
            HttpJson.post(settings.heartbeatUrl().resolve("../server-menu/snapshot"),settings.key(),body,200000).whenComplete((reply,failure)->server.execute(()->{
                if(token!=generation)return;ServerMenuData value=null;
                if(failure==null)try{value=ServerMenuData.parse(reply,server.getMaxPlayers());}catch(RuntimeException invalid){/* Fail closed, never send partial or malformed rosters. */}
                achievements=null;if(value!=null)try{achievements=ClanAchievementPayload.parse(reply);}catch(RuntimeException invalid){/* Keep the main roster available. */}
                finish(server,value);
            }));
        }catch(RuntimeException unavailable){finish(server,null);}
    });}
    private static void finish(MinecraftServer server,ServerMenuData value){
        if(value==null)achievements=null;
        inFlight=false;nextAttempt=server.getTickCount()+200;cached=value;cachedAt=server.getTickCount();
        for(var id:java.util.List.copyOf(waiting)){var player=server.getPlayerList().getPlayer(id);if(player!=null)send(player,value==null?ServerMenuData.unavailable(server.getMaxPlayers()):value);}waiting.clear();
    }
    private static void send(ServerPlayer player,ServerMenuData data){if(player.connection.hasChannel(ServerMenuPayloads.Snapshot.TYPE.id()))PacketDistributor.sendToPlayer(player,new ServerMenuPayloads.Snapshot(data));if(achievements!=null&&player.connection.hasChannel(ClanAchievementPayload.TYPE.id()))PacketDistributor.sendToPlayer(player,achievements);}
    static void rememberCapture(ServerPlayer player){var point=CaptureEntry.here(player.server,player,CaptureSites.view());if(point==null)captureOffered.remove(player.getUUID());else captureOffered.put(player.getUUID(),new Offered(point.code(),player.server.getTickCount()));}
    static boolean captureMatches(ServerPlayer player){var offered=captureOffered.get(player.getUUID());var point=CaptureEntry.here(player.server,player,CaptureSites.view());return offered!=null&&point!=null&&offered.code().equals(point.code())&&player.server.getTickCount()-offered.tick()<=300;}
    static void logout(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event){captureOffered.remove(event.getEntity().getUUID());requested.remove(event.getEntity().getUUID());waiting.remove(event.getEntity().getUUID());}
    static void stopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event){generation++;captureOffered.clear();requested.clear();waiting.clear();cached=null;achievements=null;cachedAt=0;nextAttempt=0;inFlight=false;}
}
