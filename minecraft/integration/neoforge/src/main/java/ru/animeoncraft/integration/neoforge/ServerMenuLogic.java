package ru.animeoncraft.integration.neoforge;

/** Input and hit-testing are independent of Minecraft rendering and GUI scale. */
final class ServerMenuLogic {
    enum Page { WHEEL,CLANS,ONLINE,GUIDE,RULES,PROFILE,BETA_PASS,WARDROBE }
    private Page page=Page.WHEEL;private boolean heldWheel=true;
    Page page(){return page;}
    boolean closesOnRelease(){return page==Page.WHEEL&&heldWheel;}
    boolean select(Page value,int button,boolean held){if(button!=0||value==null||value==Page.WHEEL||heldWheel&&!held)return false;page=value;heldWheel=false;return true;}
    void back(){page=Page.WHEEL;heldWheel=false;}
    static Page sector(double x,double y,double cx,double cy,double radius){
        double dx=x-cx,dy=y-cy,d=Math.hypot(dx,dy);
        if(d<radius*.31)return Page.PROFILE;
        if(d<radius*.38||d>radius)return null;
        double angle=(Math.toDegrees(Math.atan2(dy,dx))+390)%360;
        if(Math.min(angle%60,60-angle%60)*Math.PI/180*d<3)return null;
        return switch((int)(angle/60)){case 0->Page.BETA_PASS;case 1->Page.GUIDE;case 2->Page.RULES;case 3->Page.WARDROBE;case 4->Page.CLANS;default->Page.ONLINE;};
    }
    static int scroll(int offset,int delta,int total,int visible){return Math.clamp(offset+delta,0,Math.max(0,total-visible));}
}
