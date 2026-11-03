package ru.animeoncraft.integration.neoforge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Disposable stress probe (never in the production JAR). Simulates N exploring players without network clients:
 * each "bot" is a moving chunk ticket of radius aoc.stress.radius (like a player's view) walking outward at sprint speed,
 * plus a few mobs near it. Every 5 s it samples MSPT, loaded chunks, entities and heap, then writes stress-result.json.
 * Real-client cost (packets, tracking) is not simulated: treat the result as a lower bound for the server thread.
 */
@Mod("aocstressprobe")
public final class StressProbe {
    private static final TicketType<Integer> TICKET=TicketType.create("aoc_stress",Comparator.<Integer>naturalOrder());
    private final int bots=Integer.getInteger("aoc.stress.bots",30),seconds=Integer.getInteger("aoc.stress.seconds",300),radius=Integer.getInteger("aoc.stress.radius",6),mobsPerBot=Integer.getInteger("aoc.stress.mobs",4);
    private final double speed=Double.parseDouble(System.getProperty("aoc.stress.speed","5.6"));
    private final List<double[]> positions=new ArrayList<>();private final List<ChunkPos> tickets=new ArrayList<>();
    private final List<String> samples=new ArrayList<>();private final List<Double> mspt=new ArrayList<>();
    private final Random random=new Random(20261103);
    private long startTick=-1,startNanos,lastSampleNanos,lastSampleTick;private boolean done;

    public StressProbe(){if(Integer.getInteger("aoc.stress.bots")!=null){NeoForge.EVENT_BUS.addListener(this::started);NeoForge.EVENT_BUS.addListener(this::tick);}}
    private void started(ServerStartedEvent event){
        var level=event.getServer().overworld();
        for(int i=0;i<bots;i++){double angle=2*Math.PI*i/bots;positions.add(new double[]{Math.cos(angle)*32,Math.sin(angle)*32,angle});tickets.add(null);}
        startTick=event.getServer().getTickCount();
        startNanos=lastSampleNanos=System.nanoTime();
        System.out.println("AOC_STRESS_START bots="+bots+" seconds="+seconds+" radius="+radius+" level="+level.dimension().location());
    }
    private void tick(ServerTickEvent.Post event){
        if(done||startTick<0)return;
        MinecraftServer server=event.getServer();ServerLevel level=server.overworld();long t=server.getTickCount()-startTick;
        for(int i=0;i<bots;i++){
            var p=positions.get(i);
            p[2]+=(random.nextDouble()-0.5)*0.02; // gentle turning, mostly outward: always new chunks like exploring players
            p[0]+=Math.cos(p[2])*speed/20;p[1]+=Math.sin(p[2])*speed/20;
            var chunk=new ChunkPos(BlockPos.containing(p[0],0,p[1]));var old=tickets.get(i);
            if(!chunk.equals(old)){
                level.getChunkSource().addRegionTicket(TICKET,chunk,radius,i);
                if(old!=null)level.getChunkSource().removeRegionTicket(TICKET,old,radius,i);
                tickets.set(i,chunk);
            }
            if(t%200==i%200&&t>100){
                int x=(int)p[0]+random.nextInt(17)-8,z=(int)p[1]+random.nextInt(17)-8;
                if(level.hasChunk(x>>4,z>>4)&&countNear(level,p)<mobsPerBot){
                    var pos=new BlockPos(x,level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z),z);
                    (random.nextBoolean()?EntityType.ZOMBIE:EntityType.SKELETON).spawn(level,pos,MobSpawnType.COMMAND);
                }
            }
        }
        if(t>0&&System.nanoTime()-lastSampleNanos>=5_000_000_000L){
            double ms=server.getAverageTickTimeNanos()/1_000_000.0;mspt.add(ms);
            int entities=0;for(var ignored:level.getAllEntities())entities++;
            var runtime=Runtime.getRuntime();long heap=(runtime.totalMemory()-runtime.freeMemory())>>20;
            long now=System.nanoTime();double actualTps=(t-lastSampleTick)*1_000_000_000.0/(now-lastSampleNanos);lastSampleNanos=now;lastSampleTick=t;
            String line=String.format(Locale.ROOT,"{\"t\":%d,\"wall_seconds\":%.2f,\"mspt\":%.2f,\"tps\":%.2f,\"chunks\":%d,\"entities\":%d,\"heap_mib\":%d}",t/20,(now-startNanos)/1_000_000_000.0,ms,actualTps,level.getChunkSource().getLoadedChunksCount(),entities,heap);
            samples.add(line);System.out.println("AOC_STRESS_SAMPLE "+line);
        }
        // --minutes means wall time even if terrain generation slows the server.
        if(System.nanoTime()-startNanos>=seconds*1_000_000_000L){done=true;write();server.halt(false);}
    }
    private static int countNear(ServerLevel level,double[] p){
        var box=new net.minecraft.world.phys.AABB(p[0]-48,level.getMinBuildHeight(),p[1]-48,p[0]+48,level.getMaxBuildHeight(),p[1]+48);
        return level.getEntities(EntityType.ZOMBIE,box,e->true).size()+level.getEntities(EntityType.SKELETON,box,e->true).size();
    }
    private void write(){
        var sorted=new ArrayList<>(mspt);sorted.sort(Double::compare);
        double p50=sorted.isEmpty()?0:sorted.get(sorted.size()/2),p95=sorted.isEmpty()?0:sorted.get(Math.min(sorted.size()-1,(int)Math.ceil(sorted.size()*0.95)-1)),max=sorted.isEmpty()?0:sorted.getLast();
        String json=String.format(Locale.ROOT,"{\"bots\":%d,\"seconds\":%d,\"radius\":%d,\"speed\":%.1f,\"mspt_p50\":%.2f,\"mspt_p95\":%.2f,\"mspt_max\":%.2f,\"lagging\":%b,\"mspt_statistic\":\"samples of server rolling 100-tick average\",\"tps_statistic\":\"ticks per measured wall time\",\"samples\":[%s]}",
            bots,seconds,radius,speed,p50,p95,max,p95>50,String.join(",",samples));
        try{Files.writeString(Path.of("stress-result.json"),json);System.out.println("AOC_STRESS_DONE p50="+p50+" p95="+p95+" max="+max);}
        catch(java.io.IOException failure){System.out.println("AOC_STRESS_FAILED write");}
    }
}
