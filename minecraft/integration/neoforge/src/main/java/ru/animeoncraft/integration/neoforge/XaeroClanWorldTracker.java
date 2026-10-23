package ru.animeoncraft.integration.neoforge;
import java.util.Iterator;
import java.util.UUID;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import xaero.map.radar.tracker.system.IPlayerTrackerSystem;
import xaero.map.radar.tracker.system.ITrackedPlayerReader;
final class XaeroClanWorldTracker implements IPlayerTrackerSystem<ClanPositionsPayload.Position>,ITrackedPlayerReader<ClanPositionsPayload.Position> {
 private static boolean installed;
 static void install(){if(installed||xaero.map.WorldMap.playerTrackerSystemManager==null)return;xaero.map.WorldMap.playerTrackerSystemManager.register("animeoncraft_clan",new XaeroClanWorldTracker());installed=true;System.getLogger("AnimeOnCraft").log(System.Logger.Level.INFO,"AOC clan world map player tracker registered");}
 public ITrackedPlayerReader<ClanPositionsPayload.Position> getReader(){return this;}
 public Iterator<ClanPositionsPayload.Position> getTrackedPlayerIterator(){return ClientClanPositions.current().iterator();}
 public UUID getId(ClanPositionsPayload.Position p){return p.id();}
 public double getX(ClanPositionsPayload.Position p){return p.x();}
 public double getY(ClanPositionsPayload.Position p){return p.y();}
 public double getZ(ClanPositionsPayload.Position p){return p.z();}
 public ResourceKey<Level> getDimension(ClanPositionsPayload.Position p){return p.dimension();}
}
