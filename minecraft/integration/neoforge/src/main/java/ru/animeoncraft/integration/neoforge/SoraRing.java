package ru.animeoncraft.integration.neoforge;

import java.util.ArrayList;
import java.util.List;

/**
 * Geometry of Sora's outer ring (pure, unit-tested): what each column between the old wall (r110) and the new wall
 * becomes. Columns inside {@link #START} are never touched, so the old city, its wall, towers and the clan markets stay.
 */
final class SoraRing {
    enum Cell { NONE, LAWN, STREET, WALL, SKIRT }
    static final int OLD_WALL = PoiBuilder.SORA_WALL, START = OLD_WALL + 5, STREET_START = OLD_WALL + 3;
    static final int RING_STREET = 124, SKIRT = 16, GATE_HALF = 5, TOWER_OFFSET = 7;
    static final double STREET_HALF = 2.5, WALL_HALF = 0.75;
    final int x, z, radius; final List<Double> bearings;

    SoraRing(int x, int z, int radius, List<Double> bearings) {
        if (radius < RING_STREET + 20 || radius > 240) throw new IllegalArgumentException("Sora ring radius " + radius);
        this.x = x; this.z = z; this.radius = radius; this.bearings = List.copyOf(bearings);
    }
    /** Bearings of the main roads (the old gates), measured clockwise from north like the old wall. */
    static SoraRing of(WorldLayout.State layout, WorldLayout.Poi poi) {
        var bearings = new ArrayList<Double>();
        for (var road : layout.roads().values()) if (road.code().startsWith("road.main.")) {
            var first = road.points().getFirst();
            bearings.add(Math.atan2(first[0] - poi.x(), -(first[1] - poi.z())));
        }
        return new SoraRing(poi.x(), poi.z(), poi.radius(), bearings);
    }
    int reach() { return radius + 3 + SKIRT; }
    /** Distance along and across the street of {@code bearing} for an offset from the centre. */
    static double along(double dx, double dz, double bearing) { return dx * Math.sin(bearing) - dz * Math.cos(bearing); }
    static double across(double dx, double dz, double bearing) { return dx * Math.cos(bearing) + dz * Math.sin(bearing); }

    Cell cell(int dx, int dz) {
        double d = Math.hypot(dx, dz);
        if (d < STREET_START || d > reach()) return Cell.NONE;
        boolean street = false, gate = false;
        for (double b : bearings) {
            if (along(dx, dz, b) <= 0) continue;
            double off = Math.abs(across(dx, dz, b));
            street |= off < STREET_HALF; gate |= off < GATE_HALF;
        }
        if (d > radius + 3) return street ? Cell.STREET : Cell.SKIRT;
        if (Math.abs(d - radius) < WALL_HALF + 1.5 && gate) return Cell.STREET;
        if (Math.abs(d - radius) < WALL_HALF) return Cell.WALL;
        if (street) return Cell.STREET;
        if (d < START) return Cell.NONE;
        return Math.abs(d - RING_STREET) < STREET_HALF ? Cell.STREET : Cell.LAWN;
    }
    /** On the corridor of a main road (paving, rails and bridge decks of width 5 plus rails). */
    boolean onRoad(int dx, int dz) {
        for (double b : bearings) if (along(dx, dz, b) > 0 && Math.abs(across(dx, dz, b)) < 4.5) return true;
        return false;
    }
    /** Crenellation pattern along the wall: every other block of arc. */
    boolean crenel(int dx, int dz) { return Math.floorMod((int) Math.floor(Math.atan2(dx, -dz) * radius), 2) == 0; }
    /** Gate towers on both sides of every gate, as on the old wall. */
    List<int[]> towers() {
        var list = new ArrayList<int[]>();
        for (double b : bearings) for (int side : new int[] {-1, 1})
            list.add(new int[] {x + (int) Math.round(radius * Math.sin(b) + side * TOWER_OFFSET * Math.cos(b)), z + (int) Math.round(-radius * Math.cos(b) + side * TOWER_OFFSET * Math.sin(b))});
        return list;
    }
    /** Lantern posts beside the streets in the ring and along the ring street, clear of every street. */
    List<int[]> lanterns() {
        var list = new ArrayList<int[]>();
        for (double b : bearings) for (int r = START + 6; r < radius - 6; r += 14) for (int side : new int[] {-1, 1})
            list.add(new int[] {x + (int) Math.round(r * Math.sin(b) + side * 3.5 * Math.cos(b)), z + (int) Math.round(-r * Math.cos(b) + side * 3.5 * Math.sin(b))});
        int r = RING_STREET + 4, count = (int) Math.round(2 * Math.PI * r / 20);
        for (int i = 0; i < count; i++) {
            double a = 2 * Math.PI * i / count;
            int px = x + (int) Math.round(r * Math.sin(a)), pz = z - (int) Math.round(r * Math.cos(a));
            if (cell(px - x, pz - z) == Cell.LAWN && bearings.stream().noneMatch(b -> Math.abs(across(px - x, pz - z, b)) < 6 && along(px - x, pz - z, b) > 0)) list.add(new int[] {px, pz});
        }
        return list;
    }
}
