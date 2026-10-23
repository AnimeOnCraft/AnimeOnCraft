package ru.animeoncraft.integration.neoforge;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SavedServersTest {
    @TempDir Path directory;

    @Test void createsVisibleServerAndDoesNotDuplicateOnNextLaunch() throws Exception {
        assertTrue(SavedServers.ensure(directory, "193.178.158.117", 25565));
        assertFalse(SavedServers.ensure(directory, "193.178.158.117", 25565));
        ListTag servers = NbtIo.read(directory.resolve("servers.dat")).getList("servers", Tag.TAG_COMPOUND);
        assertEquals(1, servers.size());
        assertEquals("AnimeOnCraft", servers.getCompound(0).getString("name"));
        assertEquals("193.178.158.117:25565", servers.getCompound(0).getString("ip"));
        assertFalse(servers.getCompound(0).getBoolean("hidden"));
    }

    @Test void preservesOtherServersAndSettingsAndUnhidesQuickPlayEntry() throws Exception {
        CompoundTag root = new CompoundTag();
        root.putString("future_setting", "keep");
        ListTag servers = new ListTag();
        CompoundTag other = new CompoundTag();
        other.putString("name", "My other server");
        other.putString("ip", "example.com");
        other.putString("icon", "keep-icon");
        servers.add(other);
        CompoundTag target = new CompoundTag();
        target.putString("name", "My custom name");
        target.putString("ip", "193.178.158.117");
        target.putBoolean("hidden", true);
        target.putBoolean("acceptTextures", true);
        servers.add(target);
        root.put("servers", servers);
        NbtIo.write(root, directory.resolve("servers.dat"));
        byte[] original = Files.readAllBytes(directory.resolve("servers.dat"));
        assertTrue(SavedServers.ensure(directory, "193.178.158.117", 25565));
        CompoundTag saved = NbtIo.read(directory.resolve("servers.dat"));
        assertEquals("keep", saved.getString("future_setting"));
        ListTag actual = saved.getList("servers", Tag.TAG_COMPOUND);
        assertEquals(2, actual.size());
        assertEquals(other, actual.getCompound(0));
        assertEquals("AnimeOnCraft", actual.getCompound(1).getString("name"));
        assertTrue(actual.getCompound(1).getBoolean("acceptTextures"));
        assertFalse(actual.getCompound(1).getBoolean("hidden"));
        assertArrayEquals(original, Files.readAllBytes(directory.resolve("servers.dat_old")));
    }

    @Test void corruptFileRemainsUntouched() throws Exception {
        byte[] broken = new byte[]{99, 1, 2, 3};
        Files.write(directory.resolve("servers.dat"), broken);
        assertThrows(java.io.IOException.class, () -> SavedServers.ensure(directory, "127.0.0.1", 25565));
        assertArrayEquals(broken, Files.readAllBytes(directory.resolve("servers.dat")));
    }

    @Test void visibleCustomEntryIsNotRenamedOrRewritten() throws Exception {
        CompoundTag root = new CompoundTag();
        ListTag servers = new ListTag();
        CompoundTag target = new CompoundTag();
        target.putString("ip", "play.example.com");
        target.putString("name", "My custom name");
        servers.add(target);
        root.put("servers", servers);
        NbtIo.write(root, directory.resolve("servers.dat"));
        byte[] original = Files.readAllBytes(directory.resolve("servers.dat"));
        assertFalse(SavedServers.ensure(directory, "PLAY.EXAMPLE.COM", 25565));
        assertArrayEquals(original, Files.readAllBytes(directory.resolve("servers.dat")));
    }
}
