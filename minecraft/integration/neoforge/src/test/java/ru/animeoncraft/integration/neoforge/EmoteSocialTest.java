package ru.animeoncraft.integration.neoforge;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EmoteSocialTest {
    @Test void favoritesAreBoundedDistinctAndCannotGrantUnknownIds(){assertEquals(List.of("NEON_DANCE"),EmoteFavorites.clean(Arrays.asList(null,"NEON_DANCE","NEON_DANCE","ADMIN_GRANT")));assertEquals(List.of(),EmoteFavorites.toggle(List.of("HERO_POSE"),"HERO_POSE"));assertEquals(List.of("ZEN_POSE"),EmoteFavorites.toggle(List.of(),"ZEN_POSE"));assertEquals(List.of(),EmoteFavorites.toggle(List.of(),"ADMIN_GRANT"));}
    @Test void pairNeedsActualProximityAndFacingAndExpiresAtBoundary(){assertFalse(EmotePairRules.inRange(0,0));assertTrue(EmotePairRules.inRange(1.96,.2));assertFalse(EmotePairRules.inRange(1.96,.46));assertFalse(EmotePairRules.inRange(3.25,0));assertFalse(EmotePairRules.facing(.79));assertTrue(EmotePairRules.facing(.8));assertFalse(EmotePairRules.expired(199,200));assertTrue(EmotePairRules.expired(200,200));}
    @Test void requestsCannotStartSoloOrInjectActions(){assertTrue(Emotes.paired("HIGH_FIVE"));assertTrue(Emotes.paired("HANDSHAKE"));assertFalse(Emotes.paired("NEON_DANCE"));assertThrows(IllegalArgumentException.class,()->new EmotePairPayloads.Request("GRANT",UUID.randomUUID(),"HIGH_FIVE"));assertThrows(IllegalArgumentException.class,()->new EmotePairPayloads.Request("ACCEPT",UUID.randomUUID(),"HERO_POSE"));assertThrows(IllegalArgumentException.class,()->new EmotePairPayloads.Notice(UUID.randomUUID(),"a".repeat(17),"HIGH_FIVE",10));assertThrows(IllegalArgumentException.class,()->new EmotePairPayloads.Notice(UUID.randomUUID(),"A","HIGH_FIVE",201));assertDoesNotThrow(()->new EmotePairPayloads.Notice(UUID.randomUUID(),"","",0));}
}
