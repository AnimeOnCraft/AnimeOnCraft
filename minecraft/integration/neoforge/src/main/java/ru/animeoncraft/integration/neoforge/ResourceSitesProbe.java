package ru.animeoncraft.integration.neoforge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Blocks;

/** Explicitly opt-in disposable-world verification of every shipped resource descriptor. */
final class ResourceSitesProbe {
    private static final JsonArray RESULTS=new JsonArray();
    private ResourceSitesProbe(){}
    static void verify(MinecraftServer server,CaptureSites.Point point) {
        if(server.getPort()!=25569||!"127.0.0.1".equals(server.getLocalIp())||!server.getWorldData().getLevelName().startsWith("aoc-capture-probe-"))throw new IllegalStateException("Isolated resource fixture required");
        try {
            var level=server.overworld();int r=point.radius();
            for(int x=(point.x()-r)>>4;x<=((point.x()+r)>>4);x++)for(int z=(point.z()-r)>>4;z<=((point.z()+r)>>4);z++)level.getChunk(x,z);
            var initial=new CaptureSites.Refresh(point.code(),Instant.now().plusSeconds(3600),1);
            boolean pit="PIT".equals(point.resource().kind());
            var path=ResourcePits.directory(server).resolve((pit?"pits/":"caches/")+point.code()+".json");
            // Exercise migration of the two actual empty-field shapes, preserving their previous file.
            if(point.resource().family().equals("AGRARIAN")) {
                var empty=new JsonObject();empty.addProperty("format_version",1);empty.add("blocks",new JsonArray());ResourcePits.write(path,empty);
            }
            int first=ResourcePits.apply(server,List.of(),point,initial);
            var saved=ResourcePits.read(path);check(saved!=null,"Missing state");
            String before=Files.readString(path);
            check(ResourcePits.apply(server,List.of(),point,initial)==first,"Retry count changed");
            check(Files.readString(path).equals(before),"Duplicate due changed state");
            int regenerated;
            if(pit) {
                var blocks=saved.getAsJsonArray("blocks");check(!blocks.isEmpty(),"Empty template");
                check(blocks.size()<=saved.get("limit").getAsInt(),"Template exceeds cap");
                for(var seed:point.resource().output().getAsJsonArray("seed")) {
                    String expected=seed.getAsJsonArray().get(0).getAsString();boolean found=false;
                    for(var entry:blocks) {
                        var row=entry.getAsJsonArray();var pos=new BlockPos(point.x()+row.get(0).getAsInt(),row.get(1).getAsInt(),point.z()+row.get(2).getAsInt());
                        String actual=BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString().replace(":deepslate_",":");
                        if(actual.equals(expected))found=true;
                    }
                    check(found,"Promised block absent: "+expected);
                }
                // Mine all renewable positions; protect a player block in the first slot.
                for(var entry:blocks){var row=entry.getAsJsonArray();level.setBlockAndUpdate(new BlockPos(point.x()+row.get(0).getAsInt(),row.get(1).getAsInt(),point.z()+row.get(2).getAsInt()),Blocks.AIR.defaultBlockState());}
                var row=blocks.get(0).getAsJsonArray();var protectedPos=new BlockPos(point.x()+row.get(0).getAsInt(),row.get(1).getAsInt(),point.z()+row.get(2).getAsInt());
                level.setBlockAndUpdate(protectedPos,Blocks.BRICKS.defaultBlockState());
                regenerated=ResourcePits.apply(server,List.of(),point,new CaptureSites.Refresh(point.code(),initial.dueAt().plusSeconds(1),.5));
                check(regenerated>0&&regenerated<=Math.ceil(saved.get("limit").getAsInt()*.5),"Wrong restoration cap");
                check(level.getBlockState(protectedPos).is(Blocks.BRICKS),"Player block overwritten");
                if(point.resource().family().equals("AGRARIAN"))check(Files.isRegularFile(path.resolveSibling(path.getFileName()+".before-v2")),"Old empty template not backed up");
            } else {
                for(var item:point.resource().output().getAsJsonArray("items")) {
                    String expected=item.getAsJsonArray().get(0).getAsString();boolean found=false;
                    check(BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse(expected)),"Item unavailable");
                    for(var entry:saved.getAsJsonArray("items"))if(entry.getAsJsonArray().get(0).getAsString().equals(expected)&&entry.getAsJsonArray().get(1).getAsInt()>0)found=true;
                    check(found,"Promised cache item absent");
                }
                regenerated=first;check(first>0,"Cache empty");
                for(int i=2;i<=8;i++)ResourcePits.apply(server,List.of(),point,new CaptureSites.Refresh(point.code(),initial.dueAt().plusSeconds(i),1));
                check(ResourcePits.read(path).get("refreshes").getAsInt()==4,"Cache accumulation unbounded");
            }
            var result=new JsonObject();result.addProperty("code",point.code());result.addProperty("kind",point.resource().kind());result.addProperty("initial",first);result.addProperty("regenerated",regenerated);result.addProperty("passed",true);RESULTS.add(result);
            Files.writeString(ResourcePits.directory(server).resolve("all-sites-result.json"),RESULTS.toString());
            System.out.println("AOC_CAPTURE_RESOURCE_OK "+point.code()+" initial="+first+" regenerated="+regenerated);
        }catch(java.io.IOException failure){throw new IllegalStateException(failure);}
    }
    private static void check(boolean valid,String message){if(!valid)throw new IllegalStateException(message);}
}
