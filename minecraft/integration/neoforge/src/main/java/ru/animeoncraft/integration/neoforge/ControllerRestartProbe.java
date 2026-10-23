package ru.animeoncraft.integration.neoforge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.contraptions.IControlContraption;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.Files;

/** Separate full Java process restart; never enabled by normal launcher/server settings. */
final class ControllerRestartProbe {
    private static MinecraftServer pending;
    private static JsonArray rows;
    private static boolean restoring,prepared;
    private static int nextTick,deadline;
    private ControllerRestartProbe() {}
    static void schedule(MinecraftServer server)throws Exception{
        if(server.getPort()!=25569||!"127.0.0.1".equals(server.getLocalIp())||!server.getWorldData().getLevelName().startsWith("aoc-territory-probe-"))throw new IllegalStateException("Controller restart requires disposable world");
        pending=server;nextTick=server.getTickCount()+20;deadline=server.getTickCount()+1000;prepared=false;
        var file=server.getWorldPath(LevelResource.ROOT).resolve("aoc-controller-restart.json");
        restoring=Files.isRegularFile(file);rows=restoring?JsonParser.parseString(Files.readString(file)).getAsJsonArray():new JsonArray();
        if(restoring)for(var value:rows){var row=value.getAsJsonObject();var target=pos(row,"target");server.overworld().setChunkForced(target.getX()>>4,target.getZ()>>4,true);}
    }
    static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event){
        var server=event.getServer();if(pending!=server||server.getTickCount()<nextTick)return;
        try{
            if(server.getTickCount()>deadline)throw new IllegalStateException("Controller reload deadline");
            var level=server.overworld();
            if(!restoring){
                if(!prepared){
                    var policy=TerritoryPermissions.read(JsonParser.parseString(Files.readString(java.nio.file.Path.of("config/animeoncraft-clan-spawns.json"))).getAsJsonObject(),level.getSeed());
                    var own=policy.territories().getFirst();var foreign=policy.territories().get(1);
                    for(int kind=0;kind<3;kind++){
                        var root=new BlockPos(own.minX()+32+kind*8,180,own.minZ()+32);
                        var fixture=CreateControllerProbe.create(level,kind,root);
                        var target=new BlockPos(foreign.minX()+32+kind*8,180,foreign.minZ()+32);
                        level.setChunkForced(target.getX()>>4,target.getZ()>>4,true);
                        fixture.moving().setPos(target.getX(),target.getY(),target.getZ());
                        if(!level.addFreshEntity(fixture.moving())||!level.destroyBlock(root,false))throw new IllegalStateException("Cannot prepare missing controller");
                        var row=new JsonObject();row.addProperty("kind",kind);row.addProperty("uuid",fixture.moving().getUUID().toString());put(row,"root",root);put(row,"target",target);put(row,"anchor",fixture.contraption().anchor);rows.add(row);
                    }
                    prepared=true;nextTick=server.getTickCount()+40;return;
                }
                for(var value:rows){var row=value.getAsJsonObject();if(!(level.getEntity(java.util.UUID.fromString(row.get("uuid").getAsString())) instanceof ControlledContraptionEntity entity)||entity.isRemoved())throw new IllegalStateException("Mechanism lost before restart");}
                Files.writeString(server.getWorldPath(LevelResource.ROOT).resolve("aoc-controller-restart.json"),rows.toString());
                pending=null;System.out.println("AOC_CONTROLLER_RESTART_REQUEST");return;
            }
            for(var value:rows)if(level.getEntity(java.util.UUID.fromString(value.getAsJsonObject().get("uuid").getAsString()))==null)return;
            for(var value:rows){
                var row=value.getAsJsonObject();var entity=(ControlledContraptionEntity)level.getEntity(java.util.UUID.fromString(row.get("uuid").getAsString()));
                var anchor=pos(row,"anchor");
                if(entity.isRemoved()||entity.getContraption()==null||entity.getContraption().getBlocks().size()!=1||!entity.getContraption().anchor.equals(anchor))throw new IllegalStateException("Reload lost mechanism blocks or ownership anchor");
                String name=switch(row.get("kind").getAsInt()){case 0->"mechanical_piston";case 1->"rope_pulley";default->"mechanical_bearing";};
                var state=BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create:"+name)).defaultBlockState();
                if(state.hasProperty(BlockStateProperties.FACING))state=state.setValue(BlockStateProperties.FACING,Direction.EAST);
                var root=pos(row,"root");level.setBlockAndUpdate(root,state);entity.tick();
                var controller=level.getBlockEntity(root);
                if(!(controller instanceof IControlContraption control)||!control.isAttachedTo(entity))throw new IllegalStateException("Reloaded mechanism did not reattach");
                entity.setPos(anchor.getX(),anchor.getY(),anchor.getZ());
                controller.getClass().getMethod("disassemble").invoke(controller);
                if(!entity.isRemoved())throw new IllegalStateException("Reloaded mechanism did not restore blocks");
            }
            pending=null;System.out.println("AOC_CONTROLLER_RESTART_PROBE_PASS controllers=3 real_process_restart=true original_uuid=true ownership_anchor=true restored_disassembly=true");
        }catch(Exception failure){pending=null;System.err.println("AOC_CONTROLLER_RESTART_PROBE_FAILED");throw new IllegalStateException("Controller persistence probe failed",failure);}
    }
    private static void put(JsonObject row,String key,BlockPos pos){var value=new JsonArray();value.add(pos.getX());value.add(pos.getY());value.add(pos.getZ());row.add(key,value);}
    private static BlockPos pos(JsonObject row,String key){var value=row.getAsJsonArray(key);return new BlockPos(value.get(0).getAsInt(),value.get(1).getAsInt(),value.get(2).getAsInt());}
}
