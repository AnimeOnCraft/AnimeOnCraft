package ru.animeoncraft.integration.neoforge;

final class BossRewards {
    private static final java.util.Set<String> ELIGIBLE=java.util.Set.of(
        "minecraft:wither","minecraft:ender_dragon","irons_spellbooks:dead_king","irons_spellbooks:fire_boss");
    private BossRewards() {}
    static boolean eligible(String entityId){return ELIGIBLE.contains(entityId);}
}
