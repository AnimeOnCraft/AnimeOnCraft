package ru.animeoncraft.integration.neoforge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

/** One small resource site at a time; expiring tickets never persist forced chunks in the world. */
final class ResourceRefreshChunks {
    private static final TicketType<ChunkPos> TYPE=TicketType.create("aoc_resource_refresh",Comparator.comparingLong(ChunkPos::toLong),100);
    private static final List<ChunkPos> CHUNKS=new ArrayList<>();
    private static String code;
    private static int started;
    private ResourceRefreshChunks(){}
    static void request(MinecraftServer server,CaptureSites.Point point) {
        if(code!=null&&!code.equals(point.code()))return;
        if(code==null){code=point.code();started=server.getTickCount();int r=point.radius();
            for(int x=(point.x()-r)>>4;x<=((point.x()+r)>>4);x++)for(int z=(point.z()-r)>>4;z<=((point.z()+r)>>4);z++)CHUNKS.add(new ChunkPos(x,z));
        }
        for(var pos:CHUNKS)server.overworld().getChunkSource().addRegionTicket(TYPE,pos,0,pos);
    }
    static void releaseIfFinished(MinecraftServer server,CaptureSites.View view,List<ServerPlayer> players) {
        if(code==null)return;var point=view.points().get(code);
        if(point==null||ResourcePits.occupied(players,server,point)||view.refreshes().stream().noneMatch(r->r.pointCode().equals(code))||server.getTickCount()-started>1200)release(server);
    }
    static void release(MinecraftServer server) {
        for(var pos:CHUNKS)server.overworld().getChunkSource().removeRegionTicket(TYPE,pos,0,pos);
        CHUNKS.clear();code=null;
    }
}
