package ru.animeoncraft.integration.neoforge;

import java.nio.file.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.world.level.GameType;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/** Actual LAN packets and actual PlayerModel mixin; isolated world, no production accounts. */
@Mod("aocemoteprobe")
public final class EmoteMultiplayerProbe {
    private final boolean host="host".equals(System.getProperty("aoc.emoteProbeRole"));
    private final Path root=Path.of(System.getProperty("aoc.emoteProbeRoot"));
    private int phase,ticks,frames;private final long started=System.nanoTime();
    public EmoteMultiplayerProbe(){
        if(!root.toAbsolutePath().toString().replace('\\','/').contains("/.tools/emote-multiplayer-"))throw new IllegalStateException("Disposable directory required");
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST,this::tick);
        if(host)NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST,(PlayerEvent.PlayerLoggedInEvent e)->{if(e.getEntity() instanceof net.minecraft.server.level.ServerPlayer p&&p.getGameProfile().getName().equals("AocObserver"))p.teleportTo(p.serverLevel(),.5,121,4.5,180,0);});
    }
    private void signal(String name){try{Files.writeString(root.resolve(name+".ok"),"ok");}catch(java.io.IOException e){throw new IllegalStateException(e);}}
    private boolean ready(String name){return Files.exists(root.resolve(name+".ok"));}
    private static void check(boolean value,String message){if(!value)throw new IllegalStateException(message);}
    private void shot(String name){var mc=Minecraft.getInstance();Screenshot.grab(mc.gameDirectory,name+".png",mc.getMainRenderTarget(),ignored->{});}
    private void tick(ClientTickEvent.Post event){
        var mc=Minecraft.getInstance();ticks++;
        try{
            check(System.nanoTime()-started<300_000_000_000L,"Timeout phase="+phase);
            if(mc.player==null){if(mc.screen!=null&&ticks%30==0)for(var c:mc.screen.children())if(c instanceof net.minecraft.client.gui.components.Button b&&b.active&&Set.of("Proceed","I know what I'm doing!","Yes").contains(b.getMessage().getString()))b.onPress();return;}
            if(host){
                var server=mc.getSingleplayerServer();
                if(phase==0){server.execute(()->{var p=server.getPlayerList().getPlayer(mc.player.getUUID());p.setGameMode(GameType.SURVIVAL);p.serverLevel().getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING).set(false,server);for(int x=-6;x<=6;x++)for(int z=-6;z<=6;z++)p.serverLevel().setBlockAndUpdate(new net.minecraft.core.BlockPos(x,120,z),net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());p.teleportTo(p.serverLevel(),.5,121,.5,0,0);});phase=1;frames=0;}
                else if(phase==1&&++frames>40){ClientEmotes.read();if(Boolean.getBoolean("aoc.emoteMenuOnly")){phase=50;frames=0;}else{server.setUsesAuthentication(false);check(server.publishServer(GameType.SURVIVAL,true,Integer.getInteger("aoc.emoteProbePort")),"LAN publish");signal("host-ready");phase=2;}}
                else if(phase==2&&ready("observer-ready")&&ClientEmotes.inventory()!=null){check(ClientEmotes.inventory().owned().size()==5,"Host starter inventory");ClientEmotes.choose("NEON_DANCE");phase=3;}
                else if(phase==3&&ready("dance")){check(mc.options.getCameraType()==net.minecraft.client.CameraType.THIRD_PERSON_BACK,"Third person preview");ClientEmotes.choose("HERO_POSE");phase=4;}
                else if(phase==4&&ready("hero")){ClientEmotes.choose("ZEN_POSE");phase=5;}
                else if(phase==5&&ready("zen")){server.execute(()->{var p=server.getPlayerList().getPlayer(mc.player.getUUID());p.teleportTo(p.serverLevel(),1.5,121,.5,0,0);});phase=6;frames=0;}
                else if(phase==6&&++frames>25&&ready("movement-stopped")){check(mc.options.getCameraType()==net.minecraft.client.CameraType.FIRST_PERSON,"Camera restored after interruption");ClientEmotes.choose("HERO_POSE");phase=7;frames=0;}
                else if(phase==7&&++frames>20&&ClientEmotes.current().equals("HERO_POSE")){server.execute(()->{var p=server.getPlayerList().getPlayer(mc.player.getUUID());p.hurt(p.damageSources().generic(),1);});phase=8;frames=0;}
                else if(phase==8&&++frames>20&&ClientEmotes.current().isEmpty()){signal("damage-checked");phase=9;}
                else if(phase==9&&ready("observer-done")){
                    NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.client.event.InputEvent.Key(mc.options.keyPlayerList.getKey().getValue(),0,org.lwjgl.glfw.GLFW.GLFW_PRESS,0));
                    click(377,315);NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.client.event.InputEvent.Key(mc.options.keyPlayerList.getKey().getValue(),0,org.lwjgl.glfw.GLFW.GLFW_RELEASE,0));
                    check(mc.screen instanceof ServerMenuScreen s&&s.page()==ServerMenuLogic.Page.WARDROBE,"Wardrobe opened through radial menu");phase=10;frames=0;
                }
                else if(phase==10&&++frames==10)click(995,166);
                else if(phase==10&&frames>20){check(((ServerMenuScreen)mc.screen).wardrobeSlot().equals("EMOTES"),"Emote tab selected");check(ClientEmotes.inventory()!=null&&ClientEmotes.inventory().available(),"Local wardrobe keeps starter inventory");shot("emote-wardrobe");System.out.println("AOC_EMOTE_HOST_PASS starters=5 camera=true movement=true damage=true wardrobe=true");signal("host-done");phase=11;mc.stop();}
                else if(phase==50&&ClientEmotes.inventory()!=null){NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.client.event.InputEvent.Key(mc.options.keyPlayerList.getKey().getValue(),0,org.lwjgl.glfw.GLFW.GLFW_PRESS,0));click(377,315);NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.client.event.InputEvent.Key(mc.options.keyPlayerList.getKey().getValue(),0,org.lwjgl.glfw.GLFW.GLFW_RELEASE,0));phase=51;frames=0;}
                else if(phase==51&&++frames==10)click(995,166);
                else if(phase==51&&frames>25){check(((ServerMenuScreen)mc.screen).wardrobeSlot().equals("EMOTES"),"Emote tab selected");check(ClientEmotes.inventory().available()&&ClientEmotes.inventory().owned().size()==5,"All five emotes in wardrobe");shot("emote-wardrobe");net.neoforged.neoforge.network.PacketDistributor.sendToServer(new EmotePayloads.Request("READ"));click(977,219);check(mc.screen==null,"Play button closes wardrobe");phase=52;frames=0;}
                else if(phase==52&&++frames>15){check(ClientEmotes.current().equals("NEON_DANCE"),"Play works immediately after inventory READ");ClientEmotes.choose("");phase=53;frames=0;}
                else if(phase==53&&++frames>15){check(ClientEmotes.current().isEmpty(),"Explicit stop works");check(mc.options.getCameraType()==net.minecraft.client.CameraType.FIRST_PERSON,"Camera restored after explicit stop");System.out.println("AOC_EMOTE_MENU_PASS read_then_play=true stop=true");phase=54;mc.stop();}
            }else{
                var other=mc.level.players().stream().filter(p->p.getGameProfile().getName().equals("AocWearer")).findFirst().orElse(null);if(other==null)return;
                mc.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,other.position().add(0,1.1,0));ClientEmotes.read();
                if(phase==0&&ClientEmotes.inventory()!=null){check(ClientEmotes.inventory().owned().equals(Emotes.starters()),"Remote starter inventory");signal("observer-ready");phase=1;}
                else if(phase==1&&ClientEmotes.playing(other.getUUID(),"NEON_DANCE")&&ClientEmotes.draws.getOrDefault("NEON_DANCE",0L)>12){shot("dance");signal("dance");phase=2;}
                else if(phase==2&&ClientEmotes.playing(other.getUUID(),"HERO_POSE")&&ClientEmotes.draws.getOrDefault("HERO_POSE",0L)>12){shot("hero-pose");signal("hero");phase=3;}
                else if(phase==3&&ClientEmotes.playing(other.getUUID(),"ZEN_POSE")&&ClientEmotes.draws.getOrDefault("ZEN_POSE",0L)>12){shot("zen-pose");signal("zen");phase=4;}
                else if(phase==4&&!ClientEmotes.playing(other.getUUID(),"ZEN_POSE")){signal("movement-stopped");phase=5;}
                else if(phase==5&&ready("damage-checked")){signal("observer-done");phase=6;}
                else if(phase==6&&ready("host-done")){System.out.println("AOC_EMOTE_OBSERVER_PASS real_remote_render=true all_three=true cancellation=true");phase=7;mc.stop();}
            }
        }catch(Exception e){System.out.println("AOC_EMOTE_FAILED role="+(host?"host":"observer")+" phase="+phase+" message="+e.getMessage());throw new IllegalStateException(e);}
    }
    private void click(double x,double y){var s=Minecraft.getInstance().screen;check(s!=null,"Menu is open");double k=Math.min(s.width/1100.0,s.height/620.0);s.mouseClicked((s.width-1100*k)/2+x*k,(s.height-620*k)/2+y*k,0);}
}
