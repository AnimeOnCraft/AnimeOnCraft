package ru.animeoncraft.integration.neoforge;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** World identity metadata only; no terrain image is packaged or loaded. */
final class WorldAtlasAsset {
    static final String ROOT = "/assets/animeoncraft/atlas/";
    record Bounds(String worldId, String sha256, int x0, int z0, int width, int height) {
        Bounds {
            if (!worldId.matches("[a-z0-9][a-z0-9-]{1,63}") || !sha256.matches("[a-f0-9]{64}")
                || Math.abs((long)x0) > 30_000_000 || Math.abs((long)z0) > 30_000_000 || width < 256 || width > 60000 || height < 256 || height > 60000)
                throw new IllegalArgumentException("Invalid atlas bounds");
        }
        boolean contains(int x, int z) { return x >= x0 && z >= z0 && x < (long)x0 + width && z < (long)z0 + height; }
        boolean intersects(long x, long z, int size) { return x < (long)x0+width && z < (long)z0+height && x+size > x0 && z+size > z0; }
        boolean containsChunk(int x, int z) { return contains(x*16,z*16) && contains(x*16+15,z*16+15); }
    }
    static final Bounds BOUNDS = read();
    private WorldAtlasAsset() {}
    private static Bounds read() {
        try (var stream = WorldAtlasAsset.class.getResourceAsStream(ROOT + "manifest.json")) {
            if (stream == null) throw new IOException("Missing atlas manifest");
            var json = JsonParser.parseString(new String(stream.readNBytes(8193), StandardCharsets.UTF_8)).getAsJsonObject();
            if (json.get("format_version").getAsInt() != 1) throw new IOException("Invalid map marker manifest");
            return new Bounds(json.get("world_id").getAsString(),json.get("sha256").getAsString(),json.get("x0").getAsInt(),json.get("z0").getAsInt(),json.get("width_blocks").getAsInt(),json.get("height_blocks").getAsInt());
        } catch (IOException | RuntimeException failure) { throw new IllegalStateException("Packaged world atlas is invalid", failure); }
    }
}
