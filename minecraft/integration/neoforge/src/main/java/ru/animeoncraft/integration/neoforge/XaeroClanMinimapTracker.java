package ru.animeoncraft.integration.neoforge;
import java.util.Iterator;
import java.util.UUID;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import xaero.hud.minimap.player.tracker.system.IRenderedPlayerTracker;
import xaero.hud.minimap.player.tracker.system.ITrackedPlayerReader;
final class XaeroClanMinimapTracker implements IRenderedPlayerTracker<ClanPositionsPayload.Position>,ITrackedPlayerReader<ClanPositionsPayload.Position> {
 private static boolean installed;
 static void install(){if(installed||xaero.common.HudMod.INSTANCE==null||xaero.common.HudMod.INSTANCE.getPlayerTracker()==null)return;xaero.common.HudMod.INSTANCE.getPlayerTracker().register("animeoncraft_clan",new XaeroClanMinimapTracker());installed=true;System.getLogger("AnimeOnCraft").log(System.Logger.Level.INFO,"AOC clan minimap player tracker registered");}
 public ITrackedPlayerReader<ClanPositionsPayload.Position> getReader(){return this;}
 public Iterator<ClanPositionsPayload.Position> getTrackedPlayerIterator(){return ClientClanPositions.current().iterator();}
 public UUID getId(ClanPositionsPayload.Position p){return p.id();}
 public double getX(ClanPositionsPayload.Position p){return p.x();}
 public double getY(ClanPositionsPayload.Position p){return p.y();}
 public double getZ(ClanPositionsPayload.Position p){return p.z();}
 public ResourceKey<Level> getDimension(ClanPositionsPayload.Position p){return p.dimension();}
}
