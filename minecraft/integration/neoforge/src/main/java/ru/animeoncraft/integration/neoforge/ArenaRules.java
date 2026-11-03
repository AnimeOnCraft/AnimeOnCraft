package ru.animeoncraft.integration.neoforge;

/** Pure rules of the Sora PvP arena (unit-tested): rating, anti-farming and the arena geometry. */
final class ArenaRules {
    private ArenaRules() {}
    static final int START_RATING=1000,K=32,MIN_RATING=100;
    /** Fighting area radius (floor), wall radius and the two start pads on the X axis. */
    static final int FLOOR_RADIUS=11,WALL_RADIUS=13,WALL_HEIGHT=4,PAD_OFFSET=8,CLEAR_HEIGHT=8;
    static final int COUNTDOWN_SECONDS=5,MATCH_SECONDS=180;
    /** A pair of players earns rewards for at most this many wins per day (Yekaterinburg calendar); rating still moves. */
    static final int REWARDED_WINS_PER_PAIR_PER_DAY=3;

    /** Elo change for the winner (the loser loses the same amount). Always at least 1. */
    static int ratingDelta(int winner,int loser){
        double expected=1.0/(1.0+Math.pow(10,(loser-winner)/400.0));
        return Math.max(1,(int)Math.round(K*(1-expected)));
    }
    static int afterLoss(int rating,int delta){return Math.max(MIN_RATING,rating-delta);}
    /** Day key in UTC+5, the same calendar as the guild board. */
    static String day(long epochMs){return java.time.Instant.ofEpochMilli(epochMs+5*3600_000L).toString().substring(0,10);}
    /** Order-independent key of two players for the anti-farming counter. */
    static String pair(java.util.UUID a,java.util.UUID b){return a.compareTo(b)<0?a+"|"+b:b+"|"+a;}
    /** Inside the fighting area (horizontal distance from the centre; a little margin for the wall). */
    static boolean inside(double dx,double dz){return dx*dx+dz*dz<=(WALL_RADIUS-0.6)*(WALL_RADIUS-0.6);}
    enum Cell { NONE, FLOOR, PAD, WALL }
    /** What the builder puts at an offset from the centre. */
    static Cell cell(int dx,int dz){
        double d=Math.sqrt(dx*dx+dz*dz);
        if(d>WALL_RADIUS+0.5)return Cell.NONE;
        // 1.5 blocks thick so the voxel ring has no diagonal gaps.
        if(d>WALL_RADIUS-1.0)return Cell.WALL;
        if(Math.abs(dz)<=1&&Math.abs(Math.abs(dx)-PAD_OFFSET)<=1)return Cell.PAD;
        return Cell.FLOOR;
    }
}
