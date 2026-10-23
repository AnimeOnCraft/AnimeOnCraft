package ru.animeoncraft.integration.neoforge;

import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Local preview and favorites; only an explicit play/accept click sends a gameplay request. */
final class EmoteMenuScreen extends Screen {
    private record Hit(int x,int y,int w,int h,Runnable action){boolean contains(double a,double b){return a>=x&&a<x+w&&b>=y&&b<y+h;}}
    private final List<Hit> hits=new ArrayList<>();private boolean all;private String selected="NEON_DANCE";private long previewStart=System.nanoTime();private float angle=-25;
    private double scale,left,top,mx,my;
    EmoteMenuScreen(){super(Component.literal("AnimeOnCraft — эмоции"));}
    EmoteMenuScreen(String code,boolean catalog){this();select(code);all=catalog;}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void tick(){if(minecraft.player==null||minecraft.getConnection()==null){onClose();return;}ClientEmotes.read();}
    @Override public boolean keyPressed(int key,int scan,int modifiers){if(key==GLFW.GLFW_KEY_ESCAPE||ClientEmoteMenu.OPEN.matches(key,scan)){onClose();return true;}return super.keyPressed(key,scan,modifiers);}
    @Override public boolean mouseClicked(double x,double y,int button){if(button!=0)return false;double a=(x-left)/scale,b=(y-top)/scale;for(int i=hits.size()-1;i>=0;i--)if(hits.get(i).contains(a,b)){hits.get(i).action().run();return true;}if(!all){int slot=sector(a,b);var favorites=favorites();if(slot>=0&&slot<favorites.size()){ClientEmotes.choose(favorites.get(slot));return true;}}return false;}
    private static List<String> favorites(){var inventory=ClientEmotes.inventory();return inventory==null||!inventory.available()?List.of():ClientEmoteFavorites.all().stream().filter(inventory.owned()::contains).toList();}
    private void select(String code){if(!selected.equals(code)){selected=code;previewStart=System.nanoTime();}}
    String previewCode(){return selected;}
    boolean showsAll(){return all;}
    private static int sector(double x,double y){double dx=x-755,dy=y-317,d=dx*dx+dy*dy;if(d<52*52||d>166*166)return -1;return Math.floorMod((int)Math.floor((Math.atan2(dy,dx)+Math.PI/6)/(Math.PI/3)),6);}
    @Override public void render(GuiGraphics g,int mouseX,int mouseY,float delta){
        scale=Math.min(width/1100d,height/620d);left=(width-1100*scale)/2;top=(height-620*scale)/2;mx=(mouseX-left)/scale;my=(mouseY-top)/scale;hits.clear();renderBlurredBackground(delta);g.fill(0,0,width,height,0x95060010);g.pose().pushPose();g.pose().translate(left,top,0);g.pose().scale((float)scale,(float)scale,1);
        text(g,"ЭМОЦИИ",65,37,22,0xfff7efff);text(g,"AnimeOnCraft · "+ClientControls.label("key.animeoncraft.emotes"),65,67,11,0xffb9a6d0);button(g,956,34,89,29,"Закрыть",this::onClose,false);
        panel(g,65,108,320,376);center(g,"ПРЕДПРОСМОТР",225,125,12,0xffcdb0f2);
        var inventory=ClientEmotes.inventory();boolean available=inventory!=null&&inventory.available();button(g,435,105,145,30,"Избранное",()->all=false,!all);button(g,592,105,145,30,"Все эмоции",()->all=true,all);
        if(all){for(int i=0;i<Emotes.ALL.size();i++){var entry=Emotes.ALL.get(i);int y=151+i*65;boolean owned=available&&inventory.owned().contains(entry.code());panel(g,435,y,610,58);button(g,446,y+9,323,39,entry.name()+(Emotes.paired(entry.code())?" · вдвоём":""),()->select(entry.code()),entry.code().equals(selected));if(owned){button(g,786,y+10,40,36,ClientEmoteFavorites.contains(entry.code())?"★":"☆",()->ClientEmoteFavorites.toggle(entry.code()),ClientEmoteFavorites.contains(entry.code()));button(g,840,y+10,192,36,Emotes.paired(entry.code())?"Пригласить":"Исполнить",()->ClientEmotes.choose(entry.code()),false);}else text(g,available?"Закрыто":"Загрузка…",852,y+24,12,0xffb9a6d0);}}
        else{
            var codes=favorites();int hovered=sector(mx,my);if(hovered>=0&&hovered<codes.size())select(codes.get(hovered));
            for(int y=-166;y<=166;y++){int from=-166,prior=-2;for(int x=-166;x<=167;x++){int current=x==167?-2:sector(755+x,317+y);if(current!=prior){if(prior>=0)g.fill(755+from,317+y,755+x,318+y,prior==hovered?0xf07536ab:0xea21152f);from=x;prior=current;}}}
            for(int i=0;i<6;i++){double a=i*Math.PI/3;int x=755+(int)(Math.cos(a)*114),y=317+(int)(Math.sin(a)*114);var e=i<codes.size()?Emotes.find(codes.get(i)):null;center(g,e==null?"+":e.name(),x,y-5,10,e==null?0xff756181:0xfff7efff);}
            button(g,711,295,88,43,"Стоп",()->ClientEmotes.choose(""),!ClientEmotes.current().isEmpty());center(g,"Наведи для просмотра · ЛКМ — исполнить",755,496,12,0xffcdb0f2);
            if(codes.isEmpty())center(g,available?"Добавь эмоции звёздочкой во вкладке «Все эмоции»":"Загружаем эмоции аккаунта…",755,170,12,0xffcdb0f2);
        }
        var entry=Emotes.find(selected);if(entry!=null){renderPreview(g,entry);center(g,entry.name(),225,424,15,0xfff7efff);button(g,90,447,115,25,"Влево",()->angle-=45,false);button(g,245,447,115,25,"Вправо",()->angle+=45,false);}
        var invite=ClientEmotes.invitation();if(invite!=null){panel(g,65,527,980,63);text(g,invite.name()+" предлагает «"+Emotes.find(invite.code()).name()+"»",80,540,13,0xfff7efff);text(g,"Принимай только стоя лицом к напарнику",80,562,10,0xffcdb0f2);button(g,723,540,145,34,"Принять",()->ClientEmotes.answer(true),true);button(g,881,540,149,34,"Отклонить",()->ClientEmotes.answer(false),false);}
        else{text(g,"☆ — до 6 избранных · Выбор сохраняется отдельно для каждого аккаунта",65,540,11,0xffcdb0f2);text(g,"Совместные: лицом друг к другу, 1–1,8 блока · Движение или урон прекращают эмоцию",65,563,11,0xffb9a6d0);}
        g.pose().popPose();
    }
    private void renderPreview(GuiGraphics g,Emotes.Entry entry){if(minecraft.player==null)return;float ticks=10+(System.nanoTime()-previewStart)/50_000_000f%Math.max(1,entry.ticks()-20);ClientEmotes.preview(entry.code(),ticks,()->{
        var p=minecraft.player;float body=p.yBodyRot,bodyOld=p.yBodyRotO,head=p.yHeadRot,headOld=p.yHeadRotO,yaw=p.getYRot(),oldYaw=p.yRotO;p.yBodyRot=p.yBodyRotO=p.yHeadRot=p.yHeadRotO=0;p.setYRot(0);p.yRotO=0;
        g.pose().pushPose();g.pose().translate(225,404,170);g.pose().scale(112,-112,112);g.pose().mulPose(com.mojang.math.Axis.XP.rotationDegrees(-8));g.pose().mulPose(com.mojang.math.Axis.YP.rotationDegrees(angle));var dispatcher=minecraft.getEntityRenderDispatcher();var orientation=new org.joml.Quaternionf(dispatcher.cameraOrientation());dispatcher.setRenderShadow(false);
        try{dispatcher.render(p,0,0,0,0,0,g.pose(),g.bufferSource(),net.minecraft.client.renderer.LightTexture.FULL_BRIGHT);g.flush();}finally{p.yBodyRot=body;p.yBodyRotO=bodyOld;p.yHeadRot=head;p.yHeadRotO=headOld;p.setYRot(yaw);p.yRotO=oldYaw;dispatcher.setRenderShadow(true);dispatcher.overrideCameraOrientation(orientation);g.pose().popPose();}
    });}
    private void button(GuiGraphics g,int x,int y,int w,int h,String title,Runnable action,boolean active){boolean hover=mx>=x&&mx<x+w&&my>=y&&my<y+h;g.fill(x,y,x+w,y+h,active?0xef7131b0:hover?0xf044285f:0xf0281b38);center(g,title,x+w/2,y+(h-11)/2,11,0xfff2e8ff);hits.add(new Hit(x,y,w,h,action));}
    private static void panel(GuiGraphics g,int x,int y,int w,int h){g.fill(x-1,y-1,x+w+1,y+h+1,0xff684783);g.fill(x,y,x+w,y+h,0xf1130d20);}
    private static void text(GuiGraphics g,String value,double x,double y,int size,int color){g.pose().pushPose();g.pose().translate(x,y,0);g.pose().scale(size/9f,size/9f,1);g.drawString(Minecraft.getInstance().font,Component.literal(value).withStyle(s->s.withFont(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("animeoncraft","menu"))),0,0,color,false);g.pose().popPose();}
    private static void center(GuiGraphics g,String value,int x,int y,int size,int color){var font=Minecraft.getInstance().font;var c=Component.literal(value).withStyle(s->s.withFont(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("animeoncraft","menu")));text(g,value,x-font.width(c)*size/18d,y,size,color);}
}
