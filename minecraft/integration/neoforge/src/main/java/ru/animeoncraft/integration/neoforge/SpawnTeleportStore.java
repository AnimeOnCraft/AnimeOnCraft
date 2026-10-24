package ru.animeoncraft.integration.neoforge;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/** Cooldowns survive reconnects/restarts and are included in the ordinary world backup. */
final class SpawnTeleportStore extends SavedData {
    final SpawnTeleportQueue queue=new SpawnTeleportQueue();
    static SpawnTeleportStore get(MinecraftServer server){
        return server.overworld().getDataStorage().computeIfAbsent(new Factory<>(SpawnTeleportStore::new,SpawnTeleportStore::load),"animeoncraft-spawn-cooldowns");
    }
    static SpawnTeleportStore load(CompoundTag tag,HolderLookup.Provider registries){
        var store=new SpawnTeleportStore();var players=tag.getList("players",Tag.TAG_COMPOUND);
        for(int i=0;i<players.size();i++){
            var row=players.getCompound(i);
            if(row.hasUUID("uuid"))store.queue.restore(row.getUUID("uuid"),row.getLong("until"));
        }
        return store;
    }
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries){
        var players=new ListTag();
        queue.cooldowns().forEach((id,until)->{
            var row=new CompoundTag();row.putUUID("uuid",id);row.putLong("until",until);players.add(row);
        });
        tag.put("players",players);return tag;
    }
}
