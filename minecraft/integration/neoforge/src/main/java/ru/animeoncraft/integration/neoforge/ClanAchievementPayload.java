package ru.animeoncraft.integration.neoforge;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** New optional channel; the already-published roster protocol remains unchanged. */
record ClanAchievementPayload(List<Definition> definitions,Map<String,List<Progress>> clans) implements CustomPacketPayload {
    record Definition(String code,String name,String metric,long target,int xp,int reputation){
        Definition{if(!code.matches("[A-Z0-9_]{1,40}")||name.isBlank()||name.length()>64||name.codePoints().anyMatch(c->Character.isISOControl(c)||c==167)||!List.of("BOSS_KILL","POINT_CAPTURE","POINT_DEFENCE","TERRITORY_CAPTURE").contains(metric)||target<1||target>1000000||xp<0||xp>100000||reputation<0||reputation>10000)throw new IllegalArgumentException("Invalid clan achievement");}
        String unit(){return switch(metric){case "BOSS_KILL"->"боссов";case "POINT_CAPTURE"->"захватов точек";case "POINT_DEFENCE"->"защит точек";default->"завоеваний";};}
    }
    record Progress(String code,long value,boolean unlocked){}
    ClanAchievementPayload{
        definitions=List.copyOf(definitions);var copy=new LinkedHashMap<String,List<Progress>>();clans.forEach((key,value)->copy.put(key,List.copyOf(value)));clans=Map.copyOf(copy);
        if(definitions.size()>32||clans.size()>64||definitions.stream().map(Definition::code).distinct().count()!=definitions.size())throw new IllegalArgumentException("Too many clan achievements");
        for(var entry:clans.entrySet()){
            if(!ClanCodes.valid(entry.getKey())||entry.getValue().size()>32||entry.getValue().stream().map(Progress::code).distinct().count()!=entry.getValue().size())throw new IllegalArgumentException("Invalid achievement clan");
            for(var progress:entry.getValue()){var def=definitions.stream().filter(d->d.code().equals(progress.code())).findFirst().orElseThrow(()->new IllegalArgumentException("Unknown achievement"));if(progress.value()<0||progress.value()>def.target()||progress.unlocked()&&progress.value()<def.target())throw new IllegalArgumentException("Invalid achievement progress");}
        }
    }
    static final Type<ClanAchievementPayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath("animeoncraft","clan_achievements_v1"));
    static final StreamCodec<FriendlyByteBuf,ClanAchievementPayload> CODEC=StreamCodec.of((b,p)->{
        b.writeVarInt(p.definitions.size());for(var d:p.definitions){b.writeUtf(d.code,40);b.writeUtf(d.name,64);b.writeUtf(d.metric,32);b.writeVarLong(d.target);b.writeVarInt(d.xp);b.writeVarInt(d.reputation);}
        b.writeVarInt(p.clans.size());for(var entry:p.clans.entrySet()){b.writeUtf(entry.getKey(),12);b.writeVarInt(entry.getValue().size());for(var a:entry.getValue()){b.writeUtf(a.code,40);b.writeVarLong(a.value);b.writeBoolean(a.unlocked);}}
    },b->{int n=count(b,32);var definitions=new ArrayList<Definition>();for(int i=0;i<n;i++)definitions.add(new Definition(b.readUtf(40),b.readUtf(64),b.readUtf(32),b.readVarLong(),b.readVarInt(),b.readVarInt()));n=count(b,64);var clans=new LinkedHashMap<String,List<Progress>>();for(int i=0;i<n;i++){String clan=b.readUtf(12);int rows=count(b,32);var progress=new ArrayList<Progress>();for(int j=0;j<rows;j++)progress.add(new Progress(b.readUtf(40),b.readVarLong(),b.readBoolean()));if(clans.put(clan,progress)!=null)throw new IllegalArgumentException("Duplicate clan");}return new ClanAchievementPayload(definitions,clans);});
    @Override public Type<ClanAchievementPayload> type(){return TYPE;}
    private static int count(FriendlyByteBuf b,int max){int n=b.readVarInt();if(n<0||n>max)throw new IllegalArgumentException("Achievement packet too large");return n;}
    static ClanAchievementPayload parse(JsonObject json){
        if(!json.has("achievement_definitions")||!json.has("clan_achievements"))return null;
        if(json.getAsJsonArray("achievement_definitions").size()>32||json.getAsJsonArray("clan_achievements").size()>64)throw new IllegalArgumentException("Achievement snapshot too large");
        var definitions=new ArrayList<Definition>();for(var value:json.getAsJsonArray("achievement_definitions")){var a=value.getAsJsonObject();definitions.add(new Definition(a.get("code").getAsString(),a.get("name").getAsString(),a.get("metric").getAsString(),a.get("target").getAsLong(),a.get("xp").getAsInt(),a.get("reputation").getAsInt()));}
        var clans=new LinkedHashMap<String,List<Progress>>();for(var value:json.getAsJsonArray("clan_achievements")){var c=value.getAsJsonObject();var rows=new ArrayList<Progress>();if(c.getAsJsonArray("rows").size()>32)throw new IllegalArgumentException("Too many achievement rows");for(var item:c.getAsJsonArray("rows")){var r=item.getAsJsonObject();rows.add(new Progress(r.get("code").getAsString(),r.get("progress").getAsLong(),r.get("unlocked").getAsBoolean()));}if(clans.put(c.get("clan").getAsString(),rows)!=null)throw new IllegalArgumentException("Duplicate clan");}
        return new ClanAchievementPayload(definitions,clans);
    }
}
