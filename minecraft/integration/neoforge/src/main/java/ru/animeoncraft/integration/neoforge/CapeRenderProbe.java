package ru.animeoncraft.integration.neoforge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.CameraType;
import net.minecraft.client.Screenshot;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Isolated acceptance diagnostics; never changes the ordinary player's camera or captures it. */
public final class CapeRenderProbe {
 private static final org.apache.logging.log4j.Logger LOGGER=org.apache.logging.log4j.LogManager.getLogger("AnimeOnCraft");
 private static boolean rendered,saved,personal;
 private static int ticks;
 private CapeRenderProbe(){}
 private static boolean enabled(){
  var mc=Minecraft.getInstance();
  return Boolean.getBoolean("animeoncraft.acceptance.cape")&&mc.player!=null
    &&mc.player.getUUID().toString().equals("34237c32-b733-39aa-8838-90e69387170c")
    &&mc.getCurrentServer()!=null&&mc.getCurrentServer().ip.equals("127.0.0.1:25566");
 }
 static void tick(ClientTickEvent.Post event){
  if(!enabled())return;
  var mc=Minecraft.getInstance();mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
  if(rendered&&!saved&&++ticks>=30){saved=true;Screenshot.grab(mc.gameDirectory,personal?"personal-cape-acceptance.png":"beta-cape-acceptance.png",mc.getMainRenderTarget(),message->LOGGER.info("AOC cape screenshot saved"));}
 }
 public static void drawn(AbstractClientPlayer player){
  if(!enabled()||rendered||player.isInvisible()||!player.isModelPartShown(PlayerModelPart.CAPE))return;
  var cape=player.getSkin().capeTexture();
  if(cape!=null&&cape.toString().equals("animeoncraft:textures/capes/beta.png")){
   rendered=true;LOGGER.info("AOC beta cape layer rendered: uuid={}",player.getUUID());
  }
  else if(cape!=null&&cape.getNamespace().equals("animeoncraft")&&cape.getPath().startsWith("capes/")){
   rendered=true;personal=true;LOGGER.info("AOC personal cape layer rendered: uuid={}",player.getUUID());
  }
 }
}
