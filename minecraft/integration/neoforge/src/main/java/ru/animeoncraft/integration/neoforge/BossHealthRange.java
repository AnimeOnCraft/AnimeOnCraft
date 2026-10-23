package ru.animeoncraft.integration.neoforge;

import net.minecraft.world.entity.ai.attributes.Attributes;
import ru.animeoncraft.integration.neoforge.mixin.HealthAttributeAccess;

final class BossHealthRange {
    private BossHealthRange() {}
    static void initialize(){
        // Change only the allowed ceiling, never the default/base health of ordinary entities.
        // Apply on both logical sides so synchronized boss attributes have the same range.
        var health=(HealthAttributeAccess)Attributes.MAX_HEALTH.value();
        if(health.aocMaximum()<3000)health.aocMaximum(3000);
    }
}
