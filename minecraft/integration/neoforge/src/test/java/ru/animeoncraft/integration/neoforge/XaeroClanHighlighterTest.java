package ru.animeoncraft.integration.neoforge;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class XaeroClanHighlighterTest {
    private final XaeroClanHighlighter map = new XaeroClanHighlighter();
    @AfterEach void clear() { ClientTerritories.logout(null); }

    @Test void rectanglesRespectNegativeChunksAndHalfOpenEdges() {
        ClientTerritories.regions = List.of(new Territory("NYA", -64, -64, 64, 64, 0xAA33CC, "NYA"));
        assertTrue(map.chunkIsHighlit(Level.OVERWORLD, -4, -4));
        assertTrue(map.chunkIsHighlit(Level.OVERWORLD, 3, 3));
        assertFalse(map.chunkIsHighlit(Level.OVERWORLD, -5, -4));
        assertFalse(map.chunkIsHighlit(Level.OVERWORLD, 4, 0));
        var pixels = new XaeroClanHighlighter(true).getChunkHighlitColor(Level.OVERWORLD, -4, -4);
        assertEquals(0xCC33AAF2, pixels[0]);
        assertEquals(0xCC33AA29, pixels[17]);
        assertTrue(map.regionHasHighlights(Level.OVERWORLD, -1, -1));
        assertFalse(map.regionHasHighlights(Level.OVERWORLD, 1, 0));
    }

    @Test void thinPolygonTipsAreNotLostBetweenCornerSamples() {
        ClientTerritories.polygons = List.of(new RegionMapPayload.Region("NYA", "NYA", 0xAA33CC,
            new int[] {1,3,3,1}, new int[] {4,4,6,6}));
        assertTrue(map.chunkIsHighlit(Level.OVERWORLD, 0, 0));
        var pixels = map.getChunkHighlitColor(Level.OVERWORLD, 0, 0);
        assertNotNull(pixels);assertEquals(0xCC33AAF2, pixels[4*16+1]);
        assertEquals(0, pixels[0]);assertEquals(0, pixels[8*16+8]);
    }

    @Test void polygonOwnershipUpdatesBothTintAndCachedRegionHash() {
        var original = new RegionMapPayload.Region("NYA", "NYA", 0xAA33CC,
            new int[] {-64,64,64,-64}, new int[] {-64,-64,64,64});
        ClientTerritories.polygons = List.of(original);
        int before = map.calculateRegionHash(Level.OVERWORLD, 0, 0);
        ClientTerritories.polygons = List.of(new RegionMapPayload.Region("NYA", "SCAM", 0xE84A5F, original.xs(), original.zs()));
        assertNotEquals(before, map.calculateRegionHash(Level.OVERWORLD, 0, 0));
        assertTrue(map.getBlockHighlightBluntTooltip(Level.OVERWORLD, 0, 0).getString().contains("SCAM"));
        assertEquals(Integer.reverseBytes(0xE84A5F) | 41, map.getChunkHighlitColor(Level.OVERWORLD, 0, 0)[0]);
        int captured = map.calculateRegionHash(Level.OVERWORLD, 0, 0);
        ClientTerritories.polygons = List.of(new RegionMapPayload.Region("NYA", "SCAM", 0xE84A5F,
            new int[] {-64,32,64,-64}, original.zs()));
        assertNotEquals(captured, map.calculateRegionHash(Level.OVERWORLD, 0, 0));
    }

    @Test void allFiveOriginalPolygonsAndEveryCapitalMatchServerProtection() throws Exception {
        var policy = RegionPolicy.parse(JsonParser.parseString(Files.readString(Path.of("src/test/resources/regions-states.json"))).getAsJsonObject());
        ClientTerritories.polygons = RegionMapPayload.of(policy).regions();
        assertEquals(5, ClientTerritories.polygons.size());
        for (var region : policy.regions()) if (region.clan() != null) {
            int x = region.spawn().x(), z = region.spawn().z();
            assertEquals(region.clan(), policy.ownerAt(x, z));
            assertTrue(map.chunkIsHighlit(Level.OVERWORLD, Math.floorDiv(x,16), Math.floorDiv(z,16)));
            assertTrue(map.getBlockHighlightSubtleTooltip(Level.OVERWORLD,x,z).getString().contains(region.clan()));
            assertTrue(new XaeroClanHighlighter().chunkIsHighlit(Level.OVERWORLD, Math.floorDiv(x,16), Math.floorDiv(z,16)), "independent minimap adapter");
            // Check every vertex and both sides of nearby borders, not just capital centres.
            for (int i=0;i<region.xs().length;i++) {
                int chunkX=Math.floorDiv(region.xs()[i],16),chunkZ=Math.floorDiv(region.zs()[i],16);
                var pixels=map.getChunkHighlitColor(Level.OVERWORLD,chunkX,chunkZ);
                for(int dz=0;dz<16;dz++)for(int dx=0;dx<16;dx++) {
                    String owner=policy.ownerAt(chunkX*16+dx,chunkZ*16+dz);
                    boolean protectedClan=owner!=null&&!RegionPolicy.NOBODY.equals(owner);
                    assertEquals(protectedClan,pixels!=null&&(pixels[dz*16+dx]&255)!=0,"map border differs from server");
                }
            }
        }
        assertNull(map.getBlockHighlightSubtleTooltip(Level.OVERWORLD,0,0),"neutral capital is not clan land");
        assertNull(map.getChunkHighlitColor(Level.OVERWORLD,0,0),"no invented central claim or terrain image");
    }

    @Test void dimensionsAndLogoutCannotLeakAnotherWorldsClaims() {
        ClientTerritories.regions=List.of(new Territory("NYA",-64,-64,64,64,0xAA33CC,"NYA"));
        for(var dimension:List.of(Level.NETHER,Level.END)) {
            assertFalse(map.regionHasHighlights(dimension,0,0));assertFalse(map.chunkIsHighlit(dimension,0,0));
            assertNull(map.getChunkHighlitColor(dimension,0,0));assertNull(map.getBlockHighlightSubtleTooltip(dimension,0,0));
        }
        long before=ClientTerritories.revision();ClientTerritories.logout(null);
        assertTrue(ClientTerritories.revision()>before);assertEquals(0,map.calculateRegionHash(Level.OVERWORLD,0,0));
        assertFalse(map.regionHasHighlights(Level.OVERWORLD,0,0));assertNull(map.getChunkHighlitColor(Level.OVERWORLD,0,0));
    }

    @Test void smallSoraMarketsMatchExactServerColumnsOnMapAndMinimap() throws Exception {
        var policy=RegionPolicy.parse(JsonParser.parseString(Files.readString(Path.of("src/test/resources/regions-markets.json"))).getAsJsonObject());
        ClientTerritories.polygons=RegionMapPayload.of(policy).regions();assertEquals(10,ClientTerritories.polygons.size());
        for(var plot:policy.regions())if(plot.market())for(var renderer:List.of(map,new XaeroClanHighlighter(true))){
            for(int x=plot.minX()-1;x<=plot.maxX();x++)for(int z=plot.minZ()-1;z<=plot.maxZ();z++){
                var pixels=renderer.getChunkHighlitColor(Level.OVERWORLD,Math.floorDiv(x,16),Math.floorDiv(z,16));
                boolean visible=pixels!=null&&(pixels[Math.floorMod(z,16)*16+Math.floorMod(x,16)]&255)!=0;
                assertEquals(plot.contains(x,z),visible,"Market outline/tint at "+x+","+z);
            }
        }
        assertNull(map.getBlockHighlightSubtleTooltip(Level.OVERWORLD,0,0));
        assertNull(map.getBlockHighlightSubtleTooltip(Level.OVERWORLD,0,52));
    }
}
