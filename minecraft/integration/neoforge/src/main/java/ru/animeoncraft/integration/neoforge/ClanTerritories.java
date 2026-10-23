package ru.animeoncraft.integration.neoforge;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;

final class ClanTerritories {
    private static final org.apache.logging.log4j.Logger LOGGER = org.apache.logging.log4j.LogManager.getLogger("AnimeOnCraft");
    private ClanTerritories() {}

    static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !GameIdentity.PLAYERS.containsKey(player.getUUID())) return;
        if (TerritoryProtection.statesWorld()) { PacketDistributor.sendToPlayer(player, RegionMapPayload.of(WorldRegions.current())); return; }
        List<Territory> regions=regions(player.server);
        PacketDistributor.sendToPlayer(player, new TerritoryPayload(regions));
    }
    static void broadcast(net.minecraft.server.MinecraftServer server){
        if (TerritoryProtection.statesWorld()) {
            var payload = RegionMapPayload.of(WorldRegions.current());
            for(var player:server.getPlayerList().getPlayers())if(GameIdentity.PLAYERS.containsKey(player.getUUID()))PacketDistributor.sendToPlayer(player,payload);
            return;
        }
        var regions=regions(server);
        for(var player:server.getPlayerList().getPlayers())if(GameIdentity.PLAYERS.containsKey(player.getUUID()))PacketDistributor.sendToPlayer(player,new TerritoryPayload(regions));
    }
    private static List<Territory> regions(net.minecraft.server.MinecraftServer server){
        List<Territory> regions=List.of();
        var path=Path.of("config/animeoncraft-clan-spawns.json");
        try{
            if(Files.isRegularFile(path)){
                if(Files.size(path)>65536)throw new IllegalArgumentException("Territory config too large");
                var root=JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                var colors=root.getAsJsonObject("territories").getAsJsonObject("colors");
                regions=TerritoryLayout.read(root,server.overworld().getSeed()).stream().map(region->{
                    String owner=ClanControlSync.owner(region.clan());
                    return new Territory(region.clan(),region.minX(),region.minZ(),region.maxX(),region.maxZ(),Integer.parseInt(colors.get(owner).getAsString(),16),owner);
                }).toList();
            }
        }catch(Exception failure){LOGGER.error("AOC clan territories unavailable: invalid configuration",failure);}
        return regions;
    }
}
