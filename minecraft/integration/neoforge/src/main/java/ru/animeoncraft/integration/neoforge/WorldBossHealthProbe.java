package ru.animeoncraft.integration.neoforge;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;

final class WorldBossHealthProbe {
    private WorldBossHealthProbe() {}
    static void verify(MinecraftServer server){
        for(int players:new int[]{0,15,30}){
            double expected=switch(players){case 0->600;case 15->1800;default->3000;};
            if(WorldBossEncounter.maximumHealth(players)!=expected)throw new IllegalStateException("Incorrect boss scaling");
            var entity=EntityType.WITHER.create(server.overworld());
            if(entity==null||entity.getMaxHealth()!=300)throw new IllegalStateException("Ordinary Wither changed");
            entity.getAttribute(Attributes.MAX_HEALTH).setBaseValue(WorldBossEncounter.maximumHealth(players));
            entity.setHealth((float)expected);
            if(entity.getMaxHealth()!=expected||entity.getHealth()!=expected)throw new IllegalStateException("Minecraft capped world boss health for "+players+" players: "+entity.getMaxHealth());
            entity.setHealth((float)expected-73);
            var saved=entity.saveWithoutId(new CompoundTag());
            var restored=EntityType.WITHER.create(server.overworld());
            if(restored==null)throw new IllegalStateException("Cannot restore boss");
            restored.load(saved);
            if(restored.getMaxHealth()!=expected||restored.getHealth()!=expected-73)throw new IllegalStateException("Boss health lost on NBT reload");
        }
        System.out.println("AOC_WORLD_BOSS_HEALTH_PASS players_15=1800 players_30=3000 ordinary_wither=300 nbt_roundtrip=true");
    }
}
