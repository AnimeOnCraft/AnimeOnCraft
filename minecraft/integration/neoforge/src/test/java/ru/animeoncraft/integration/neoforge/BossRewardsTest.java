package ru.animeoncraft.integration.neoforge;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class BossRewardsTest {
    @Test void awardsOnlyRealBossTypesAndExcludesPlayersSummonsAndBossParts(){
        for(String id:java.util.List.of("minecraft:wither","minecraft:ender_dragon","irons_spellbooks:dead_king","irons_spellbooks:fire_boss"))assertTrue(BossRewards.eligible(id));
        for(String id:java.util.List.of("minecraft:player","minecraft:iron_golem","irons_spellbooks:dead_king_corpse","irons_spellbooks:dead_king_soul","irons_spellbooks:catacombs_zombie"))assertFalse(BossRewards.eligible(id));
    }
}
