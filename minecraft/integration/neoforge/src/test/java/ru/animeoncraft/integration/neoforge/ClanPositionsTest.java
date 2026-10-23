package ru.animeoncraft.integration.neoforge;
import java.util.List;
import java.util.UUID;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ClanPositionsTest {
 @AfterEach void clear(){ClientClanPositions.logout(null);}
 @Test void positionsOnlyForDifferentPlayerInSameKnownClan(){
  ClanCodes.register(List.of("SCAM","NYA"));
  UUID a=UUID.randomUUID(),b=UUID.randomUUID();
  assertTrue(ClanPositionRules.visible(a,"NYA",b,"NYA"));
  assertFalse(ClanPositionRules.visible(a,"NYA",b,"SCAM"));
  assertFalse(ClanPositionRules.visible(a,null,b,null));
  assertFalse(ClanPositionRules.visible(a,"",b,""));
  assertFalse(ClanPositionRules.visible(a,"NYA",a,"NYA"));
  assertFalse(ClanPositionRules.visible(a,"bad",b,"bad"));
 }
 @Test void packetRoundTripKeepsFarCoordinatesAndDimension(){
  var p=new ClanPositionsPayload("NYA",List.of(new ClanPositionsPayload.Position(UUID.randomUUID(),Level.NETHER,-24000,77,3100)));
  var b=new FriendlyByteBuf(Unpooled.buffer());try{ClanPositionsPayload.CODEC.encode(b,p);assertEquals(p,ClanPositionsPayload.CODEC.decode(b));}finally{b.release();}
  assertThrows(IllegalArgumentException.class,()->new ClanPositionsPayload.Position(UUID.randomUUID(),Level.OVERWORLD,Double.NaN,64,0));
  assertThrows(IllegalArgumentException.class,()->new ClanPositionsPayload("",p.players()));
 }
 @Test void completeSnapshotsClearOfflineMembersAndExpireAfterDisconnect(){
  var p=new ClanPositionsPayload("NYA",List.of(new ClanPositionsPayload.Position(UUID.randomUUID(),Level.OVERWORLD,2000,80,3000)));
  ClientClanPositions.replace(p,100);assertEquals(1,ClientClanPositions.current(101).size());assertTrue(ClientClanPositions.current(12_000_000_101L).isEmpty());
  ClientClanPositions.replace(new ClanPositionsPayload("NYA",List.of()),200);assertTrue(ClientClanPositions.current(201).isEmpty());
  ClientClanPositions.replace(p,300);ClientClanPositions.logout(null);assertTrue(ClientClanPositions.current(301).isEmpty());
 }
 @Test void bothXaeroReadersUseSameCoordinatesAndDimension(){
  var p=new ClanPositionsPayload.Position(UUID.randomUUID(),Level.END,-7000,90,2300);
  var mini=new XaeroClanMinimapTracker();var world=new XaeroClanWorldTracker();
  assertEquals(p.id(),mini.getId(p));assertEquals(p.id(),world.getId(p));assertEquals(-7000,mini.getX(p));assertEquals(2300,world.getZ(p));assertEquals(Level.END,mini.getDimension(p));assertEquals(Level.END,world.getDimension(p));
 }
}
