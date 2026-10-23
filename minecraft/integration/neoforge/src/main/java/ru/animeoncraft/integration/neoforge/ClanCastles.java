package ru.animeoncraft.integration.neoforge;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/** Builds the five small, repairable clan keeps once in the prepared world. */
final class ClanCastles {
    private static final int HALF = 20;
    private ClanCastles() {}

    static void started(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        Path marker = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("animeoncraft-clan-castles-v1.done");
        if (Files.isRegularFile(marker)) return;
        // A states world gets its capitals from the layout builder (PoiBuilder), not from the clan spawn points.
        if (WorldLayout.active()) return;
        var root = readConfig();
        ServerLevel level = server.overworld();
        for (String clan : ClanCodes.fromConfig(root)) {
            var point = root.getAsJsonObject("clans").getAsJsonObject(clan);
            int x = point.get("x").getAsInt();
            int z = point.get("z").getAsInt();
            int y = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z)).getY();
            build(level, x, y, z, palette(root, clan));
            System.out.println("AOC clan castle prepared: " + clan + " center=" + x + "," + y + "," + z);
        }
        try {
            Files.writeString(marker, "AnimeOnCraft clan castles v1\n");
        } catch (IOException failure) {
            throw new IllegalStateException("Could not persist castle generation marker", failure);
        }
    }

    private static com.google.gson.JsonObject readConfig() {
        try {
            return JsonParser.parseString(Files.readString(Path.of("config/animeoncraft-clan-spawns.json"))).getAsJsonObject();
        } catch (Exception failure) {
            throw new IllegalStateException("Clan castle configuration is missing or invalid", failure);
        }
    }

    record Palette(Block wall, Block roof, Block accent, Block floor) {}

    /** Optional {@code castle_palettes.<CLAN>} block ids in the clan-world config; neutral stone otherwise. */
    private static Palette palette(com.google.gson.JsonObject root, String clan) {
        var fallback = new Palette(Blocks.STONE_BRICKS, Blocks.WHITE_CONCRETE, Blocks.WHITE_WOOL, Blocks.POLISHED_ANDESITE);
        if (!root.has("castle_palettes") || !root.getAsJsonObject("castle_palettes").has(clan)) return fallback;
        var entry = root.getAsJsonObject("castle_palettes").getAsJsonObject(clan);
        return new Palette(block(entry, "wall"), block(entry, "roof"), block(entry, "accent"), block(entry, "floor"));
    }

    private static Block block(com.google.gson.JsonObject entry, String key) {
        var id = net.minecraft.resources.ResourceLocation.parse(entry.get(key).getAsString());
        if (!net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(id)) throw new IllegalArgumentException("Unknown castle block: " + id);
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(id);
    }

    static void build(ServerLevel level, int cx, int floorY, int cz, Palette p) {
        // A compact 41x41 keep: courtyard, four towers, walls, gate and a raised central hall.
        fill(level, cx - HALF, floorY - 1, cz - HALF, cx + HALF, floorY - 1, cz + HALF, p.floor);
        for (int i = -HALF; i <= HALF; i++) {
            wall(level, cx + i, floorY, cz - HALF, 8, p.wall);
            wall(level, cx + i, floorY, cz + HALF, 8, p.wall);
            wall(level, cx - HALF, floorY, cz + i, 8, p.wall);
            wall(level, cx + HALF, floorY, cz + i, 8, p.wall);
        }
        // Leave a five-wide entrance in the south wall.
        fill(level, cx - 2, floorY, cz + HALF, cx + 2, floorY + 4, cz + HALF, Blocks.AIR);
        for (int dx : new int[] {-HALF, HALF}) for (int dz : new int[] {-HALF, HALF}) tower(level, cx + dx, floorY, cz + dz, p);
        for (int dx = -5; dx <= 5; dx++) for (int dz = -5; dz <= 5; dz++) {
            if (Math.abs(dx) == 5 || Math.abs(dz) == 5) fill(level, cx + dx, floorY, cz + dz, cx + dx, floorY + 11, cz + dz, p.wall);
            if (Math.abs(dx) == 5 || Math.abs(dz) == 5) fill(level, cx + dx, floorY + 12, cz + dz, cx + dx, floorY + 12, cz + dz, p.roof);
        }
        fill(level, cx - 3, floorY + 1, cz - 3, cx + 3, floorY + 1, cz + 3, p.floor);
        fill(level, cx - 1, floorY + 2, cz + 5, cx + 1, floorY + 4, cz + 5, Blocks.AIR);
        fill(level, cx - 1, floorY + 2, cz + HALF, cx + 1, floorY + 5, cz + HALF, p.accent);
    }

    private static void tower(ServerLevel level, int x, int y, int z, Palette p) {
        fill(level, x - 4, y, z - 4, x + 4, y + 13, z + 4, p.wall);
        fill(level, x - 2, y + 1, z - 2, x + 2, y + 12, z + 2, Blocks.AIR);
        fill(level, x - 5, y + 14, z - 5, x + 5, y + 14, z + 5, p.roof);
        fill(level, x - 3, y + 15, z - 3, x + 3, y + 15, z + 3, p.roof);
    }

    private static void wall(ServerLevel level, int x, int y, int z, int height, Block block) {
        fill(level, x, y, z, x, y + height, z, block);
    }

    private static void fill(ServerLevel level, int x1, int y1, int z1, int x2, int y2, int z2, Block block) {
        BlockPos min = new BlockPos(Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2));
        BlockPos max = new BlockPos(Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) level.setBlock(pos, block.defaultBlockState(), 3);
    }
}
