package ru.animeoncraft.integration.neoforge;

/** Accelerate only the sky clock; never the simulation, machines or playtime. */
public record NightRules(boolean enabled, int speed) {
    public NightRules {
        if (speed < 1 || speed > 4) throw new IllegalArgumentException("night_speed must be 1..4");
    }

    public long extraTicks(long dayTime, boolean daylightCycle) {
        return enabled && daylightCycle && Math.floorMod(dayTime, 24000L) >= 12000L
            ? Math.min(speed - 1L, 24000L - Math.floorMod(dayTime, 24000L)) : 0L;
    }
}
