package ru.animeoncraft.integration.neoforge;
final class SpawnRules {
 private SpawnRules(){}
 static boolean useClan(boolean personal,boolean missingBlock,boolean fromEnd){return !fromEnd&&(!personal||missingBlock);}
}
