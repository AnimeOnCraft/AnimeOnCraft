package ru.animeoncraft.integration.neoforge;

import java.time.Instant;
import java.time.ZoneId;
import java.time.DayOfWeek;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

/** Stable season and server-owned catalog. XP is separate from profile/clan XP. */
final class BetaPass {
    static final String SEASON="beta-rift-1";
    static final ZoneId ZONE=ZoneId.of("Asia/Yekaterinburg");
    record Reward(String code,String name,String slot,int level){}
    record Quest(String code,String name,String unit,int metric,long daily,int xp,int weeklyXp){
        long target(boolean weekly){return daily*(weekly?7:1);}
        int reward(boolean weekly){return weekly?weeklyXp:xp;}
    }
    static final List<Reward> REWARDS=List.of(
        new Reward("BETA_SCOUT","Бета-следопыт","Титул",1),new Reward("FOX_EARS","Ушки лисы","Голова",2),
        new Reward("ONI_MASK","Маска они","Лицо",3),new Reward("RIFT_LANTERN","Фонарь разлома","Пояс",4),
        new Reward("SHARD_CROWN","Корона осколков","Голова",5),new Reward("CYAN_VISOR","Визор","Лицо",6),
        new Reward("NEON_LEGEND","Неоновая легенда","Титул",7),new Reward("STAR_CHAIN","Звёздная цепь","Пояс",8),
        new Reward("ORBIT_HALO","Орбита","Голова",9),new Reward("RIFT_MANTLE","Мантия Разлома","Спина",10));
    // Metrics: distance in cm, mob kills, crafted items, active seconds, mined blocks, fish.
    static final List<Quest> QUESTS=List.of(
        new Quest("TRAVEL","Исследователь","м",0,200000,30,100),new Quest("MOBS","Охотник","мобов",1,20,25,90),
        new Quest("CRAFT","Мастерская","предметов",2,32,20,75),new Quest("ACTIVE","Активная игра","мин",3,1800,25,100),
        new Quest("MINE","Шахтёр","блоков",4,128,20,75),new Quest("FISH","Рыболов","рыб",5,5,20,60));
    private BetaPass(){}
    static final class ActivityClock {
        private long sampled,active,remainder;
        ActivityClock(long now){sampled=now;}
        long advance(long now,boolean action){if(now<sampled){sampled=now;active=0;remainder=0;return 0;}long elapsed=Math.clamp(now-sampled,0,10_000_000_000L);sampled=now;if(action)active=now;
            if(active==0||now-active>120_000_000_000L){remainder=0;return 0;}remainder+=elapsed;long seconds=remainder/1_000_000_000L;remainder%=1_000_000_000L;return seconds;
        }
    }
    static Reward find(String code){return REWARDS.stream().filter(r->r.code().equals(code)).findFirst().orElse(null);}
    static String day(long millis){return Instant.ofEpochMilli(millis).atZone(ZONE).toLocalDate().toString();}
    static String week(long millis){return Instant.ofEpochMilli(millis).atZone(ZONE).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString();}
    static int level(int xp){return Math.min(10,Math.max(0,xp)/100);}
    static String slot(Reward r){return switch(r.slot()){case "Голова"->"HEAD";case "Лицо"->"FACE";case "Пояс"->"BELT";case "Спина"->"BACK";default->"TITLE";};}
    static boolean title(String code){return code.equals("BETA_SCOUT")||code.equals("NEON_LEGEND");}
}
