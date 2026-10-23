package ru.animeoncraft.explorationprobe;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.storage.loot.LootTable;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/** Separate, opt-in mod: never packaged in the launcher or live server. */
@Mod("aocexplorationprobe")
public final class ExplorationProbe {
    public ExplorationProbe(IEventBus ignored) { NeoForge.EVENT_BUS.addListener(this::started); }
    private void started(ServerStartedEvent event) {
        if (!Boolean.getBoolean("animeoncraft.explorationProbe")) return;
        var server = event.getServer();
        if (server.getPort() != 25569 || !"127.0.0.1".equals(server.getLocalIp())
            || !server.getWorldData().getLevelName().startsWith("aoc-exploration-review-")) throw new IllegalStateException("Exploration probe requires its own loopback world");
        try {
            var config = JsonParser.parseString(Files.readString(Path.of("config/aoc-exploration-probe.json"))).getAsJsonObject();
            for (var id : config.getAsJsonArray("mods")) check(ModList.get().isLoaded(id.getAsString()), "loaded mod " + id);
            var ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
            var sets = server.registryAccess().registryOrThrow(Registries.STRUCTURE_SET);
            for (var entry : config.getAsJsonArray("structure_sets")) {
                var expected = entry.getAsJsonObject();
                var value = sets.get(ResourceLocation.parse(expected.get("id").getAsString()));
                check(value != null, "structure set " + expected);
                var actual = StructureSet.DIRECT_CODEC.encodeStart(ops, value).getOrThrow().getAsJsonObject().getAsJsonObject("placement");
                check(Math.abs(actual.get("frequency").getAsDouble() - expected.get("after").getAsDouble()) < 0.000001, "loaded frequency " + expected);
            }
            for (var entry : config.getAsJsonArray("loot_tables")) {
                var id = ResourceLocation.parse(entry.getAsJsonObject().get("id").getAsString());
                var table = server.reloadableRegistries().getLootTable(ResourceKey.create(Registries.LOOT_TABLE, id));
                var actual = LootTable.DIRECT_CODEC.encodeStart(ops, table).getOrThrow().toString();
                check(actual.contains("minecraft:random_chance") && actual.contains("0.5"), "loaded valuable loot condition " + id);
            }
            for (var id : config.getAsJsonArray("advancement_repairs")) check(server.getAdvancements().get(ResourceLocation.parse(id.getAsString())) != null, "repaired advancement " + id);
            var commands = server.getCommands().getDispatcher();
            for (String alias : List.of("ecore", "essentialcore", "core")) {
                var root = commands.getRoot().getChild(alias); check(root != null, "admin command registered " + alias);
                check(!root.canUse(server.createCommandSourceStack().withPermission(0)), "ordinary player cannot inspect/restore " + alias);
                check(root.canUse(server.createCommandSourceStack().withPermission(2)), "operator can inspect/restore " + alias);
            }
            var essential = Class.forName("net.night.nightcore.Config");
            for (String field : List.of("LOG_CHAT", "LOG_COMMANDS", "LOG_CONSOLE_COMMANDS")) {
                Object value = essential.getField(field).get(null);
                check(Boolean.FALSE.equals(value.getClass().getMethod("get").invoke(value)), "sensitive logging disabled " + field);
            }
            Object threads = essential.getField("QUEUE_THREAD_COUNT").get(null);
            check(Integer.valueOf(2).equals(threads.getClass().getMethod("get").invoke(threads)), "server config applied to Essential Core");
            var structures = server.registryAccess().registryOrThrow(Registries.STRUCTURE);
            for (String namespace : List.of("betterdungeons", "betterdeserttemples", "betterjungletemples", "bettermineshafts", "betterfortresses", "betterstrongholds", "betteroceanmonuments", "mvs", "dungeons_arise")) {
                check(structures.keySet().stream().anyMatch(id -> id.getNamespace().equals(namespace)), "registered structures " + namespace);
            }
            // End Island rebuilds the fight through its mixins; it has no worldgen structure entry.
            var endCommand = commands.getRoot().getChild("end_island");
            check(endCommand != null && !endCommand.canUse(server.createCommandSourceStack().withPermission(0)), "End Island reset remains administrative");
            var biomes = server.registryAccess().registryOrThrow(Registries.BIOME);
            check(biomes.keySet().stream().filter(id -> id.getNamespace().equals("yungscavebiomes")).count() >= 2, "both new cave biomes registered");
            int chunks = 0;
            System.out.println("AOC_EXPLORATION_PHASE registry_policy_permissions_passed; generating_dimensions");
            for (var dimension : List.of(Level.OVERWORLD, Level.NETHER, Level.END)) {
                var level = server.getLevel(dimension); check(level != null, "dimension " + dimension);
                for (int delta : new int[] {0, 2}) { level.getChunk((6400 + delta * 16) >> 4, 6400 >> 4); chunks++; }
                if (dimension.equals(Level.END)) { level.getChunk(0, 0); chunks++; }
            }
            var source = server.createCommandSourceStack().withPermission(4).withSuppressedOutput();
            for (String id : List.of("mvs:well", "dungeons_arise:bandit_towers")) {
                check(structures.containsKey(ResourceLocation.parse(id)), "placement target " + id);
                System.out.println("AOC_EXPLORATION_PHASE preparing_placement " + id);
                boolean placed = false;
                for (int attempt = 0; attempt < 8 && !placed; attempt++) {
                    int x = 6400 + attempt * 256, z = id.startsWith("mvs:") ? 6400 : 8000;
                    int radius = id.startsWith("mvs:") ? 3 : 8;
                    // Honor the author's liquid/terrain filters, trying independent dry terrain rather than changing them.
                    var origin = server.overworld().getChunk(x >> 4, z >> 4);
                    int y = origin.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG, 0, 0);
                    if (id.startsWith("mvs:") && !server.overworld().getFluidState(new net.minecraft.core.BlockPos(x, y, z)).isEmpty()) continue;
                    for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) server.overworld().getChunk((x >> 4) + dx, (z >> 4) + dz);
                    try { placed = commands.execute("place structure " + id + " " + x + " 90 " + z, source.withLevel(server.overworld())) > 0; }
                    catch (com.mojang.brigadier.exceptions.CommandSyntaxException unsuitableTerrain) {
                        System.out.println("AOC_EXPLORATION_PHASE terrain_rejected " + id + " attempt=" + attempt);
                    }
                }
                check(placed, "actual structure generation " + id);
            }
            var result = new JsonObject(); result.addProperty("passed", true); result.addProperty("loaded_mods", config.getAsJsonArray("mods").size());
            result.addProperty("loaded_structure_overrides", config.getAsJsonArray("structure_sets").size());
            result.addProperty("loaded_loot_overrides", config.getAsJsonArray("loot_tables").size());
            result.addProperty("generated_dimensions", 3); result.addProperty("generated_chunks", chunks);
            result.addProperty("mvs_and_wda_real_placement", true); result.addProperty("essential_op_permissions", true); result.addProperty("essential_private_logs_disabled", true);
            Files.writeString(Path.of("exploration-result.json"), result.toString());
            System.out.println("AOC_EXPLORATION_PROBE_PASS " + result);
        } catch (Exception failure) { failure.printStackTrace(); System.out.println("AOC_EXPLORATION_PROBE_FAILED"); }
    }
    private static void check(boolean value, String label) { if (!value) throw new IllegalStateException(label); }
}
