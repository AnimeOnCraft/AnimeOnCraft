package ru.animeoncraft.integration.neoforge;

import java.util.*;

/** Local preferences never confer ownership. IDs are validated independently of account grants. */
final class EmoteFavorites {
    static final int LIMIT=6;
    static final List<String> DEFAULT=List.of("NEON_DANCE","HERO_POSE","ZEN_POSE");
    private EmoteFavorites(){}
    static List<String> clean(Collection<String> input){return input.stream().filter(Objects::nonNull).filter(c->Emotes.find(c)!=null).distinct().limit(LIMIT).toList();}
    static List<String> toggle(List<String> input,String code){var out=new ArrayList<>(clean(input));if(Emotes.find(code)==null)return List.copyOf(out);if(!out.remove(code)&&out.size()<LIMIT)out.add(code);return List.copyOf(out);}
}
