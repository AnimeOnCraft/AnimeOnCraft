package ru.animeoncraft.integration.neoforge;

import java.util.*;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Only the admitted connection may change its account. HTTP completions are fenced across reconnects. */
final class ServerWardrobe {
 private record Flight(UUID connection,long generation){}
 private static final Map<UUID,Flight> flights=new HashMap<>();
 private static final Map<UUID,Integer> attempted=new HashMap<>();
 private static final Map<UUID,WardrobePayloads.Snapshot> cached=new HashMap<>();
 private static long generation;
 private ServerWardrobe(){}
 static void request(WardrobePayloads.Request r,IPayloadContext c){c.enqueueWork(()->{if(c.player() instanceof ServerPlayer p)request(p,r);});}
 static void request(ServerPlayer p,WardrobePayloads.Request r){
  var identity=GameIdentity.PLAYERS.get(p.getUUID());
  if(identity==null){unavailable(p);return;}
  var old=cached.get(p.getUUID());boolean read=r.kind().equals("READ");
  if(!read&&(old==null||!old.available()||old.revision()!=r.revision()||!allowed(p,r,old))){refresh(p);return;}
  int now=p.server.getTickCount();if(flights.containsKey(p.getUUID())||now-attempted.getOrDefault(p.getUUID(),Integer.MIN_VALUE/2)<10)return;
  attempted.put(p.getUUID(),now);var flight=new Flight(identity.connection(),generation);flights.put(p.getUUID(),flight);
  try{var settings=ServerSettings.from(System.getenv());var body=new JsonObject();body.addProperty("server_id",settings.serverId().toString());body.addProperty("connection_id",identity.connection().toString());body.addProperty("minecraft_uuid",p.getUUID().toString());body.addProperty("action",read?"READ":"BACK");
   if(!read){body.addProperty("kind",r.kind());body.addProperty("code",r.code());body.addProperty("expected_revision",Long.toString(r.revision()));}
   HttpJson.post(settings.heartbeatUrl().resolve("../wardrobe"),settings.key(),body,40000).whenComplete((reply,failure)->p.server.execute(()->{
    if(flights.get(p.getUUID())!=flight||generation!=flight.generation())return;flights.remove(p.getUUID());
    var current=p.server.getPlayerList().getPlayer(p.getUUID());var currentIdentity=GameIdentity.PLAYERS.get(p.getUUID());
    if(current!=p||currentIdentity==null||!currentIdentity.connection().equals(flight.connection()))return;
    if(failure!=null){unavailable(p);return;}
    try{var value=parse(p.getUUID(),reply);apply(p,currentIdentity,value);cached.put(p.getUUID(),value);send(p,value);ServerEmotes.inventory(p,reply);}catch(RuntimeException invalid){unavailable(p);}
   }));
  }catch(RuntimeException unavailable){flights.remove(p.getUUID(),flight);unavailable(p);}
 }
 static boolean allowed(ServerPlayer p,WardrobePayloads.Request r,WardrobePayloads.Snapshot old){
  if(r.kind().equals("CAPE"))return old.capes().stream().anyMatch(c->c.id().equals(r.code()));
  if(r.kind().equals("BETA")){var reward=BetaPass.find(r.code());return reward!=null&&(BetaPassStore.get(p.server).player(p.getUUID()).rewards&(1<<(reward.level()-1)))!=0;}
  return r.kind().equals("NONE");
 }
 static WardrobePayloads.Snapshot parse(UUID id,JsonObject reply){
  var back=reply.getAsJsonObject("back");String revision=back.get("revision").getAsString();if(!revision.matches("0|[1-9][0-9]{0,17}"))throw new IllegalArgumentException("Invalid wardrobe revision");
  var list=reply.getAsJsonArray("capes");if(list.size()>32)throw new IllegalArgumentException("Too many capes");var capes=new ArrayList<WardrobePayloads.Cape>();for(var item:list){var c=item.getAsJsonObject();capes.add(new WardrobePayloads.Cape(c.get("id").getAsString(),c.get("name").getAsString()));}
  return new WardrobePayloads.Snapshot(id,true,Long.parseLong(revision),back.get("kind").getAsString(),back.get("code").getAsString(),capes);
 }
 private static void apply(ServerPlayer p,GameIdentity identity,WardrobePayloads.Snapshot value){
  var store=BetaPassStore.get(p.server);var e=store.player(p.getUUID());String code=value.kind().equals("BETA")?value.code():"";
  if(!code.isEmpty()&&!e.owns(code))code="";
  boolean changed=!e.equipped.getOrDefault("BACK","").equals(code)&&e.equip("BACK",code);if(changed){store.setDirty();BetaPassServer.broadcast(p);BetaPassServer.send(p);}
  String cape=value.kind().equals("CAPE")?value.code():"";
  if(!identity.cape().equals(cape)){var next=identity.withCape(cape);GameIdentity.PLAYERS.put(p.getUUID(),next);CapeAppearance.broadcast(p,next);}
 }
 static void betaBack(ServerPlayer p,String code){if(!code.isEmpty()&&!code.equals("RIFT_MANTLE"))return;var old=cached.get(p.getUUID());if(old==null||!old.available()){refresh(p);return;}request(p,new WardrobePayloads.Request(code.isEmpty()?"NONE":"BETA",code,old.revision()));}
 private static void refresh(ServerPlayer p){request(p,new WardrobePayloads.Request("READ","",0));}
 private static void unavailable(ServerPlayer p){cached.remove(p.getUUID());send(p,new WardrobePayloads.Snapshot(p.getUUID(),false,0,"NONE","",List.of()));ServerEmotes.unavailable(p);}
 private static void send(ServerPlayer p,WardrobePayloads.Snapshot value){if(p.connection.hasChannel(WardrobePayloads.Snapshot.TYPE.id()))PacketDistributor.sendToPlayer(p,value);}
 static void tick(ServerTickEvent.Post event){var s=event.getServer();if(s.getTickCount()%40!=0)return;for(var p:s.getPlayerList().getPlayers())if(GameIdentity.PLAYERS.containsKey(p.getUUID())&&s.getTickCount()-attempted.getOrDefault(p.getUUID(),Integer.MIN_VALUE/2)>=200)request(p,new WardrobePayloads.Request("READ","",0));}
 static void logout(PlayerEvent.PlayerLoggedOutEvent event){var id=event.getEntity().getUUID();flights.remove(id);attempted.remove(id);cached.remove(id);}
 static void stopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event){generation++;flights.clear();attempted.clear();cached.clear();}
}
