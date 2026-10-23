package ru.animeoncraft.integration.neoforge;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;

final class ClientSavedServers {
    private static boolean checked;
    private ClientSavedServers() {}

    static void tick(ClientTickEvent.Post event) {
        if (checked) return;
        checked = true;
        String host = System.getenv("AOC_GAME_TARGET_HOST");
        if (host == null || host.isBlank()) return;
        try {
            int port = Integer.parseInt(System.getenv().getOrDefault("AOC_GAME_TARGET_PORT", "0"));
            SavedServers.ensure(Minecraft.getInstance().gameDirectory.toPath(), host, port);
        } catch (java.io.IOException | IllegalArgumentException failure) {
            org.apache.logging.log4j.LogManager.getLogger("AnimeOnCraft")
                    .warn("Could not save AnimeOnCraft in the multiplayer server list", failure);
        }
    }
}
