package ru.animeoncraft.integration.neoforge;

import java.util.ArrayList;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

final class ServerMenuPayloads {
    private ServerMenuPayloads(){}
    record CaptureSummary(String name,String detail,boolean canDeclare) implements CustomPacketPayload {
        static final Type<CaptureSummary> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath("animeoncraft","capture_summary_v1"));
        static final StreamCodec<FriendlyByteBuf,CaptureSummary> CODEC=StreamCodec.of((b,p)->{b.writeUtf(p.name(),120);b.writeUtf(p.detail(),512);b.writeBoolean(p.canDeclare());},
            b->new CaptureSummary(b.readUtf(120),b.readUtf(512),b.readBoolean()));
        @Override public Type<CaptureSummary> type(){return TYPE;}
    }
    record Request() implements CustomPacketPayload {
        static final Type<Request> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath("animeoncraft","server_menu_request_v1"));
        static final StreamCodec<FriendlyByteBuf,Request> CODEC=StreamCodec.of((b,p)->{},b->new Request());
        @Override public Type<Request> type(){return TYPE;}
    }
    record Snapshot(ServerMenuData data) implements CustomPacketPayload {
        static final Type<Snapshot> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath("animeoncraft","server_menu_snapshot_v1"));
        static final StreamCodec<FriendlyByteBuf,Snapshot> CODEC=StreamCodec.of((b,p)->{
            var d=p.data();b.writeBoolean(d.available());b.writeVarInt(d.maximum());b.writeVarInt(d.clans().size());
            for(var c:d.clans()){b.writeUtf(c.code(),12);b.writeUtf(c.name(),64);b.writeInt(c.color());b.writeVarInt(c.level());b.writeVarLong(c.xp());b.writeVarInt(c.reputation());b.writeVarInt(c.points());b.writeVarInt(c.memberCount());}
            b.writeVarInt(d.members().size());for(var m:d.members()){b.writeUUID(m.uuid());b.writeUtf(m.name(),16);b.writeUtf(m.clan(),12);b.writeUtf(m.role(),8);}
        },b->{boolean available=b.readBoolean();int maximum=b.readVarInt(),count=count(b,64);var clans=new ArrayList<ServerMenuData.Clan>();
            for(int i=0;i<count;i++)clans.add(new ServerMenuData.Clan(b.readUtf(12),b.readUtf(64),b.readInt(),b.readVarInt(),b.readVarLong(),b.readVarInt(),b.readVarInt(),b.readVarInt()));
            count=count(b,512);var members=new ArrayList<ServerMenuData.Member>();for(int i=0;i<count;i++)members.add(new ServerMenuData.Member(b.readUUID(),b.readUtf(16),b.readUtf(12),b.readUtf(8)));
            return new Snapshot(new ServerMenuData(available,maximum,clans,members));});
        @Override public Type<Snapshot> type(){return TYPE;}
        private static int count(FriendlyByteBuf b,int max){int n=b.readVarInt();if(n<0||n>max)throw new IllegalArgumentException("Menu payload too large");return n;}
    }
}
