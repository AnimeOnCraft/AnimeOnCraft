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
            default -> throw new IllegalArgumentException("Unknown emote motion");
        };
    }
    static float weight(float elapsed,int duration){return Math.clamp(Math.min(elapsed/6f,(duration-elapsed)/8f),0,1);}
}
