package ru.animeoncraft.integration.neoforge;

import java.util.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

final class BetaPassServer {
    private static final class Session {long[] counters;BetaPass.ActivityClock clock;net.minecraft.world.phys.Vec3 position;Session(ServerPlayer p){counters=counters(p);clock=new BetaPass.ActivityClock(System.nanoTime());position=p.position();}}
    private static final Map<UUID,Session> sessions=new HashMap<>();
    private static final Map<UUID,ArrayDeque<BetaPassPayloads.Request>> pending=new HashMap<>();
    private static final Map<UUID,Long> requested=new HashMap<>();
    private BetaPassServer(){}
    private static boolean admitted(ServerPlayer p){return !p.server.isDedicatedServer()||GameIdentity.PLAYERS.containsKey(p.getUUID());}
    private static long[] counters(ServerPlayer p){var m=PlayerProfiles.metrics(p);long distance=0;for(var id:List.of(Stats.WALK_ONE_CM,Stats.SPRINT_ONE_CM,Stats.CROUCH_ONE_CM,Stats.SWIM_ONE_CM,Stats.WALK_ON_WATER_ONE_CM,Stats.WALK_UNDER_WATER_ONE_CM))distance+=Math.max(0,p.getStats().getValue(Stats.CUSTOM.get(id)));return new long[]{distance,m.mobs(),m.crafted(),0,m.mined(),m.fish()};}
    private static void sample(ServerPlayer p){var store=BetaPassStore.get(p.server);var e=store.player(p.getUUID());e.reset(System.currentTimeMillis());var session=sessions.computeIfAbsent(p.getUUID(),id->new Session(p));long now=System.nanoTime();long[] counters=counters(p),delta=new long[6];boolean action=p.position().distanceToSqr(session.position)>.04;
        for(int i=0;i<6;i++){delta[i]=Math.max(0,counters[i]-session.counters[i]);action|=delta[i]>0;}
        delta[3]=session.clock.advance(now,action);
        if(store.open&&admitted(p)&&!p.isCreative()&&!p.isSpectator())e.progress(delta);
        session.counters=counters;session.position=p.position();store.setDirty();
    }
    static void request(BetaPassPayloads.Request payload,IPayloadContext context){context.enqueueWork(()->{if(!(context.player() instanceof ServerPlayer p)||!admitted(p))return;var queue=pending.computeIfAbsent(p.getUUID(),id->new ArrayDeque<>());if(queue.size()<8&&(queue.isEmpty()||!payload.action().equals("GET")))queue.add(payload);});}
    static void send(ServerPlayer p){if(!p.connection.hasChannel(BetaPassPayloads.Snapshot.TYPE.id()))return;var store=BetaPassStore.get(p.server);var e=store.player(p.getUUID());if(e.reset(System.currentTimeMillis()))store.setDirty();PacketDistributor.sendToPlayer(p,new BetaPassPayloads.Snapshot(p.getUUID(),store.open,e.xp,e.rewards,e.day,e.week,Arrays.stream(e.daily).boxed().toList(),Arrays.stream(e.weekly).boxed().toList(),e.dayClaims,e.weekClaims,e.equipped));}
    private static BetaPassPayloads.Appearance appearance(ServerPlayer p){return new BetaPassPayloads.Appearance(p.getUUID(),BetaPassStore.get(p.server).player(p.getUUID()).equipped);}
    static void broadcast(ServerPlayer p){var packet=appearance(p);for(var observer:p.server.getPlayerList().getPlayers())if(observer.connection.hasChannel(BetaPassPayloads.Appearance.TYPE.id())&&(observer==p||observer.serverLevel()==p.serverLevel()&&observer.distanceToSqr(p)<128*128))PacketDistributor.sendToPlayer(observer,packet);}
    /** Full nearby state repairs missed tracking packets, respawns and late client readiness. */
    private static void syncVisibleTo(ServerPlayer observer){
        if(!observer.connection.hasChannel(BetaPassPayloads.Appearance.TYPE.id()))return;
        for(var target:observer.server.getPlayerList().getPlayers())if(observer==target||observer.serverLevel()==target.serverLevel()&&observer.distanceToSqr(target)<128*128)
            PacketDistributor.sendToPlayer(observer,appearance(target));
    }
    static void tracking(PlayerEvent.StartTracking event){if(event.getEntity() instanceof ServerPlayer p&&event.getTarget() instanceof ServerPlayer target&&p.connection.hasChannel(BetaPassPayloads.Appearance.TYPE.id()))PacketDistributor.sendToPlayer(p,appearance(target));}
    static void login(PlayerEvent.PlayerLoggedInEvent event){if(event.getEntity() instanceof ServerPlayer p){sessions.put(p.getUUID(),new Session(p));var store=BetaPassStore.get(p.server);store.player(p.getUUID()).reset(System.currentTimeMillis());store.setDirty();send(p);broadcast(p);syncVisibleTo(p);}}
    static void tick(ServerTickEvent.Post event){var server=event.getServer();if(server.getTickCount()%100==0)for(var p:server.getPlayerList().getPlayers())if(admitted(p)){sample(p);syncVisibleTo(p);}
        long now=System.nanoTime();var it=pending.entrySet().iterator();while(it.hasNext()){var item=it.next();if(now-requested.getOrDefault(item.getKey(),0L)<400_000_000L)continue;var request=item.getValue().poll();if(item.getValue().isEmpty())it.remove();var p=server.getPlayerList().getPlayer(item.getKey());if(p==null||request==null||!admitted(p))continue;requested.put(p.getUUID(),now);sample(p);var store=BetaPassStore.get(server);var e=store.player(p.getUUID());boolean changed=false;
            if(request.action().equals("EQUIP")){if(request.slot().equals("BACK")&&GameIdentity.PLAYERS.containsKey(p.getUUID())){ServerWardrobe.betaBack(p,request.code());send(p);continue;}changed=e.equip(request.slot(),request.code());if(changed){if(request.slot().equals("TITLE")&&!request.code().isEmpty()){PlayerProfileStore.get(server).select(p.getUUID(),"");PlayerProfiles.broadcast(p);}broadcast(p);}}
            else if(store.open)changed=switch(request.action()){case "DAILY"->e.claimQuest(false,request.index());case "WEEKLY"->e.claimQuest(true,request.index());case "REWARD"->e.claimReward(request.index());default->false;};
            if(changed)store.setDirty();send(p);
        }
    }
    static void clearTitle(ServerPlayer p){var store=BetaPassStore.get(p.server);if(store.player(p.getUUID()).equipped.remove("TITLE")!=null){store.setDirty();broadcast(p);send(p);}}
    static void logout(PlayerEvent.PlayerLoggedOutEvent event){if(event.getEntity() instanceof ServerPlayer p)sample(p);var id=event.getEntity().getUUID();sessions.remove(id);pending.remove(id);requested.remove(id);}
    static void stopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event){sessions.clear();pending.clear();requested.clear();}
    static void commands(net.neoforged.neoforge.event.RegisterCommandsEvent event){BetaPassCommands.register(event.getDispatcher());}
}
