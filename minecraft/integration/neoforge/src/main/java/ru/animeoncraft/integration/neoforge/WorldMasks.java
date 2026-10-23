package ru.animeoncraft.integration.neoforge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.imageio.ImageIO;

/**
 * Macro rasters of the AnimeOnCraft "states" world (World Design stage 3, docs/WORLDGEN_PIPELINE.md).
 * Loaded only from the world's own datapack {@code <world>/datapacks/animeoncraft-states/aoc/}; worlds
 * without that datapack never load masks, so their generation is unchanged.
 */
final class WorldMasks {
    static final String PACK = "animeoncraft-states";
    record Raster(int width, int height, float[] values) {}
    record Profile(int index, String code, Map<String, Double> multipliers) {}
    record State(int originX, int originZ, int pixel, Map<String, Raster> masks, int[] regions, int regionWidth, int regionHeight,
                 Map<Integer, Profile> profiles, String digest) {}

    private static volatile State state;
    private WorldMasks() {}

    static boolean active() { return state != null; }
    static State state() { return state; }
    static void clear() { state = null; }

    private static State require() {
        State current = state;
        if (current == null) throw new IllegalStateException("AOC world masks are not loaded: the animeoncraft-states datapack is missing or failed to load");
        return current;
    }

    /** Bilinear sample in [0, 1]; positions outside the raster clamp to the edge. */
    static double sample(String name, int blockX, int blockZ) {
        State current = require();
        Raster raster = current.masks.get(name);
        if (raster == null) throw new IllegalStateException("Unknown AOC mask " + name);
        double fx = (blockX - current.originX) / (double) current.pixel - 0.5, fz = (blockZ - current.originZ) / (double) current.pixel - 0.5;
        int x0 = (int) Math.floor(fx), z0 = (int) Math.floor(fz);
        double tx = fx - x0, tz = fz - z0;
        double a = at(raster, x0, z0), b = at(raster, x0 + 1, z0), c = at(raster, x0, z0 + 1), d = at(raster, x0 + 1, z0 + 1);
        return (a * (1 - tx) + b * tx) * (1 - tz) + (c * (1 - tx) + d * tx) * tz;
    }

    private static double at(Raster raster, int x, int z) {
        x = Math.max(0, Math.min(raster.width - 1, x));
        z = Math.max(0, Math.min(raster.height - 1, z));
        return raster.values[z * raster.width + x];
    }

    static int regionIndex(int blockX, int blockZ) {
        State current = require();
        int x = Math.floorDiv(blockX - current.originX, current.pixel), z = Math.floorDiv(blockZ - current.originZ, current.pixel);
        x = Math.max(0, Math.min(current.regionWidth - 1, x));
        z = Math.max(0, Math.min(current.regionHeight - 1, z));
        return current.regions[z * current.regionWidth + x];
    }

    static String regionCode(int index) {
        Profile profile = require().profiles.get(index);
        return profile == null ? "unknown-" + index : profile.code;
    }

    /** Ore placement multiplier of a resource family at a block position; 1.0 when masks are inactive. */
    static double multiplier(String family, int blockX, int blockZ) {
        State current = state;
        if (current == null) return 1.0;
        Profile profile = current.profiles.get(regionIndex(blockX, blockZ));
        return profile == null ? 1.0 : profile.multipliers.getOrDefault(family, 1.0);
    }

    /** Placement attempts for a multiplier: integer part always, fractional part with that probability. */
    static int attempts(double multiplier, double roll) {
        int whole = (int) Math.floor(multiplier);
        return whole + (roll < multiplier - whole ? 1 : 0);
    }

    /** Loads masks from {@code aocDirectory}; records their digest in the world so later changes are refused. */
    static void load(Path aocDirectory, Path worldRoot) throws IOException {
        Path manifestPath = aocDirectory.resolve("masks.json");
        if (Files.size(manifestPath) > 1_048_576) throw new IOException("AOC mask manifest too large");
        JsonObject manifest = JsonParser.parseString(Files.readString(manifestPath)).getAsJsonObject();
        if (manifest.get("format_version").getAsInt() != 1) throw new IOException("Unsupported AOC mask manifest");
        int originX = manifest.get("origin_x").getAsInt(), originZ = manifest.get("origin_z").getAsInt(), pixel = manifest.get("pixel").getAsInt();
        if (pixel < 1 || pixel > 256) throw new IOException("Invalid AOC mask pixel size");
        MessageDigest sha = sha256();
        sha.update(Files.readAllBytes(manifestPath));
        var masks = new HashMap<String, Raster>();
        var names = new TreeMap<String, String>();
        for (var entry : manifest.getAsJsonObject("masks").entrySet()) names.put(entry.getKey(), entry.getValue().getAsString());
        for (var entry : names.entrySet()) {
            Path file = safe(aocDirectory, entry.getValue());
            byte[] bytes = Files.readAllBytes(file);
            sha.update(bytes);
            BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(bytes));
            if (image == null) throw new IOException("Unreadable AOC mask " + entry.getValue());
            float[] values = new float[image.getWidth() * image.getHeight()];
            for (int z = 0; z < image.getHeight(); z++) for (int x = 0; x < image.getWidth(); x++)
                values[z * image.getWidth() + x] = (image.getRaster().getSample(x, z, 0) & 0xFF) / 255f;
            masks.put(entry.getKey(), new Raster(image.getWidth(), image.getHeight(), values));
        }
        JsonObject regionInfo = manifest.getAsJsonObject("regions");
        Path regionFile = safe(aocDirectory, regionInfo.get("file").getAsString());
        byte[] regionBytes = Files.readAllBytes(regionFile);
        sha.update(regionBytes);
        BufferedImage regionImage = ImageIO.read(new java.io.ByteArrayInputStream(regionBytes));
        if (regionImage == null) throw new IOException("Unreadable AOC region raster");
        int[] regions = new int[regionImage.getWidth() * regionImage.getHeight()];
        for (int z = 0; z < regionImage.getHeight(); z++) for (int x = 0; x < regionImage.getWidth(); x++)
            regions[z * regionImage.getWidth() + x] = regionImage.getRaster().getSample(x, z, 0) & 0xFF;
        var profiles = new HashMap<Integer, Profile>();
        for (var value : regionInfo.getAsJsonArray("profiles")) {
            JsonObject row = value.getAsJsonObject();
            var multipliers = new HashMap<String, Double>();
            for (var m : row.getAsJsonObject("multipliers").entrySet()) {
                double v = m.getValue().getAsDouble();
                if (!(v >= 0 && v <= 4)) throw new IOException("AOC ore multiplier out of range");
                multipliers.put(m.getKey(), v);
            }
            int index = row.get("index").getAsInt();
            if (profiles.put(index, new Profile(index, row.get("code").getAsString(), Map.copyOf(multipliers))) != null) throw new IOException("Duplicate AOC region index");
        }
        String digest = HexFormat.of().formatHex(sha.digest());
        Path marker = worldRoot.resolve("animeoncraft-worldgen.sha256");
        if (Files.isRegularFile(marker)) {
            String recorded = Files.readString(marker).trim();
            if (!recorded.equals(digest) && !Boolean.getBoolean("animeoncraft.acceptMaskChange"))
                throw new IOException("AOC world masks changed after this world was generated (recorded " + recorded + ", found " + digest + ")");
        } else {
            Files.createDirectories(worldRoot);
            Files.writeString(marker, digest + "\n");
        }
        state = new State(originX, originZ, pixel, Map.copyOf(masks), regions, regionImage.getWidth(), regionImage.getHeight(), Map.copyOf(profiles), digest);
    }

    /** Test hook: install rasters directly. */
    static void install(State replacement) { state = replacement; }

    private static Path safe(Path directory, String name) throws IOException {
        if (!name.matches("[a-z0-9_-]{1,64}\\.png")) throw new IOException("Invalid AOC mask file name");
        Path file = directory.resolve(name).normalize();
        if (!file.getParent().equals(directory.normalize()) || Files.size(file) > 16_777_216) throw new IOException("Invalid AOC mask file");
        return file;
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    static List<String> maskNames() { return state == null ? List.of() : List.copyOf(new TreeMap<>(state.masks).keySet()); }
}
