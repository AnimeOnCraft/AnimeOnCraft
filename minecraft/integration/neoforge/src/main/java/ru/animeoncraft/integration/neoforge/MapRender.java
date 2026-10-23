package ru.animeoncraft.integration.neoforge;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;

/**
 * World Design stage 6: a top-down overview of a pregenerated states world — one sample every {@code step} blocks
 * inside a circle of {@code radius} around (0, 0): map colour of the surface block, water depth and height.
 * Runs as a layout job (time budget per tick, not recorded as built) and writes
 * {@code <world>/animeoncraft-map/map-<radius>-<step>.bin}, rendered by scripts/world-design/render-map.py.
 *
 * Format (big-endian): "AOCM", int version=1, int x0, int z0, int size, int step,
 * then size*size ints (0xDDRRGGBB, DD = water depth 0..12; 0 = outside the circle), then size*size shorts (height).
 * Chunks are loaded (or generated, at the rim) one at a time and left to the normal unloading.
 * While sampling it also counts random structure starts inside and outside the POI exclusion zones (stage 5 check 3
 * on the whole pregenerated world); the totals go to the AOC_MAP_DONE line and {@code <file>.json}.
 */
final class MapRender implements LayoutJobs.Job {
    private final int radius, step, size, x0, z0, chunks0, chunkSpan;
    private final int[] colors;
    private final short[] heights;
    private int chunkIndex, lastPercent = -1, chunks, zoneChunks, zoneStarts, otherStarts;
    private final long started = System.currentTimeMillis();

    MapRender(int radius, int step) {
        this.radius = radius; this.step = step;
        this.size = (2 * radius) / step + 1;
        this.x0 = -radius; this.z0 = -radius;
        this.chunks0 = Math.floorDiv(-radius, 16);
        this.chunkSpan = Math.floorDiv(radius, 16) - chunks0 + 1;
        this.colors = new int[size * size];
        this.heights = new short[size * size];
    }
    @Override public String code() { return "map-" + radius + "-" + step; }
    @Override public boolean recorded() { return false; }
    @Override public String progress() { return code() + " " + (100L * chunkIndex / ((long) chunkSpan * chunkSpan)) + "%"; }

    @Override public boolean step(ServerLevel level) {
        long total = (long) chunkSpan * chunkSpan;
        for (int done = 0; done < 8 && chunkIndex < total; chunkIndex++) {
            int cx = chunks0 + chunkIndex % chunkSpan, cz = chunks0 + chunkIndex / chunkSpan;
            if (!touchesCircle(cx, cz) || !hasSample(cx, cz)) continue;
            sample(level, cx, cz); done++;
        }
        int percent = (int) (100L * chunkIndex / total);
        if (percent / 5 != lastPercent / 5) { lastPercent = percent; System.out.println("AOC_MAP_PROGRESS " + code() + " " + percent + "%"); }
        if (chunkIndex < total) return false;
        try {
            var file = write(level);
            System.out.println("AOC_MAP_DONE " + file + " size=" + size + " step=" + step + " seconds=" + (System.currentTimeMillis() - started) / 1000
                + " chunks=" + chunks + " zone_chunks=" + zoneChunks + " zone_starts=" + zoneStarts + " other_starts=" + otherStarts);
        } catch (IOException failure) { throw new IllegalStateException("map write failed: " + failure.getMessage(), failure); }
        return true;
    }

    private boolean touchesCircle(int cx, int cz) {
        double nx = Math.max(cx * 16, Math.min(0, cx * 16 + 15)), nz = Math.max(cz * 16, Math.min(0, cz * 16 + 15));
        return nx * nx + nz * nz <= (double) radius * radius;
    }
    /** True when one of the sample columns (x0 + i*step) falls into the chunk. */
    private boolean hasSample(int cx, int cz) {
        return firstSample(cx * 16 - x0) < 16 && firstSample(cz * 16 - z0) < 16;
    }
    private int firstSample(int offset) { return Math.floorMod(-offset, step); }

    private void sample(ServerLevel level, int cx, int cz) {
        var chunk = level.getChunk(cx, cz);
        chunks++;
        int starts = chunk.getAllStarts().size();
        if (WorldLayout.excludedChunk(cx, cz)) { zoneChunks++; zoneStarts += starts; } else otherStarts += starts;
        var pos = new BlockPos.MutableBlockPos();
        for (int lz = firstSample(cz * 16 - z0); lz < 16; lz += step) for (int lx = firstSample(cx * 16 - x0); lx < 16; lx += step) {
            int x = cx * 16 + lx, z = cz * 16 + lz;
            if ((long) x * x + (long) z * z > (long) radius * radius) continue;
            int ix = (x - x0) / step, iz = (z - z0) / step;
            if (ix < 0 || iz < 0 || ix >= size || iz >= size) continue;
            int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz) - 1;
            var state = chunk.getBlockState(pos.set(x, y, z));
            int depth = 0;
            while (!state.getFluidState().isEmpty() && depth < 12) { depth++; state = chunk.getBlockState(pos.set(x, y - depth, z)); }
            var top = chunk.getBlockState(pos.set(x, y, z));
            int color = top.getMapColor(level, pos).col & 0xFFFFFF;
            if (color == 0) color = 0x010101; // keep "inside the circle" distinguishable from 0
            colors[iz * size + ix] = (Math.min(depth, 12) << 24) | color;
            heights[iz * size + ix] = (short) y;
        }
    }

    private Path write(ServerLevel level) throws IOException {
        var directory = level.getServer().getWorldPath(LevelResource.ROOT).resolve("animeoncraft-map");
        Files.createDirectories(directory);
        var file = directory.resolve(code() + ".bin");
        var temporary = directory.resolve(code() + ".bin.pending");
        try (var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary), 1 << 20))) {
            out.writeBytes("AOCM"); out.writeInt(1); out.writeInt(x0); out.writeInt(z0); out.writeInt(size); out.writeInt(step);
            for (int value : colors) out.writeInt(value);
            for (short value : heights) out.writeShort(value);
        }
        Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        var summary = new com.google.gson.JsonObject();
        summary.addProperty("radius", radius); summary.addProperty("step", step); summary.addProperty("size", size);
        summary.addProperty("chunks", chunks); summary.addProperty("zone_chunks", zoneChunks);
        summary.addProperty("structure_starts_in_zones", zoneStarts); summary.addProperty("structure_starts_elsewhere", otherStarts);
        Files.writeString(directory.resolve(code() + ".json"), summary.toString());
        return file.toAbsolutePath();
    }
}
