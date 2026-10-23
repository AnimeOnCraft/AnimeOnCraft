package ru.animeoncraft.integration.neoforge;
import java.nio.file.Files;
import java.nio.file.Path;
import com.google.gson.JsonParser;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

final class CapeAppearance {
 private CapeAppearance(){}
 static byte[] texture(String id){
  if(id.isEmpty()||id.equals("beta"))return new byte[0];
  if(!id.matches("[a-z][a-z0-9-]{0,31}"))return new byte[0];
  try{
   // The production server runs in .tools/modpack-mvp/server. Probe worlds
   // have no public catalog, so they cannot load arbitrary external textures.
   Path directory=Path.of(System.getProperty("animeoncraft.capeDirectory","../../../assets/capes")).toAbsolutePath().normalize();
   var catalog=JsonParser.parseString(Files.readString(directory.resolve("catalog.json"))).getAsJsonObject();
   for(var value:catalog.getAsJsonArray("capes")){
    var entry=value.getAsJsonObject();if(!id.equals(entry.get("id").getAsString())||entry.get("texture").isJsonNull())continue;
    var file=directory.resolve(entry.get("texture").getAsString()).normalize();if(!file.getParent().equals(directory)||Files.size(file)>CapeTexturePayload.MAX_BYTES)return new byte[0];
    byte[] png=Files.readAllBytes(file);if(png.length<24||java.nio.ByteBuffer.wrap(png,16,8).getInt()!=1024||java.nio.ByteBuffer.wrap(png,20,4).getInt()!=512)return new byte[0];return png;
   }
  }catch(java.io.IOException|RuntimeException failure){System.getLogger("AnimeOnCraft").log(System.Logger.Level.WARNING,"Personal cape texture unavailable: "+id);}
  return new byte[0];
 }
 static void send(ServerPlayer viewer,ServerPlayer subject,GameIdentity identity){
  PacketDistributor.sendToPlayer(viewer,new SkinPayload(subject.getUUID(),identity.skin(),identity.slim(),identity.cape().equals("beta")?"beta":"",identity.clan()==null?"":identity.clan()));
  if(viewer.connection.hasChannel(CapeTexturePayload.TYPE.id())){
   byte[] png=texture(identity.cape());
   if(png.length>0)PacketDistributor.sendToPlayer(viewer,new CapeTexturePayload(subject.getUUID(),png));
  }
 }
 static void broadcast(ServerPlayer subject,GameIdentity identity){for(var viewer:subject.server.getPlayerList().getPlayers())send(viewer,subject,identity);}
}
