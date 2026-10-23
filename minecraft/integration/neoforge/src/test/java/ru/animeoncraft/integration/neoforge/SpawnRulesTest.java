package ru.animeoncraft.integration.neoforge;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class SpawnRulesTest {
 @Test void preservesBedsAnchorsAndPersonalSpawnButFallsBackWhenDestroyed(){
  assertFalse(SpawnRules.useClan(true,false,false));
  assertTrue(SpawnRules.useClan(false,false,false));
  assertTrue(SpawnRules.useClan(true,true,false));
  assertFalse(SpawnRules.useClan(false,false,true));
  assertFalse(SpawnRules.useClan(true,true,true));
 }
}
