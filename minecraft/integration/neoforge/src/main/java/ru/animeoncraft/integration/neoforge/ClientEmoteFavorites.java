package ru.animeoncraft.integration.neoforge;

import com.google.gson.*;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import net.minecraft.client.Minecraft;

final class ClientEmoteFavorites {
    private static UUID account;private static List<String> selected=EmoteFavorites.DEFAULT;
    private ClientEmoteFavorites(){}
    private static Path file(){return Minecraft.getInstance().gameDirectory.toPath().resolve("config/animeoncraft-emotes.json");}
    private static JsonObject load()throws IOException{
        if(!Files.exists(file()))return new JsonObject();if(Files.size(file())>16384)throw new IOException("Preferences too large");
        var value=JsonParser.parseString(Files.readString(file()));if(!value.isJsonObject())throw new IOException("Invalid preferences");return value.getAsJsonObject();
    }
    static List<String> all(){var player=Minecraft.getInstance().player;if(player==null)return List.of();if(!player.getUUID().equals(account)){account=player.getUUID();selected=EmoteFavorites.DEFAULT;try{var data=load();if(data.has(account.toString())){var values=data.getAsJsonArray(account.toString());var codes=new ArrayList<String>();for(var v:values)if(v.isJsonPrimitive()&&v.getAsJsonPrimitive().isString())codes.add(v.getAsString());selected=EmoteFavorites.clean(codes);}}catch(IOException|RuntimeException ex){System.err.println("AnimeOnCraft: emote preferences unavailable: "+ex.getClass().getSimpleName());}}return selected;}
    static boolean contains(String code){return all().contains(code);}
    static void toggle(String code){all();if(account==null)return;var next=EmoteFavorites.toggle(selected,code);if(next.equals(selected))return;
        try{var data=load();if(data.size()>=64&&!data.has(account.toString()))throw new IOException("Too many preference accounts");var array=new JsonArray();next.forEach(array::add);data.add(account.toString(),array);Files.createDirectories(file().getParent());var tmp=file().resolveSibling("animeoncraft-emotes.json.tmp");Files.writeString(tmp,new GsonBuilder().setPrettyPrinting().create().toJson(data));try{Files.move(tmp,file(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException ex){Files.move(tmp,file(),StandardCopyOption.REPLACE_EXISTING);}selected=next;}catch(IOException|RuntimeException ex){System.err.println("AnimeOnCraft: could not save emote favorites: "+ex.getClass().getSimpleName());}
    }
    static void logout(){account=null;selected=EmoteFavorites.DEFAULT;}
}
