package ru.animeoncraft.integration.neoforge;

import java.util.List;

/** Public display data only. No players, permissions or world files. */
record WorldAtlasData(String worldId, String imageSha256, List<Point> points) {
    static final int MAX_POINTS = 1024;
    record Point(String id, String name, String kind, int x, int y, int z, int radius, int color, String owner, String state) {
        Point {
            if (!id.matches("[a-z0-9][a-z0-9._-]{1,63}") || name.isBlank() || name.length() > 160
                || !kind.matches("[A-Z_]{2,32}") || !state.matches("[A-Z_]{0,32}")
                || (!owner.isEmpty() && !ClanCodes.valid(owner)) || Math.abs((long)x) > 30_000_000 || Math.abs((long)z) > 30_000_000
                || y < -64 || y > 512 || radius < 4 || radius > 256 || color < 0 || color > 0xffffff)
                throw new IllegalArgumentException("Invalid atlas point");
        }
        boolean contains(int bx, int bz) { double dx = bx + 0.5 - x, dz = bz + 0.5 - z; return dx*dx + dz*dz <= (double)radius*radius; }
        String symbol() { return switch (kind) { case "CAPITAL", "CLAN_CAPITAL" -> "C"; case "MARKET" -> "$"; case "RESOURCE_SITE" -> "R"; case "FORT" -> "F"; case "OUTPOST" -> "O"; default -> "•"; }; }
        String label() { return name + (owner.isEmpty() ? "" : " · " + owner) + switch (state) {
            case "CONTESTED", "CAPTURING" -> " · Бой";
            case "PROTECTED" -> " · Защищено";
            case "COOLDOWN" -> " · Перезарядка";
            default -> "";
        }; }
    }
    WorldAtlasData {
        if (!worldId.matches("[a-z0-9][a-z0-9-]{1,63}") || !imageSha256.matches("[a-f0-9]{64}") || points.size() > MAX_POINTS)
            throw new IllegalArgumentException("Invalid atlas");
        points = List.copyOf(points);
        if (points.stream().map(Point::id).distinct().count() != points.size()) throw new IllegalArgumentException("Duplicate atlas point");
    }
}
