package ru.animeoncraft.integration.neoforge;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CaptureEntryTest {
    private static com.google.gson.JsonObject rules(String ranges) {
        return JsonParser.parseString("{\"timezone\":\"Asia/Yekaterinburg\",\"window\":{\"daily\":"+ranges+"},\"declare\":{\"min_players\":2,\"min_players_t1\":1}}").getAsJsonObject();
    }
    @Test void windowUsesServerTimezoneAndExclusiveEnd() {
        var r=rules("[[\"15:00\",\"23:30\"]]");
        assertFalse(CaptureEntry.open(r,Instant.parse("2026-10-23T09:59:59Z").getEpochSecond()));
        assertTrue(CaptureEntry.open(r,Instant.parse("2026-10-23T10:00:00Z").getEpochSecond()));
        assertFalse(CaptureEntry.open(r,Instant.parse("2026-10-23T18:30:00Z").getEpochSecond()));
    }
    @Test void midnightAndAllDayWindows() {
        assertTrue(CaptureEntry.open(rules("[[\"23:00\",\"02:00\"]]"),Instant.parse("2026-10-23T20:00:00Z").getEpochSecond()));
        assertTrue(CaptureEntry.open(rules("[[\"00:00\",\"24:00\"]]"),Instant.parse("2026-10-23T18:30:00Z").getEpochSecond()));
    }
    @Test void hudTextIsBoundedAndClockIsReadable() {
        assertEquals("1:01:01",CaptureEntry.clock(3661));assertEquals("5:00",CaptureEntry.clock(300));assertEquals("0:00",CaptureEntry.clock(-1));
        var text=CaptureEntry.compact("⭐".repeat(100),62);
        assertEquals(62,text.codePointCount(0,text.length()));assertTrue(text.endsWith("…"));
    }
    @Test void onlyTierOneNeedsOnePlayer() {
        var r=rules("[[\"00:00\",\"24:00\"]]");
        var p=new CaptureSites.Point("test.point","Test","RESOURCE_SITE",0,64,0,16,null,"NEUTRAL",null,null,new CaptureSites.Resource("FERROUS",1,"PIT",null,1));
        assertEquals(1,CaptureEntry.needed(p,r));
        assertEquals(2,CaptureEntry.needed(new CaptureSites.Point("test.fort","Fort","FORT",0,64,0,16,null,"NEUTRAL",null,null,null),r));
    }
    @Test void all57AnchorsKeepGroundInsideAndDistantCavesAndSkyOutside() throws java.io.IOException {
        var data=JsonParser.parseString(java.nio.file.Files.readString(java.nio.file.Path.of("../../server/world-config/capture-sites-states.json"))).getAsJsonObject().getAsJsonObject("points");
        assertEquals(57,data.size());
        for(var entry:data.entrySet()){
            var row=entry.getValue().getAsJsonObject();int x=row.get("x").getAsInt(),z=row.get("z").getAsInt(),y=row.get("y").getAsInt();
            var point=new CaptureSites.Point(entry.getKey(),"Test","FORT",x,y,z,16,null,"NEUTRAL",null,null,null);
            assertTrue(point.contains(x+6.5,y,z+6.5,y,12),entry.getKey());
            assertTrue(point.contains(x+.5,y+12,z+.5,y,12));
            assertFalse(point.contains(x+.5,y+13,z+.5,y,12));
            assertFalse(point.contains(x+.5,y-30,z+.5,y,12));
            assertFalse(point.contains(x+17.5,y,z+.5,y,12));
        }
    }
    @Test void menuSummaryPacketRoundtrip() {
        var buffer=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            var value=new ServerMenuPayloads.CaptureSummary("Форт Перевала","Владелец NYA · защита ещё 42 мин",false);
            ServerMenuPayloads.CaptureSummary.CODEC.encode(buffer,value);
            assertEquals(value,ServerMenuPayloads.CaptureSummary.CODEC.decode(buffer));
        }finally{buffer.release();}
    }
}
