package ru.animeoncraft.integration.neoforge;

import io.netty.buffer.Unpooled;
import java.time.Instant;
import java.util.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BetaPassTest {
    @Test void inactiveOfflineAndLagDoNotCreateUnlimitedActiveTime(){
        var clock=new BetaPass.ActivityClock(1);assertEquals(0,clock.advance(5_000_000_001L,false));assertEquals(5,clock.advance(10_000_000_001L,true));
        assertEquals(0,clock.advance(10_400_000_001L,false));assertEquals(0,clock.advance(10_800_000_001L,false));assertEquals(1,clock.advance(11_200_000_001L,false));
        assertEquals(0,clock.advance(200_000_000_001L,false));assertEquals(10,clock.advance(500_000_000_001L,true));assertEquals(0,clock.advance(1,false));
        var newSession=new BetaPass.ActivityClock(1);assertEquals(0,newSession.advance(30_000_000_001L,false));
    }
    @Test void sixDailyAndWeeklyQuestsResetAtEkaterinburgMidnightAndMonday(){
        long sunday=Instant.parse("2026-10-25T18:59:59Z").toEpochMilli(),monday=sunday+1000;
        assertEquals("2026-10-25",BetaPass.day(sunday));assertEquals("2026-10-26",BetaPass.day(monday));assertEquals("2026-10-19",BetaPass.week(sunday));assertEquals("2026-10-26",BetaPass.week(monday));
        assertEquals(6,BetaPass.QUESTS.size());assertEquals(10,BetaPass.REWARDS.size());
        var e=new BetaPassStore.Entry();e.reset(sunday);e.progress(new long[]{Long.MAX_VALUE,Long.MAX_VALUE,Long.MAX_VALUE,Long.MAX_VALUE,Long.MAX_VALUE,Long.MAX_VALUE});
        for(int i=0;i<6;i++){assertTrue(e.claimQuest(false,i));assertFalse(e.claimQuest(false,i));assertTrue(e.claimQuest(true,i));assertFalse(e.claimQuest(true,i));}
        assertEquals(640,e.xp);assertEquals(63,e.dayClaims);assertEquals(63,e.weekClaims);assertFalse(e.claimReward(9));assertTrue(e.claimReward(1));assertFalse(e.claimReward(1));assertTrue(e.equip("HEAD","FOX_EARS"));
        e.reset(monday);assertEquals(640,e.xp);assertEquals(0,e.dayClaims);assertEquals(0,e.weekClaims);assertArrayEquals(new long[6],e.daily);assertTrue(e.owns("FOX_EARS"));assertEquals("FOX_EARS",e.equipped.get("HEAD"));
    }
    @Test void dailyResetKeepsWeekProgressAndClaimedRewardsCannotBeFarmed(){
        var e=new BetaPassStore.Entry();long day=Instant.parse("2026-10-22T08:00:00Z").toEpochMilli();e.reset(day);e.progress(new long[]{10000,20,32,1800,128,5});for(int i=1;i<6;i++)assertTrue(e.claimQuest(false,i));int xp=e.xp;e.reset(day+86400000);assertEquals(0,e.daily[1]);assertEquals(20,e.weekly[1]);assertFalse(e.claimQuest(false,1));assertEquals(xp,e.xp);
        assertFalse(e.equip("FACE","FOX_EARS"));assertFalse(e.equip("HEAD","MADE_UP"));assertFalse(e.equip("HEAD","ORBIT_HALO"));assertFalse(e.claimQuest(false,6));assertFalse(e.claimReward(10));
        e.xp=1000;for(int i=0;i<10;i++)assertTrue(e.claimReward(i));assertTrue(e.equip("HEAD","ORBIT_HALO"));assertTrue(e.equip("HEAD","FOX_EARS"));assertEquals(1,e.equipped.size());assertTrue(e.equip("HEAD",""));assertTrue(e.equipped.isEmpty());
    }
    @Test void worldSavedDataRetainsXpClaimMasksPeriodAndEquipment(){
        var store=new BetaPassStore();var id=UUID.randomUUID();var e=store.player(id);e.reset(Instant.parse("2026-10-22T12:00:00Z").toEpochMilli());e.xp=1000;e.progress(new long[]{30000,14,10,30,128,5});assertTrue(e.claimReward(9));assertTrue(e.equip("BACK","RIFT_MANTLE"));store.open=false;
        var loaded=BetaPassStore.load(store.save(new CompoundTag(),null),null);assertFalse(loaded.open);assertEquals(1000,loaded.player(id).xp);assertEquals(e.rewards,loaded.player(id).rewards);assertArrayEquals(e.weekly,loaded.player(id).weekly);assertEquals(e.equipped,loaded.player(id).equipped);
    }
    @Test void packetsBoundEveryCollectionAndRejectInventedCosmetics(){
        var id=UUID.randomUUID();var a=new BetaPassPayloads.Appearance(id,Map.of("BACK","RIFT_MANTLE","TITLE","NEON_LEGEND"));var e=new BetaPassStore.Entry();e.reset(System.currentTimeMillis());
        var snapshot=new BetaPassPayloads.Snapshot(id,true,1000,1023,e.day,e.week,Collections.nCopies(6,0L),Collections.nCopies(6,0L),0,0,a.equipped());
        var b=new FriendlyByteBuf(Unpooled.buffer());try{BetaPassPayloads.Snapshot.CODEC.encode(b,snapshot);assertEquals(snapshot,BetaPassPayloads.Snapshot.CODEC.decode(b));b.clear();var r=new BetaPassPayloads.Request("EQUIP",0,"HEAD","FOX_EARS");BetaPassPayloads.Request.CODEC.encode(b,r);assertEquals(r,BetaPassPayloads.Request.CODEC.decode(b));b.clear();b.writeUUID(id);b.writeVarInt(6);assertThrows(IllegalArgumentException.class,()->BetaPassPayloads.Appearance.CODEC.decode(b));}finally{b.release();}
        assertThrows(IllegalArgumentException.class,()->new BetaPassPayloads.Request("SET_XP",9,"",""));assertThrows(IllegalArgumentException.class,()->new BetaPassPayloads.Request("REWARD",-1,"",""));assertThrows(IllegalArgumentException.class,()->new BetaPassPayloads.Appearance(id,Map.of("HEAD","RIFT_MANTLE")));
        assertNotEquals(ClientBetaPass.shimmer("NEON_LEGEND",0),ClientBetaPass.shimmer("NEON_LEGEND",1500));
    }
    @Test void newWheelSectorsAreDistinctAndCentreStillOpensProfile(){
        assertEquals(ServerMenuLogic.Page.BETA_PASS,ServerMenuLogic.sector(100,0,0,0,150));assertEquals(ServerMenuLogic.Page.WARDROBE,ServerMenuLogic.sector(-100,0,0,0,150));assertEquals(ServerMenuLogic.Page.PROFILE,ServerMenuLogic.sector(0,0,0,0,150));assertEquals(10,BetaPass.level(Integer.MAX_VALUE));assertEquals(0,BetaPass.level(-1));
    }
}
