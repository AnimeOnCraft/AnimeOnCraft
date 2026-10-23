package ru.animeoncraft.integration.neoforge;
import java.util.List;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.fml.ModList;

final class ClientClanPositions {
 private static List<ClanPositionsPayload.Position> players=List.of();
 private static long received;
 private static boolean probeSeen,probeDone;
 private ClientClanPositions(){}
 static List<ClanPositionsPayload.Position> current(){return current(System.nanoTime());}
 static List<ClanPositionsPayload.Position> current(long now){return now-received>12_000_000_000L?List.of():players;}
 static void replace(ClanPositionsPayload payload,long now){players=payload.players();received=now;}
 static void accept(ClanPositionsPayload payload,IPayloadContext context){context.enqueueWork(()->replace(payload,System.nanoTime()));}
 static void logout(ClientPlayerNetworkEvent.LoggingOut event){players=List.of();received=0;}
 static void tick(ClientTickEvent.Post event){
  if(ModList.get().isLoaded("xaerominimap"))XaeroClanMinimapTracker.install();
  if(ModList.get().isLoaded("xaeroworldmap"))XaeroClanWorldTracker.install();
  var mc=net.minecraft.client.Minecraft.getInstance();
  if(Boolean.getBoolean("animeoncraft.acceptance.clanPositions")&&!probeDone&&mc.player!=null&&mc.getCurrentServer()!=null&&mc.getCurrentServer().ip.equals("127.0.0.1:25566")&&mc.player.getUUID().toString().equals("34237c32-b733-39aa-8838-90e69387170c")){
   if(current().size()==2){
    boolean mini=false,world=false;
    for(var tracker:xaero.common.HudMod.INSTANCE.getPlayerTracker().getAllSystems())if(tracker instanceof XaeroClanMinimapTracker&&tracker.getTrackedPlayerIterator().hasNext())mini=true;
    for(var tracker:xaero.map.WorldMap.playerTrackerSystemManager.getSystems())if(tracker instanceof XaeroClanWorldTracker&&tracker.getTrackedPlayerIterator().hasNext())world=true;
    if(!mini||!world)throw new IllegalStateException("Clan map trackers missing from actual registries");
    if(current().stream().noneMatch(p->p.dimension().equals(net.minecraft.world.level.Level.NETHER))||current().stream().noneMatch(p->p.x()==2000&&p.z()==3000))throw new IllegalStateException("Clan marker position changed");
    probeSeen=true;
   }else if(probeSeen&&current().isEmpty()){
    probeDone=true;System.out.println("AOC_CLAN_POSITION_CLIENT_PASS world_map=true minimap=true far=true dimension=true cleared=true");
   }
  }
 }
}
