package ru.animeoncraft.integration.neoforge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Native GUI, with a stable logical canvas independent of the Minecraft GUI scale. */
final class ServerMenuScreen extends Screen {
    private static final int WHITE=0xfff4efff,MUTED=0xffb7a7ca,VIOLET=0xffa36aff,PANEL=0xeb120e20;
    private static final net.minecraft.resources.ResourceLocation MENU_FONT=net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("animeoncraft","menu");
    private static final net.minecraft.resources.ResourceLocation HEADING_FONT=net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("animeoncraft","menu_heading");
    private record Hit(double x,double y,double w,double h,Runnable action){boolean contains(double px,double py){return px>=x&&px<x+w&&py>=y&&py<y+h;}}
    private final ServerMenuLogic logic=new ServerMenuLogic();private final List<Hit> hits=new ArrayList<>();
    private double scale=1,left,top,mx,my;private String selected="";private int clanPage,rowOffset,guideTab;private boolean ownOnly;private int profileTab,clanTab,betaTab,betaSelected;private boolean weeklyQuests;private String wardrobeSlot="HEAD";private float previewAngle=145;
    ServerMenuScreen(){super(Component.literal("AnimeOnCraft — меню сервера"));}
    ServerMenuLogic.Page page(){return logic.page();}
    int scrollOffset(){return rowOffset;}
    String selectedClan(){return selected;}
    String wardrobeSlot(){return wardrobeSlot;}
    @Override protected void init(){layout();ClientServerMenu.request();ClientProfiles.request("-");}
    private void layout(){scale=Math.min(width/1100.0,height/620.0);left=(width-1100*scale)/2;top=(height-620*scale)/2;}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void tick(){if(minecraft.player==null||minecraft.getConnection()==null){onClose();return;}if(logic.closesOnRelease()&&!ClientServerMenu.held())onClose();}
    @Override public boolean keyReleased(int key,int scan,int mods){if(minecraft.options.keyPlayerList.matches(key,scan)){if(logic.closesOnRelease())onClose();return true;}return super.keyReleased(key,scan,mods);}
    @Override public boolean keyPressed(int key,int scan,int mods){if(key==GLFW.GLFW_KEY_ESCAPE){onClose();return true;}if(minecraft.options.keyPlayerList.matches(key,scan))return true;return super.keyPressed(key,scan,mods);}
    @Override public void onClose(){minecraft.options.keyPlayerList.setDown(false);super.onClose();}
    @Override public boolean mouseClicked(double x,double y,int button){
        if(button!=0)return false;double px=(x-left)/scale,py=(y-top)/scale;boolean main=page()==ServerMenuLogic.Page.WHEEL;
        var sector=ServerMenuLogic.sector(px,py,main?510:224,315,main?195:150);
        if(page()!=ServerMenuLogic.Page.BETA_PASS&&page()!=ServerMenuLogic.Page.WARDROBE&&sector!=null&&logic.select(sector,button,ClientServerMenu.held())){rowOffset=0;triggerImmediateNarration(true);return true;}
        for(int i=hits.size()-1;i>=0;i--)if(hits.get(i).contains(px,py)){hits.get(i).action().run();return true;}return false;
    }
    @Override public boolean mouseScrolled(double x,double y,double dx,double dy){if(dy==0)return false;if(page()==ServerMenuLogic.Page.WARDROBE&&wardrobeSlot.equals("BACK")){var d=ClientWardrobe.data();rowOffset=ServerMenuLogic.scroll(rowOffset,dy>0?-1:1,1+(d!=null&&d.available()?d.capes().size():0),3);return true;}if(page()==ServerMenuLogic.Page.PROFILE&&profileTab>0){rowOffset=ServerMenuLogic.scroll(rowOffset,dy>0?-1:1,ProfileProgression.ALL.size(),4);return true;}if(page()==ServerMenuLogic.Page.CLANS||page()==ServerMenuLogic.Page.ONLINE){rowOffset=ServerMenuLogic.scroll(rowOffset,dy>0?-1:1,page()==ServerMenuLogic.Page.CLANS?(clanTab==0?members().size():clanAchievementCount()):online().size(),page()==ServerMenuLogic.Page.CLANS?4:7);return true;}return false;}
    @Override public Component getNarrationMessage(){return Component.literal("Меню сервера. "+label(page())+". ЛКМ — выбрать. Escape — закрыть.");}
    @Override public void render(GuiGraphics g,int mouseX,int mouseY,float delta){
        layout();mx=(mouseX-left)/scale;my=(mouseY-top)/scale;hits.clear();renderBlurredBackground(delta);g.fill(0,0,width,height,0x68060010);
        g.pose().pushPose();g.pose().translate(left,top,0);g.pose().scale((float)scale,(float)scale,1);
        double brandEnd=32+minecraft.font.width(styled("AnimeOn",19))*19/9.0;text(g,"AnimeOn",32,25,19,WHITE);text(g,"Craft",brandEnd,25,19,VIOLET);int separator=(int)(brandEnd+minecraft.font.width(styled("Craft",19))*19/9.0+18);g.fill(separator,24,separator+1,46,0xff665178);text(g,"МЕНЮ СЕРВЕРА",separator+16,29,12,MUTED);button(g,976,21,94,29,"ESC  Закрыть",this::onClose,false);
        boolean main=page()==ServerMenuLogic.Page.WHEEL;if(page()!=ServerMenuLogic.Page.BETA_PASS&&page()!=ServerMenuLogic.Page.WARDROBE)wheel(g,main?510:224,315,main?195:150,main);else betaPreview(g);
        if(main){String count="В сети: "+ClientServerMenu.online().size();var data=ClientServerMenu.data();if(data!=null&&data.maximum()!=Integer.MAX_VALUE)count+=" / "+data.maximum();badge(g,444,81,135,28,count,0x71e5a7);
            var hover=ServerMenuLogic.sector(mx,my,510,315,195);var preview=hover==null?ServerMenuLogic.Page.CLANS:hover;
            round(g,762,189,305,176,10,PANEL,0xff624580);icon(g,preview,783,210,27,WHITE);text(g,label(preview),824,214,19,WHITE);
            String description=switch(preview){case CLANS->"Состав кланов, онлайн участников, уровень и репутация.";case ONLINE->"Игроки в сети, их кланы и пинг. Фильтр по своему клану.";case PROFILE->"Время в игре, личный уровень, достижения и выбор титула.";case BETA_PASS->"10 уровней, ежедневные и недельные задания. Косметические награды бета-теста.";case WARDROBE->"Аксессуары на голову, лицо и пояс, плащи и переливающиеся титулы.";case GUIDE->"Развитие базы, технологии, магия, экспедиции и общение.";default->"Территории, клановые спавны, войны и захват точек.";};paragraph(g,description,782,258,263,13,MUTED,5);
            center(g,logic.closesOnRelease()?"Удерживай "+ClientControls.label("key.playerlist")+" · выбери раздел · нажми ЛКМ":"Выбери раздел и нажми ЛКМ · Esc — закрыть",550,542,13,WHITE);
        }else{button(g,129,492,191,32,"← Назад к кругу",()->{logic.back();rowOffset=0;},false);round(g,435,88,630,472,10,PANEL,0xff8d56ba);switch(page()){case CLANS->clans(g);case ONLINE->players(g);case GUIDE->guide(g,false);case RULES->guide(g,true);case PROFILE->profile(g);case BETA_PASS->betaPass(g);case WARDROBE->wardrobe(g);default->{}}}
        g.pose().popPose();
    }
    private static String label(ServerMenuLogic.Page p){return switch(p){case WHEEL->"Меню";case CLANS->"КЛАНЫ";case ONLINE->"ОНЛАЙН";case GUIDE->"ГАЙД";case RULES->"ПРАВИЛА";case PROFILE->"ПРОФИЛЬ";case BETA_PASS->"БЕТА-ПАСС";case WARDROBE->"ГАРДЕРОБ";};}
    private void wheel(GuiGraphics g,int cx,int cy,int r,boolean main){
        var hover=ServerMenuLogic.sector(mx,my,cx,cy,r);
        for(int y=-r;y<=r;y++){
            ServerMenuLogic.Page previous=null;int run=-r;
            for(int x=-r;x<=r+1;x++){
                var p=x>r?null:ServerMenuLogic.sector(cx+x,cy+y,cx,cy,r);
                if(p==ServerMenuLogic.Page.PROFILE)p=null;
                if(p!=previous){if(previous!=null){boolean active=previous==hover||!main&&previous==page();g.fill(cx+run,cy+y,cx+x,cy+y+1,active?0xf07635ad:0xe4131023);}run=x;previous=p;}
            }
        }
        circle(g,cx,cy,(int)(r*.345),0xe8100c1d,0xff75518c);center(g,"ПРОФИЛЬ",cx,cy-(main?12:5),main?13:15,WHITE);if(main)center(g,"ЛКМ — открыть",cx,cy+12,9,MUTED);
        var pages=List.of(ServerMenuLogic.Page.BETA_PASS,ServerMenuLogic.Page.GUIDE,ServerMenuLogic.Page.RULES,ServerMenuLogic.Page.WARDROBE,ServerMenuLogic.Page.CLANS,ServerMenuLogic.Page.ONLINE);
        for(int i=0;i<pages.size();i++){var p=pages.get(i);double a=i*Math.PI/3;int x=cx+(int)(Math.cos(a)*r*.68),y=cy+(int)(Math.sin(a)*r*.68);icon(g,p,x-11,y-22,22,WHITE);center(g,label(p),x,y+10,main?11:9,WHITE);}
    }

    private void clans(GuiGraphics g){
        text(g,"Кланы сервера",455,108,20,WHITE);text(g,"Участники, онлайн и прогресс",455,135,11,MUTED);button(g,799,106,112,28,"Состав",()->{clanTab=0;rowOffset=0;},clanTab==0);button(g,922,106,122,28,"Достижения",()->{clanTab=1;rowOffset=0;},clanTab==1);var data=ClientServerMenu.data();if(data==null){paragraph(g,ClientServerMenu.status(),455,212,570,15,MUTED,5);return;}if(data.clans().isEmpty()){text(g,"Нет доступных кланов",455,213,15,MUTED);return;}
        if(selected.isEmpty()||data.clans().stream().noneMatch(c->c.code().equals(selected))){selected=data.clans().stream().filter(c->c.code().equals(ClientServerMenu.ownClan())).findFirst().orElse(data.clans().getFirst()).code();clanPage=Math.max(0,indexOf(data,selected)/5);}clanPage=Math.clamp(clanPage,0,(data.clans().size()-1)/5);
        for(int i=clanPage*5;i<Math.min(data.clans().size(),clanPage*5+5);i++){var c=data.clans().get(i);button(g,455+(i%5)*108,158,100,28,c.code(),()->{selected=c.code();rowOffset=0;},selected.equals(c.code()),0xff000000|c.color());}
        if(data.clans().size()>5){button(g,997,158,21,28,"‹",()->clanPage=Math.max(0,clanPage-1),false);button(g,1023,158,21,28,"›",()->clanPage=Math.min((data.clans().size()-1)/5,clanPage+1),false);}
        var clan=data.clans().stream().filter(c->c.code().equals(selected)).findFirst().orElseThrow();text(g,clan.code(),455,204,24,0xff000000|clan.color());long online=members().stream().filter(m->info(m.uuid())!=null).count();badge(g,842,201,203,28,online+" в сети / "+clan.memberCount(),clan.color());
        stat(g,455,244,"УРОВЕНЬ",String.valueOf(clan.level()));stat(g,655,244,"РЕПУТАЦИЯ",String.valueOf(clan.reputation()));stat(g,855,244,"ТОЧЕК",String.valueOf(clan.points()));text(g,"Опыт клана",455,306,11,MUTED);right(g,clan.level()>=100?"Максимальный уровень":clan.xp()+" / "+clan.nextXp()+" XP",1045,306,11,MUTED);round(g,455,326,590,6,3,0xff302540,0xff302540);g.fill(457,327,457+(int)(586*clan.progress()),331,VIOLET);
        if(clanTab==1){clanAchievements(g);return;}
        text(g,"Участники",455,348,15,WHITE);var rows=members();rowOffset=ServerMenuLogic.scroll(rowOffset,0,rows.size(),4);
        for(int i=rowOffset;i<Math.min(rows.size(),rowOffset+4);i++){var m=rows.get(i);int y=375+(i-rowOffset)*35;round(g,455,y,580,31,5,0x771b1428,0xff3e304d);face(g,m.uuid(),465,y+5,21);text(g,m.name(),497,y+10,12,WHITE);text(g,switch(m.role()){case "OWNER"->"Владелец";case "MANAGER"->"Менеджер";default->"Участник";},718,y+10,10,MUTED);boolean live=info(m.uuid())!=null;right(g,live?"● В сети":"● Не в сети",1022,y+10,11,live?0xff72e2a1:MUTED);}
        scrollbar(g,1040,375,136,rows.size(),4);text(g,rows.isEmpty()?"Участников пока нет":"Показано "+Math.min(rows.size(),rowOffset+4)+" из "+rows.size()+" · Колесо мыши — прокрутка",455,533,10,MUTED);
    }
    private int clanAchievementCount(){var data=ClientClanAchievements.data();return data==null?0:data.definitions().size();}
    private void clanAchievements(GuiGraphics g){
        var data=ClientClanAchievements.data();if(data==null){paragraph(g,"Загружаем достижения. Если они не появляются, серверная часть ещё не обновлена.",455,365,570,13,MUTED,4);return;}
        var rows=data.clans().getOrDefault(selected,List.of());long unlocked=rows.stream().filter(ClanAchievementPayload.Progress::unlocked).count();text(g,"Достижения: "+unlocked+" / "+data.definitions().size(),455,348,14,WHITE);
        rowOffset=ServerMenuLogic.scroll(rowOffset,0,data.definitions().size(),4);
        for(int i=rowOffset;i<Math.min(data.definitions().size(),rowOffset+4);i++){
            var def=data.definitions().get(i);var progress=rows.stream().filter(a->a.code().equals(def.code())).findFirst().orElse(new ClanAchievementPayload.Progress(def.code(),0,false));int y=374+(i-rowOffset)*37;
            round(g,455,y,580,33,5,0x771b1428,0xff3e304d);text(g,def.name(),465,y+6,12,progress.unlocked()?0xff73dfa1:WHITE);text(g,progress.value()+" / "+def.target()+" "+def.unit(),465,y+21,9,MUTED);right(g,progress.unlocked()?"✓ Получено":"+"+def.xp()+" XP · +"+def.reputation()+" реп.",1022,y+11,10,progress.unlocked()?0xff73dfa1:MUTED);
        }
        scrollbar(g,1040,374,145,data.definitions().size(),4);text(g,"Награды клану начисляются один раз · Колесо мыши — прокрутка",455,533,10,MUTED);
    }
    private void profile(GuiGraphics g){
        text(g,"Профиль игрока",455,108,20,WHITE);
        for(int i=0;i<3;i++){final int tab=i;button(g,455+i*151,151,140,28,List.of("Обзор","Достижения","Титулы").get(i),()->{profileTab=tab;rowOffset=0;},profileTab==i);}
        var data=ClientProfiles.profile();
        if(data==null){paragraph(g,ClientProfiles.supported()?"Загружаем статистику с сервера…":"Для профиля нужно обновление серверной части.",455,220,570,14,MUTED,4);return;}
        var earned=java.util.Set.copyOf(data.earned());var metrics=data.metrics();long xp=ProfileProgression.xp(metrics,earned);int level=ProfileProgression.level(xp);
        if(profileTab==0){
            face(g,data.player(),455,198,40);text(g,minecraft.player.getGameProfile().getName(),508,200,19,WHITE);
            var title=ProfileProgression.find(data.selected());var betaTitle=ClientBetaPass.title(data.player());text(g,betaTitle!=null?betaTitle.getString():title==null?"Без титула":title.title(),508,226,12,betaTitle!=null?0xff82dfed:title==null?MUTED:0xff000000|title.color());
            right(g,"Уровень "+level,1045,205,19,VIOLET);text(g,"ОПЫТ ПРОФИЛЯ",455,258,11,MUTED);
            long next=(long)level*level*100,start=ProfileProgression.floor(level);right(g,level>=100?"Максимум":xp+" / "+next+" XP",1045,258,11,MUTED);
            round(g,455,278,590,6,3,0xff302540,0xff302540);g.fill(457,279,457+(int)(586*(level>=100?1:Math.clamp((double)(xp-start)/(next-start),0,1))),283,VIOLET);
            String time=(metrics.seconds()/3600)+" ч "+(metrics.seconds()/60%60)+" мин";
            String[][] values={{"НА СЕРВЕРЕ",time},{"УБИЙСТВ ИГРОКОВ",String.valueOf(metrics.kills())},{"СМЕРТЕЙ",String.valueOf(metrics.deaths())},{"МОБОВ ПОБЕЖДЕНО",String.valueOf(metrics.mobs())},{"БЛОКОВ ДОБЫТО",String.valueOf(metrics.mined())},{"ПРЕДМЕТОВ СОЗДАНО",String.valueOf(metrics.crafted())},{"ПРОЙДЕНО",String.format(java.util.Locale.ROOT,"%.1f км",metrics.centimeters()/100000.0)},{"БОССОВ ПОБЕЖДЕНО",String.valueOf(metrics.bosses())},{"ДОСТИЖЕНИЙ",earned.size()+" / "+ProfileProgression.ALL.size()}};
            for(int i=0;i<values.length;i++)stat(g,455+(i%3)*200,304+(i/3)*61,values[i][0],values[i][1]);
            paragraph(g,"1 XP за минуту на сервере + разовые награды достижений. Уровень профиля не даёт боевого преимущества.",455,501,585,11,MUTED,3);
        }else{
            if(profileTab==2){button(g,714,194,151,27,"Титулы бета-пасса",()->{wardrobeSlot="TITLE";logic.select(ServerMenuLogic.Page.WARDROBE,0,true);},false);}if(profileTab==2)button(g,878,194,166,27,data.selected().isEmpty()?"Без титула ✓":"Снять титул",()->ClientProfiles.request(""),data.selected().isEmpty());
            text(g,profileTab==1?"Получено: "+earned.size()+" / "+ProfileProgression.ALL.size():"Заработанные титулы",455,204,12,MUTED);
            rowOffset=ServerMenuLogic.scroll(rowOffset,0,ProfileProgression.ALL.size(),4);
            for(int i=rowOffset;i<Math.min(rowOffset+4,ProfileProgression.ALL.size());i++){
                var a=ProfileProgression.ALL.get(i);int y=235+(i-rowOffset)*69;boolean unlocked=earned.contains(a.code());
                round(g,455,y,580,62,5,0x771b1428,0xff3e304d);text(g,profileTab==1?a.name():a.title(),468,y+9,13,unlocked?0xff000000|a.color():MUTED);
                paragraph(g,a.condition(),468,y+29,profileTab==1?450:410,10,MUTED,2);
                if(profileTab==1)right(g,unlocked?"✓ +"+a.xp()+" XP":Math.min(metrics.value(a.metric()),a.target())+" / "+a.target(),1022,y+10,10,unlocked?0xff73dfa1:MUTED);
                else if(unlocked)button(g,928,y+18,94,27,data.selected().equals(a.code())?"Выбран ✓":"Выбрать",()->ClientProfiles.request(a.code()),data.selected().equals(a.code()));
                else right(g,"Закрыт",1020,y+12,10,MUTED);
            }
            scrollbar(g,1040,235,269,ProfileProgression.ALL.size(),4);text(g,"Колесо мыши — прокрутка · Титулы выдаёт только сервер",455,533,10,MUTED);
        }
    }
    private void betaPreview(GuiGraphics g){
        round(g,65,113,330,360,10,PANEL,0xff65497c);center(g,"ТВОЙ ПЕРСОНАЖ",230,129,13,WHITE);
        if(minecraft.player!=null){
            g.pose().pushPose();g.pose().translate(230,432,170);g.pose().scale(135,-135,135);g.pose().mulPose(com.mojang.math.Axis.XP.rotationDegrees(-8));g.pose().mulPose(com.mojang.math.Axis.YP.rotationDegrees(previewAngle));
            var dispatcher=minecraft.getEntityRenderDispatcher();var orientation=new org.joml.Quaternionf(dispatcher.cameraOrientation());dispatcher.setRenderShadow(false);
            try{dispatcher.render(minecraft.player,0,0,0,0,0,g.pose(),g.bufferSource(),net.minecraft.client.renderer.LightTexture.FULL_BRIGHT);g.flush();}finally{dispatcher.setRenderShadow(true);dispatcher.overrideCameraOrientation(orientation);g.pose().popPose();}
        }
        button(g,93,440,122,25,"↶ Повернуть",()->previewAngle-=45,false);button(g,234,440,130,25,"Повернуть ↷",()->previewAngle+=45,false);
        center(g,"Показаны надетые аксессуары",230,475,10,MUTED);
    }
    private void betaPass(GuiGraphics g){
        text(g,"Бета-пасс: Разлом",455,108,20,WHITE);var data=ClientBetaPass.data();
        button(g,830,104,97,27,"Награды",()->betaTab=0,betaTab==0);button(g,939,104,105,27,"Задания",()->betaTab=1,betaTab==1);
        if(data==null){paragraph(g,ClientBetaPass.supported()?"Загружаем пропуск…":"Бета-пасс ещё не установлен на этом сервере.",455,200,560,14,MUTED,4);return;}
        text(g,"БЕСПЛАТНО · Уровень "+BetaPass.level(data.xp())+" / 10",455,147,12,WHITE);right(g,data.xp()+" / 1000 XP",1044,147,12,VIOLET);
        round(g,455,171,590,7,3,0xff302540,0xff302540);g.fill(457,172,457+586*data.xp()/1000,177,VIOLET);
        if(betaTab==1){betaQuests(g,data);return;}
        for(int i=0;i<10;i++){final int index=i;var r=BetaPass.REWARDS.get(i);int x=455+(i%5)*119,y=197+(i/5)*139;boolean owned=(data.rewards()&(1<<i))!=0,ready=BetaPass.level(data.xp())>=i+1;
            round(g,x,y,112,130,6,0xe51c142d,i==9?0xffbc9258:betaSelected==i?VIOLET:0xff493458);hits.add(new Hit(x,y,112,103,()->betaSelected=index));
            text(g,String.valueOf(i+1),x+8,y+8,13,i==9?0xffffd18d:WHITE);BetaRewardIcons.draw(g,r.code(),x+30,y+10,52);
            paragraph(g,r.name(),x+7,y+64,99,11,WHITE,2);text(g,r.slot(),x+7,y+94,9,MUTED);
            if(owned)center(g,"✓ Получено",x+56,y+112,10,0xff73dfa1);
            else if(ready&&data.open())button(g,x+6,y+104,100,21,"Забрать",()->ClientBetaPass.request("REWARD",index,"",""),true);
            else center(g,data.open()?"Уровень "+(i+1):"Закрыто",x+56,y+112,10,MUTED);
        }
        var selectedReward=BetaPass.REWARDS.get(betaSelected);text(g,selectedReward.name()+" · "+selectedReward.slot(),455,487,12,WHITE);
        text(g,data.open()?"Полученная косметика остаётся после беты":"Бета-пасс закрыт · полученная косметика сохранена",455,509,10,MUTED);
        button(g,897,523,146,25,"Открыть гардероб",()->logic.select(ServerMenuLogic.Page.WARDROBE,0,true),false);
    }
    private void betaQuests(GuiGraphics g,BetaPassPayloads.Snapshot data){
        button(g,455,194,143,27,"Ежедневные",()->weeklyQuests=false,!weeklyQuests);button(g,608,194,143,27,"Недельные",()->weeklyQuests=true,weeklyQuests);
        String period=weeklyQuests?data.week():data.day();text(g,"Период: "+period+" · сброс 00:00 ЕКБ"+(weeklyQuests?" по Пн":""),455,233,10,MUTED);
        for(int i=0;i<6;i++){final int index=i;var q=BetaPass.QUESTS.get(i);int x=455+(i%2)*301,y=255+(i/2)*84;long progress=(weeklyQuests?data.weekly():data.daily()).get(i),target=q.target(weeklyQuests);boolean claimed=(((weeklyQuests?data.weekClaims():data.dayClaims())>>i)&1)!=0;
            round(g,x,y,289,76,5,0x991d152c,claimed?0xff4d8064:0xff48335d);text(g,q.name(),x+9,y+8,12,WHITE);
            long divisor=i==0?100:i==3?60:1;text(g,(progress/divisor)+" / "+(target/divisor)+" "+q.unit(),x+9,y+29,10,MUTED);
            round(g,x+9,y+48,155,5,2,0xff302540,0xff302540);g.fill(x+10,y+49,x+10+(int)(153*progress/target),y+52,VIOLET);
            text(g,"+"+q.reward(weeklyQuests)+" XP",x+9,y+62,9,VIOLET);
            if(claimed)right(g,"✓ Получено",x+279,y+32,10,0xff73dfa1);
            else if(progress>=target&&data.open())button(g,x+188,y+27,91,25,"Забрать",()->ClientBetaPass.request(weeklyQuests?"WEEKLY":"DAILY",index,"",""),true);
        }
        var player=net.minecraft.client.Minecraft.getInstance().player;
        boolean creative=player!=null&&(player.isCreative()||player.isSpectator());
        text(g,creative?"Задания не засчитываются в творческом режиме и наблюдении":"Без AFK · задание и награда проверяются сервером",455,518,10,creative?0xffffbd70:MUTED);
        text(g,data.open()?"Прогресс пропуска не связан с уровнем профиля":"Пропуск закрыт: получение XP приостановлено",455,537,10,MUTED);
    }
    private void wardrobe(GuiGraphics g){
        text(g,"Гардероб",455,108,20,WHITE);var data=ClientBetaPass.data();
        String[] slots={"HEAD","FACE","BELT","BACK","TITLE","EMOTES"},names={"Голова","Лицо","Пояс","Спина","Титул","Эмоции"};
        for(int i=0;i<slots.length;i++){final String slot=slots[i];button(g,455+i*99,153,91,28,names[i],()->{wardrobeSlot=slot;rowOffset=0;},wardrobeSlot.equals(slot));}
        if(wardrobeSlot.equals("EMOTES")){emotes(g);return;}
        if(data==null){paragraph(g,"Загружаем полученную косметику…",455,200,550,14,MUTED,4);return;}
        if(wardrobeSlot.equals("BACK")){backWardrobe(g,data);return;}
        var rows=BetaPass.REWARDS.stream().filter(r->BetaPass.slot(r).equals(wardrobeSlot)).toList();
        for(int i=0;i<rows.size();i++){var r=rows.get(i);int y=208+i*91;boolean owned=(data.rewards()&(1<<(r.level()-1)))!=0,selected=r.code().equals(data.equipped().get(wardrobeSlot));
            round(g,455,y,590,80,6,0xb51d152c,selected?VIOLET:0xff48335d);BetaRewardIcons.draw(g,r.code(),468,y+16,48);
            text(g,r.name(),522,y+15,14,owned?WHITE:MUTED);text(g,"Награда "+r.level()+" уровня",522,y+39,10,MUTED);
            if(BetaPass.title(r.code())){g.pose().pushPose();g.pose().translate(522,y+57,0);g.drawString(minecraft.font,ClientBetaPass.shimmer(r.code(),System.currentTimeMillis()),0,0,WHITE,false);g.pose().popPose();}
            if(owned)button(g,925,y+23,103,29,selected?"Снять":"Надеть",()->ClientBetaPass.request("EQUIP",0,wardrobeSlot,selected?"":r.code()),selected);
            else right(g,"Закрыто",1028,y+31,11,MUTED);
        }
        text(g,"По одному аксессуару каждого типа · Титул общий с профилем",455,510,10,MUTED);
        text(g,"Мантия скрывается при надетых элитрах",455,529,10,MUTED);
    }
    private void emotes(GuiGraphics g){
        var inventory=ClientEmotes.inventory();
        if(!ClientEmotes.supported()){paragraph(g,"Эмоции появятся после установки обновления на сервер.",455,218,560,14,MUTED,4);return;}
        if(inventory==null||!inventory.available()){paragraph(g,"Получаем эмоции аккаунта… Если сервис недоступен, попробуй позже.",455,218,560,14,MUTED,4);return;}
        for(int i=0;i<Emotes.ALL.size();i++){var entry=Emotes.ALL.get(i);int y=195+i*57;boolean owned=inventory.owned().contains(entry.code()),selected=ClientEmotes.current().equals(entry.code());round(g,455,y,590,50,6,0xb51d152c,selected?VIOLET:0xff48335d);
            String symbol=entry.code().equals("NEON_DANCE")?"♪":Emotes.paired(entry.code())?"↔":"✦";center(g,symbol,483,y+13,22,VIOLET);text(g,entry.name(),507,y+9,13,owned?WHITE:MUTED);text(g,Emotes.paired(entry.code())?"Совместная · по приглашению":"Стартовая · есть у каждого",507,y+30,9,MUTED);
            button(g,843,y+10,70,29,"Смотреть",()->minecraft.setScreen(new EmoteMenuScreen(entry.code(),true)),false);
            if(owned){button(g,799,y+10,34,29,ClientEmoteFavorites.contains(entry.code())?"★":"☆",()->ClientEmoteFavorites.toggle(entry.code()),ClientEmoteFavorites.contains(entry.code()));button(g,925,y+10,103,29,selected?"Стоп":Emotes.paired(entry.code())?"Пригласить":"Исполнить",()->ClientEmotes.choose(selected?"":entry.code()),selected);}else right(g,"Закрыто",1028,y+20,11,MUTED);
        }
        text(g,"Шаг, прыжок, удар или урон прекращают эмоцию",455,493,11,MUTED);button(g,455,519,300,25,"Избранное · меню "+ClientControls.label("key.animeoncraft.emotes"),()->minecraft.setScreen(new EmoteMenuScreen()),false);
    }
    private void backWardrobe(GuiGraphics g,BetaPassPayloads.Snapshot beta){
        var d=ClientWardrobe.data();boolean available=d!=null&&d.available();
        boolean mantle=available?d.kind().equals("BETA"):beta.equipped().containsKey("BACK");
        button(g,885,185,159,20,"Снять со спины",()->{if(available)ClientWardrobe.choose("NONE","");else if(!minecraft.hasSingleplayerServer())ClientWardrobe.read();else ClientBetaPass.request("EQUIP",0,"BACK","");},available?d.kind().equals("NONE"):!mantle);
        int total=1+(available?d.capes().size():0);rowOffset=ServerMenuLogic.scroll(rowOffset,0,total,3);
        for(int i=rowOffset;i<Math.min(total,rowOffset+3);i++){
            int y=208+(i-rowOffset)*91;boolean isMantle=i==0;var cape=isMantle?null:d.capes().get(i-1);
            boolean owned=!isMantle||(beta.rewards()&512)!=0,selected=isMantle?mantle:d.kind().equals("CAPE")&&d.code().equals(cape.id());
            round(g,455,y,590,80,6,0xb51d152c,selected?VIOLET:0xff48335d);
            if(isMantle)BetaRewardIcons.draw(g,"RIFT_MANTLE",468,y+16,48);
            else {round(g,479,y+15,29,44,3,0xff57308a,0xffc596ff);g.fill(485,y+24,502,y+27,0xff99e4f6);g.fill(485,y+32,502,y+35,0xff99e4f6);}
            text(g,isMantle?"Мантия Разлома":cape.name(),522,y+15,14,owned?WHITE:MUTED);
            text(g,isMantle?"Бета-пасс · награда 10 уровня":"Плащ из гардероба аккаунта",522,y+39,10,MUTED);
            if(owned&&(available||minecraft.hasSingleplayerServer()))button(g,925,y+23,103,29,selected?"Снять":"Надеть",()->{if(available)ClientWardrobe.choose(selected?"NONE":isMantle?"BETA":"CAPE",selected?"":isMantle?"RIFT_MANTLE":cape.id());else ClientBetaPass.request("EQUIP",0,"BACK",selected?"":"RIFT_MANTLE");},selected);
            else right(g,owned?"Нет связи":"Закрыто",1028,y+31,11,MUTED);
        }
        scrollbar(g,1040,208,262,total,3);
        text(g,available?"Плащ или мантия · выбор сохраняется в аккаунте":"Плащи доступны при подключении к основному серверу",455,510,10,MUTED);
        text(g,"Мантия скрывается при надетых элитрах · Колесо мыши — прокрутка",455,529,10,MUTED);
    }
    private static int indexOf(ServerMenuData data,String code){for(int i=0;i<data.clans().size();i++)if(data.clans().get(i).code().equals(code))return i;return 0;}
    private List<ServerMenuData.Member> members(){var d=ClientServerMenu.data();return d==null?List.of():d.members().stream().filter(m->m.clan().equals(selected)).sorted(Comparator.<ServerMenuData.Member,Boolean>comparing(m->info(m.uuid())==null).thenComparing(ServerMenuData.Member::name,String.CASE_INSENSITIVE_ORDER)).toList();}
    private List<PlayerInfo> online(){String own=ClientServerMenu.ownClan();return ClientServerMenu.online().stream().filter(p->!ownOnly||!own.isEmpty()&&own.equals(ClientServerMenu.clan(p.getProfile().getId()))).toList();}
    private void players(GuiGraphics g){
        text(g,"Игроки в сети",455,108,20,WHITE);var d=ClientServerMenu.data();String count=String.valueOf(ClientServerMenu.online().size());if(d!=null&&d.maximum()!=Integer.MAX_VALUE)count+=" / "+d.maximum();badge(g,914,101,131,28,count,0x71e5a7);button(g,455,155,112,29,"Все "+ClientServerMenu.online().size(),()->{ownOnly=false;rowOffset=0;},!ownOnly);String own=ClientServerMenu.ownClan();long n=ClientServerMenu.online().stream().filter(p->!own.isEmpty()&&own.equals(ClientServerMenu.clan(p.getProfile().getId()))).count();button(g,579,155,140,29,"Мой клан "+n,()->{ownOnly=true;rowOffset=0;},ownOnly);
        text(g,"Игрок",465,207,11,MUTED);text(g,"Клан",787,207,11,MUTED);text(g,"Пинг",978,207,11,MUTED);g.fill(455,225,1045,226,0xff51405e);var rows=online();rowOffset=ServerMenuLogic.scroll(rowOffset,0,rows.size(),7);
        for(int i=rowOffset;i<Math.min(rows.size(),rowOffset+7);i++){var p=rows.get(i);int y=232+(i-rowOffset)*40;face(g,p.getProfile().getId(),465,y+5,24);text(g,p.getProfile().getName(),503,y+13,13,WHITE);String clan=ClientServerMenu.clan(p.getProfile().getId());text(g,clan.isEmpty()?"—":clan,787,y+13,12,0xff000000|ClientServerMenu.color(clan));int ping=p.getLatency();right(g,ping<0?"—":ping+" мс",1028,y+13,12,ping<0?MUTED:ping<100?0xff73dfa1:ping<200?0xffffc66d:0xffff8896);g.fill(455,y+37,1036,y+38,0xff382c47);}
        if(rows.isEmpty())text(g,ownOnly?"Соклановцев в сети нет":"Список игроков пока пуст",465,253,14,MUTED);scrollbar(g,1040,233,277,rows.size(),7);text(g,"Онлайн обновляется автоматически · Колесо мыши — прокрутка",455,535,10,MUTED);
    }
    private void guide(GuiGraphics g,boolean rules){
        text(g,rules?"Территории и правила":"Что делать на сервере",455,108,20,WHITE);text(g,rules?"Основные игровые ограничения":"Развитие, экспедиции и общение",455,135,11,MUTED);if(!rules)for(int i=0;i<4;i++){final int index=i;button(g,455+i*129,157,117,29,List.of("Главное","Развитие","Чат","Торговля").get(i),()->guideTab=index,guideTab==i);}
        if(rules){
            var point=ClientServerMenu.capture();round(g,455,157,590,76,7,0x991d152c,0xff6e4897);
            paragraph(g,point==null?"Загружаем текущую точку…":point.name(),467,166,365,13,WHITE,1);
            paragraph(g,point==null?"Состояние точки появится после ответа сервера.":point.detail(),467,187,point!=null&&point.canDeclare()?365:564,11,MUTED,3);
            if(point!=null&&point.canDeclare())button(g,851,177,180,30,"Объявить / продлить",()->{var player=Minecraft.getInstance().player;if(player!=null){Minecraft.getInstance().setScreen(null);player.connection.sendCommand("aoc capture menu-declare");}},false);
        }
        var cards=rules?ServerMenuGuide.RULES:guideTab==0?ServerMenuGuide.START:guideTab==1?ServerMenuGuide.DEVELOPMENT:guideTab==2?ServerMenuGuide.CHAT:ServerMenuGuide.ECONOMY;boolean compact=!rules&&guideTab==0;int start=rules?244:205,h=compact?94:rules?134:143;
        for(int i=0;i<cards.size();i++){var c=cards.get(i);int x=455+(i%2)*302,y=start+(i/2)*(h+13);round(g,x,y,288,h,7,0x991d152c,0xff48335d);text(g,c.title(),x+13,y+14,14,WHITE);paragraph(g,ClientControls.guide(c.body()),x+13,y+40,262,11,MUTED,compact?3:7);}
        if(compact){text(g,"КАК ОБЩАТЬСЯ",455,427,12,WHITE);String[][] chat={{"Без префикса","Чат рядом · до 100 блоков"},{"!","Глобальный чат"},{"!!","Чат клана"}};for(int i=0;i<chat.length;i++){int y=449+i*25;badge(g,455,y,110,21,chat[i][0],0xbba4db);text(g,chat[i][1],578,y+6,11,MUTED);}}
        text(g,rules?"Ограничения и доступность захвата проверяются сервером.":"Карта: "+ClientControls.label("gui.xaero_open_map")+" · Голос и микрофон: "+ClientControls.label("key.voice_chat")+" · Клавиши: Настройки → Управление",455,539,10,MUTED);
    }
    private void scrollbar(GuiGraphics g,int x,int y,int h,int total,int visible){if(total<=visible)return;round(g,x,y,4,h,2,0xff352940,0xff352940);int size=Math.max(12,h*visible/total),pos=(h-size)*rowOffset/(total-visible);round(g,x,y+pos,4,size,2,0xffac91c9,0xffac91c9);}
    private static PlayerInfo info(UUID id){var c=Minecraft.getInstance().getConnection();return c==null?null:c.getListedOnlinePlayers().stream().filter(p->p.getProfile().getId().equals(id)).findFirst().orElse(null);}
    private static void face(GuiGraphics g,UUID id,int x,int y,int size){var p=info(id);var skin=p==null?DefaultPlayerSkin.get(id):ClientSkins.appearance(id,p.getSkin());g.pose().pushPose();g.pose().translate(x,y,0);g.pose().scale(size/8f,size/8f,1);g.blit(skin.texture(),0,0,8f,8f,8,8,64,64);g.blit(skin.texture(),0,0,40f,8f,8,8,64,64);g.pose().popPose();}
    private static void stat(GuiGraphics g,int x,int y,String title,String value){round(g,x,y,190,48,6,0x901d142c,0xff48335d);text(g,title,x+12,y+9,10,MUTED);text(g,value,x+12,y+25,18,WHITE);}
    private void button(GuiGraphics g,int x,int y,int w,int h,String title,Runnable action,boolean active){button(g,x,y,w,h,title,action,active,VIOLET);}
    private void button(GuiGraphics g,int x,int y,int w,int h,String title,Runnable action,boolean active,int accent){boolean over=mx>=x&&mx<x+w&&my>=y&&my<y+h;round(g,x,y,w,h,5,active?0xf06027ac:over?0xec39234e:0xd6191326,active?accent:0xff674b7e);center(g,title,x+w/2,y+(h-11)/2,11,active?WHITE:accent);hits.add(new Hit(x,y,w,h,action));}
    private static void badge(GuiGraphics g,int x,int y,int w,int h,String value,int color){round(g,x,y,w,h,5,0xc41c132b,0xff55406b);center(g,value,x+w/2,y+(h-11)/2,11,0xff000000|color);}
    private static Component styled(String value,double size){return Component.literal(value).withStyle(s->s.withFont(size>=14?HEADING_FONT:MENU_FONT));}
    private static void text(GuiGraphics g,String value,double x,double y,double size,int color){g.pose().pushPose();g.pose().translate(x,y,0);float s=(float)(size/9);g.pose().scale(s,s,1);g.drawString(Minecraft.getInstance().font,styled(value,size),0,0,color,false);g.pose().popPose();}
    private static void center(GuiGraphics g,String v,int cx,int y,int size,int color){text(g,v,cx-Minecraft.getInstance().font.width(styled(v,size))*size/18.0,y,size,color);}
    private static void right(GuiGraphics g,String v,int x,int y,int size,int color){text(g,v,x-Minecraft.getInstance().font.width(styled(v,size))*size/9.0,y,size,color);}
    private static void paragraph(GuiGraphics g,String v,int x,int y,int w,int size,int color,int max){float s=size/9f;g.pose().pushPose();g.pose().translate(x,y,0);g.pose().scale(s,s,1);var lines=Minecraft.getInstance().font.split(styled(v,size),(int)(w/s));for(int i=0;i<Math.min(max,lines.size());i++)g.drawString(Minecraft.getInstance().font,lines.get(i),0,i*12,color,false);g.pose().popPose();}
    private static void round(GuiGraphics g,int x,int y,int w,int h,int r,int color,int border){for(int row=0;row<h;row++){int dy=row<r?r-row-1:row>=h-r?row-(h-r):0,cut=dy==0?0:r-(int)Math.sqrt(r*r-dy*dy);if(row==0||row==h-1)g.fill(x+cut,y+row,x+w-cut,y+row+1,border);else{g.fill(x+cut,y+row,x+cut+1,y+row+1,border);g.fill(x+w-cut-1,y+row,x+w-cut,y+row+1,border);if(w-cut*2>2)g.fill(x+cut+1,y+row,x+w-cut-1,y+row+1,color);}}}
    private static void circle(GuiGraphics g,int cx,int cy,int r,int color,int border){for(int y=-r;y<=r;y++){int x=(int)Math.sqrt(r*r-y*y);if(x>2&&Math.abs(y)<r-1){g.fill(cx-x,cy+y,cx-x+2,cy+y+1,border);g.fill(cx+x-2,cy+y,cx+x,cy+y+1,border);g.fill(cx-x+2,cy+y,cx+x-2,cy+y+1,color);}else g.fill(cx-x,cy+y,cx+x,cy+y+1,border);}}
    private static void icon(GuiGraphics g,ServerMenuLogic.Page p,int x,int y,int size,int color){g.pose().pushPose();g.pose().translate(x,y,0);g.pose().scale(size/24f,size/24f,1);switch(p){case CLANS->{for(int row=0;row<23;row++){int edge=row<12?0:(row-11)/2;g.fill(2+edge,row,22-edge,row+1,color);if(row>2&&row<19)g.fill(5+edge,row,19-edge,row+1,0xff7132bf);}}case ONLINE->{round(g,3,0,8,8,4,color,color);round(g,14,3,7,7,3,color,color);round(g,0,11,14,12,5,color,color);round(g,14,13,10,10,4,color,color);}case GUIDE->{g.fill(1,2,11,22,color);g.fill(13,2,23,22,color);g.fill(11,5,13,24,color);g.fill(4,5,9,18,0xff49305f);g.fill(16,5,21,18,0xff49305f);}case BETA_PASS->{g.fill(3,4,21,22,color);g.fill(1,1,23,7,color);g.fill(10,1,14,22,0xff7132bf);}case WARDROBE->{g.fill(5,4,19,8,color);g.fill(2,8,7,22,color);g.fill(17,8,22,22,color);g.fill(7,10,17,21,color);}case PROFILE->{round(g,7,0,10,10,5,color,color);round(g,2,12,20,12,5,color,color);}case RULES->{g.fill(3,1,20,23,color);g.fill(1,1,22,5,color);for(int yy=8;yy<20;yy+=4)g.fill(6,yy,17,yy+1,0xff49305f);}default->{}}g.pose().popPose();}
}
