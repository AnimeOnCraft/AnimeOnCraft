package ru.animeoncraft.integration.neoforge;

import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/** Operator-only, offline noise survey. Never writes blocks or touches a live main world. */
final class WorldSurvey {
 private WorldSurvey() {}
 static void started(ServerStartedEvent event) {
  if (!"true".equals(System.getenv("AOC_WORLD_SURVEY"))) return;
  var server=event.getServer();
  if(server.getPort()!=25567||!"127.0.0.1".equals(server.getLocalIp()))throw new IllegalStateException("Survey requires own loopback fixture");
  try {
   var level=server.overworld();
   if(!(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator generator))throw new IllegalStateException("Noise generator required");
   var reports=new ArrayList<Map<String,Object>>();
   for(String raw:System.getenv("AOC_WORLD_SURVEY_SEEDS").split(",")) {
    long seed=Long.parseLong(raw);
    var random=RandomState.create(generator.generatorSettings().value(),level.registryAccess().lookupOrThrow(Registries.NOISE),seed);
    var sites=new ArrayList<Map<String,Object>>();
    int range=Integer.parseInt(System.getenv().getOrDefault("AOC_WORLD_SURVEY_RANGE","2048"));
    if(range<128||range>4096||range%128!=0)throw new IllegalArgumentException("Invalid survey range");
    for(int x=-range;x<=range;x+=128)for(int z=-range;z<=range;z+=128) {
     var biome=generator.getBiomeSource().getNoiseBiome(QuartPos.fromBlock(x),20,QuartPos.fromBlock(z),random.sampler()).unwrapKey().orElseThrow().location().toString();
     if(!List.of("minecraft:plains","minecraft:sunflower_plains","minecraft:savanna","minecraft:forest","minecraft:birch_forest").contains(biome))continue;
     int min=999,max=-999;boolean same=true;
     for(int dx=-96;dx<=96;dx+=96)for(int dz=-96;dz<=96;dz+=96) {
      int y=generator.getBaseHeight(x+dx,z+dz,Heightmap.Types.WORLD_SURFACE_WG,level,random);
      min=Math.min(min,y);max=Math.max(max,y);
      var b=generator.getBiomeSource().getNoiseBiome(QuartPos.fromBlock(x+dx),QuartPos.fromBlock(y),QuartPos.fromBlock(z+dz),random.sampler()).unwrapKey().orElseThrow().location().toString();
      if(!List.of("minecraft:plains","minecraft:sunflower_plains","minecraft:savanna","minecraft:forest","minecraft:birch_forest").contains(b))same=false;
     }
     if(min>=64&&max<=100&&max-min<=18&&same)sites.add(Map.of("x",x,"z",z,"min_y",min,"max_y",max,"biome",biome));
    }
    var report=new LinkedHashMap<String,Object>();report.put("seed",Long.toString(seed));report.put("sites",sites);reports.add(report);
    System.out.println("AOC survey seed="+seed+" flat_sites="+sites.size());
   }
   Files.writeString(Path.of("world-survey.json"),new GsonBuilder().setPrettyPrinting().create().toJson(reports));
  }catch(Exception failure){throw new IllegalStateException("World survey failed",failure);}
  finally{server.halt(false);}
 }
}
