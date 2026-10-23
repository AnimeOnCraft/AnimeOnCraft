package ru.animeoncraft.integration.neoforge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;

/** Disposable native Xaero test: live terrain, markers, private points and cave layers. */
final class AtlasClientProbe {
    private int ticks,phase,frames,upperLayer;private long writerCounter;
    static void register(){new AtlasClientProbe();}
    private AtlasClientProbe(){NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST,this::starting);NeoForge.EVENT_BUS.addListener(this::tick);}
    private boolean enabled(){return Boolean.getBoolean("animeoncraft.atlasClientProbe")&&Minecraft.getInstance().gameDirectory.getAbsolutePath().contains("atlas-client-");}
    private void starting(ServerAboutToStartEvent event){
        if(!enabled())return;
        try{WorldLayout.load(Path.of("config/atlas-probe-layout.json"));WorldRegions.loadCache();}catch(Exception failure){throw new IllegalStateException("Marker fixture failed",failure);}
    }
    @SuppressWarnings("deprecation")
    private void tick(ClientTickEvent.Post event){
        if(!enabled())return;var mc=Minecraft.getInstance();ticks++;
        if(mc.player==null){if(mc.screen!=null&&ticks%30==0)for(var child:mc.screen.children())if(child instanceof Button b&&b.active&&java.util.Set.of("Proceed","I know what I'm doing!","Yes").contains(b.getMessage().getString()))b.onPress();return;}
        if(!ClientWorldAtlas.active()||ticks<200)return;
        try{
            var data=ClientWorldAtlas.current();var session=xaero.map.WorldMapSession.getCurrentSession();if(session==null||session.getMapProcessor().getMapWorld()==null)return;
            var processor=session.getMapProcessor();var points=points();
            if(phase==0){
                check(data.points().size()>=60,"Public points arrived through real login payload");
                check(data.points().stream().anyMatch(p->p.kind().equals("FORT"))&&data.points().stream().anyMatch(p->p.kind().equals("RESOURCE_SITE")),"Fort and resource markers");
                check(points.getCount()==data.points().size(),"Native waypoint count");
                check(processor.getHighlighterRegistry().getHighlighters().stream().noneMatch(h->h.getClass().getName().startsWith("ru.animeoncraft")),"No custom terrain or territory paint registered");
                check(!processor.regionExists(Integer.MAX_VALUE,1000,1000),"No invented terrain in unexplored regions");
                ClientClanPositions.replace(new ClanPositionsPayload("NYA",List.of(new ClanPositionsPayload.Position(java.util.UUID.fromString("00000000-0000-4000-8000-000000000002"),Level.OVERWORLD,2000,80,2000))),System.nanoTime());
                boolean world=false;for(var tracker:xaero.map.WorldMap.playerTrackerSystemManager.getSystems())if(tracker instanceof XaeroClanWorldTracker&&tracker.getTrackedPlayerIterator().hasNext())world=true;check(world,"Clan world-map tracker");
                boolean mini=false;for(var tracker:xaero.common.HudMod.INSTANCE.getPlayerTracker().getAllSystems())if(tracker instanceof XaeroClanMinimapTracker&&tracker.getTrackedPlayerIterator().hasNext())mini=true;check(mini,"Clan minimap tracker");
                privatePoints().add("home",new xaero.common.minimap.waypoints.Waypoint(1500,80,1000,"Private test home","H",xaero.hud.minimap.waypoint.WaypointColor.WHITE));
                writerCounter=processor.getMapWriter().getUpdateCounter();surface(mc,79);phase=1;frames=0;
            }else if(phase==1){
                check(++frames<1200,"Native surface must map a new building");
                if(frames>80&&XaeroAtlasLayers.surfaceWorldMap()&&mappedHeight(processor,Integer.MAX_VALUE)==79){
                    check(processor.getMapWriter().getUpdateCounter()>writerCounter,"Surface writer runs normally");shot(mc,"atlas-minimap");openMap(mc,processor);phase=2;frames=0;
                }
            }else if(phase==2&&++frames==80){shot(mc,"atlas-world");mc.setScreen(null);writerCounter=processor.getMapWriter().getUpdateCounter();surface(mc,80);phase=3;frames=0;
            }else if(phase==3){
                check(++frames<1200,"Changed surface block must update the native map");
                if(frames>80&&mappedHeight(processor,Integer.MAX_VALUE)==80){
                    check(processor.getMapWriter().getUpdateCounter()>writerCounter,"Writer continues after building changes");shot(mc,"atlas-surface-edited");
                    var changed=new java.util.ArrayList<>(data.points());var p=changed.getFirst();changed.set(0,new WorldAtlasData.Point(p.id(),p.name(),p.kind(),p.x(),p.y(),p.z(),p.radius(),0xff0044,"NYA","CAPTURING"));
                    ClientWorldAtlas.apply(new WorldAtlasData(data.worldId(),data.imageSha256(),changed));phase=4;frames=0;
                }
            }else if(phase==4&&++frames==35){
                check(points.get(data.points().getFirst().id()).getName().contains("Бой"),"Live marker ownership/state update");
                prepareCaves(mc);phase=5;frames=0;
            }else if(phase==5||phase==7){
                int floor=phase==5?39:-9,layer=processor.getCurrentCaveLayer();check(++frames<1200,"Native cave floor, phase="+phase+" mapped="+mappedHeight(processor,layer));
                if(frames%200==0)System.out.println("AOC_NATIVE_CAVE_WAIT phase="+phase+" layer="+layer+" y="+mc.player.getY()+" mapped="+mappedHeight(processor,layer)+" floor="+mc.level.getBlockState(new BlockPos(64,floor,64)));
                if(frames>100&&layer!=Integer.MAX_VALUE&&!XaeroAtlasLayers.surfaceMinimap()&&mappedHeight(processor,layer)==floor){
                    if(phase==5)upperLayer=layer;else check(layer!=upperLayer,"Distinct native cave layers");
                    check(points.getCount()==data.points().size(),"Markers remain available underground");shot(mc,phase==5?"atlas-cave-upper-minimap":"atlas-cave-lower-minimap");openMap(mc,processor);phase++;frames=0;
                }
            }else if((phase==6||phase==8)&&++frames==100){
                check(!XaeroAtlasLayers.surfaceWorldMap(),"Full map follows cave height");shot(mc,phase==6?"atlas-cave-upper-world":"atlas-cave-lower-world");mc.setScreen(null);move(mc,phase==6?-8:40);phase=phase==6?7:9;frames=0;
            }else if(phase==9){
                check(++frames<1200,"Previously explored cave remains cached");
                if(frames>100&&processor.getCurrentCaveLayer()==upperLayer&&mappedHeight(processor,upperLayer)==39){shot(mc,"atlas-cave-revisited");move(mc,200);phase=10;frames=0;}
            }else if(phase==10){
                check(++frames<1200,"Native map returns to the current surface");
                if(frames>100&&XaeroAtlasLayers.surfaceWorldMap()&&XaeroAtlasLayers.surfaceMinimap()&&mappedHeight(processor,Integer.MAX_VALUE)==60){
                    shot(mc,"atlas-surface-restored");ClientWorldAtlas.logout(null);check(!ClientWorldAtlas.active()&&points.isEmpty(),"Logout clears only server markers");check(privatePoints().getCount()==1,"Private waypoints preserved");
                    ClientClanPositions.logout(null);ClientWorldAtlas.apply(new WorldAtlasData("other-world",WorldAtlasAsset.BOUNDS.sha256(),List.of()));check(!ClientWorldAtlas.active(),"Wrong-world markers rejected");
                    System.out.println("AOC_ATLAS_CLIENT_PASS native_terrain=true writer_live=true edited_surface_mapped=true image_absent=true colored_overlays_absent=true live_points=true clan_trackers=true private_markers=true cave_layers=true surface_restored=true");phase=11;mc.stop();
                }
            }
        }catch(Exception failure){System.out.println("AOC_ATLAS_CLIENT_FAILED phase="+phase+" "+failure);throw new IllegalStateException("Native map probe failed",failure);}
    }
    private static xaero.hud.minimap.waypoint.thirdparty.ThirdPartyWaypoints points(){return thirdParty("animeoncraft","world_atlas");}
    private static xaero.hud.minimap.waypoint.thirdparty.ThirdPartyWaypoints privatePoints(){return thirdParty("aoc_probe","private");}
    private static xaero.hud.minimap.waypoint.thirdparty.ThirdPartyWaypoints thirdParty(String namespace,String key){return xaero.hud.minimap.BuiltInHudModules.MINIMAP.getCurrentSession().getWorldManager().getCurrentWorld().getContainer().getThirdPartyWaypointManager().get(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(namespace,key));}
    private static void check(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
    private static void shot(Minecraft mc,String name){Screenshot.grab(mc.gameDirectory,name+".png",mc.getMainRenderTarget(),ignored->{});}
    private static int mappedHeight(xaero.map.MapProcessor processor,int layer){
        var region=processor.getLeafMapRegion(layer,0,0,false);if(region==null)return Integer.MIN_VALUE;
        synchronized(region){var chunk=region.getChunk(1,1);if(chunk==null)return Integer.MIN_VALUE;var tile=chunk.getTile(0,0);if(tile==null)return Integer.MIN_VALUE;var block=tile.getBlock(0,0);return block==null?Integer.MIN_VALUE:block.getHeight();}
    }
    private static void surface(Minecraft mc,int height){
        var server=java.util.Objects.requireNonNull(mc.getSingleplayerServer());var id=mc.player.getUUID();server.execute(()->{
            var p=server.getPlayerList().getPlayer(id);p.setGameMode(net.minecraft.world.level.GameType.CREATIVE);p.getAbilities().flying=true;p.onUpdateAbilities();
            for(int x=48;x<=80;x++)for(int z=48;z<=80;z++)p.serverLevel().setBlock(new BlockPos(x,height,z),(height==79?net.minecraft.world.level.block.Blocks.BLUE_CONCRETE:net.minecraft.world.level.block.Blocks.ORANGE_CONCRETE).defaultBlockState(),2);
            p.teleportTo(64.5,200,64.5);
        });
    }
    private static void prepareCaves(Minecraft mc){
        var server=java.util.Objects.requireNonNull(mc.getSingleplayerServer());var id=mc.player.getUUID();server.execute(()->{
            var p=server.getPlayerList().getPlayer(id);var level=p.serverLevel();
            for(int x=48;x<=80;x++)for(int z=48;z<=80;z++)for(int y=-10;y<=81;y++){
                var block=net.minecraft.world.level.block.Blocks.STONE;
                if(y==39)block=net.minecraft.world.level.block.Blocks.BLUE_CONCRETE;if(y==-9)block=net.minecraft.world.level.block.Blocks.ORANGE_CONCRETE;
                if(y>=40&&y<=45||y>=-8&&y<=-3||y>60)block=net.minecraft.world.level.block.Blocks.AIR;
                level.setBlock(new BlockPos(x,y,z),block.defaultBlockState(),2);
            }p.teleportTo(64.5,40,64.5);
        });
    }
    private static void move(Minecraft mc,int y){var server=java.util.Objects.requireNonNull(mc.getSingleplayerServer());var id=mc.player.getUUID();server.execute(()->server.getPlayerList().getPlayer(id).teleportTo(64.5,y,64.5));}
    private static void openMap(Minecraft mc,xaero.map.MapProcessor processor)throws ReflectiveOperationException{
        var screen=Class.forName("xaero.map.gui.GuiMap").getConstructor(net.minecraft.client.gui.screens.Screen.class,net.minecraft.client.gui.screens.Screen.class,xaero.map.MapProcessor.class,net.minecraft.world.entity.Entity.class).newInstance(null,null,processor,mc.player);mc.setScreen((net.minecraft.client.gui.screens.Screen)screen);
        for(String field:List.of("scale","userScale","destScale")){var f=screen.getClass().getDeclaredField(field);f.setAccessible(true);f.setDouble(field.equals("destScale")?null:screen,2);}
        for(String field:List.of("cameraX","cameraZ")){var f=screen.getClass().getDeclaredField(field);f.setAccessible(true);f.setDouble(screen,64.5);}
    }
}
