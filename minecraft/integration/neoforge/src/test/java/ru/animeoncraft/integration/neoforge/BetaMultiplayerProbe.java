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

/** Two actual network clients in disposable directories; no fabricated appearance packets. */
@Mod("aocbetamultiplayerprobe")
public final class BetaMultiplayerProbe {
    private final boolean host=System.getProperty("aoc.betaProbeRole").equals("host");
    private final Path root=Path.of(System.getProperty("aoc.betaProbeRoot"));
    private int phase,ticks,frames;
    private long started=System.nanoTime();
    private UUID target;
    public BetaMultiplayerProbe(){
        if(!root.toAbsolutePath().toString().replace('\\','/').contains("/.tools/beta-multiplayer-"))throw new IllegalStateException("Disposable directory required");
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST,this::tick);
        if(host)NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST,(PlayerEvent.PlayerLoggedInEvent e)->{
            if(e.getEntity() instanceof net.minecraft.server.level.ServerPlayer p&&p.getGameProfile().getName().equals("AocObserver")){
                var owner=p.server.getPlayerList().getPlayers().stream().filter(v->v.getGameProfile().getName().equals("AocWearer")).findFirst().orElseThrow();
                p.teleportTo(owner.serverLevel(),owner.getX()+3,owner.getY(),owner.getZ(),90,0);
                p.setInvulnerable(true);
            }
        });
    }
    private void signal(String name){try{Files.writeString(root.resolve(name+".ok"),"ok");}catch(java.io.IOException e){throw new IllegalStateException(e);}}
    private boolean ready(String name){return Files.exists(root.resolve(name+".ok"));}
    private static void check(boolean b,String message){if(!b)throw new IllegalStateException(message);}
    private void tick(ClientTickEvent.Post event){
        var mc=Minecraft.getInstance();ticks++;
        try{
            check(System.nanoTime()-started<300_000_000_000L,"Timed out role="+(host?"host":"observer")+" phase="+phase);
            if(mc.player==null){if(mc.screen!=null&&ticks%30==0)for(var c:mc.screen.children())if(c instanceof net.minecraft.client.gui.components.Button b&&b.active&&Set.of("Proceed","I know what I'm doing!","Yes").contains(b.getMessage().getString()))b.onPress();return;}
            if(host){
                var server=mc.getSingleplayerServer();
                if(phase==0){server.execute(()->{var p=server.getPlayerList().getPlayer(mc.player.getUUID());p.setGameMode(GameType.SURVIVAL);p.setInvulnerable(true);var store=BetaPassStore.get(server);var e=store.player(p.getUUID());e.xp=1000;e.rewards=1023;for(var entry:Map.of("HEAD","FOX_EARS","FACE","ONI_MASK","BELT","RIFT_LANTERN","BACK","RIFT_MANTLE","TITLE","NEON_LEGEND").entrySet())e.equip(entry.getKey(),entry.getValue());store.setDirty();BetaPassServer.send(p);BetaPassServer.broadcast(p);});phase=1;frames=0;}
                else if(phase==1&&++frames>40){server.setUsesAuthentication(false);check(server.publishServer(GameType.SURVIVAL,true,Integer.getInteger("aoc.betaProbePort")),"LAN publish");signal("host-ready");phase=2;}
                else if(phase==2&&ready("observer-initial")){ClientBetaPass.request("EQUIP",0,"HEAD","SHARD_CROWN");phase=3;}
                else if(phase==3&&ready("observer-change")){server.execute(()->{var p=server.getPlayerList().getPlayer(mc.player.getUUID());p.awardStat(net.minecraft.stats.Stats.ITEM_CRAFTED.get(net.minecraft.world.item.Items.STICK),4);});phase=4;frames=0;}
                else if(phase==4&&++frames>120){ClientBetaPass.request("GET",0,"","");phase=5;frames=0;}
                else if(phase==5&&++frames>30){check(ClientBetaPass.data().daily().get(2)==4,"Survival craft quest tracks");server.execute(()->{var p=server.getPlayerList().getPlayer(mc.player.getUUID());p.setGameMode(GameType.CREATIVE);});phase=6;frames=0;}
                else if(phase==6&&++frames>120){server.execute(()->{var p=server.getPlayerList().getPlayer(mc.player.getUUID());p.awardStat(net.minecraft.stats.Stats.ITEM_CRAFTED.get(net.minecraft.world.item.Items.STICK),5);});phase=7;frames=0;}
                else if(phase==7&&++frames>120){ClientBetaPass.request("GET",0,"","");phase=8;frames=0;}
                else if(phase==8&&++frames>30){check(ClientBetaPass.data().daily().get(2)==4,"Creative crafting excluded");signal("quests-checked");phase=9;}
                else if(phase==9&&ready("observer-done")){signal("host-done");System.out.println("AOC_BETA_MULTIPLAYER_HOST_PASS");phase=10;mc.stop();}
            }else{
                var other=mc.level.players().stream().filter(p->p.getGameProfile().getName().equals("AocWearer")).findFirst().orElse(null);
                if(other==null)return;target=other.getUUID();mc.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,other.position().add(0,1.5,0));
                var equipped=ClientBetaPass.equipped(target);
                if(phase==0&&equipped.size()==5){check("FOX_EARS".equals(equipped.get("HEAD")),"Late join sees selected head");check(ClientBetaPass.title(target)!=null,"Remote title received");signal("observer-initial");phase=1;frames=0;}
                else if(phase==1&&"SHARD_CROWN".equals(equipped.get("HEAD"))){signal("observer-change");phase=2;frames=0;}
                else if(phase==2&&++frames>60){check(DetailedCosmetics.draws.getOrDefault("SHARD_CROWN",0L)>0&&DetailedCosmetics.draws.getOrDefault("ONI_MASK",0L)>0&&DetailedCosmetics.draws.getOrDefault("RIFT_LANTERN",0L)>0&&BetaCosmeticRenderer.mantleDraws>0,"Actual remote player rendered all four models");Screenshot.grab(mc.gameDirectory,"remote-cosmetics.png",mc.getMainRenderTarget(),ignored->{});ClientBetaPass.logout(null);check(ClientBetaPass.equipped(target).isEmpty(),"Drop observer cache to exercise recovery");phase=3;frames=0;}
                else if(phase==3&&equipped.size()==5){check("SHARD_CROWN".equals(equipped.get("HEAD")),"Nearby state restored without wearer opening menu");signal("observer-recovered");phase=4;}
                else if(phase==4&&ready("quests-checked")){signal("observer-done");System.out.println("AOC_BETA_MULTIPLAYER_OBSERVER_PASS late_join=true real_remote_models=true equip_change=true recovery=true");phase=5;mc.stop();}
            }
        }catch(Exception failure){System.out.println("AOC_BETA_MULTIPLAYER_FAILED role="+(host?"host":"observer")+" phase="+phase+" message="+failure.getMessage());throw new IllegalStateException(failure);}
    }
}
