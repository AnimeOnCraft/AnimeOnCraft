package ru.animeoncraft.integration.neoforge;

/** Pure model-space motion, radians. Smooth entry/exit is applied by the renderer. */
final class EmoteMotion {
    record Part(float x,float y,float z){}
    record Frame(Part head,Part body,Part rightArm,Part leftArm,Part rightLeg,Part leftLeg,float lift){}
    private static Part p(double x,double y,double z){return new Part((float)x,(float)y,(float)z);}
    private EmoteMotion(){}
    static Frame frame(String code,float ticks){
        double beat=ticks*Math.PI/10,step=Math.sin(beat),sway=Math.sin(beat/2);
        return switch(code){
            case "NEON_DANCE" -> new Frame(p(-.08,Math.sin(beat/2)*.18,0),p(0,sway*.18,step*.10),p(-.9-.65*step,.1,-.65-.3*sway),p(-.9+.65*step,-.1,.65-.3*sway),p(.32*step,0,-.08),p(-.32*step,0,.08),(float)(-.5-.5*Math.cos(beat*2)));
            case "HERO_POSE" -> new Frame(p(-.12,-.2,0),p(0,-.12,-.04),p(-2.65,-.15,-.27),p(-.7,.3,.95),p(-.08,.1,-.13),p(.08,-.1,.13),0);
            case "ZEN_POSE" -> new Frame(p(.1,0,0),p(0,0,0),p(-.9,0,-.5),p(-.9,0,.5),p(-1.4,.35,-.65),p(-1.4,-.35,.65),(float)(-2+Math.sin(ticks*.06)*.35));
            case "HIGH_FIVE" -> new Frame(p(-.12,0,0),p(0,-.08,0),p(-2.05-.16*Math.sin(Math.min(ticks,45)*Math.PI/45),-.12,-.5),p(-.15,0,.1),p(0,0,-.04),p(0,0,.04),0);
            case "HANDSHAKE" -> new Frame(p(.05,0,0),p(.05,-.08,0),p(-1.18+.10*Math.sin(ticks*.35),-.12,-.46),p(-.1,0,.08),p(0,0,-.04),p(0,0,.04),0);
            case "HANAMI" -> { double sip=Math.max(0,Math.sin(ticks*.045)-.6)/.4;
                yield new Frame(p(-.32+.06*Math.sin(ticks*.05)+.25*sip,.28*Math.sin(ticks*.017),.05*Math.sin(ticks*.03)),p(-.05,0,0),p(-1.05-.75*sip,-.35-.2*sip,.1),p(-.55,.1,.15),p(-1.45,.22,-.08),p(-1.45,-.22,.08),10); }
            case "VICTORY_V" -> { double hop=ticks<10?Math.sin(Math.PI*ticks/10):0,bob=Math.sin(ticks*.25)*.04;
                yield new Frame(p(-.05,-.15,.18+bob),p(0,-.1,bob*.5),p(-2.45,-.25,.32),p(-.7,.3,.95),p(0,0,.08),p(-.08,-.1,-.05),(float)(-2.5*hop)); }
            case "NINJA_HOP" -> keys(ticks,
                k(0,neutral),
                k(12,new Frame(p(.15,0,0),p(.45,0,0),p(.8,0,-.2),p(.8,0,.2),p(-.6,0,-.05),p(.3,0,.05),2)),
                k(24,new Frame(p(-.2,0,0),p(.3,0,0),p(1.25,0,-.35),p(1.25,0,.35),p(-1.2,0,-.05),p(-.45,0,.05),-9)),
                k(36,new Frame(p(-.25,0,0),p(.35,0,0),p(1.0,0,-.3),p(1.0,0,.3),p(-1.1,0,-.05),p(.5,0,.05),3)),
                k(44,new Frame(p(-.25,-.2,0),p(.35,0,0),p(-1.6,.35,.1),p(.6,0,.2),p(-1.1,0,-.05),p(.5,0,.05),3)),
                k(80,new Frame(p(-.25,-.2,0),p(.35,0,0),p(-1.6,.35,.1),p(.6,0,.2),p(-1.1,0,-.05),p(.5,0,.05),3)));
            case "BOW_DUEL" -> keys(ticks,
                k(0,neutral),
                k(18,new Frame(p(.55,0,0),p(.3,0,0),p(.3,0,-.05),p(.3,0,.05),p(0,0,-.03),p(0,0,.03),0)),
                k(38,new Frame(p(.55,0,0),p(.3,0,0),p(.3,0,-.05),p(.3,0,.05),p(0,0,-.03),p(0,0,.03),0)),
                k(55,new Frame(p(-.05,-.2,0),p(0,.2,0),p(-.7,.45,.35),p(-.35,.1,.05),p(-.25,0,-.1),p(.25,0,.1),.5f)),
                k(100,new Frame(p(-.05,-.2,0),p(0,.2,0),p(-.7,.45,.35),p(-.35,.1,.05),p(-.25,0,-.1),p(.25,0,.1),.5f)),
                k(106,new Frame(p(-.05,.1,0),p(0,-.25,0),p(-1.9,-.4,-.6),p(-.35,.1,.05),p(-.35,0,-.1),p(.3,0,.1),.5f)),
                k(113,new Frame(p(-.05,.15,0),p(0,-.3,0),p(-1.2,-.9,-.9),p(-.35,.1,.05),p(-.35,0,-.1),p(.3,0,.1),.5f)),
                k(160,new Frame(p(-.05,.15,0),p(0,-.3,0),p(-1.2,-.9,-.9),p(-.35,.1,.05),p(-.35,0,-.1),p(.3,0,.1),.5f)));
            case "PETAL_STORM" -> { double rise=Math.clamp(ticks/24.0,0,1),e=rise*rise*(3-2*rise),wave=Math.sin(ticks*.1);
                yield new Frame(p(-.35*e,.12*Math.sin(ticks*.04),0),p(0,.15*Math.sin(ticks*.05)*e,0),p(-.15*e,0,(-2.2-.2*wave)*e),p(-.15*e,0,(2.2+.2*wave)*e),p(.15*e,0,.1*e),p(-.1*e,0,-.1*e),(float)((-2-1.5*Math.sin(ticks*.1))*e)); }
            default -> throw new IllegalArgumentException("Unknown emote motion");
        };
    }
    private static final Frame neutral=new Frame(p(0,0,0),p(0,0,0),p(0,0,0),p(0,0,0),p(0,0,0),p(0,0,0),0);
    private record Key(float at,Frame frame){}
    private static Key k(float at,Frame frame){return new Key(at,frame);}
    /** Smoothstep between key poses; past the last key the pose holds. */
    private static Frame keys(float ticks,Key... keys){
        for(int i=1;i<keys.length;i++)if(ticks<=keys[i].at()){var a=keys[i-1];var b=keys[i];float t=Math.clamp((ticks-a.at())/(b.at()-a.at()),0,1);return mix(a.frame(),b.frame(),t*t*(3-2*t));}
        return keys[keys.length-1].frame();
    }
    private static Part mix(Part a,Part b,float t){return new Part(a.x()+(b.x()-a.x())*t,a.y()+(b.y()-a.y())*t,a.z()+(b.z()-a.z())*t);}
    private static Frame mix(Frame a,Frame b,float t){return new Frame(mix(a.head(),b.head(),t),mix(a.body(),b.body(),t),mix(a.rightArm(),b.rightArm(),t),mix(a.leftArm(),b.leftArm(),t),mix(a.rightLeg(),b.rightLeg(),t),mix(a.leftLeg(),b.leftLeg(),t),a.lift()+(b.lift()-a.lift())*t);}
    static float weight(float elapsed,int duration){return Math.clamp(Math.min(elapsed/6f,(duration-elapsed)/8f),0,1);}
}
