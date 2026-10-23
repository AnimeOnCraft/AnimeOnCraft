package ru.animeoncraft.integration.neoforge;

import com.google.gson.JsonObject;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

record GameIdentity(String clan, byte[] skin, boolean slim, String cape, UUID connection, UUID userId) {
 static final Map<UUID,GameIdentity> PLAYERS=new ConcurrentHashMap<>();
 static GameIdentity parse(JsonObject reply) {
  String clan=null;
  if(reply.has("clan")&&!reply.get("clan").isJsonNull())clan=reply.getAsJsonObject("clan").get("short_code").getAsString();
  if(clan!=null&&!ClanCodes.known(clan))clan=null;
  byte[] bytes=new byte[0];boolean slim=false;
  if(reply.has("skin")&&!reply.get("skin").isJsonNull()) {
   var skin=reply.getAsJsonObject("skin");bytes=Base64.getDecoder().decode(skin.get("png_base64").getAsString());
   if(bytes.length>24000)throw new IllegalArgumentException("Invalid skin size");
   slim="slim".equals(skin.get("model").getAsString());
  }
  return new GameIdentity(clan,bytes,slim,cape(reply),UUID.fromString(reply.get("connection_id").getAsString()),UUID.fromString(reply.get("user_id").getAsString()));
 }
 static String cape(JsonObject reply){
  if(!reply.has("cape_id")||reply.get("cape_id").isJsonNull())return "";
  String value=reply.get("cape_id").getAsString();if(!value.matches("[a-z][a-z0-9-]{0,31}"))throw new IllegalArgumentException("Invalid cape");return value;
 }
 GameIdentity withClan(String value){return new GameIdentity(value,skin,slim,cape,connection,userId);}
 GameIdentity withCape(String value){return new GameIdentity(clan,skin,slim,value,connection,userId);}
}
