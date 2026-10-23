package ru.animeoncraft.integration.neoforge;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
public final class ClientSkins {
 private static final org.apache.logging.log4j.Logger LOGGER=org.apache.logging.log4j.LogManager.getLogger("AnimeOnCraft");
 private static final Map<UUID,PlayerSkin> SKINS=new HashMap<>();
 private static final Map<UUID,ResourceLocation> CAPES=new HashMap<>();
 private static final Map<UUID,String> CLANS=new HashMap<>();
 private static final java.util.Set<UUID> CAPE_APPLIED=new java.util.HashSet<>();
 private static final java.util.Set<UUID> APPLIED=new java.util.HashSet<>();
 private static final java.util.Set<UUID> CUSTOM_CAPES=new java.util.HashSet<>();
 private ClientSkins(){}
 static void accept(SkinPayload payload,IPayloadContext context){context.enqueueWork(()->{
  var textures=Minecraft.getInstance().getTextureManager();
  var previous=SKINS.remove(payload.player());if(previous!=null)textures.release(previous.texture());
  clearCape(payload.player());
  if(payload.cape().equals("beta"))CAPES.put(payload.player(),ResourceLocation.fromNamespaceAndPath("animeoncraft","textures/capes/beta.png"));
  if(payload.clan().isEmpty())CLANS.remove(payload.player());else CLANS.put(payload.player(),payload.clan());
  CAPE_APPLIED.remove(payload.player());
  if(payload.png().length==0)return;
  try{
   NativeImage image=NativeImage.read(new ByteArrayInputStream(payload.png()));
   if(image.getWidth()!=64||image.getHeight()!=64){image.close();return;}
   ResourceLocation id=ResourceLocation.fromNamespaceAndPath("animeoncraft","skins/"+payload.player());
   textures.register(id,new DynamicTexture(image));
   SKINS.put(payload.player(),new PlayerSkin(id,null,null,null,payload.slim()?PlayerSkin.Model.SLIM:PlayerSkin.Model.WIDE,false));
   LOGGER.info("AOC skin received: uuid={} model={}",payload.player(),payload.slim()?"SLIM":"WIDE");
  }catch(java.io.IOException ignored){/* Keep the vanilla skin on an invalid image. */}
 });}
 private static void clearCape(UUID player){
  var previous=CAPES.remove(player);if(previous!=null&&CUSTOM_CAPES.remove(player))Minecraft.getInstance().getTextureManager().release(previous);
 }
 static void acceptCape(CapeTexturePayload payload,IPayloadContext context){context.enqueueWork(()->{
  try{
   NativeImage image=NativeImage.read(new ByteArrayInputStream(payload.png()));
   if(image.getWidth()!=1024||image.getHeight()!=512){image.close();return;}
   clearCape(payload.player());
   ResourceLocation id=ResourceLocation.fromNamespaceAndPath("animeoncraft","capes/"+payload.player());
   Minecraft.getInstance().getTextureManager().register(id,new DynamicTexture(image));CAPES.put(payload.player(),id);CUSTOM_CAPES.add(payload.player());CAPE_APPLIED.remove(payload.player());
   LOGGER.info("AOC personal cape received: uuid={}",payload.player());
  }catch(java.io.IOException|RuntimeException ignored){/* Keep the current appearance if a texture is invalid. */}
 });}
 public static PlayerSkin get(UUID id){var skin=SKINS.get(id);if(skin!=null&&APPLIED.add(id))LOGGER.info("AOC skin rendered: uuid={} model={}",id,skin.model());return skin;}
 public static String clan(UUID id){return CLANS.get(id);}
 public static PlayerSkin appearance(UUID id,PlayerSkin original){
  var custom=get(id);var base=custom==null?original:custom;var cape=CAPES.get(id);
  if(cape!=null&&CAPE_APPLIED.add(id))LOGGER.info("AOC cape applied: uuid={} texture={}",id,cape);
  if(custom==null&&cape==null)return original;
  return new PlayerSkin(base.texture(),base.textureUrl(),cape,base.elytraTexture(),base.model(),base.secure());
 }
 static void logout(ClientPlayerNetworkEvent.LoggingOut event){
  var textures=Minecraft.getInstance().getTextureManager();for(var skin:SKINS.values())textures.release(skin.texture());for(var id:java.util.List.copyOf(CUSTOM_CAPES))clearCape(id);SKINS.clear();APPLIED.clear();CAPES.clear();CUSTOM_CAPES.clear();CLANS.clear();CAPE_APPLIED.clear();
 }
}
