package ru.animeoncraft.integration.neoforge;

import java.util.List;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

final class ProfilePayloads {
    private ProfilePayloads(){}
    record Request(String selection) implements CustomPacketPayload {
        Request{if(!selection.matches("-|[A-Z0-9_]{0,40}"))throw new IllegalArgumentException("Invalid title selection");}
        static final Type<Request> TYPE=new Type<>(id("profile_request_v1"));
        static final StreamCodec<FriendlyByteBuf,Request> CODEC=StreamCodec.of((b,p)->b.writeUtf(p.selection,40),b->new Request(b.readUtf(40)));
        @Override public Type<Request> type(){return TYPE;}
    }
    record Snapshot(UUID player,ProfileProgression.Metrics metrics,String selected,List<String> earned) implements CustomPacketPayload {
        Snapshot{earned=List.copyOf(earned);if(earned.size()>64||earned.stream().distinct().count()!=earned.size()||earned.stream().anyMatch(c->ProfileProgression.find(c)==null)||!selected.isEmpty()&&!earned.contains(selected))throw new IllegalArgumentException("Invalid profile snapshot");}
        static final Type<Snapshot> TYPE=new Type<>(id("profile_snapshot_v1"));
        static final StreamCodec<FriendlyByteBuf,Snapshot> CODEC=StreamCodec.of((b,p)->{b.writeUUID(p.player);var m=p.metrics;for(long n:new long[]{m.seconds(),m.kills(),m.deaths(),m.mobs(),m.mined(),m.crafted(),m.centimeters(),m.bosses(),m.fish()})b.writeVarLong(n);b.writeUtf(p.selected,40);b.writeVarInt(p.earned.size());for(var c:p.earned)b.writeUtf(c,40);},b->{var uuid=b.readUUID();var m=new ProfileProgression.Metrics(b.readVarLong(),b.readVarLong(),b.readVarLong(),b.readVarLong(),b.readVarLong(),b.readVarLong(),b.readVarLong(),b.readVarLong(),b.readVarLong());String selected=b.readUtf(40);int size=b.readVarInt();if(size<0||size>64)throw new IllegalArgumentException("Profile too large");var earned=new java.util.ArrayList<String>();for(int i=0;i<size;i++)earned.add(b.readUtf(40));return new Snapshot(uuid,m,selected,earned);});
        @Override public Type<Snapshot> type(){return TYPE;}
    }
    record Title(UUID player,String code) implements CustomPacketPayload {
        Title{if(!code.isEmpty()&&ProfileProgression.find(code)==null)throw new IllegalArgumentException("Invalid title");}
        static final Type<Title> TYPE=new Type<>(id("player_title_v1"));
        static final StreamCodec<FriendlyByteBuf,Title> CODEC=StreamCodec.of((b,p)->{b.writeUUID(p.player);b.writeUtf(p.code,40);},b->new Title(b.readUUID(),b.readUtf(40)));
        @Override public Type<Title> type(){return TYPE;}
    }
    private static ResourceLocation id(String name){return ResourceLocation.fromNamespaceAndPath("animeoncraft",name);}
}
