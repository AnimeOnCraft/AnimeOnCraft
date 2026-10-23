package ru.animeoncraft.integration.neoforge;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/** Denies new mobs; existing saved pets/NPCs and transported mobs are preserved. No chunk reads in join events. */
final class CentralSpawn {
    private static final String EXISTING = "aoc_spawn_existing_mob";
    private static volatile CentralSpawnRules.Zone fallback;
    private CentralSpawn() {}

    static CentralSpawnRules.Zone zone() {
        var layout = CentralSpawnRules.fromLayout(WorldLayout.state());
        return layout != null ? layout : fallback;
    }

    static void starting(ServerStartingEvent event) {
        var common = ClanSpawns.read(event.getServer()).get("COMMON");
        fallback = common == null ? null : new CentralSpawnRules.Zone(common.getX(), common.getZ());
        var area = zone();
        if (area != null) System.out.println("AOC central spawn: new mobs blocked at " + area.x() + ", " + area.z() + " radius=" + CentralSpawnRules.RADIUS);
    }

    static void stopped(ServerStoppedEvent event) { fallback = null; }

    static void placement(MobSpawnEvent.SpawnPlacementCheck event) {
        var area = zone();
        if (area != null && event.getLevel().getLevel().dimension().equals(Level.OVERWORLD)
            && area.contains(event.getPos().getX(), event.getPos().getZ()))
            event.setResult(MobSpawnEvent.SpawnPlacementCheck.Result.FAIL);
    }

    static void join(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Mob mob)) return;
        // PersistentData is copied on dimension travel. Loading or moving an existing mob is not spawning one.
        boolean existing = event.loadedFromDisk() || mob.getPersistentData().getBoolean(EXISTING);
        var area = zone();
        if (!existing && area != null && event.getLevel().dimension().equals(Level.OVERWORLD) && area.contains(mob.getX(), mob.getZ())) {
            event.setCanceled(true);
            return;
        }
        mob.getPersistentData().putBoolean(EXISTING, true);
    }
}
