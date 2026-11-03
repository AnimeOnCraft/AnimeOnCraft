package ru.animeoncraft.integration.neoforge;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/** Arena location and player ratings; data/animeoncraft-arena.dat inside the world (part of the normal backup). */
final class ArenaStore extends SavedData {
    static final class Fighter { String name="";int rating=ArenaRules.START_RATING,wins,losses,draws; }
    String dimension;int x,y,z;boolean placed;
    final Map<UUID,Fighter> fighters=new HashMap<>();
    /** Rewarded wins per pair and day: key "day|pair". Old days are dropped on save. */
    final Map<String,Integer> rewarded=new HashMap<>();

    static ArenaStore get(MinecraftServer server){
        return server.overworld().getDataStorage().computeIfAbsent(new Factory<>(ArenaStore::new,ArenaStore::load),"animeoncraft-arena");
    }
    Fighter fighter(UUID id,String name){var row=fighters.computeIfAbsent(id,ignored->new Fighter());if(name!=null)row.name=name;setDirty();return row;}
    static ArenaStore load(CompoundTag tag,HolderLookup.Provider registries){
        var store=new ArenaStore();
        if(tag.getBoolean("placed")){store.placed=true;store.dimension=tag.getString("dimension");store.x=tag.getInt("x");store.y=tag.getInt("y");store.z=tag.getInt("z");}
        var list=tag.getList("fighters",Tag.TAG_COMPOUND);
        for(int i=0;i<list.size();i++){
            var row=list.getCompound(i);if(!row.hasUUID("uuid"))continue;
            var fighter=new Fighter();fighter.name=row.getString("name");fighter.rating=row.getInt("rating");fighter.wins=row.getInt("wins");fighter.losses=row.getInt("losses");fighter.draws=row.getInt("draws");
            store.fighters.put(row.getUUID("uuid"),fighter);
        }
        var rewards=tag.getCompound("rewarded");for(var key:rewards.getAllKeys())store.rewarded.put(key,rewards.getInt(key));
        return store;
    }
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries){
        tag.putBoolean("placed",placed);
        if(placed){tag.putString("dimension",dimension);tag.putInt("x",x);tag.putInt("y",y);tag.putInt("z",z);}
        var list=new ListTag();
        fighters.forEach((id,fighter)->{var row=new CompoundTag();row.putUUID("uuid",id);row.putString("name",fighter.name);row.putInt("rating",fighter.rating);row.putInt("wins",fighter.wins);row.putInt("losses",fighter.losses);row.putInt("draws",fighter.draws);list.add(row);});
        tag.put("fighters",list);
        String today=ArenaRules.day(System.currentTimeMillis());var rewards=new CompoundTag();
        rewarded.forEach((key,count)->{if(key.startsWith(today+"|"))rewards.putInt(key,count);});
        tag.put("rewarded",rewards);
        return tag;
    }
}
