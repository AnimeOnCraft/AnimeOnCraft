package ru.animeoncraft.integration.neoforge;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ArenaRulesTest {
    @Test void ratingRewardsUpsetsMoreAndNeverDropsBelowFloor(){
        assertEquals(16,ArenaRules.ratingDelta(1000,1000));
        assertTrue(ArenaRules.ratingDelta(900,1300)>ArenaRules.ratingDelta(1300,900),"an upset is worth more");
        assertEquals(1,ArenaRules.ratingDelta(3000,100),"a win is always worth something");
        assertEquals(ArenaRules.MIN_RATING,ArenaRules.afterLoss(110,30));
        assertEquals(984,ArenaRules.afterLoss(1000,16));
    }
    @Test void dayFollowsYekaterinburgAndPairIsSymmetric(){
        long utc1859=java.time.Instant.parse("2026-11-03T18:59:00Z").toEpochMilli();
        assertEquals("2026-11-03",ArenaRules.day(utc1859));
        assertEquals("2026-11-04",ArenaRules.day(utc1859+60_000));
        var a=UUID.randomUUID();var b=UUID.randomUUID();
        assertEquals(ArenaRules.pair(a,b),ArenaRules.pair(b,a));
    }
    @Test void geometryHasFloorPadsAndClosedWall(){
        assertEquals(ArenaRules.Cell.FLOOR,ArenaRules.cell(0,0));
        assertEquals(ArenaRules.Cell.PAD,ArenaRules.cell(ArenaRules.PAD_OFFSET,0));
        assertEquals(ArenaRules.Cell.PAD,ArenaRules.cell(-ArenaRules.PAD_OFFSET,1));
        assertEquals(ArenaRules.Cell.WALL,ArenaRules.cell(ArenaRules.WALL_RADIUS,0));
        assertEquals(ArenaRules.Cell.NONE,ArenaRules.cell(ArenaRules.WALL_RADIUS+2,0));
        // The wall ring has no gaps: walking outward along any bearing hits a wall cell.
        for(int angle=0;angle<360;angle+=3){
            boolean wall=false;
            for(double r=0;r<ArenaRules.WALL_RADIUS+1;r+=0.25){
                int x=(int)Math.round(r*Math.cos(Math.toRadians(angle))),z=(int)Math.round(r*Math.sin(Math.toRadians(angle)));
                wall|=ArenaRules.cell(x,z)==ArenaRules.Cell.WALL;
            }
            assertTrue(wall,"wall at "+angle);
        }
        assertTrue(ArenaRules.inside(ArenaRules.PAD_OFFSET+0.5,0));
        assertFalse(ArenaRules.inside(ArenaRules.WALL_RADIUS,0));
    }
}
