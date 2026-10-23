package ru.animeoncraft.integration.neoforge;

import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import ru.animeoncraft.integration.neoforge.mixin.ChunkMapAccess;

/** Complete already requested generation before Minecraft starts unloading it. */
final class ArenaChunkLifecycle {
    private ArenaChunkLifecycle(){}
    static void stopping(ServerStoppingEvent event){
        var server=event.getServer();
        if(!server.isDedicatedServer()||(WorldBossEncounter.snapshot()==null&&!Boolean.getBoolean("animeoncraft.chunkControl")))return;
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
        int steps=0;
        while(System.nanoTime()<deadline){
            boolean pending=false,worked=false;
            for(var level:server.getAllLevels()){
                var cache=level.getChunkSource();var map=cache.chunkMap;
                if(((ChunkMapAccess)map).animeoncraft$holders().values().stream().anyMatch(holder->holder.getGenerationRefCount()>0)){
                    pending=true;map.runGenerationTasks();worked|=cache.pollTask();
                }
            }
            if(!pending){if(steps>0)System.out.println("AOC_ARENA_GENERATION_DRAINED steps="+steps);return;}
            steps++;
            if(!worked)java.util.concurrent.locks.LockSupport.parkNanos(1000000);
        }
        System.getLogger("AnimeOnCraft").log(System.Logger.Level.WARNING,"Arena generation still pending at shutdown; vanilla saving continues");
    }
}
