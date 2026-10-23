package ru.animeoncraft.integration.neoforge;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtAccounter;

class OfflineBetaGrantTest {
 @Test void operatorWriterIsReadableByMinecraftAndPreservesOtherPlayers() throws Exception {
  try(var stream=getClass().getResourceAsStream("/owner-beta-pass-fixture.dat")){
   assertNotNull(stream);var root=NbtIo.readCompressed(stream,NbtAccounter.unlimitedHeap());assertEquals(3955,root.getInt("DataVersion"));
   var store=BetaPassStore.load(root.getCompound("data"),null);assertFalse(store.open);
   var owner=store.player(UUID.fromString("12345678-1234-4000-8000-123456789abc"));assertEquals(1000,owner.xp);assertEquals(1023,owner.rewards);
   for(var reward:BetaPass.REWARDS)assertTrue(owner.owns(reward.code()));assertTrue(owner.equipped.isEmpty());
   assertEquals(120,store.player(UUID.fromString("abcdef01-1234-4000-8000-123456789abc")).xp);
  }
 }
}
