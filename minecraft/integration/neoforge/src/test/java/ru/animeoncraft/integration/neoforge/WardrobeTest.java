package ru.animeoncraft.integration.neoforge;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import java.util.*;

class WardrobeTest {
 @Test void legacyWrongSlotRequestCannotReachAccountMutation(){assertDoesNotThrow(()->ServerWardrobe.betaBack(null,"FOX_EARS"));}
 @Test void boundedPrivateSnapshot(){
  var id=UUID.randomUUID();var value=ServerWardrobe.parse(id,JsonParser.parseString("{\"back\":{\"kind\":\"CAPE\",\"code\":\"beta\",\"revision\":\"17\"},\"capes\":[{\"id\":\"beta\",\"name\":\"Beta\"}]}").getAsJsonObject());
  assertEquals(id,value.player());assertEquals(17,value.revision());assertEquals("beta",value.code());
  assertThrows(IllegalArgumentException.class,()->new WardrobePayloads.Snapshot(id,true,0,"CAPE","not-owned",value.capes()));
  assertThrows(IllegalArgumentException.class,()->new WardrobePayloads.Snapshot(id,true,0,"NONE","",Collections.nCopies(33,value.capes().getFirst())));
  assertThrows(IllegalArgumentException.class,()->new WardrobePayloads.Cape("beta","§kHidden"));
 }
 @Test void onlyOneKnownBackKind(){
  assertTrue(WardrobePayloads.valid("BETA","RIFT_MANTLE"));assertFalse(WardrobePayloads.valid("BETA","FOX_EARS"));
  assertThrows(IllegalArgumentException.class,()->new WardrobePayloads.Request("NONE","beta",0));
  assertThrows(IllegalArgumentException.class,()->new WardrobePayloads.Request("CAPE","../../cape",0));
  assertThrows(IllegalArgumentException.class,()->new WardrobePayloads.Request("READ","",-1));
  assertThrows(IllegalArgumentException.class,()->new WardrobePayloads.Request("CAPE","beta",Long.MAX_VALUE));
 }
}
