package ru.animeoncraft.integration.neoforge;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

/** Add the launcher's destination without replacing the player's other saved servers. */
final class SavedServers {
    private SavedServers() {}

    static boolean ensure(Path directory, String host, int port) throws IOException {
        if (host == null || !host.matches("[A-Za-z0-9][A-Za-z0-9.-]{0,252}") || port < 1 || port > 65535)
            throw new IllegalArgumentException("Invalid launcher destination");
        Path file = directory.resolve("servers.dat");
        CompoundTag root = NbtIo.read(file);
        if (root == null) root = new CompoundTag();
        if (root.contains("servers") && !root.contains("servers", Tag.TAG_LIST))
            throw new IOException("Invalid saved server list");
        ListTag servers = root.getList("servers", Tag.TAG_COMPOUND);
        if (root.contains("servers") && ((ListTag)root.get("servers")).getElementType() != Tag.TAG_END
                && ((ListTag)root.get("servers")).getElementType() != Tag.TAG_COMPOUND)
            throw new IOException("Invalid saved server entries");
        String address = host + ":" + port;
        boolean found = false;
        boolean changed = false;
        for (int i = 0; i < servers.size(); i++) {
            CompoundTag server = servers.getCompound(i);
            String ip = server.getString("ip");
            if (ip.equalsIgnoreCase(address) || (port == 25565 && ip.equalsIgnoreCase(host))) {
                found = true;
                if (server.getBoolean("hidden")) {
                    server.putBoolean("hidden", false);
                    server.putString("name", "AnimeOnCraft");
                    changed = true;
                }
            }
        }
        if (!found) {
            CompoundTag server = new CompoundTag();
            server.putString("name", "AnimeOnCraft");
            server.putString("ip", address);
            server.putBoolean("hidden", false);
            servers.add(server);
            changed = true;
        }
        if (!changed) return false;
        root.put("servers", servers);
        Path temporary = Files.createTempFile(directory, "aoc-servers-", ".dat");
        try {
            NbtIo.write(root, temporary);
            if (Files.exists(file)) Files.copy(file, directory.resolve("servers.dat_old"), StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException failure) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return true;
    }
}
