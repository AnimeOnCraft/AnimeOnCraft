package ru.animeoncraft.integration.neoforge;

import java.util.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

final class BetaPassPayloads {
    private BetaPassPayloads(){}
    private static ResourceLocation id(String name){return ResourceLocation.fromNamespaceAndPath("animeoncraft",name);}
    record Request(String action,int index,String slot,String code) implements CustomPacketPayload {
        Request{if(!Set.of("GET","DAILY","WEEKLY","REWARD","EQUIP").contains(action)||index<0||index>9||!slot.matches("[A-Z]{0,5}")||!code.matches("[A-Z_]{0,32}"))throw new IllegalArgumentException("Invalid beta request");}
        static final Type<Request> TYPE=new Type<>(id("beta_pass_request_v1"));
        static final StreamCodec<FriendlyByteBuf,Request> CODEC=StreamCodec.of((b,p)->{b.writeUtf(p.action,8);b.writeVarInt(p.index);b.writeUtf(p.slot,5);b.writeUtf(p.code,32);},b->new Request(b.readUtf(8),b.readVarInt(),b.readUtf(5),b.readUtf(32)));
        @Override public Type<Request> type(){return TYPE;}
    }
    record Appearance(UUID player,Map<String,String> equipped) implements CustomPacketPayload {
        Appearance{equipped=Map.copyOf(equipped);if(equipped.size()>5)throw new IllegalArgumentException("Too many slots");for(var e:equipped.entrySet()){var r=BetaPass.find(e.getValue());if(r==null||!BetaPass.slot(r).equals(e.getKey()))throw new IllegalArgumentException("Invalid cosmetic");}}
        static final Type<Appearance> TYPE=new Type<>(id("beta_pass_appearance_v1"));
        static final StreamCodec<FriendlyByteBuf,Appearance> CODEC=StreamCodec.of((b,p)->{b.writeUUID(p.player);b.writeVarInt(p.equipped.size());p.equipped.forEach((s,c)->{b.writeUtf(s,5);b.writeUtf(c,32);});},b->{var id=b.readUUID();int n=b.readVarInt();if(n<0||n>5)throw new IllegalArgumentException("Too many slots");var map=new HashMap<String,String>();for(int i=0;i<n;i++){String slot=b.readUtf(5);if(map.put(slot,b.readUtf(32))!=null)throw new IllegalArgumentException("Duplicate slot");}return new Appearance(id,map);});
        @Override public Type<Appearance> type(){return TYPE;}
    }
    record Snapshot(UUID player,boolean open,int xp,int rewards,String day,String week,List<Long> daily,List<Long> weekly,int dayClaims,int weekClaims,Map<String,String> equipped) implements CustomPacketPayload {
        Snapshot{daily=List.copyOf(daily);weekly=List.copyOf(weekly);equipped=Map.copyOf(equipped);if(xp<0||xp>1000||rewards<0||rewards>1023||daily.size()!=6||weekly.size()!=6||dayClaims<0||dayClaims>63||weekClaims<0||weekClaims>63||!day.matches("\\d{4}-\\d{2}-\\d{2}")||!week.matches("\\d{4}-\\d{2}-\\d{2}"))throw new IllegalArgumentException("Invalid beta snapshot");for(int i=0;i<6;i++)if(daily.get(i)<0||daily.get(i)>BetaPass.QUESTS.get(i).daily()||weekly.get(i)<0||weekly.get(i)>BetaPass.QUESTS.get(i).daily()*7)throw new IllegalArgumentException("Invalid quest progress");new Appearance(player,equipped);}
        static final Type<Snapshot> TYPE=new Type<>(id("beta_pass_snapshot_v1"));
        static final StreamCodec<FriendlyByteBuf,Snapshot> CODEC=StreamCodec.of((b,p)->{Appearance.CODEC.encode(b,new Appearance(p.player,p.equipped));b.writeBoolean(p.open);b.writeVarInt(p.xp);b.writeVarInt(p.rewards);b.writeUtf(p.day,10);b.writeUtf(p.week,10);for(long n:p.daily)b.writeVarLong(n);for(long n:p.weekly)b.writeVarLong(n);b.writeVarInt(p.dayClaims);b.writeVarInt(p.weekClaims);},b->{var a=Appearance.CODEC.decode(b);boolean open=b.readBoolean();int xp=b.readVarInt(),rewards=b.readVarInt();String d=b.readUtf(10),w=b.readUtf(10);var daily=new ArrayList<Long>();var weekly=new ArrayList<Long>();for(int i=0;i<6;i++)daily.add(b.readVarLong());for(int i=0;i<6;i++)weekly.add(b.readVarLong());return new Snapshot(a.player,open,xp,rewards,d,w,daily,weekly,b.readVarInt(),b.readVarInt(),a.equipped);});
        @Override public Type<Snapshot> type(){return TYPE;}
    }
}
