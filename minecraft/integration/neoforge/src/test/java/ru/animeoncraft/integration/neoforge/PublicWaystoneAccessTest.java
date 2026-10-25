package ru.animeoncraft.integration.neoforge;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class PublicWaystoneAccessTest {
    private TerritoryPermissions policy() {
        return TerritoryPermissions.states(RegionPolicy.parse(JsonParser.parseString("""
            {"revision":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","world_id":"fixture",
             "regions":[
              {"code":"market","kind":"MARKET","pvp_policy":"OFF","build_policy":"MEMBERS","clan_code":"NYA","polygon":[[16,16],[32,16],[32,32],[16,32]]},
              {"code":"core","kind":"CORE","pvp_policy":"OFF","build_policy":"NONE","polygon":[[-64,-64],[64,-64],[64,64],[-64,64]]},
              {"code":"state","kind":"STATE","build_policy":"MEMBERS","clan_code":"NYA","polygon":[[128,128],[256,128],[256,256],[128,256]]},
              {"code":"fort","kind":"FORT","build_policy":"NONE","polygon":[[300,300],[400,300],[400,400],[300,400]]}
             ]}
            """).getAsJsonObject()),16);
    }
    @Test void admittedPlayersCanUseAllThreeWaystoneVariantsInPublicCore() {
        for(String block:new String[]{"waystones:waystone","waystones:mossy_waystone","waystones:sandy_waystone"})
            assertTrue(PublicWaystoneAccess.allowed(policy(),0,0,block,true,false));
    }
    @Test void noPermissionLeaksToClanPlotsStatesOtherProtectedRegionsOrMissingCatalog() {
        var p=policy();
        for(int[] pos:new int[][]{{20,20},{150,150},{350,350},{500,500}})
            assertFalse(PublicWaystoneAccess.allowed(p,pos[0],pos[1],"waystones:waystone",true,false));
        assertFalse(PublicWaystoneAccess.allowed(TerritoryPermissions.states(null,16),0,0,"waystones:waystone",true,false));
        assertFalse(PublicWaystoneAccess.allowed(null,0,0,"waystones:waystone",true,false));
        assertFalse(p.canBuild(true,false,"NYA",0,0),"activation does not grant construction rights");
    }
    @Test void containersEditingAndUnauthenticatedPlayersRemainDenied() {
        var p=policy();
        for(String block:new String[]{"minecraft:chest","minecraft:barrel","minecraft:hopper","waystones:warp_plate","other:waystone"})
            assertFalse(PublicWaystoneAccess.allowed(p,0,0,block,true,false));
        assertFalse(PublicWaystoneAccess.allowed(p,0,0,"waystones:waystone",false,false));
        assertFalse(PublicWaystoneAccess.allowed(p,0,0,"waystones:waystone",true,true));
    }
}
