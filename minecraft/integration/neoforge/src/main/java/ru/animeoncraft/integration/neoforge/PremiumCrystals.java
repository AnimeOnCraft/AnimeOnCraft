package ru.animeoncraft.integration.neoforge;

import java.util.*;
import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Reports crystal-worthy events (profile level, guild quests) to the backend. The backend decides amounts and caps;
 * every report carries a stable reference, so a retry after a timeout can never pay twice. Never touches coins. */
final class PremiumCrystals {
 private record Report(UUID player,JsonObject body,int attempts,long due){}
 private static final List<Report> queue=new ArrayList<>();
 private static final Map<UUID,Integer> levels=new HashMap<>();
 static final Map<UUID,Integer> balances=new HashMap<>();
 private static boolean inFlight;
 private PremiumCrystals(){}
 static void level(ServerPlayer p,int level){if(!GameIdentity.PLAYERS.containsKey(p.getUUID())||levels.getOrDefault(p.getUUID(),0)>=level)return;levels.put(p.getUUID(),level);var b=new JsonObject();b.addProperty("action","LEVEL");b.addProperty("level",level);enqueue(p,b);}
 static void guild(ServerPlayer p,boolean team,GuildRules.Rank rank,String ref){if(!GameIdentity.PLAYERS.containsKey(p.getUUID()))return;var b=new JsonObject();b.addProperty("action",team?"GUILD_TEAM":"GUILD");b.addProperty("rank",rank.name());b.addProperty("ref",ref.replaceAll("[^A-Za-z0-9_.:-]","_"));enqueue(p,b);}
 /** First clear of a dungeon difficulty: the backend pays it once per player (ref dungeon:code:difficulty), so every clear may report. */
 static void dungeon(ServerPlayer p,String dungeon,String difficulty){if(!GameIdentity.PLAYERS.containsKey(p.getUUID()))return;var b=new JsonObject();b.addProperty("action","DUNGEON");b.addProperty("dungeon",dungeon);b.addProperty("difficulty",difficulty);b.addProperty("ref",DungeonRules.crystalRef(dungeon,difficulty));enqueue(p,b);}
 /** Arena win; the match id is the reference, so a retry never pays twice. The backend applies the weekly cap. */
 static void arena(ServerPlayer p,UUID match){if(!GameIdentity.PLAYERS.containsKey(p.getUUID()))return;var b=new JsonObject();b.addProperty("action","ARENA");b.addProperty("ref","arena:"+match);enqueue(p,b);}
 static int profileLevel(ServerPlayer p,ProfileProgression.Metrics metrics){return ProfileProgression.level(ProfileProgression.xp(metrics,PlayerProfileStore.get(p.server).player(p.getUUID()).unlocked));}
 private static void retry(Report r){if(r.attempts()>=8||!r.body().get("action").getAsString().startsWith("GUILD")&&!r.body().get("action").getAsString().equals("DUNGEON")&&!r.body().get("action").getAsString().equals("ARENA")&&r.attempts()>=3)return;synchronized(queue){queue.add(new Report(r.player(),r.body(),r.attempts()+1,System.currentTimeMillis()+60_000L*(r.attempts()+1)));}}
 private static void enqueue(ServerPlayer p,JsonObject body){synchronized(queue){if(queue.size()<500)queue.add(new Report(p.getUUID(),body,0,0));}}
 static void tick(ServerTickEvent.Post event){
  var server=event.getServer();if(server.getTickCount()%10!=0||inFlight)return;Report next=null;long now=System.currentTimeMillis();
  synchronized(queue){for(var r:queue)if(r.due()<=now){next=r;queue.remove(r);break;}}
  if(next==null)return;var report=next;var p=server.getPlayerList().getPlayer(report.player());var identity=GameIdentity.PLAYERS.get(report.player());
  if(p==null||identity==null){retry(report);return;} // Guild reports wait for the next login; levels are re-reported anyway.
  try{var settings=ServerSettings.from(System.getenv());var body=report.body().deepCopy();body.addProperty("server_id",settings.serverId().toString());body.addProperty("connection_id",identity.connection().toString());body.addProperty("minecraft_uuid",report.player().toString());
   inFlight=true;
   HttpJson.post(settings.heartbeatUrl().resolve("../premium"),settings.key(),body,4000).whenComplete((reply,failure)->server.execute(()->{inFlight=false;
    if(failure!=null){retry(report);return;}
    try{int granted=reply.get("granted").getAsInt(),total=reply.getAsJsonObject("wallet").get("total").getAsInt();balances.put(report.player(),total);
     var current=server.getPlayerList().getPlayer(report.player());
     if(granted>0&&current!=null)current.sendSystemMessage(Component.literal("+"+granted+" ◆ Кристаллов Разлома (всего "+total+")").withColor(0xf07aa6));
     if(granted>0)System.out.println("AOC_CRYSTALS "+report.player()+" "+report.body().get("action").getAsString()+" +"+granted+" total="+total);
    }catch(RuntimeException ignored){}
   }));
  }catch(RuntimeException unavailable){inFlight=false;}
 }
 static void logout(PlayerEvent.PlayerLoggedOutEvent event){var id=event.getEntity().getUUID();levels.remove(id);balances.remove(id);synchronized(queue){queue.removeIf(r->r.player().equals(id)&&r.body().get("action").getAsString().equals("LEVEL"));}}
 static void stopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event){synchronized(queue){queue.clear();}levels.clear();balances.clear();inFlight=false;}
}
