package ru.animeoncraft.integration.neoforge;

/** Pure limits shared by invitation validation and tests. No forced movement/rotation. */
final class EmotePairRules {
    static final int INVITE_TICKS=200,COOLDOWN_TICKS=60;
    private EmotePairRules(){}
    static boolean inRange(double squared,double height){return squared>=.81&&squared<=3.24&&Math.abs(height)<=.45;}
    static boolean facing(double dot){return dot>=.80;}
    static boolean expired(int now,int deadline){return now>=deadline;}
}
