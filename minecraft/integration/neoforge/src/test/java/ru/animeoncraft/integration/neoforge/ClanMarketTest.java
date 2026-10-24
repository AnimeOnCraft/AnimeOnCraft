package ru.animeoncraft.integration.neoforge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClanMarketTest {
    static JsonObject catalog() throws Exception {
        return JsonParser.parseString(Files.readString(Path.of("src/test/resources/regions-markets.json"))).getAsJsonObject();
    }
    @Test void allFivePlotsFollowExactEdgesAndOnlyTheirOwnClanBuilds() throws Exception {
        var regions=RegionPolicy.parse(catalog());var policy=TerritoryPermissions.states(regions,16);
        var plots=regions.regions().stream().filter(RegionPolicy.Region::market).toList();assertEquals(5,plots.size());
        for(var plot:plots){
            assertEquals(36,plot.maxX()-plot.minX());assertEquals(36,plot.maxZ()-plot.minZ());
            for(int x=plot.minX()-1;x<=plot.maxX();x++)for(int z=plot.minZ()-1;z<=plot.maxZ();z++){
                boolean inside=x>=plot.minX()&&x<plot.maxX()&&z>=plot.minZ()&&z<plot.maxZ();
                assertEquals(inside?plot.clan():RegionPolicy.NOBODY,regions.ownerAt(x,z),"column "+x+","+z);
                for(var member:plots)assertEquals(inside&&member.clan().equals(plot.clan()),policy.canBuild(true,false,member.clan(),x,z));
                assertFalse(policy.canBuild(false,false,plot.clan(),x,z));assertFalse(policy.canBuild(true,false,null,x,z));
            }
            assertFalse(policy.canMove(plot.minX()-1,plot.minZ(),plot.minX(),plot.minZ()));
            assertTrue(policy.canMove(plot.minX(),plot.minZ(),plot.minX()+1,plot.minZ()));
            var state=regions.regions().stream().filter(r->!r.market()&&plot.clan().equals(r.clan())).findFirst().orElseThrow();
            assertFalse(policy.canMove(plot.minX(),plot.minZ(),state.spawn().x(),state.spawn().z()),"market is a separate automation boundary");
        }
        assertEquals(RegionPolicy.NOBODY,regions.ownerAt(0,0));assertEquals(RegionPolicy.NOBODY,regions.ownerAt(0,52));
        assertEquals(RegionPolicy.NOBODY,regions.ownerAt(0,800));
        var reversed=catalog();var rows=new com.google.gson.JsonArray();
        for(int i=reversed.getAsJsonArray("regions").size()-1;i>=0;i--)rows.add(reversed.getAsJsonArray("regions").get(i));reversed.add("regions",rows);
        var other=RegionPolicy.parse(reversed);
        for(var plot:plots)assertEquals(plot.clan(),other.ownerAt(plot.minX(),plot.minZ()),"order-independent core exception");
    }
    @Test void marketMapOwnershipDoesNotFollowWarCaptureAndSurvivesTheWire() throws Exception {
        var regions=RegionPolicy.parse(catalog());
        try{
            ClanControlSync.accept(Map.of("SCAM","NYA"));
            var data=RegionMapPayload.of(regions);assertEquals(10,data.regions().size());
            var market=regions.regions().stream().filter(r->r.market()&&r.clan().equals("SCAM")).findFirst().orElseThrow();
            var rendered=data.regions().stream().filter(r->r.contains(market.minX(),market.minZ())).findFirst().orElseThrow();
            assertEquals("SCAM",rendered.owner());assertEquals(market.color(),rendered.color());
            var state=data.regions().stream().filter(r->r.contains(0,-2704)).findFirst().orElseThrow();assertEquals("NYA",state.owner());
            var buffer=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            try {RegionMapPayload.CODEC.encode(buffer,data);var decoded=RegionMapPayload.CODEC.decode(buffer);assertEquals(10,decoded.regions().size());
                assertEquals("SCAM",decoded.regions().stream().filter(r->r.contains(market.minX(),market.minZ())).findFirst().orElseThrow().owner());}
            finally {buffer.release();}
        }finally{ClanControlSync.reset();}
    }
    @Test void malformedMarketNeverRelaxesCoreProtection() throws Exception {
        var oversized=catalog();var large=oversized.getAsJsonArray("regions").asList().stream().map(e->e.getAsJsonObject()).filter(r->r.get("kind").getAsString().equals("MARKET")).findFirst().orElseThrow();
        for(int index:new int[]{1,2}){var corner=large.getAsJsonArray("polygon").get(index).getAsJsonArray();corner.set(0,new com.google.gson.JsonPrimitive(corner.get(0).getAsInt()+1));}
        assertThrows(RuntimeException.class,()->RegionPolicy.parse(oversized),"A side of 37 blocks is still rejected");
        for(String field:new String[]{"pvp_policy","build_policy","clan_code"}){
            var invalid=catalog();var plot=invalid.getAsJsonArray("regions").asList().stream().map(e->e.getAsJsonObject()).filter(r->r.get("kind").getAsString().equals("MARKET")).findFirst().orElseThrow();
            plot.addProperty(field,field.equals("pvp_policy")?"ON":field.equals("build_policy")?"OPEN":null);
            assertThrows(RuntimeException.class,()->RegionPolicy.parse(invalid),field);
        }
        var invalid=catalog();var core=invalid.getAsJsonArray("regions").asList().stream().map(e->e.getAsJsonObject()).filter(r->r.get("kind").getAsString().equals("CORE")).findFirst().orElseThrow();
        core.addProperty("build_policy","OPEN");assertThrows(RuntimeException.class,()->RegionPolicy.parse(invalid));
    }
    @Test void previousThirtyTwoRegionsAndSpawnsAreUnchanged() throws Exception {
        var old=RegionPolicy.parse(JsonParser.parseString(Files.readString(Path.of("src/test/resources/regions-states.json"))).getAsJsonObject());
        var next=RegionPolicy.parse(catalog());assertEquals(old.regions().size()+5,next.regions().size());
        for(var original:old.regions()){
            var current=next.regions().stream().filter(r->r.code().equals(original.code())).findFirst().orElseThrow();
            assertEquals(original.owner(),current.owner());assertEquals(original.clan(),current.clan());assertEquals(original.color(),current.color());
            assertArrayEquals(original.xs(),current.xs());assertArrayEquals(original.zs(),current.zs());assertEquals(original.spawn(),current.spawn());
        }
        assertEquals(new java.util.HashSet<>(old.spawns()),new java.util.HashSet<>(next.spawns()));
        var random=new java.util.Random(20261024);
        for(int i=0;i<20_000;i++){
            int x=random.nextInt(11000)-5500,z=random.nextInt(11000)-5500;
            if(next.marketAt(x,z)==null)assertEquals(old.ownerAt(x,z),next.ownerAt(x,z));
        }
    }
}
