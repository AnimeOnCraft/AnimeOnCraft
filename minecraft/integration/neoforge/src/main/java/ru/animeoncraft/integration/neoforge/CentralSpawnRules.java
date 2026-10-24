package ru.animeoncraft.integration.neoforge;

final class CentralSpawnRules {
    static final int RADIUS = 128;
    /** Beyond Sora's outer wall the ban reaches as far past it as the old 128 did past the old wall (r110). */
    static final int RING_MARGIN = RADIUS - PoiBuilder.SORA_WALL;
    record Zone(int x, int z, int radius) {
        Zone(int x, int z) { this(x, z, RADIUS); }
        boolean contains(double px, double pz) { double dx = px - x, dz = pz - z; return dx * dx + dz * dz <= (double) radius * radius; }
    }
    static Zone fromLayout(WorldLayout.State layout) {
        if (layout == null) return null;
        var capital = layout.pois().get("core.capital");
        if (capital == null || !"CAPITAL".equals(capital.type())) return null;
        int radius = RADIUS;
        for (var poi : layout.pois().values())
            if (SoraExpansion.TYPE.equals(poi.type()) && poi.x() == capital.x() && poi.z() == capital.z()) radius = Math.max(radius, poi.radius() + RING_MARGIN);
        return new Zone(capital.x(), capital.z(), radius);
    }
}
