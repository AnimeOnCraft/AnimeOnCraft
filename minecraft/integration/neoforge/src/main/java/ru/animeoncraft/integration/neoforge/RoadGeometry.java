package ru.animeoncraft.integration.neoforge;

import java.util.ArrayList;
import java.util.List;

/** Pure road geometry (no Minecraft types, unit-tested): centre line and smoothed heights. */
final class RoadGeometry {
    static final double SLOPE = 0.5;
    private RoadGeometry() {}

    /** Block-by-block centre line of a polyline (no duplicates). */
    static List<int[]> trace(List<int[]> points) {
        var out = new ArrayList<int[]>();
        for (int i = 0; i + 1 < points.size(); i++) {
            int[] a = points.get(i), b = points.get(i + 1);
            int steps = Math.max(Math.abs(b[0] - a[0]), Math.abs(b[1] - a[1]));
            for (int s = 0; s < steps; s++) {
                int x = (int) Math.round(a[0] + (b[0] - a[0]) * (double) s / steps), z = (int) Math.round(a[1] + (b[1] - a[1]) * (double) s / steps);
                if (out.isEmpty() || out.getLast()[0] != x || out.getLast()[1] != z) out.add(new int[] {x, z});
            }
        }
        out.add(points.getLast());
        return out;
    }

    /** Pure smoothing used by the paving pass (unit-tested): moving average, then a two-way slope limit; water runs get a deck. */
    static int[] smooth(int[] ground, boolean[] water, int seaLevel) {
        int n = ground.length;
        double[] base = new double[n];
        for (int i = 0; i < n; i++) {
            double sum = 0; int count = 0;
            for (int j = Math.max(0, i - 4); j <= Math.min(n - 1, i + 4); j++) if (!water[j]) { sum += ground[j]; count++; }
            base[i] = count == 0 ? seaLevel + 2 : sum / count;
        }
        // A bridge deck over a water run stays level at the higher bank (at least two above the sea).
        for (int i = 0; i < n; ) {
            if (!water[i]) { i++; continue; }
            int j = i; while (j < n && water[j]) j++;
            double deck = Math.max(seaLevel + 2, Math.max(i > 0 ? base[i - 1] : seaLevel + 2, j < n ? base[j] : seaLevel + 2));
            for (int k = i; k < j; k++) base[k] = deck;
            i = j;
        }
        for (int i = 1; i < n; i++) base[i] = Math.max(base[i - 1] - SLOPE, Math.min(base[i - 1] + SLOPE, base[i]));
        for (int i = n - 2; i >= 0; i--) base[i] = Math.max(base[i + 1] - SLOPE, Math.min(base[i + 1] + SLOPE, base[i]));
        int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = Math.max((int) Math.round(base[i]), water[i] ? seaLevel + 1 : Integer.MIN_VALUE); // the deck never dips into the water
        return out;
    }

}
