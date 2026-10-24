package ru.animeoncraft.integration.neoforge;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/** Discovery only. All build/container/war permissions remain server-side in TerritoryProtection. */
final class ClanMarkets {
    private static final Map<UUID,String> INSIDE=new HashMap<>();
    private ClanMarkets() {}
    static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("aocmarket").executes(context->{
            var regions=WorldRegions.current();
            var plots=regions==null?java.util.List.<RegionPolicy.Region>of():regions.regions().stream().filter(RegionPolicy.Region::market).toList();
            if(plots.isEmpty()){context.getSource().sendFailure(Component.literal("Торговые участки пока не открыты."));return 0;}
            for(var plot:plots)context.getSource().sendSuccess(()->Component.literal("Рынок "+plot.clan()+": X "+plot.minX()+"…"+(plot.maxX()-1)
                +", Z "+plot.minZ()+"…"+(plot.maxZ()-1)+". Строит свой клан; торговля открыта всем."),false);
            return plots.size();
        }));
    }
    static void tick(ServerTickEvent.Post event) {
        var server=event.getServer();
        if(!server.isDedicatedServer()||server.getTickCount()%20!=0)return;
        var regions=WorldRegions.current();
        INSIDE.keySet().retainAll(server.getPlayerList().getPlayers().stream().map(p->p.getUUID()).collect(java.util.stream.Collectors.toSet()));
        for(var player:server.getPlayerList().getPlayers()) {
            var at=player.blockPosition();
            var plot=regions==null||!player.level().dimension().equals(Level.OVERWORLD)||!GameIdentity.PLAYERS.containsKey(player.getUUID())
                ? null : regions.marketAt(at.getX(),at.getZ());
            if(plot==null){INSIDE.remove(player.getUUID());continue;}
            var prior=INSIDE.put(player.getUUID(),plot.code());
            if(!plot.code().equals(prior))player.displayClientMessage(Component.literal("Торговый участок "+plot.clan()+" · "
                +(plot.maxX()-plot.minX())+"×"+(plot.maxZ()-plot.minZ())+". Строит клан "+plot.clan()+". Покупки — ПКМ пустой рукой."),true);
        }
    }
    static void stopped(ServerStoppedEvent event){INSIDE.clear();}
}
