package ru.animeoncraft.integration.neoforge;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

final class PlayerProfiles {
    private static final Map<UUID,Long> requested=new HashMap<>();
    private static final Map<UUID,ProfilePayloads.Request> pending=new HashMap<>();
    private static final Map<UUID,Long> sessions=new HashMap<>();
    private PlayerProfiles(){}
    private static long value(ServerPlayer player,ResourceLocation stat){return Math.max(0,player.getStats().getValue(Stats.CUSTOM.get(stat)));}
    static ProfileProgression.Metrics metrics(ServerPlayer player){
        long mined=0,crafted=0,bosses=0,distance=0;var stats=player.getStats();
        for(var block:BuiltInRegistries.BLOCK)mined+=Math.max(0,stats.getValue(Stats.BLOCK_MINED,block));
        for(var item:BuiltInRegistries.ITEM)crafted+=Math.max(0,stats.getValue(Stats.ITEM_CRAFTED,item));
        for(var type:BuiltInRegistries.ENTITY_TYPE)if(BossRewards.eligible(BuiltInRegistries.ENTITY_TYPE.getKey(type).toString()))bosses+=Math.max(0,stats.getValue(Stats.ENTITY_KILLED,type));
        for(var id:java.util.List.of(Stats.WALK_ONE_CM,Stats.CROUCH_ONE_CM,Stats.SPRINT_ONE_CM,Stats.SWIM_ONE_CM,Stats.WALK_ON_WATER_ONE_CM,Stats.WALK_UNDER_WATER_ONE_CM,Stats.AVIATE_ONE_CM,Stats.FLY_ONE_CM,Stats.BOAT_ONE_CM,Stats.MINECART_ONE_CM,Stats.HORSE_ONE_CM,Stats.PIG_ONE_CM,Stats.STRIDER_ONE_CM))distance+=value(player,id);
        var entry=PlayerProfileStore.get(player.server).player(player.getUUID());var start=sessions.get(player.getUUID());long seconds=start==null?entry.seconds:ProfileProgression.onlineSeconds(entry.seconds,start,System.nanoTime());
        return new ProfileProgression.Metrics(Math.max(seconds,value(player,Stats.PLAY_TIME)/20),value(player,Stats.PLAYER_KILLS),value(player,Stats.DEATHS),value(player,Stats.MOB_KILLS),mined,crafted,distance,bosses,value(player,Stats.FISH_CAUGHT));
    }
    static void request(ProfilePayloads.Request payload,IPayloadContext context){context.enqueueWork(()->{
        if(!(context.player() instanceof ServerPlayer player)||player.server.isDedicatedServer()&&!GameIdentity.PLAYERS.containsKey(player.getUUID()))return;
        pending.merge(player.getUUID(),payload,(old,next)->next.selection().equals("-")&&!old.selection().equals("-")?old:next);
    });}
    private static void send(ServerPlayer player,ProfileProgression.Metrics metrics){if(!player.connection.hasChannel(ProfilePayloads.Snapshot.TYPE.id()))return;var entry=PlayerProfileStore.get(player.server).player(player.getUUID());PacketDistributor.sendToPlayer(player,new ProfilePayloads.Snapshot(player.getUUID(),metrics,entry.selected,entry.unlocked.stream().sorted().toList()));}
    static void broadcast(ServerPlayer player){var payload=new ProfilePayloads.Title(player.getUUID(),PlayerProfileStore.get(player.server).player(player.getUUID()).selected);for(var observer:player.server.getPlayerList().getPlayers())if(observer.connection.hasChannel(ProfilePayloads.Title.TYPE.id())&&(observer==player||observer.serverLevel()==player.serverLevel()&&observer.distanceToSqr(player)<128*128))PacketDistributor.sendToPlayer(observer,payload);}
    static void tracking(PlayerEvent.StartTracking event){if(event.getEntity() instanceof ServerPlayer observer&&event.getTarget() instanceof ServerPlayer target&&observer.connection.hasChannel(ProfilePayloads.Title.TYPE.id()))PacketDistributor.sendToPlayer(observer,new ProfilePayloads.Title(target.getUUID(),PlayerProfileStore.get(target.server).player(target.getUUID()).selected));}
    private static void checkpoint(ServerPlayer player){var store=PlayerProfileStore.get(player.server);var entry=store.player(player.getUUID());long now=System.nanoTime();var start=sessions.get(player.getUUID());long elapsed=start==null?0:Math.max(0,now-start)/1_000_000_000L;long total=Math.max(entry.seconds+elapsed,value(player,Stats.PLAY_TIME)/20);if(total!=entry.seconds){entry.seconds=total;store.setDirty();}if(start!=null)sessions.put(player.getUUID(),start+elapsed*1_000_000_000L);}
    static void login(PlayerEvent.PlayerLoggedInEvent event){if(event.getEntity() instanceof ServerPlayer player){checkpoint(player);sessions.put(player.getUUID(),System.nanoTime());var metrics=metrics(player);PlayerProfileStore.get(player.server).unlock(player.getUUID(),metrics);send(player,metrics);broadcast(player);}}
    static void tick(ServerTickEvent.Post event){
        var server=event.getServer();long now=System.nanoTime();var iterator=pending.entrySet().iterator();
        while(iterator.hasNext()){var entry=iterator.next();var last=requested.get(entry.getKey());if(last!=null&&now-last<400_000_000L)continue;iterator.remove();
            var player=server.getPlayerList().getPlayer(entry.getKey());if(player==null||server.isDedicatedServer()&&!GameIdentity.PLAYERS.containsKey(player.getUUID()))continue;
            requested.put(entry.getKey(),now);var metrics=metrics(player);var store=PlayerProfileStore.get(server);store.unlock(player.getUUID(),metrics);
            if(!entry.getValue().selection().equals("-")&&store.select(player.getUUID(),entry.getValue().selection())){BetaPassServer.clearTitle(player);broadcast(player);}send(player,metrics);
        }
        if(server.getTickCount()%1200!=0)return;for(var player:server.getPlayerList().getPlayers()){checkpoint(player);var metrics=metrics(player);var store=PlayerProfileStore.get(server);boolean changed=store.unlock(player.getUUID(),metrics);if(changed){send(player,metrics);if(player.connection.hasChannel(ProfilePayloads.Snapshot.TYPE.id()))player.sendSystemMessage(net.minecraft.network.chat.Component.literal("Получено личное достижение! Открой профиль в меню сервера."));}}
    }
    static void logout(PlayerEvent.PlayerLoggedOutEvent event){if(event.getEntity() instanceof ServerPlayer player)checkpoint(player);requested.remove(event.getEntity().getUUID());pending.remove(event.getEntity().getUUID());sessions.remove(event.getEntity().getUUID());}
    static void stopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event){requested.clear();pending.clear();sessions.clear();}
}
