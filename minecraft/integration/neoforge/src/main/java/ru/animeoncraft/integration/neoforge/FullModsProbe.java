package ru.animeoncraft.integration.neoforge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityMobGriefingEvent;

/** Runs only inside the explicitly enabled disposable territory fixture. */
final class FullModsProbe {
    static void verify(ServerLevel level, TerritoryPermissions policy) {
        if (!net.neoforged.fml.ModList.get().isLoaded("cataclysm")) return;
        for (var id : new String[]{"cataclysm","bosses_of_mass_destruction","mowziesmobs","biomesoplenty","sereneseasons","ctov","railways","waystones","simplyswords","endermanoverhaul"}) {
            check(net.neoforged.fml.ModList.get().isLoaded(id), "Missing mod "+id);
        }
        for (var id : new String[]{"cataclysm","bosses_of_mass_destruction","mowziesmobs","endermanoverhaul"}) {
            check(BuiltInRegistries.ENTITY_TYPE.keySet().stream().anyMatch(key->key.getNamespace().equals(id)), "Missing mobs "+id);
        }
        check(level.registryAccess().registryOrThrow(Registries.BIOME).keySet().stream().anyMatch(key->key.getNamespace().equals("biomesoplenty")), "BOP biomes absent");
        check(level.registryAccess().registryOrThrow(Registries.STRUCTURE).keySet().stream().anyMatch(key->key.getNamespace().equals("ctov")), "CTOV structures absent");
        for(var id : new String[]{"railways","waystones","simplyswords"}) {
            check(level.getRecipeManager().getRecipes().stream().anyMatch(recipe->recipe.id().getNamespace().equals(id)), "Missing recipes "+id);
        }
        check(!net.neoforged.fml.ModList.get().isLoaded("overgeared"), "Retired Overgeared still loaded");
        for (var name : new String[]{"axe","boots","chestplate","helmet","hoe","leggings","pickaxe","shovel","sword"}) {
            check(level.getRecipeManager().byKey(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("minecraft", "diamond_"+name)).isPresent(), "Vanilla diamond recipe missing: "+name);
        }
        var mob = new Zombie(EntityType.ZOMBIE, level);
        var region = policy.territories().getFirst();
        for (var pos : new BlockPos[]{new BlockPos(region.minX(), 100, region.minZ()),new BlockPos(region.minX()-63,100,region.minZ()-63)}) {
            mob.setPos(pos.getX(),pos.getY(),pos.getZ());
            var event = new EntityMobGriefingEvent(level,mob); NeoForge.EVENT_BUS.post(event);
            check(!event.canGrief(), "Mob grief allowed near protected region");
        }
        mob.setPos(20000,100,20000);
        var neutral = new EntityMobGriefingEvent(level,mob); NeoForge.EVENT_BUS.post(neutral);
        check(neutral.canGrief(), "Neutral wilderness mob grief changed");
        System.out.println("AOC_FULL_MODS_PROBE_PASS mobs=true biomes=true structures=true recipes=true protected_mob_grief=false neutral_mob_grief=true");
    }

    private static void check(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
}
