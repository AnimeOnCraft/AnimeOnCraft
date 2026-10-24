package ru.animeoncraft.integration.neoforge;

import java.util.ArrayList;
import java.util.HashMap;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Small public POI snapshot. The legacy image digest remains only as a wire-compatible world identifier. */
final class WorldAtlas {
    private static WorldAtlasData previous;
    private WorldAtlas() {}
    static WorldAtlasData snapshot() {
        var layout = WorldLayout.state();
        if (layout == null || !layout.worldId().equals(WorldAtlasAsset.BOUNDS.worldId())) return null;
        var colors = new HashMap<String,Integer>();
        var regions = WorldRegions.current();
        if (regions != null) for (var r : regions.regions()) if (r.clan() != null && r.color() >= 0) colors.putIfAbsent(r.clan(),r.color());
        var sites = CaptureSites.view(); boolean live = CaptureSites.fresh() && layout.worldId().equals(sites.worldId());
        var points = new ArrayList<WorldAtlasData.Point>();
        for (var poi : layout.pois().values()) {
            if (SoraExpansion.TYPE.equals(poi.type())) continue; // part of Sora, not a separate place on the map
            var site = live ? sites.points().get(poi.code()) : null;
            String owner = site != null ? site.owner() : ClanCodes.known(poi.palette()) ? ClanControlSync.owner(poi.palette()) : null;
            int color = owner != null ? colors.getOrDefault(owner,0xAAAAAA) : switch (poi.type()) {
                case "CAPITAL", "CLAN_CAPITAL" -> 0xEAD7FF;
                case "RESOURCE_SITE" -> 0x65D99A;
                case "FORT", "OUTPOST" -> 0xF4BE64;
                default -> 0x82C9E5;
            };
            points.add(new WorldAtlasData.Point(poi.code(),poi.name(),poi.type(),poi.x(),site != null && site.y() != null ? site.y() : 80,poi.z(),poi.radius(),color,owner == null ? "" : owner,site == null ? "" : site.state()));
        }
        if (regions!=null) for (var plot : regions.regions()) if (plot.market()) {
            points.add(new WorldAtlasData.Point(plot.code(),"Торговый участок "+plot.clan(),"MARKET",
                (plot.minX()+plot.maxX())/2,80,(plot.minZ()+plot.maxZ())/2,(plot.maxX()-plot.minX())/2,
                plot.color()<0?0xAAAAAA:plot.color(),plot.clan(),""));
        }
        return new WorldAtlasData(layout.worldId(),WorldAtlasAsset.BOUNDS.sha256(),points);
    }
    private static void send(ServerPlayer player, WorldAtlasData data) {
        if(Boolean.getBoolean("animeoncraft.atlasClientProbe"))System.out.println("AOC_ATLAS_PROBE_SEND data="+(data==null?"none":data.worldId())+" channel="+player.connection.hasChannel(WorldAtlasPayload.TYPE.id()));
        if (data != null && player.connection.hasChannel(WorldAtlasPayload.TYPE.id())) PacketDistributor.sendToPlayer(player,new WorldAtlasPayload(data));
    }
    static void login(PlayerEvent.PlayerLoggedInEvent event) { if (event.getEntity() instanceof ServerPlayer player) send(player,snapshot()); }
    static void tick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount()%100 != 0) return;
        var data = snapshot(); if (data == null || data.equals(previous)) return; previous = data;
        for (var player : event.getServer().getPlayerList().getPlayers()) send(player,data);
    }
    static void stopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) { previous = null; }
}
