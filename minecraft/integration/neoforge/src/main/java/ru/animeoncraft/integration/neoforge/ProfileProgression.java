package ru.animeoncraft.integration.neoforge;

import java.util.List;
import java.util.Set;

/** Fixed rewards, thresholds and titles. A client never supplies progress or title text. */
final class ProfileProgression {
    record Metrics(long seconds,long kills,long deaths,long mobs,long mined,long crafted,long centimeters,long bosses,long fish) {
        Metrics {for(long value:new long[]{seconds,kills,deaths,mobs,mined,crafted,centimeters,bosses,fish})if(value<0)throw new IllegalArgumentException("Negative profile statistic");}
        long value(String kind){return switch(kind){case "TIME"->seconds;case "MOBS"->mobs;case "MINED"->mined;case "CRAFTED"->crafted;case "DISTANCE"->centimeters;case "BOSSES"->bosses;case "FISH"->fish;default->throw new IllegalArgumentException("Unknown statistic");};}
    }
    record Achievement(String code,String name,String condition,String metric,long target,int xp,String title,int color){}
    static final List<Achievement> ALL=List.of(
        a("HOUR_ONE","Первый час","Провести на сервере 1 час","TIME",3600,100,"Обжившийся",0x94d5ff),
        a("HOURS_TEN","Свой человек","Провести на сервере 10 часов","TIME",36000,250,"Завсегдатай",0x94d5ff),
        a("HOURS_FIFTY","Пятьдесят часов","Провести на сервере 50 часов","TIME",180000,600,"Ветеран",0xc69cff),
        a("HOURS_HUNDRED","Часть этого мира","Провести на сервере 100 часов","TIME",360000,1000,"Хранитель мира",0xffd37d),
        a("MOBS_FIFTY","Охотник","Победить 50 мобов","MOBS",50,100,"Охотник",0xffad88),
        a("MOBS_250","Опытный охотник","Победить 250 мобов","MOBS",250,250,"Истребитель",0xffad88),
        a("MOBS_THOUSAND","Гроза чудовищ","Победить 1000 мобов","MOBS",1000,600,"Гроза чудовищ",0xff789c),
        a("MINER_FIRST","Первая шахта","Добыть 128 блоков","MINED",128,100,"Шахтёр",0xa8d9de),
        a("MINER_THOUSAND","Глубже в недра","Добыть 1024 блока","MINED",1024,250,"Рудокоп",0xa8d9de),
        a("MINER_FIVE","Покоритель недр","Добыть 5000 блоков","MINED",5000,600,"Повелитель недр",0x7fe1db),
        a("CRAFT_FIRST","Своими руками","Создать 128 предметов","CRAFTED",128,100,"Ремесленник",0xffcf8b),
        a("CRAFT_THOUSAND","Мастерская","Создать 1024 предмета","CRAFTED",1024,250,"Мастер",0xffcf8b),
        a("CRAFT_FOUR","Большое производство","Создать 4096 предметов","CRAFTED",4096,600,"Мастер гильдии",0xffd761),
        a("TRAVEL_FIVE","За горизонт","Преодолеть 5 км","DISTANCE",500000,100,"Путник",0xa5e7aa),
        a("TRAVEL_25","Большое путешествие","Преодолеть 25 км","DISTANCE",2500000,250,"Странник",0xa5e7aa),
        a("TRAVEL_100","Вокруг света","Преодолеть 100 км","DISTANCE",10000000,600,"Картограф",0x68dbb6),
        a("BOSS_FIRST","Первый трофей","Победить Иссушителя, Дракона или босса Iron’s Spells","BOSSES",1,200,"Победитель боссов",0xffb1e1),
        a("BOSS_TEN","Охота на легенд","Победить 10 боссов","BOSSES",10,700,"Охотник на легенд",0xed8fff),
        a("FISH_FIRST","Тихий берег","Поймать 25 рыб","FISH",25,100,"Рыболов",0x8ed8ff),
        a("FISH_HUNDRED","Хозяин водоёмов","Поймать 100 рыб","FISH",100,250,"Хозяин водоёмов",0x60b8ef));
    private ProfileProgression(){}
    private static Achievement a(String code,String name,String condition,String metric,long target,int xp,String title,int color){return new Achievement(code,name,condition,metric,target,xp,title,color);}
    static Achievement find(String code){return ALL.stream().filter(a->a.code().equals(code)).findFirst().orElse(null);}
    static long xp(Metrics metrics,Set<String> unlocked){long reward=ALL.stream().filter(a->unlocked.contains(a.code())).mapToLong(Achievement::xp).sum();return metrics.seconds()/60+reward;}
    static int level(long xp){if(xp<0)throw new IllegalArgumentException("Negative XP");return Math.min(100,1+(int)Math.min(99,Math.sqrt(xp/100.0)));}
    static long floor(int level){return (long)(level-1)*(level-1)*100;}
    static long onlineSeconds(long saved,long start,long now){return Math.addExact(saved,Math.max(0,now-start)/1_000_000_000L);}
}
