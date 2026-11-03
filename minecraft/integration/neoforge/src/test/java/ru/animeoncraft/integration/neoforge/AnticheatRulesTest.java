package ru.animeoncraft.integration.neoforge;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import ru.animeoncraft.integration.neoforge.AnticheatRules.Check;
import ru.animeoncraft.integration.neoforge.AnticheatRules.Level;

final class AnticheatRulesTest {
    private static final long NOW=1_800_000_000_000L;
    @Test void scoreGrowsDecaysAndIsCapped(){
        double score=0;long at=NOW;
        for(int i=0;i<3;i++){score=AnticheatRules.add(score,at,NOW,Check.FLY);at=NOW;}
        assertEquals(45,score,1e-9);
        assertEquals(Level.ALERT,AnticheatRules.level(score));
        assertEquals(25,AnticheatRules.decay(score,NOW,NOW+5*60_000),1e-9,"4 points per minute");
        assertEquals(0,AnticheatRules.decay(score,NOW,NOW+60*60_000),1e-9);
        assertEquals(score,AnticheatRules.decay(score,NOW,NOW-1000),1e-9,"clock going back never adds");
        double big=0;for(int i=0;i<200;i++)big=AnticheatRules.add(big,NOW,NOW,Check.XRAY);
        assertEquals(AnticheatRules.MAX_SCORE,big,1e-9);
        assertEquals(Level.WATCH,AnticheatRules.level(39.9));assertEquals(Level.CRITICAL,AnticheatRules.level(80));
    }
    @Test void reportsOncePerMinuteUnlessLevelRises(){
        assertTrue(AnticheatRules.shouldReport(null,Level.WATCH,null,NOW));
        assertFalse(AnticheatRules.shouldReport(Level.WATCH,Level.WATCH,NOW,NOW+59_999));
        assertTrue(AnticheatRules.shouldReport(Level.WATCH,Level.WATCH,NOW,NOW+60_000));
        assertTrue(AnticheatRules.shouldReport(Level.WATCH,Level.ALERT,NOW,NOW+1));
        assertTrue(AnticheatRules.shouldReport(Level.ALERT,Level.ALERT,null,NOW),"first report of another check");
        assertFalse(AnticheatRules.shouldReport(Level.CRITICAL,Level.ALERT,NOW,NOW+10));
    }
    @Test void sprintJumpingIsNeverSpeed(){
        double normal=AnticheatRules.allowedSpeed(0.1,false,false);
        assertTrue(normal>7.1*1.3,"sprint-jump with lag margin");
        assertTrue(normal<14,"a speed hack of 2x is caught");
        assertTrue(AnticheatRules.allowedSpeed(0.14,false,false)>normal,"Speed II raises the limit");
        assertTrue(AnticheatRules.allowedSpeed(0.1,true,false)>normal*2,"ice");
        assertEquals(normal,AnticheatRules.allowedSpeed(0.05,false,false),1e-9,"slowness never lowers the limit");
    }
    @Test void fastBreakOnlyForSlowBlocksAndClearlyTooFast(){
        assertEquals(Integer.MAX_VALUE,AnticheatRules.expectedBreakTicks(0));
        assertEquals(1,AnticheatRules.expectedBreakTicks(2f));
        assertEquals(20,AnticheatRules.expectedBreakTicks(0.05f));
        assertFalse(AnticheatRules.fastBreak(1,0),"instant blocks are skipped");
        assertFalse(AnticheatRules.fastBreak(5,0),"fast blocks are skipped");
        assertTrue(AnticheatRules.fastBreak(20,5));
        assertFalse(AnticheatRules.fastBreak(20,11),"lag margin");
        assertFalse(AnticheatRules.fastBreak(Integer.MAX_VALUE,0),"unbreakable progress is ignored");
    }
    @Test void reachHasLagMargin(){
        assertFalse(AnticheatRules.reach(3.0,3.0));assertFalse(AnticheatRules.reach(4.2,3.0));
        assertTrue(AnticheatRules.reach(4.6,3.0));assertFalse(AnticheatRules.reach(5.5,4.5),"modded longer reach is respected");
    }
    @Test void xrayNeedsManyOresAndHighRatio(){
        assertFalse(AnticheatRules.xray(10,11),"too few ores");
        assertTrue(AnticheatRules.xray(200,12));
        assertFalse(AnticheatRules.xray(1000,12),"1.2% is honest branch mining luck");
        assertEquals("6.0%",AnticheatRules.percent(200,12));assertEquals("—",AnticheatRules.percent(0,3));
    }
    @Test void reportFieldsAreSanitized(){
        assertEquals("Steve_1",Anticheat.safeName("Steve_1"));
        assertEquals("bad_name",Anticheat.safeName("bad name"));
        assertEquals(16,Anticheat.safeName("a".repeat(40)).length());
        assertEquals("unknown",Anticheat.safeName(""));
        assertEquals("a b",Anticheat.safeDetail("a\nb"));
        assertEquals(300,Anticheat.safeDetail("x".repeat(400)).length());
    }
}
