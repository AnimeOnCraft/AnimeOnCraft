package ru.animeoncraft.integration.neoforge;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
final class ClanSpawns {
 private ClanSpawns(){}
 static Map<String,BlockPos> read(net.minecraft.server.MinecraftServer server){
  var points=new HashMap<String,BlockPos>();var path=Path.of("config/animeoncraft-clan-spawns.json");
  if(!Files.isRegularFile(path))return points;
  try{
   if(Files.size(path)>65536)throw new IllegalArgumentException();
   var root=JsonParser.parseString(Files.readString(path)).getAsJsonObject();
   if(root.get("format_version").getAsInt()!=1||Long.parseLong(root.get("seed").getAsString())!=server.overworld().getSeed())throw new IllegalArgumentException();
   var codes=new java.util.ArrayList<>(ClanCodes.fromConfig(root));codes.add("COMMON");
   for(String clan:codes){
    if(clan.equals("COMMON")&&!root.has("common"))continue;
    var p=clan.equals("COMMON")?root.getAsJsonObject("common"):root.getAsJsonObject("clans").getAsJsonObject(clan);int x=p.get("x").getAsInt(),z=p.get("z").getAsInt();
    if(Math.abs((long)x)>100000||Math.abs((long)z)>100000)throw new IllegalArgumentException();
    int y=p.has("y")?p.get("y").getAsInt():0;if(y!=0&&(y<1||y>319))throw new IllegalArgumentException();
    points.put(clan,new BlockPos(x,y,z));
   }
  }catch(Exception failure){System.err.println("AOC clan spawn config is invalid for this world; automatic teleport disabled.");points.clear();}
  return points;
 }
 private static BlockPos destination(ServerPlayer player){
  var identity=GameIdentity.PLAYERS.get(player.getUUID());if(identity==null||identity.clan()==null)return null;
  var point=read(player.server).get(identity.clan());if(point==null)return null;
  var level=player.server.overworld();
  var safe=safeSurface(level,point);
  if(safe==null)player.sendSystemMessage(net.minecraft.network.chat.Component.literal("Точка клана временно небезопасна. Использован обычный спавн."));
  return safe;
 }
 private static BlockPos safeSurface(net.minecraft.server.level.ServerLevel level,BlockPos point){
  // Resolve the surface at use time and reject water, lava and obstructed feet/head.
  BlockPos safe=null;
  for(int radius=0;radius<=8&&safe==null;radius++)for(int dx=-radius;dx<=radius&&safe==null;dx++)for(int dz=-radius;dz<=radius&&safe==null;dz++){
   level.getChunk((point.getX()+dx)>>4,(point.getZ()+dz)>>4);
   var floor=point.getY()==0?level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,point.offset(dx,0,dz)):point.offset(dx,0,dz);
   var state=level.getBlockState(floor.below());
   if(!state.is(net.minecraft.tags.BlockTags.LOGS)&&!state.is(net.minecraft.tags.BlockTags.LEAVES)&&!state.is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK)&&state.isCollisionShapeFullBlock(level,floor.below())&&level.getFluidState(floor.below()).isEmpty()&&level.getBlockState(floor).isAir()&&level.getBlockState(floor.above()).isAir())safe=floor;
  }
  return safe;
 }
 static void started(net.neoforged.neoforge.event.server.ServerStartedEvent event){
  var server=event.getServer();var point=read(server).get("COMMON");if(point==null)return;
  var safe=safeSurface(server.overworld(),point);if(safe==null)throw new IllegalStateException("Common spawn is unsafe");
  server.overworld().setDefaultSpawnPos(safe,0);
  for(var entry:read(server).entrySet())if(!entry.getKey().equals("COMMON")){
   var spawn=safeSurface(server.overworld(),entry.getValue());if(spawn==null)throw new IllegalStateException("Clan spawn is unsafe: "+entry.getKey());
   System.out.println("AOC clan spawn prepared: "+entry.getKey()+" pos="+spawn.toShortString());
  }
  System.out.println("AOC common spawn prepared: "+safe.toShortString());
 }
 private static void place(ServerPlayer player){
  var identity=GameIdentity.PLAYERS.get(player.getUUID());if(identity==null||identity.clan()==null)return;
  String marker="aoc_clan_spawn_"+identity.clan();if(player.getPersistentData().getBoolean(marker))return;
  var safe=destination(player);if(safe==null)return;var level=player.server.overworld();
  player.teleportTo(level,safe.getX()+0.5,safe.getY(),safe.getZ()+0.5,0,0);
  player.getPersistentData().putBoolean(marker,true);
  System.out.println("AOC clan first spawn: "+player.getGameProfile().getName()+" clan="+identity.clan()+" pos="+safe.toShortString());
 }
 static void login(PlayerEvent.PlayerLoggedInEvent event){if(event.getEntity() instanceof ServerPlayer player){
  var own=GameIdentity.PLAYERS.get(player.getUUID());if(own==null)return;
  for(var other:player.server.getPlayerList().getPlayers()){
   var identity=GameIdentity.PLAYERS.get(other.getUUID());
   if(identity!=null)CapeAppearance.send(player,other,identity);
  }
  CapeAppearance.broadcast(player,own);
  place(player); ClanOnlineHeartbeat.send(player.server);
 }}
 static void respawn(net.neoforged.neoforge.event.entity.player.PlayerRespawnPositionEvent event){
  if(!(event.getEntity() instanceof ServerPlayer player))return;
  var original=event.getDimensionTransition();
  if(!SpawnRules.useClan(player.getRespawnPosition()!=null,original.missingRespawnBlock(),event.isFromEndFight()))return;
  var safe=destination(player);if(safe==null)return;
  event.setDimensionTransition(new net.minecraft.world.level.portal.DimensionTransition(player.server.overworld(),new net.minecraft.world.phys.Vec3(safe.getX()+0.5,safe.getY(),safe.getZ()+0.5),net.minecraft.world.phys.Vec3.ZERO,0,0,original.missingRespawnBlock(),original.postDimensionTransition()));
  if(original.missingRespawnBlock())event.setCopyOriginalSpawnPosition(false);
  System.out.println("AOC clan respawn: "+player.getGameProfile().getName()+" pos="+safe.toShortString());
 }
 static void logout(PlayerEvent.PlayerLoggedOutEvent event){if(event.getEntity() instanceof ServerPlayer player){var server=player.server;GameIdentity.PLAYERS.remove(player.getUUID());ClanOnlineHeartbeat.send(server);}}
 static void clonePlayer(PlayerEvent.Clone event){for(String clan:ClanCodes.all()){
  String marker="aoc_clan_spawn_"+clan;
  if(event.getOriginal().getPersistentData().getBoolean(marker))event.getEntity().getPersistentData().putBoolean(marker,true);
 }}
}
