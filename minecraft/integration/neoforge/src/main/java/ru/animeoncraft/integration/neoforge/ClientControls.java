package ru.animeoncraft.integration.neoforge;

import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.InputConstants;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.settings.KeyModifier;

/** One-time migration of overlapping mod defaults, never of custom bindings. */
final class ClientControls {
    private static boolean checked;
    private static final Map<String,String> KEYS=Map.ofEntries(
        Map.entry("key.push_to_talk","caps.lock"),
        Map.entry("key.mute_microphone","f8"),
        Map.entry("key.disable_voice_chat","f10"),
        Map.entry("key.voice_chat_group","f7"),
        Map.entry("key.hide_icons","keypad.decimal"),
        Map.entry("key.sophisticatedbackpacks.inventory_interaction","i"),
        Map.entry("key.twilightforest.zoom","end"),
        Map.entry("key.twilightforest.swap_hotbar","home"),
        Map.entry("key.twilightforest.item_display_map_cycle","insert"),
        Map.entry("key.cataclysm.ability","keypad.6"),
        Map.entry("key.cataclysm.helmet_ability","keypad.7"),
        Map.entry("key.cataclysm.chestplate_ability","keypad.8"),
        Map.entry("key.cataclysm.boots_ability","keypad.9"),
        Map.entry("gui.xaero_open_settings","f6"),
        Map.entry("gui.xaero_new_waypoint","n"),
        Map.entry("keyinfo.copycats.fill_copycat","k"),
        Map.entry("railways.keyinfo.bogey_menu","page.up"),
        Map.entry("railways.keyinfo.cycle_menu","page.down"),
        Map.entry("create.keyinfo.toolbelt","grave.accent"),
        Map.entry("key.irons_spellbooks.spell_bar_modifier","right.alt"),
        Map.entry("key.irons_spellbooks.spellbook_cast","f4"));
    private ClientControls(){}
    static String label(String name){
        for(var key:Minecraft.getInstance().options.keyMappings)if(key.getName().equals(name)){
            if(key.isUnbound())return "не назначена";
            // GLFW returns Cyrillic letters when the OS layout is Russian. Keep physical
            // letter names consistent with game guides (M, V), independent of that layout.
            String code=key.getKey().getName(),prefix="key.keyboard.";
            if(code.startsWith(prefix)&&code.length()==prefix.length()+1){String letter=code.substring(prefix.length()).toUpperCase(java.util.Locale.ROOT);return key.getKeyModifier()==KeyModifier.NONE?letter:key.getKeyModifier()+" + "+letter;}
            return key.getTranslatedKeyMessage().getString();
        }
        return "мод недоступен";
    }
    static String guide(String body){return body.replace("{map}",label("gui.xaero_open_map")).replace("{voice}",label("key.voice_chat")).replace("{talk}",label("key.push_to_talk")).replace("{mute}",label("key.mute_microphone"));}
    static void initialize(){
        if(checked)return;checked=true;var mc=Minecraft.getInstance();var config=mc.gameDirectory.toPath().resolve("config");var marker=config.resolve("animeoncraft-controls-v1.json");
        if(Files.exists(marker))return;
        try{
            Files.createDirectories(config);var options=mc.gameDirectory.toPath().resolve("options.txt");var backup=config.resolve("options.before-animeoncraft-controls-v1.txt");
            if(Files.exists(options)&&!Files.exists(backup))Files.copy(options,backup,StandardCopyOption.COPY_ATTRIBUTES);
            var changed=new LinkedHashMap<String,String>();
            for(var key:mc.options.keyMappings){var target=KEYS.get(key.getName());if(target==null||!key.isDefault()||key.getKeyModifier()!=KeyModifier.NONE)continue;
                var next=InputConstants.getKey("key.keyboard."+target);key.setKey(next);changed.put(key.getName(),next.getName());}
            KeyMapping.resetMapping();mc.options.save();
            Files.writeString(marker,new GsonBuilder().setPrettyPrinting().create().toJson(Map.of("revision",1,"changed",changed)));
        }catch(java.io.IOException ex){System.err.println("AnimeOnCraft: could not save control defaults: "+ex.getClass().getSimpleName());}
    }
    /** Native registry audit, including NeoForge contexts and modifier combinations. */
    static void audit(java.nio.file.Path file)throws java.io.IOException{
        var rows=new ArrayList<Map<String,String>>();var keys=Minecraft.getInstance().options.keyMappings;
        for(var key:keys)rows.add(Map.of("action",key.getName(),"key",key.saveString(),"modifier",key.getKeyModifier().toString(),"context",key.getKeyConflictContext().toString()));
        var overlaps=new ArrayList<Map<String,String>>();
        for(int i=0;i<keys.length;i++)for(int j=i+1;j<keys.length;j++)if(!keys[i].isUnbound()&&keys[i].same(keys[j]))overlaps.add(Map.of("first",keys[i].getName(),"second",keys[j].getName(),"key",keys[i].saveString()));
        Files.writeString(file,new GsonBuilder().setPrettyPrinting().create().toJson(Map.of("bindings",rows,"overlaps",overlaps)));
    }
}
