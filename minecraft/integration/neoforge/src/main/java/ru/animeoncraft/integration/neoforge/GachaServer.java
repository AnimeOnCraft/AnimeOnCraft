package ru.animeoncraft.integration.neoforge;

import java.util.*;
import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Forwards summon requests of the admitted connection to the backend, one at a time per player.
 * A roll carries a fresh request id; if the reply is lost, the next READ shows what the backend recorded. */
final class GachaServer {
 private record Flight(UUID connection,long generation){}
 private static final Map<UUID,Flight> flights=new HashMap<>();
 private static final Map<UUID,GachaPayloads.Snapshot> cached=new HashMap<>();
 private static final Map<UUID,Long> last=new HashMap<>();
 private static long generation;
 private GachaServer(){}
 static void request(GachaPayloads.Request r,IPayloadContext c){c.enqueueWork(()->{if(c.player() instanceof ServerPlayer p)request(p,r);});}
 static void request(ServerPlayer p,GachaPayloads.Request r){
  var identity=GameIdentity.PLAYERS.get(p.getUUID());
  if(identity==null){send(p,GachaPayloads.Snapshot.unavailable(p.getUUID(),"Призыв доступен только при входе через лаунчер."));return;}
  if(r.action().equals("WEAR")||r.action().equals("OFF")){wear(p,r.code(),r.action().equals("WEAR"));return;}
  long now=System.nanoTime();if(flights.containsKey(p.getUUID())||now-last.getOrDefault(p.getUUID(),0L)<700_000_000L)return;
  boolean shards=r.action().equals("FRAG"),roll=shards||r.action().equals("ROLL");var old=cached.get(p.getUUID());
  if(roll&&(old==null||!old.available())){r=new GachaPayloads.Request("READ",0);roll=false;}
  if(roll&&old.complete()){send(p,with(old,"Весь набор баннера уже собран."));return;}
  if(roll&&shards&&old.shards().get(0)<old.shards().get(1)*r.count()){send(p,with(old,"Не хватает фрагментов: нужно "+old.shards().get(1)*r.count()+"."));return;}
  if(roll&&!shards&&old.balance()<old.cost()*r.count()){send(p,with(old,"Не хватает кристаллов: нужно "+old.cost()*r.count()+" ◆."));return;}
  last.put(p.getUUID(),now);var flight=new Flight(identity.connection(),generation);flights.put(p.getUUID(),flight);
  try{var settings=ServerSettings.from(System.getenv());var body=new JsonObject();body.addProperty("server_id",settings.serverId().toString());body.addProperty("connection_id",identity.connection().toString());body.addProperty("minecraft_uuid",p.getUUID().toString());body.addProperty("action",roll?"ROLL":"READ");
   if(roll){body.addProperty("count",r.count());body.addProperty("request_id",UUID.randomUUID().toString());if(shards)body.addProperty("source","FRAGMENTS");}
   boolean rolled=roll;
   HttpJson.post(settings.heartbeatUrl().resolve("../gacha"),settings.key(),body,40000).whenComplete((reply,failure)->p.server.execute(()->{
    if(flights.get(p.getUUID())!=flight||generation!=flight.generation())return;flights.remove(p.getUUID());
    var current=p.server.getPlayerList().getPlayer(p.getUUID());var currentIdentity=GameIdentity.PLAYERS.get(p.getUUID());
    if(current!=p||currentIdentity==null||!currentIdentity.connection().equals(flight.connection()))return;
    if(failure!=null){var stale=cached.get(p.getUUID());send(p,stale!=null&&stale.available()?with(stale,rolled?"Сервер не ответил. Открой призыв заново: если кристаллы списались, предметы уже твои.":"Сервер призыва не ответил."):GachaPayloads.Snapshot.unavailable(p.getUUID(),"Сервер призыва временно недоступен."));return;}
    try{var value=parse(p.getUUID(),reply,rolled);cached.put(p.getUUID(),value);send(p,value);if(rolled){announce(p,value);
     // A new emote from the banner must show up in the emote menu without a relog.
     if(value.pulls().stream().anyMatch(i->i.slot().equals("EMOTE")&&i.owned()))ServerWardrobe.request(p,new WardrobePayloads.Request("READ","",0));}}
    catch(RuntimeException invalid){send(p,GachaPayloads.Snapshot.unavailable(p.getUUID(),"Сервер призыва ответил непонятно."));}
   }));
  }catch(RuntimeException unavailable){flights.remove(p.getUUID(),flight);send(p,GachaPayloads.Snapshot.unavailable(p.getUUID(),"Сервер призыва временно недоступен."));}
 }
 /** Ownership as last reported by the backend; the back slot is re-checked by the backend wardrobe. */
 static boolean owns(ServerPlayer p,String code){var s=cached.get(p.getUUID());return s!=null&&s.available()&&s.items().stream().anyMatch(i->i.owned()&&i.code().equals(code));}
 private static void wear(ServerPlayer p,String code,boolean on){
  String slot=BetaPassStore.GACHA_SLOTS.get(code);var s=cached.get(p.getUUID());
  if(on&&!owns(p,code)){if(s!=null&&s.available())send(p,with(s,"Сначала выбей этот предмет."));return;}
  if(slot.equals("BACK")){ServerWardrobe.gachaBack(p,on?code:"");return;}
  var store=BetaPassStore.get(p.server);var e=store.player(p.getUUID());
  boolean changed=on?!code.equals(e.equipped.get(slot))&&e.wear(slot,code):e.equipped.remove(slot,code);
  if(changed){store.setDirty();BetaPassServer.broadcast(p);BetaPassServer.send(p);}
 }
 static GachaPayloads.Snapshot parse(UUID id,JsonObject reply,boolean rolled){
  var banner=reply.getAsJsonObject("banner");var rates=banner.getAsJsonObject("rates");var pity=reply.getAsJsonObject("pity");var wallet=reply.getAsJsonObject("wallet");
  var items=new ArrayList<GachaPayloads.Item>();for(var e:banner.getAsJsonArray("items")){var o=e.getAsJsonObject();items.add(new GachaPayloads.Item(o.get("code").getAsString(),o.get("name").getAsString(),o.get("slot").getAsString(),o.get("tier").getAsInt(),o.get("owned").getAsBoolean()));}
  var pulls=new ArrayList<GachaPayloads.Item>();
  if(reply.has("pulls"))for(var e:reply.getAsJsonArray("pulls")){var o=e.getAsJsonObject();var item=o.get("item");
   if(item==null||item.isJsonNull())pulls.add(new GachaPayloads.Item("CRYSTALS","+"+o.get("consolation").getAsInt()+" ◆","NONE",0,false));
   else{var i=item.getAsJsonObject();boolean repeat=o.has("duplicate")&&o.get("duplicate").getAsBoolean();pulls.add(new GachaPayloads.Item(i.get("code").getAsString(),i.get("name").getAsString(),i.get("slot").getAsString(),i.get("tier").getAsInt(),!repeat));}}
  var values=banner.getAsJsonObject("fragment_values");
  var shards=List.of(reply.get("fragments").getAsInt(),banner.get("fragment_roll").getAsInt(),pity.get("drought").getAsInt(),banner.get("drought").getAsInt(),values.get("3").getAsInt(),values.get("4").getAsInt(),values.get("5").getAsInt());
  String message=rolled&&reply.has("replayed")&&reply.get("replayed").getAsBoolean()?"Этот призыв уже был выполнен.":"";
  return new GachaPayloads.Snapshot(id,true,banner.get("name").getAsString(),banner.get("cost").getAsInt(),wallet.get("total").getAsInt(),wallet.get("paid").getAsInt(),
   List.of(rates.get("five").getAsInt(),rates.get("four").getAsInt(),rates.get("three").getAsInt(),rates.get("consolation").getAsInt()),
   banner.get("soft5").getAsInt(),banner.get("hard5").getAsInt(),banner.get("hard4").getAsInt(),banner.get("consolation").getAsInt(),banner.get("worst_case").getAsInt(),
   pity.get("five").getAsInt(),pity.get("four").getAsInt(),pity.get("total").getAsInt(),reply.get("complete").getAsBoolean(),items,pulls,message,shards);
 }
 private static GachaPayloads.Snapshot with(GachaPayloads.Snapshot s,String message){return new GachaPayloads.Snapshot(s.player(),s.available(),s.banner(),s.cost(),s.balance(),s.paid(),s.rates(),s.soft5(),s.hard5(),s.hard4(),s.consolation(),s.worst(),s.pity5(),s.pity4(),s.total(),s.complete(),s.items(),List.of(),message,s.shards());}
 /** A legendary pull is shown to everyone online, like a boss kill. */
 private static void announce(ServerPlayer p,GachaPayloads.Snapshot s){
  for(var i:s.pulls())if(i.tier()==5&&i.owned()){var line=Component.literal("✦ "+p.getGameProfile().getName()+" призвал легендарное: «"+i.name()+"»!").withColor(0xffc85a);for(var o:p.server.getPlayerList().getPlayers())o.sendSystemMessage(line);}
  System.out.println("AOC_GACHA "+p.getGameProfile().getName()+" pulls="+s.pulls().stream().map(GachaPayloads.Item::code).toList()+" balance="+s.balance()+" pity5="+s.pity5());
 }
 private static void send(ServerPlayer p,GachaPayloads.Snapshot value){if(p.connection.hasChannel(GachaPayloads.Snapshot.TYPE.id()))PacketDistributor.sendToPlayer(p,value);}
 static void logout(PlayerEvent.PlayerLoggedOutEvent event){var id=event.getEntity().getUUID();flights.remove(id);cached.remove(id);last.remove(id);}
 static void stopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event){generation++;flights.clear();cached.clear();last.clear();}
}
