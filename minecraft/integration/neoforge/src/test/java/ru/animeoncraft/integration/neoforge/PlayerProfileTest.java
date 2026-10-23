package ru.animeoncraft.integration.neoforge;

import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlayerProfileTest {
    @Test void onlineClockUsesElapsedTimeRatherThanServerTicks(){assertEquals(3610,ProfileProgression.onlineSeconds(3600,1_000_000_000L,11_900_000_000L));assertEquals(3600,ProfileProgression.onlineSeconds(3600,10_000_000_000L,9_000_000_000L));}
    private static final ProfileProgression.Metrics ZERO=new ProfileProgression.Metrics(0,0,0,0,0,0,0,0,0);
    @Test void achievementsUnlockOnceAndTitleSurvivesSaveLoadDeathAndDifferentPlayers(){
        var store=new PlayerProfileStore();var player=UUID.randomUUID();var other=UUID.randomUUID();assertFalse(store.select(player,"BOSS_FIRST"));assertFalse(store.select(player,"MADE_UP"));
        var metrics=new ProfileProgression.Metrics(3600,2,4,50,128,128,500000,1,25);
        assertTrue(store.unlock(player,metrics));assertFalse(store.unlock(player,metrics));assertTrue(store.select(player,"BOSS_FIRST"));assertFalse(store.select(other,"BOSS_FIRST"));
        var loaded=PlayerProfileStore.load(store.save(new CompoundTag(),null),null);assertEquals(store.player(player).unlocked,loaded.player(player).unlocked);assertEquals("BOSS_FIRST",loaded.player(player).selected);
        loaded.unlock(player,ZERO);assertEquals("BOSS_FIRST",loaded.player(player).selected);assertTrue(loaded.select(player,""));assertEquals("",loaded.player(player).selected);
    }
    @Test void catalogAndProfileXpAreBoundedAndDoNotRewardDeathsOrPvpFarming(){
        assertEquals(20,ProfileProgression.ALL.size());assertEquals(20,ProfileProgression.ALL.stream().map(ProfileProgression.Achievement::code).distinct().count());assertEquals(20,ProfileProgression.ALL.stream().map(ProfileProgression.Achievement::title).distinct().count());
        for(var a:ProfileProgression.ALL){assertTrue(a.target()>0);assertTrue(a.xp()>0);assertTrue(a.title().length()<32);assertNotNull(ProfileProgression.find(a.code()));}
        var pvp=new ProfileProgression.Metrics(0,1000,1000,0,0,0,0,0,0);assertEquals(0,ProfileProgression.xp(pvp,Set.of()));assertEquals(1,ProfileProgression.level(0));assertEquals(2,ProfileProgression.level(100));assertEquals(100,ProfileProgression.level(Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class,()->new ProfileProgression.Metrics(-1,0,0,0,0,0,0,0,0));
    }
    @Test void profileAndClanPacketsRoundTripAndRejectForgedOrOversizedTitles(){
        var b=new FriendlyByteBuf(Unpooled.buffer());try{
            var packet=new ProfilePayloads.Snapshot(UUID.randomUUID(),ZERO,"BOSS_FIRST",List.of("BOSS_FIRST"));ProfilePayloads.Snapshot.CODEC.encode(b,packet);assertEquals(packet,ProfilePayloads.Snapshot.CODEC.decode(b));b.clear();
            var clan=new ClanAchievementPayload(List.of(new ClanAchievementPayload.Definition("POINT_FIRST","Первая точка","POINT_CAPTURE",1,150,5)),Map.of("NYA",List.of(new ClanAchievementPayload.Progress("POINT_FIRST",1,true))));ClanAchievementPayload.CODEC.encode(b,clan);assertEquals(clan,ClanAchievementPayload.CODEC.decode(b));b.clear();b.writeVarInt(33);assertThrows(IllegalArgumentException.class,()->ClanAchievementPayload.CODEC.decode(b));
        }finally{b.release();}
        assertThrows(IllegalArgumentException.class,()->new ProfilePayloads.Request("Император\n§4"));assertThrows(IllegalArgumentException.class,()->new ProfilePayloads.Title(UUID.randomUUID(),"MADE_UP"));assertThrows(IllegalArgumentException.class,()->new ProfilePayloads.Snapshot(UUID.randomUUID(),ZERO,"BOSS_FIRST",List.of()));
    }
}
