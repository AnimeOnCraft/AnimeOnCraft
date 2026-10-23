package ru.animeoncraft.integration.neoforge;

/** The city, not the entire core region: the shared boss arena must stay outside. */
final class CentralSpawnRules {
    static final int RADIUS = 128;
    record Zone(int x, int z) {
        boolean contains(double px, double pz) {
            double dx = px - x, dz = pz - z;
            return dx * dx + dz * dz <= (double) RADIUS * RADIUS;
        }
    }
    private CentralSpawnRules() {}

    static Zone fromLayout(WorldLayout.State layout) {
        if (layout == null) return null;
        var capital = layout.pois().get("core.capital");
        return capital != null && "CAPITAL".equals(capital.type()) ? new Zone(capital.x(), capital.z()) : null;
    }
}
