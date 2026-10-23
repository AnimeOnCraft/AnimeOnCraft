package ru.animeoncraft.integration.neoforge;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;

final class ClanPositions {
 private ClanPositions(){}
 static ClanPositionsPayload snapshot(ServerPlayer viewer,MinecraftServer server,java.util.UUID excluded){
  return snapshot(viewer,server.getPlayerList().getPlayers(),excluded);
 }
 static ClanPositionsPayload snapshot(ServerPlayer viewer,Iterable<ServerPlayer> players,java.util.UUID excluded){
  var own=GameIdentity.PLAYERS.get(viewer.getUUID());String clan=own==null||own.clan()==null?"":own.clan();
  var entries=new java.util.ArrayList<ClanPositionsPayload.Position>();
  if(!clan.isEmpty())for(var target:players){
   var member=GameIdentity.PLAYERS.get(target.getUUID());
   if(member!=null&&!target.getUUID().equals(excluded)&&ClanPositionRules.visible(viewer.getUUID(),clan,target.getUUID(),member.clan()))entries.add(new ClanPositionsPayload.Position(target.getUUID(),target.level().dimension(),target.getX(),target.getY(),target.getZ()));
   if(entries.size()>=256)break;
  }
  return new ClanPositionsPayload(clan,entries);
 }
 static void send(MinecraftServer server,java.util.UUID excluded){
  for(var viewer:server.getPlayerList().getPlayers())if(!viewer.getUUID().equals(excluded)&&viewer.connection.hasChannel(ClanPositionsPayload.TYPE.id()))PacketDistributor.sendToPlayer(viewer,snapshot(viewer,server,excluded));
 }
 static void tick(ServerTickEvent.Post event){if(event.getServer().getTickCount()%40==0)send(event.getServer(),null);}
 static void login(PlayerEvent.PlayerLoggedInEvent event){if(event.getEntity() instanceof ServerPlayer p)send(p.server,null);}
 static void logout(PlayerEvent.PlayerLoggedOutEvent event){if(event.getEntity() instanceof ServerPlayer p)send(p.server,p.getUUID());}
}
