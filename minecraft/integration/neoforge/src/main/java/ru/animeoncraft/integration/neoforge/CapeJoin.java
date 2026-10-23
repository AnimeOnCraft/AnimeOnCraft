package ru.animeoncraft.integration.neoforge;

import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Called only after the authenticated player has actually entered the world. */
final class CapeJoin {
 private static final org.apache.logging.log4j.Logger LOGGER=org.apache.logging.log4j.LogManager.getLogger("AnimeOnCraft");
 private CapeJoin(){}
 static void login(PlayerEvent.PlayerLoggedInEvent event){if(event.getEntity() instanceof ServerPlayer player){
  var identity=GameIdentity.PLAYERS.get(player.getUUID());if(identity!=null)report(player,identity,0);
 }}
 private static void report(ServerPlayer player,GameIdentity identity,int attempt){
  if(player.isRemoved()||GameIdentity.PLAYERS.get(player.getUUID())!=identity)return;
  try{
   var settings=ServerSettings.from(System.getenv());var body=new JsonObject();
   body.addProperty("connection_id",identity.connection().toString());body.addProperty("minecraft_uuid",player.getUUID().toString());
   HttpJson.post(settings.redeemUrl().resolve("joined"),settings.key(),body).whenComplete((reply,failure)->player.server.execute(()->{
    if(player.isRemoved()||GameIdentity.PLAYERS.get(player.getUUID())!=identity)return;
    if(failure!=null){retry(player,identity,attempt);return;}
    try{
     if(!reply.get("joined").getAsBoolean())throw new IllegalArgumentException();
     String cape=GameIdentity.cape(reply);GameIdentity.PLAYERS.put(player.getUUID(),identity.withCape(cape));
     CapeAppearance.broadcast(player,identity.withCape(cape));
     LOGGER.info("AOC world join confirmed: player={} cape={}",player.getGameProfile().getName(),cape.isEmpty()?"none":cape);
     if(reply.get("awarded").getAsBoolean())player.sendSystemMessage(Component.literal("Вы получили плащ Beta! Он останется у вас навсегда. Сменить плащ можно в профиле лаунчера."));
    }catch(RuntimeException invalid){LOGGER.warn("AOC join confirmation response was invalid");}
   }));
  }catch(RuntimeException failure){retry(player,identity,attempt);}
 }
 private static void retry(ServerPlayer player,GameIdentity identity,int attempt){
  if(attempt>=2){LOGGER.warn("AOC cape join confirmation unavailable; reconnect to retry");return;}
  java.util.concurrent.CompletableFuture.delayedExecutor(2L*(attempt+1),java.util.concurrent.TimeUnit.SECONDS).execute(()->player.server.execute(()->report(player,identity,attempt+1)));
 }
}
