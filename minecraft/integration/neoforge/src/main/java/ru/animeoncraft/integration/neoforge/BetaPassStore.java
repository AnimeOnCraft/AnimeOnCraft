package ru.animeoncraft.integration.neoforge;

import java.util.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/** One season per world, included in the ordinary world backup. */
final class BetaPassStore extends SavedData {
    static final class Entry {
        int xp;String day="",week="";
        final long[] daily=new long[6],weekly=new long[6];
        int dayClaims,weekClaims,rewards;
        final Map<String,String> equipped=new HashMap<>();
        boolean reset(long now){String d=BetaPass.day(now),w=BetaPass.week(now);boolean changed=false;
            if(!d.equals(day)){day=d;Arrays.fill(daily,0);dayClaims=0;changed=true;}
            if(!w.equals(week)){week=w;Arrays.fill(weekly,0);weekClaims=0;changed=true;}return changed;
        }
        void progress(long[] delta){for(int i=0;i<6;i++){daily[i]=Math.min(BetaPass.QUESTS.get(i).daily(),daily[i]+Math.clamp(delta[i],0,Long.MAX_VALUE/4));weekly[i]=Math.min(BetaPass.QUESTS.get(i).daily()*7,weekly[i]+Math.clamp(delta[i],0,Long.MAX_VALUE/4));}}
        boolean claimQuest(boolean weekQuest,int index){if(index<0||index>=6)return false;int mask=1<<index;var q=BetaPass.QUESTS.get(index);
            if(((weekQuest?weekClaims:dayClaims)&mask)!=0||(weekQuest?weekly:daily)[index]<q.target(weekQuest))return false;
            if(weekQuest)weekClaims|=mask;else dayClaims|=mask;xp=Math.min(1000,xp+q.reward(weekQuest));return true;
        }
        boolean claimReward(int index){if(index<0||index>=10||BetaPass.level(xp)<index+1||(rewards&(1<<index))!=0)return false;rewards|=1<<index;return true;}
        boolean owns(String code){var r=BetaPass.find(code);return r!=null&&(rewards&(1<<(r.level()-1)))!=0;}
        boolean equip(String slot,String code){if(!Set.of("HEAD","FACE","BELT","BACK","TITLE").contains(slot))return false;
            if(!code.isEmpty()){var r=BetaPass.find(code);if(!owns(code)||!BetaPass.slot(r).equals(slot))return false;equipped.put(slot,code);}else equipped.remove(slot);return true;
        }
    }
    private final Map<UUID,Entry> players=new HashMap<>();
    boolean open=true;
    static BetaPassStore get(MinecraftServer server){return server.overworld().getDataStorage().computeIfAbsent(new Factory<>(BetaPassStore::new,BetaPassStore::load),"animeoncraft-beta-rift-1");}
    Entry player(UUID id){return players.computeIfAbsent(id,key->new Entry());}
    static BetaPassStore load(CompoundTag tag,HolderLookup.Provider registries){var store=new BetaPassStore();store.open=!tag.contains("open")||tag.getBoolean("open");var list=tag.getList("players",Tag.TAG_COMPOUND);
        for(int i=0;i<list.size();i++){var row=list.getCompound(i);if(!row.hasUUID("uuid"))continue;var e=store.player(row.getUUID("uuid"));e.xp=Math.clamp(row.getInt("xp"),0,1000);e.day=row.getString("day");e.week=row.getString("week");e.dayClaims=row.getInt("dc")&63;e.weekClaims=row.getInt("wc")&63;e.rewards=row.getInt("rewards")&1023;
            var d=row.getLongArray("daily");var w=row.getLongArray("weekly");for(int j=0;j<6;j++){e.daily[j]=j<d.length?Math.clamp(d[j],0,BetaPass.QUESTS.get(j).daily()):0;e.weekly[j]=j<w.length?Math.clamp(w[j],0,BetaPass.QUESTS.get(j).daily()*7):0;}
            var equipped=row.getCompound("equipped");for(String key:equipped.getAllKeys())e.equip(key,equipped.getString(key));
        }return store;
    }
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries){tag.putBoolean("open",open);var list=new ListTag();for(var item:players.entrySet()){var e=item.getValue();var row=new CompoundTag();row.putUUID("uuid",item.getKey());row.putInt("xp",e.xp);row.putString("day",e.day);row.putString("week",e.week);row.putInt("dc",e.dayClaims);row.putInt("wc",e.weekClaims);row.putInt("rewards",e.rewards);row.putLongArray("daily",e.daily);row.putLongArray("weekly",e.weekly);var equipped=new CompoundTag();e.equipped.forEach(equipped::putString);row.put("equipped",equipped);list.add(row);}tag.put("players",list);tag.putString("season",BetaPass.SEASON);return tag;}
}
