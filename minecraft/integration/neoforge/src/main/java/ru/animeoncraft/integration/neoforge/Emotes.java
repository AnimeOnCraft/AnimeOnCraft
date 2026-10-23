package ru.animeoncraft.integration.neoforge;

import java.util.List;

/** Stable IDs shared with the account catalogue. Animations never change a player's hitbox. */
final class Emotes {
    record Entry(String code,String name,String description,int ticks,boolean starter) {}
    static final List<Entry> ALL=List.of(
        new Entry("NEON_DANCE","Неоновый ритм","Танец с движениями рук и шагами в ритм",240,true),
        new Entry("HERO_POSE","Герой","Победная стойка: рука вверх, другая на поясе",600,true),
        new Entry("ZEN_POSE","Медитация","Спокойная поза со скрещёнными ногами",600,true),
        new Entry("HIGH_FIVE","Дай пять","Совместная эмоция · нужно согласие напарника",80,true),
        new Entry("HANDSHAKE","Рукопожатие","Совместная эмоция · нужно согласие напарника",100,true));
    private Emotes(){}
    static Entry find(String code){return ALL.stream().filter(e->e.code().equals(code)).findFirst().orElse(null);}
    static List<String> starters(){return ALL.stream().filter(Entry::starter).map(Entry::code).toList();}
    static boolean paired(String code){return code.equals("HIGH_FIVE")||code.equals("HANDSHAKE");}
    static boolean interrupts(double distanceSquared,boolean grounded,boolean busy,boolean hurt){return distanceSquared>.0004||!grounded||busy||hurt;}
}
