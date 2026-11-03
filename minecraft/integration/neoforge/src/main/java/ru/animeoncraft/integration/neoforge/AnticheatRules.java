package ru.animeoncraft.integration.neoforge;

/**
 * Pure numbers of the suspicion system (no Minecraft classes, unit-tested).
 * Thresholds are deliberately generous: the server only signals the owner, it never punishes by itself.
 */
final class AnticheatRules {
    private AnticheatRules() {}
    enum Check {
        SPEED(12), FLY(15), REACH(15), FAST_BREAK(10), CLICK_RATE(10), XRAY(25);
        final int points;
        Check(int points){this.points=points;}
    }
    enum Level { WATCH, ALERT, CRITICAL }
    static final int ALERT_AT=40,CRITICAL_AT=80,MAX_SCORE=1000;
    /** Score drops by this much per minute without new violations. */
    static final double DECAY_PER_MINUTE=4;
    /** The same check of the same player is reported at most once a minute unless the level rises. */
    static final long REPORT_EVERY_MS=60_000;
    /** Seconds over the speed limit in a row before SPEED counts (dashes and spells are shorter). */
    static final int SPEED_WINDOWS=3;
    /** Ticks in the air without losing height before FLY counts (a normal jump is ~12 ticks). */
    static final int FLY_TICKS=60;
    /** Attacks inside one second before CLICK_RATE counts (the 1.9 cooldown makes this useless for honest play). */
    static final int MAX_ATTACKS_PER_SECOND=16;
    /** XRAY: look-back window and the minimum interval between two XRAY marks. */
    static final long XRAY_WINDOW_MS=30*60_000L,XRAY_REPEAT_MS=10*60_000L;

    static double decay(double score,long fromMs,long toMs){
        if(toMs<=fromMs)return score;
        return Math.max(0,score-DECAY_PER_MINUTE*(toMs-fromMs)/60_000.0);
    }
    static double add(double score,long fromMs,long nowMs,Check check){return Math.min(MAX_SCORE,decay(score,fromMs,nowMs)+check.points);}
    static Level level(double score){return score>=CRITICAL_AT?Level.CRITICAL:score>=ALERT_AT?Level.ALERT:Level.WATCH;}
    static boolean shouldReport(Level previous,Level now,Long lastForCheck,long nowMs){
        return previous==null||now.ordinal()>previous.ordinal()||lastForCheck==null||nowMs-lastForCheck>=REPORT_EVERY_MS;
    }
    /**
     * Highest honest horizontal blocks per second. Sprint-jumping is ~7.1; the movement-speed attribute
     * (0.1 = normal, includes Speed potions and modded boots) scales it, ice multiplies it. 35% + 0.5 margin for lag.
     */
    static double allowedSpeed(double movementAttribute,boolean onIce,boolean dolphinsGrace){
        double speed=7.4*Math.max(1,movementAttribute/0.1);
        if(onIce)speed*=2.6;
        if(dolphinsGrace)speed*=1.8;
        return speed*1.35+0.5;
    }
    /** Ticks an honest player needs; {@code progressPerTick} is BlockState#getDestroyProgress with the held tool. */
    static int expectedBreakTicks(float progressPerTick){
        if(!(progressPerTick>0))return Integer.MAX_VALUE;
        return (int)Math.ceil(1.0/Math.min(1f,progressPerTick));
    }
    /** Only slow blocks count; a break in less than 55% of the honest time is suspicious. */
    static boolean fastBreak(int expectedTicks,long actualTicks){
        return expectedTicks>=6&&expectedTicks!=Integer.MAX_VALUE&&actualTicks>=0&&actualTicks<Math.max(1,Math.round(expectedTicks*0.55));
    }
    /** The server allows hits up to range+3; anything beyond range+1.25 is suspicious even with lag. */
    static boolean reach(double distance,double allowedRange){return distance>allowedRange+1.25;}
    /** At least 12 diamond/emerald/debris ores and at least 4% of the stone mined in the window. */
    static boolean xray(int stone,int valuable){return valuable>=12&&valuable*100L>=stone*4L;}
    static String percent(int stone,int valuable){return stone<=0?"—":String.format(java.util.Locale.ROOT,"%.1f%%",valuable*100.0/stone);}
}
