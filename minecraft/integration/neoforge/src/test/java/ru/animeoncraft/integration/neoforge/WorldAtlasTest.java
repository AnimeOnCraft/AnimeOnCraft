package ru.animeoncraft.integration.neoforge;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorldAtlasTest {
    private static WorldAtlasData.Point point(String id){return new WorldAtlasData.Point(id,"Тестовая точка","RESOURCE_SITE",-2560,80,-832,48,0x44cc88,"NYA","PROTECTED");}
    @Test void onlyMarkerIdentityShipsWithoutAnyTerrainImage(){
        var b=WorldAtlasAsset.BOUNDS;
        assertEquals("states-20261020",b.worldId());
        assertEquals("e83af9bc882cc6eb5a24328fd8abb33f2c6ca40e508c87e43ed12a87cd0044f3",b.sha256());
        assertNull(getClass().getResource(WorldAtlasAsset.ROOT+"relief.jpg"));
        assertNull(getClass().getResource(WorldAtlasAsset.ROOT+"relief.png"));
        assertEquals("Тестовая точка · NYA · Защищено",point("test.point").label());
    }
    @Test void payloadValidationRejectsDuplicateIdsInvalidWorldAndInvalidCoordinates(){
        var b=WorldAtlasAsset.BOUNDS;
        assertThrows(IllegalArgumentException.class,()->new WorldAtlasData(b.worldId(),b.sha256(),List.of(point("test.same"),point("test.same"))));
        assertThrows(IllegalArgumentException.class,()->new WorldAtlasData("../world",b.sha256(),List.of()));
        assertThrows(IllegalArgumentException.class,()->new WorldAtlasData.Point("test.bad","Точка","FORT",30_000_001,80,0,32,0xffffff,"",""));
        assertEquals("R",point("test.point").symbol());
    }
    @Test void boundedPacketRoundtripRemainsCompatibleWithPreviousServer(){
        var b=WorldAtlasAsset.BOUNDS;var data=new WorldAtlasData(b.worldId(),b.sha256(),List.of(point("test.point")));
        var buffer=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try{
            WorldAtlasPayload.CODEC.encode(buffer,new WorldAtlasPayload(data));assertEquals(data,WorldAtlasPayload.CODEC.decode(buffer).data());
            buffer.clear();buffer.writeUtf(b.worldId(),64).writeUtf(b.sha256(),64).writeVarInt(1025);
            assertThrows(IllegalArgumentException.class,()->WorldAtlasPayload.CODEC.decode(buffer));
        }finally{buffer.release();}
    }
}
