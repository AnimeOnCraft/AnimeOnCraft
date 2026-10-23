package ru.animeoncraft.integration.neoforge;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/** Lives in overworld/data, is part of ordinary world backups, survives death and dimensions. */
final class PlayerProfileStore extends SavedData {
    static final class Entry {final Set<String> unlocked=new HashSet<>();String selected="";long seconds;}
    private final Map<UUID,Entry> players=new HashMap<>();
    static PlayerProfileStore get(MinecraftServer server){return server.overworld().getDataStorage().computeIfAbsent(new Factory<>(PlayerProfileStore::new,PlayerProfileStore::load),"animeoncraft-player-profiles");}
    Entry player(UUID uuid){return players.computeIfAbsent(uuid,ignored->new Entry());}
    boolean unlock(UUID uuid,ProfileProgression.Metrics metrics){var entry=player(uuid);boolean changed=false;for(var a:ProfileProgression.ALL)if(metrics.value(a.metric())>=a.target())changed|=entry.unlocked.add(a.code());if(changed)setDirty();return changed;}
    boolean select(UUID uuid,String code){var entry=player(uuid);if(!code.isEmpty()&&(ProfileProgression.find(code)==null||!entry.unlocked.contains(code)))return false;if(!entry.selected.equals(code)){entry.selected=code;setDirty();}return true;}
    static PlayerProfileStore load(CompoundTag tag,HolderLookup.Provider registries){var result=new PlayerProfileStore();var rows=tag.getList("players",Tag.TAG_COMPOUND);for(int i=0;i<rows.size();i++){var row=rows.getCompound(i);if(!row.hasUUID("uuid"))continue;var entry=result.player(row.getUUID("uuid"));entry.seconds=Math.max(0,row.getLong("seconds"));var earned=row.getList("earned",Tag.TAG_STRING);for(int j=0;j<earned.size();j++){String code=earned.getString(j);if(ProfileProgression.find(code)!=null)entry.unlocked.add(code);}String selected=row.getString("selected");if(entry.unlocked.contains(selected))entry.selected=selected;}return result;}
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries){var rows=new ListTag();for(var item:players.entrySet()){var row=new CompoundTag();row.putUUID("uuid",item.getKey());row.putString("selected",item.getValue().selected);row.putLong("seconds",item.getValue().seconds);var earned=new ListTag();item.getValue().unlocked.stream().sorted().forEach(code->earned.add(StringTag.valueOf(code)));row.put("earned",earned);rows.add(row);}tag.put("players",rows);return tag;}
}
