package ru.animeoncraft.integration.neoforge;

import java.util.*;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Game server alone starts animations after checking the admitted account's inventory. */
final class ServerEmotes {
    record Active(String code,int started,Vec3 anchor,net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension){}
    private static final Map<UUID,Active> active=new HashMap<>();
    private static final Map<UUID,List<String>> inventories=new HashMap<>();
    private static final Map<UUID,Integer> requested=new HashMap<>();
    private static final Map<UUID,Integer> reads=new HashMap<>();
    private ServerEmotes(){}
    static void inventory(ServerPlayer p,JsonObject reply){
        var list=new ArrayList<String>();
        if(reply.has("emotes")){var values=reply.getAsJsonArray("emotes");if(values.size()>128)throw new IllegalArgumentException("Too many emotes");for(var v:values){String code=v.getAsString();if(Emotes.find(code)!=null&&!list.contains(code))list.add(code);}}
        inventories.put(p.getUUID(),List.copyOf(list));sendInventory(p,true,list);
        var playing=active.get(p.getUUID());if(playing!=null&&!list.contains(playing.code()))stop(p);
    }
    static void unavailable(ServerPlayer p){if(!p.server.isDedicatedServer()){sendInventory(p,true,Emotes.starters());return;}inventories.remove(p.getUUID());stop(p);sendInventory(p,false,List.of());}
    private static boolean admitted(ServerPlayer p){return !p.server.isDedicatedServer()||GameIdentity.PLAYERS.containsKey(p.getUUID());}
    private static void sendInventory(ServerPlayer p,boolean available,List<String> codes){if(p.connection.hasChannel(EmotePayloads.Inventory.TYPE.id()))PacketDistributor.sendToPlayer(p,new EmotePayloads.Inventory(available,codes));}
    static void request(EmotePayloads.Request r,IPayloadContext context){context.enqueueWork(()->{if(context.player() instanceof ServerPlayer p)request(p,r.code());});}
    static void request(ServerPlayer p,String code){
        if(!admitted(p))return;
        if(code.isEmpty()){stop(p);return;}
        int now=p.server.getTickCount();
        var owned=p.server.isDedicatedServer()?inventories.get(p.getUUID()):Emotes.starters();
        if(code.equals("READ")){if(now-reads.getOrDefault(p.getUUID(),Integer.MIN_VALUE/2)<40)return;reads.put(p.getUUID(),now);if(owned!=null)sendInventory(p,true,owned);else ServerWardrobe.request(p,new WardrobePayloads.Request("READ","",0));return;}
        // A background inventory read must never swallow the player's next click.
        if(now-requested.getOrDefault(p.getUUID(),Integer.MIN_VALUE/2)<10)return;requested.put(p.getUUID(),now);
        if(owned==null||!owned.contains(code)||Emotes.find(code)==null||blocked(p,0))return;
        active.put(p.getUUID(),new Active(code,now,p.position(),p.level().dimension()));broadcast(p);
    }
    private static boolean blocked(ServerPlayer p,double distance){return Emotes.interrupts(distance,p.onGround(),!p.isAlive()||p.isSpectator()||p.isSleeping()||p.isPassenger()||p.isSwimming()||p.isInWater()||p.isFallFlying()||p.isUsingItem()||p.swinging||p.isCrouching()||p.isSprinting(),p.hurtTime>0);}
    private static EmotePayloads.State state(ServerPlayer p){var a=active.get(p.getUUID());return new EmotePayloads.State(p.getUUID(),a==null?"":a.code(),a==null?0:Math.min(Emotes.find(a.code()).ticks(),Math.max(0,p.server.getTickCount()-a.started())));}
    private static void send(ServerPlayer observer,ServerPlayer target){if(observer.connection.hasChannel(EmotePayloads.State.TYPE.id()))PacketDistributor.sendToPlayer(observer,state(target));}
    private static void broadcast(ServerPlayer p){for(var observer:p.server.getPlayerList().getPlayers())if(observer==p||observer.serverLevel()==p.serverLevel()&&observer.distanceToSqr(p)<128*128)send(observer,p);}
    static void stop(ServerPlayer p){if(active.remove(p.getUUID())!=null)broadcast(p);}
    static void tracking(PlayerEvent.StartTracking e){if(e.getEntity() instanceof ServerPlayer observer&&e.getTarget() instanceof ServerPlayer target)send(observer,target);}
    static void tick(ServerTickEvent.Post e){
        var server=e.getServer();for(var id:List.copyOf(active.keySet())){var p=server.getPlayerList().getPlayer(id);var a=active.get(id);if(p==null){active.remove(id);continue;}if(!admitted(p)||!p.level().dimension().equals(a.dimension())||blocked(p,p.position().distanceToSqr(a.anchor()))||server.getTickCount()-a.started()>=Emotes.find(a.code()).ticks())stop(p);}
        if(server.getTickCount()%100==0)for(var observer:server.getPlayerList().getPlayers())for(var target:server.getPlayerList().getPlayers())if(observer==target||observer.serverLevel()==target.serverLevel()&&observer.distanceToSqr(target)<128*128)send(observer,target);
    }
    static void logout(PlayerEvent.PlayerLoggedOutEvent e){if(e.getEntity() instanceof ServerPlayer p)stop(p);inventories.remove(e.getEntity().getUUID());requested.remove(e.getEntity().getUUID());reads.remove(e.getEntity().getUUID());}
    static void stopped(net.neoforged.neoforge.event.server.ServerStoppedEvent e){active.clear();inventories.clear();requested.clear();reads.clear();}
}
