package ru.animeoncraft.integration.neoforge;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import xaero.hud.minimap.BuiltInHudModules;
import xaero.hud.minimap.waypoint.WaypointColor;
import xaero.hud.minimap.waypoint.WaypointPurpose;
import xaero.hud.minimap.waypoint.thirdparty.ThirdPartyWaypoints;
import xaero.common.minimap.waypoints.Waypoint;
import xaero.common.minimap.waypoints.WaypointVisibilityType;

/** Dedicated third-party origin: never replaces a user's sets, death points or private markers. */
final class XaeroAtlasWaypoints {
    private static final ResourceLocation ORIGIN = ResourceLocation.fromNamespaceAndPath("animeoncraft","world_atlas");
    private static ThirdPartyWaypoints installed;
    private static WorldAtlasData previous;
    private XaeroAtlasWaypoints() {}
    static void clear() { if (installed != null) installed.clear(); installed=null; previous=null; }
    @SuppressWarnings("deprecation") // Pinned Xaero exposes updates to an existing marker's color through this index setter.
    static void sync(WorldAtlasData atlas) {
        var session = BuiltInHudModules.MINIMAP.getCurrentSession();
        var world = session == null ? null : session.getWorldManager().getCurrentWorld();
        if (atlas == null || world == null || !Level.OVERWORLD.equals(world.getDimId())) { clear(); return; }
        var target = world.getContainer().getThirdPartyWaypointManager().get(ORIGIN);
        if (target == installed && atlas.equals(previous)) return;
        if (installed != null && installed != target) installed.clear();
        installed=target; previous=atlas;
        var ids = atlas.points().stream().map(WorldAtlasData.Point::id).collect(java.util.stream.Collectors.toSet());
        for (String old : java.util.List.copyOf(target.getWaypoints().keySet())) if (!ids.contains(old)) target.remove(old);
        for (var point : atlas.points()) {
            var waypoint = target.get(point.id());
            if (waypoint == null) {
                waypoint = new Waypoint(point.x(),point.y(),point.z(),point.label(),point.symbol(),nearest(point.color()),WaypointPurpose.NORMAL);
                waypoint.setVisibility(WaypointVisibilityType.LOCAL); waypoint.setYIncluded(false);
                target.add(point.id(),waypoint);
            } else { waypoint.setX(point.x());waypoint.setY(point.y());waypoint.setZ(point.z());waypoint.setName(point.label());waypoint.setColor(nearest(point.color()).ordinal()); }
        }
    }
    private static WaypointColor nearest(int rgb) {
        WaypointColor best=WaypointColor.WHITE; long distance=Long.MAX_VALUE;
        for(var color:WaypointColor.values()) {
            int value=color.getHex();long r=(rgb>>16&255)-(value>>16&255),g=(rgb>>8&255)-(value>>8&255),b=(rgb&255)-(value&255),d=r*r+g*g+b*b;
            if(d<distance){distance=d;best=color;}
        }
        return best;
    }
}
