package ru.animeoncraft.integration.neoforge;
import java.util.List;
import java.util.UUID;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
/** Opt-in synthetic members in the disposable loopback transport fixture only. */
final class ClanPositionProbe {
 private ClanPositionProbe(){}
 static void login(PlayerEvent.PlayerLoggedInEvent event){
  if(!Boolean.getBoolean("animeoncraft.acceptance.clanPositions")||!(event.getEntity() instanceof ServerPlayer viewer))return;
  System.out.println("AOC_CLAN_POSITION_PROBE_ENTER port="+viewer.server.getPort()+" host="+viewer.server.getLocalIp()+" name="+viewer.getGameProfile().getName());
  if(viewer.server.getPort()!=25566||!"127.0.0.1".equals(viewer.server.getLocalIp())||!viewer.getGameProfile().getName().equals("aocgameprobe"))return;
  var own=GameIdentity.PLAYERS.get(viewer.getUUID());if(own==null)throw new IllegalStateException("Authenticated fixture required");
  var friend=new FakePlayer(viewer.server.overworld(),new GameProfile(UUID.randomUUID(),"AocMapFriend"));friend.setPos(2000,80,3000);
  var netherFriend=new FakePlayer(viewer.server.getLevel(net.minecraft.world.level.Level.NETHER),new GameProfile(UUID.randomUUID(),"AocNetherFriend"));netherFriend.setPos(-1500,70,4000);
  var enemy=new FakePlayer(viewer.server.overworld(),new GameProfile(UUID.randomUUID(),"AocMapEnemy"));enemy.setPos(2100,80,3100);
  var neutral=new FakePlayer(viewer.server.overworld(),new GameProfile(UUID.randomUUID(),"AocMapNeutral"));
  try{
   GameIdentity.PLAYERS.put(viewer.getUUID(),own.withClan("NYA"));
   for(var p:List.of(friend,netherFriend))GameIdentity.PLAYERS.put(p.getUUID(),own.withClan("NYA"));
   GameIdentity.PLAYERS.put(enemy.getUUID(),own.withClan("SCAM"));GameIdentity.PLAYERS.put(neutral.getUUID(),own.withClan(null));
   var all=List.of(viewer,friend,netherFriend,enemy,neutral);var snapshot=ClanPositions.snapshot(viewer,all,null);
   if(snapshot.players().size()!=2||snapshot.players().stream().anyMatch(p->p.id().equals(enemy.getUUID())||p.id().equals(neutral.getUUID())||p.id().equals(viewer.getUUID())))throw new IllegalStateException("Clan marker visibility failed");
   if(ClanPositions.snapshot(viewer,all,friend.getUUID()).players().size()!=1)throw new IllegalStateException("Logout marker removal failed");
   GameIdentity.PLAYERS.put(viewer.getUUID(),own.withClan(null));if(!ClanPositions.snapshot(viewer,all,null).players().isEmpty())throw new IllegalStateException("Clanless positions leaked");
   GameIdentity.PLAYERS.put(viewer.getUUID(),own.withClan("NYA"));
   if(!viewer.connection.hasChannel(ClanPositionsPayload.TYPE.id()))throw new IllegalStateException("Client marker channel missing");
   // Queue after login handlers; normal two-second snapshots then clear the synthetic members.
   viewer.server.execute(()->PacketDistributor.sendToPlayer(viewer,snapshot));
   // First client chunk preparation can take longer than the normal snapshot
   // interval; repeat once after the real client has had time to enter PLAY.
   java.util.concurrent.CompletableFuture.delayedExecutor(10,java.util.concurrent.TimeUnit.SECONDS).execute(()->viewer.server.execute(()->{
    if(!viewer.isRemoved()&&GameIdentity.PLAYERS.containsKey(viewer.getUUID()))PacketDistributor.sendToPlayer(viewer,snapshot);
   }));
   System.out.println("AOC_CLAN_POSITION_SERVER_PASS friends=2 far=true nether=true enemies_hidden=true clanless_hidden=true logout=true");
  }finally{for(var p:List.of(friend,netherFriend,enemy,neutral))GameIdentity.PLAYERS.remove(p.getUUID());}
 }
}
