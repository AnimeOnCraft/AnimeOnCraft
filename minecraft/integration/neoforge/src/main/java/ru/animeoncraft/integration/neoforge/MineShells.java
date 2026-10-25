package ru.animeoncraft.integration.neoforge;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;

/**
 * The shell of every built mine ({@link MineBuilder}): lining, floor, stairs, timber, portal, lanterns. Nobody but an
 * operator breaks it, and explosions leave it alone; the ore slots are not part of it and stay minable.
 * Loaded from {@code animeoncraft-capture/mines/*.json} the first time a block of the server is checked.
 */
final class MineShells {
    private static final Set<Long> SHELL = ConcurrentHashMap.newKeySet();
    private static volatile MinecraftServer loadedFor;
    private MineShells() {}

    private static void ensure(MinecraftServer server) {
        if (loadedFor == server) return;
        synchronized (MineShells.class) {
            if (loadedFor == server) return;
            SHELL.clear();
            for (var dir : new java.nio.file.Path[] {ResourcePits.directory(server).resolve("mines"), ResourcePits.directory(server).resolve("decor")})
            if (Files.isDirectory(dir)) try (var files = Files.list(dir)) {
                for (var file : files.filter(f -> f.getFileName().toString().endsWith(".json")).toList()) {
                    try { var mine = ResourcePits.read(file); if (mine != null) put(mine); }
                    catch (java.io.IOException | RuntimeException invalid) { System.getLogger("AnimeOnCraft").log(System.Logger.Level.WARNING, "Mine file unreadable: " + file.getFileName()); }
                }
            } catch (java.io.IOException unavailable) { System.getLogger("AnimeOnCraft").log(System.Logger.Level.WARNING, "Mine files unavailable"); }
            loadedFor = server;
        }
    }
    private static void put(JsonObject mine) {
        int x = mine.get("x").getAsInt(), z = mine.get("z").getAsInt();
        for (var value : mine.getAsJsonArray("shell")) {
            var row = value.getAsJsonArray();
            SHELL.add(BlockPos.asLong(x + row.get(0).getAsInt(), row.get(1).getAsInt(), z + row.get(2).getAsInt()));
        }
    }
    static void add(MinecraftServer server, JsonObject mine) { ensure(server); put(mine); }

    static boolean shell(ServerLevel level, BlockPos pos) {
        if (!level.dimension().equals(Level.OVERWORLD)) return false;
        ensure(level.getServer());
        return SHELL.contains(pos.asLong());
    }
    static boolean denied(LevelAccessor level, Entity entity, BlockPos pos) {
        if (!(level instanceof ServerLevel world) || !shell(world, pos)) return false;
        if (entity instanceof ServerPlayer player) {
            if (player.hasPermissions(2)) return false;
            player.displayClientMessage(Component.literal("Постройки точки не ломаются. Ресурсы добывать можно."), true);
        }
        return true;
    }
}
