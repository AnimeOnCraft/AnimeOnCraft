package ru.animeoncraft.integration.neoforge;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EmotesTest {
    @Test void starterBundleAndStableIdentifiers(){assertEquals(3,Emotes.starters().size());assertEquals(List.of("NEON_DANCE","HERO_POSE","ZEN_POSE"),Emotes.starters());assertNull(Emotes.find("../../anything"));}
    @Test void onlyKnownBoundedPackets(){var id=UUID.randomUUID();assertThrows(IllegalArgumentException.class,()->new EmotePayloads.Request("ADMIN_GRANT"));assertThrows(IllegalArgumentException.class,()->new EmotePayloads.State(id,"NEON_DANCE",241));assertThrows(IllegalArgumentException.class,()->new EmotePayloads.State(id,"",1));assertThrows(IllegalArgumentException.class,()->new EmotePayloads.Inventory(true,List.of("HERO_POSE","HERO_POSE")));assertThrows(IllegalArgumentException.class,()->new EmotePayloads.Inventory(false,Emotes.starters()));assertDoesNotThrow(()->new EmotePayloads.Request(""));}
    @Test void gameplayInterruptions(){assertFalse(Emotes.interrupts(0,true,false,false));assertTrue(Emotes.interrupts(.01,true,false,false));assertTrue(Emotes.interrupts(0,false,false,false));assertTrue(Emotes.interrupts(0,true,true,false));assertTrue(Emotes.interrupts(0,true,false,true));}
    @Test void danceMovesButPosesHoldAndAllFramesFinite(){assertNotEquals(EmoteMotion.frame("NEON_DANCE",0),EmoteMotion.frame("NEON_DANCE",5));assertEquals(EmoteMotion.frame("HERO_POSE",10),EmoteMotion.frame("HERO_POSE",30));for(var e:Emotes.ALL)for(int i=0;i<=e.ticks();i++){var f=EmoteMotion.frame(e.code(),i);for(var p:List.of(f.head(),f.body(),f.leftArm(),f.rightArm(),f.leftLeg(),f.rightLeg()))assertTrue(Float.isFinite(p.x())&&Float.isFinite(p.y())&&Float.isFinite(p.z()));assertTrue(EmoteMotion.weight(i,e.ticks())>=0&&EmoteMotion.weight(i,e.ticks())<=1);}assertEquals(0,EmoteMotion.weight(0,240));assertEquals(0,EmoteMotion.weight(240,240));}
}
