package ru.animeoncraft.integration.neoforge;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

final class GameplayEvents {
    private GameplayEvents() {}

    static void death(LivingDeathEvent event) {
        if (!(event.getSource().getEntity() instanceof ServerPlayer player)) return;
        LivingEntity defeated = event.getEntity();
        if(WorldBossEncounter.isBoss(defeated))return;
        if (!BossRewards.eligible(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(defeated.getType()).toString())) return;
        var identity = GameIdentity.PLAYERS.get(player.getUUID());
        if (identity == null || identity.clan() == null) return;
        ClanOnlineHeartbeat.event(player.server, identity.clan(), defeated.getUUID(), "BOSS_KILL", 100, 5);
    }
}
