package ru.animeoncraft.integration.neoforge;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.UUID;

record ServerMenuData(boolean available,int maximum,List<Clan> clans,List<Member> members) {
    record Clan(String code,String name,int color,int level,long xp,int reputation,int points,int memberCount) {
        Clan { if(!ClanCodes.valid(code)||name.isBlank()||name.length()>64||name.codePoints().anyMatch(c->Character.isISOControl(c)||c==167)||color<0||color>0xffffff||level<1||level>100||xp<0||reputation<0||points<0||memberCount<0)throw new IllegalArgumentException("Invalid menu clan"); }
        long nextXp(){return level>=100?xp:(long)level*level*100;}
        double progress(){long start=(long)(level-1)*(level-1)*100,end=nextXp();return level>=100?1:Math.clamp((double)(xp-start)/Math.max(1,end-start),0,1);}
    }
    record Member(UUID uuid,String name,String clan,String role) {
        Member {if(!name.matches("[A-Za-z0-9_]{3,16}")||!ClanCodes.valid(clan)||!List.of("OWNER","MANAGER","MEMBER").contains(role))throw new IllegalArgumentException("Invalid menu member");}
    }
    ServerMenuData {
        clans=List.copyOf(clans);members=List.copyOf(members);
        var codes=clans.stream().map(Clan::code).collect(java.util.stream.Collectors.toSet());
        if(maximum<1||clans.size()>64||members.size()>512||codes.size()!=clans.size()||members.stream().map(Member::uuid).distinct().count()!=members.size()||members.stream().anyMatch(m->!codes.contains(m.clan())))throw new IllegalArgumentException("Invalid menu snapshot");
    }
    static ServerMenuData unavailable(int maximum){return new ServerMenuData(false,maximum,List.of(),List.of());}
    static ServerMenuData parse(JsonObject json,int maximum){
        if(!json.has("clans")||!json.has("members")||json.getAsJsonArray("clans").size()>64||json.getAsJsonArray("members").size()>512)throw new IllegalArgumentException("Invalid menu snapshot");
        var clans=new java.util.ArrayList<Clan>();var members=new java.util.ArrayList<Member>();
        for(var value:json.getAsJsonArray("clans")){var c=value.getAsJsonObject();clans.add(new Clan(c.get("code").getAsString(),c.get("name").getAsString(),Integer.parseInt(c.get("color").getAsString(),16),c.get("level").getAsInt(),c.get("xp").getAsLong(),c.get("reputation").getAsInt(),c.get("points").getAsInt(),c.get("members").getAsInt()));}
        for(var value:json.getAsJsonArray("members")){var m=value.getAsJsonObject();members.add(new Member(UUID.fromString(m.get("uuid").getAsString()),m.get("name").getAsString(),m.get("clan").getAsString(),m.get("role").getAsString()));}
        return new ServerMenuData(true,maximum,clans,members);
    }
}
