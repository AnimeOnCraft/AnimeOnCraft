package ru.animeoncraft.integration.neoforge;

import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ServerMenuTest {
    @Test void unlimitedProductionPlayerLimitDoesNotRejectTheWholeRoster(){
        var data=new ServerMenuData(true,Integer.MAX_VALUE,List.of(),List.of());
        assertEquals(Integer.MAX_VALUE,data.maximum());
        assertFalse(ServerMenuData.unavailable(Integer.MAX_VALUE).available());
        var b=new FriendlyByteBuf(Unpooled.buffer());
        try{var packet=new ServerMenuPayloads.Snapshot(data);ServerMenuPayloads.Snapshot.CODEC.encode(b,packet);assertEquals(packet,ServerMenuPayloads.Snapshot.CODEC.decode(b));}finally{b.release();}
        assertThrows(IllegalArgumentException.class,()->new ServerMenuData(true,0,List.of(),List.of()));
    }
    @Test void releaseNeverSelectsAndOnlyLeftClickWhileHoldingOpensInitialWheel(){
        var m=new ServerMenuLogic();assertTrue(m.closesOnRelease());assertFalse(m.select(ServerMenuLogic.Page.CLANS,1,true));assertFalse(m.select(ServerMenuLogic.Page.GUIDE,0,false));assertEquals(ServerMenuLogic.Page.WHEEL,m.page());
        assertTrue(m.select(ServerMenuLogic.Page.GUIDE,0,true));assertFalse(m.closesOnRelease());assertEquals(ServerMenuLogic.Page.GUIDE,m.page());m.back();assertFalse(m.closesOnRelease());assertTrue(m.select(ServerMenuLogic.Page.ONLINE,0,false));
    }
    @Test void deadZoneGapsAndOutsideNeverActivateASection(){
        assertEquals(ServerMenuLogic.Page.CLANS,ServerMenuLogic.sector(-70,-70,0,0,150));assertEquals(ServerMenuLogic.Page.ONLINE,ServerMenuLogic.sector(70,-70,0,0,150));assertEquals(ServerMenuLogic.Page.GUIDE,ServerMenuLogic.sector(70,70,0,0,150));assertEquals(ServerMenuLogic.Page.RULES,ServerMenuLogic.sector(-70,70,0,0,150));assertEquals(ServerMenuLogic.Page.PROFILE,ServerMenuLogic.sector(0,0,0,0,150));assertNull(ServerMenuLogic.sector(50,0,0,0,150));assertNull(ServerMenuLogic.sector(200,200,0,0,150));assertNull(ServerMenuLogic.sector(1,-100,0,0,150));
        assertEquals(0,ServerMenuLogic.scroll(10,-100,50,7));assertEquals(43,ServerMenuLogic.scroll(10,100,50,7));assertEquals(0,ServerMenuLogic.scroll(20,0,2,7));
    }
    @Test void completeRosterPacketKeepsBigXpAndRejectsBoundsAndDuplicates(){
        var c=new ServerMenuData.Clan("NYA","Кошки",0xff55ee,4,1350,120,3,1);var m=new ServerMenuData.Member(UUID.randomUUID(),"Player_One","NYA","MEMBER");var value=new ServerMenuPayloads.Snapshot(new ServerMenuData(true,30,List.of(c),List.of(m)));
        var b=new FriendlyByteBuf(Unpooled.buffer());try{ServerMenuPayloads.Snapshot.CODEC.encode(b,value);assertEquals(value,ServerMenuPayloads.Snapshot.CODEC.decode(b));b.clear();b.writeBoolean(true);b.writeVarInt(30);b.writeVarInt(65);assertThrows(IllegalArgumentException.class,()->ServerMenuPayloads.Snapshot.CODEC.decode(b));}finally{b.release();}
        assertEquals(450.0/700,c.progress(),.0001);assertEquals(1600,c.nextXp());assertThrows(IllegalArgumentException.class,()->new ServerMenuData(true,30,List.of(c,c),List.of(m)));assertThrows(IllegalArgumentException.class,()->new ServerMenuData(true,30,List.of(c),List.of(m,m)));assertThrows(IllegalArgumentException.class,()->new ServerMenuData(true,30,List.of(),List.of(m)));assertThrows(IllegalArgumentException.class,()->new ServerMenuData.Member(UUID.randomUUID(),"email@host","NYA","OWNER"));
    }
    @Test void guideStartsWithGameplayRatherThanClanApplication(){
        String body=ServerMenuGuide.START.toString().toLowerCase(java.util.Locale.ROOT);assertFalse(body.contains("вступ"));assertFalse(body.contains("заявк"));assertFalse(body.contains("лаунчер"));assertTrue(body.contains("create"));assertTrue(body.contains("ресурс"));assertTrue(ServerMenuGuide.CHAT.toString().contains("100 блоков"));
    }
}
