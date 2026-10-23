package ru.animeoncraft.integration.neoforge;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * States world (World Design stage 7): clan regions as polygons for the client map (Xaero, JourneyMap).
 * Display only — the server never trusts it; rights come from {@link RegionPolicy}. Replaces the rectangle
 * {@link TerritoryPayload} in a states world.
 */
record RegionMapPayload(List<Region> regions) implements CustomPacketPayload {
    static final int MAX_VERTICES = 1024;
    record Region(String clan, String owner, int color, int[] xs, int[] zs) {
        Region {
            if (!ClanCodes.valid(clan) || !ClanCodes.valid(owner) || color < 0 || color > 0xFFFFFF) throw new IllegalArgumentException("Invalid map region");
            if (xs.length < 3 || xs.length != zs.length || xs.length > MAX_VERTICES) throw new IllegalArgumentException("Invalid map polygon");
            for (int i = 0; i < xs.length; i++) if (Math.abs(xs[i]) > 100_000 || Math.abs(zs[i]) > 100_000) throw new IllegalArgumentException("Map polygon out of range");
        }
        int minX() { int m = Integer.MAX_VALUE; for (int v : xs) m = Math.min(m, v); return m; }
        int maxX() { int m = Integer.MIN_VALUE; for (int v : xs) m = Math.max(m, v); return m; }
        int minZ() { int m = Integer.MAX_VALUE; for (int v : zs) m = Math.min(m, v); return m; }
        int maxZ() { int m = Integer.MIN_VALUE; for (int v : zs) m = Math.max(m, v); return m; }
        /** Even-odd rule at the column centre, the same as the server's {@link RegionPolicy}. */
        boolean contains(int x, int z) {
            double px = x + 0.5, pz = z + 0.5; boolean inside = false;
            for (int i = 0, j = xs.length - 1; i < xs.length; j = i++)
                if ((zs[i] > pz) != (zs[j] > pz) && px < (double) (xs[j] - xs[i]) * (pz - zs[i]) / (zs[j] - zs[i]) + xs[i]) inside = !inside;
            return inside;
        }
    }
    static final Type<RegionMapPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("animeoncraft", "region_map"));
    static final StreamCodec<FriendlyByteBuf, RegionMapPayload> CODEC = StreamCodec.of((buffer, value) -> {
        buffer.writeVarInt(value.regions().size());
        for (var region : value.regions()) {
            buffer.writeUtf(region.clan(), 12).writeUtf(region.owner(), 12);
            buffer.writeInt(region.color()).writeVarInt(region.xs().length);
            for (int i = 0; i < region.xs().length; i++) buffer.writeInt(region.xs()[i]).writeInt(region.zs()[i]);
        }
    }, buffer -> {
        int count = buffer.readVarInt();
        if (count < 0 || count > ClanCodes.MAX) throw new IllegalArgumentException("Invalid region count");
        var regions = new ArrayList<Region>();
        for (int i = 0; i < count; i++) {
            String clan = buffer.readUtf(12), owner = buffer.readUtf(12);
            int color = buffer.readInt(), n = buffer.readVarInt();
            if (n < 3 || n > MAX_VERTICES) throw new IllegalArgumentException("Invalid polygon size");
            int[] xs = new int[n], zs = new int[n];
            for (int v = 0; v < n; v++) { xs[v] = buffer.readInt(); zs[v] = buffer.readInt(); }
            regions.add(new Region(clan, owner, color, xs, zs));
        }
        return new RegionMapPayload(regions);
    });

    RegionMapPayload { regions = List.copyOf(regions); if (regions.size() > ClanCodes.MAX) throw new IllegalArgumentException("Too many regions"); }

    /** Clan regions of the policy with their current controller (war capture) and colour. */
    static RegionMapPayload of(RegionPolicy policy) {
        var list = new ArrayList<Region>();
        if (policy == null) return new RegionMapPayload(list);
        var colors = new java.util.HashMap<String, Integer>();
        for (var region : policy.regions()) if (region.clan() != null && region.color() >= 0) colors.putIfAbsent(region.clan(), region.color());
        for (var region : policy.regions()) {
            if (region.clan() == null || list.size() >= ClanCodes.MAX) continue;
            // A region taken in a war is painted in the controller's colour, like the old rectangles.
            String owner = ClanControlSync.owner(region.clan());
            int color = colors.getOrDefault(owner, region.color() < 0 ? 0x8F8A73 : region.color());
            list.add(new Region(region.clan(), owner, color, region.xs(), region.zs()));
        }
        return new RegionMapPayload(list);
    }

    @Override public Type<RegionMapPayload> type() { return TYPE; }
}
