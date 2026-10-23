package ru.animeoncraft.integration.neoforge;

import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

final class EmotePairPayloads {
    private EmotePairPayloads(){}
    record Request(String action,UUID target,String code) implements CustomPacketPayload {
        Request{if(!java.util.Set.of("INVITE","ACCEPT","DENY").contains(action)||target==null||!Emotes.paired(code))throw new IllegalArgumentException("Invalid pair request");}
        static final Type<Request> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath("animeoncraft","emote_pair_request_v1"));
        static final StreamCodec<FriendlyByteBuf,Request> CODEC=StreamCodec.of((b,p)->{b.writeUtf(p.action,8);b.writeUUID(p.target);b.writeUtf(p.code,32);},b->new Request(b.readUtf(8),b.readUUID(),b.readUtf(32)));
        public Type<Request> type(){return TYPE;}
    }
    record Notice(UUID sender,String name,String code,int remaining) implements CustomPacketPayload {
        Notice{if(sender==null||name==null||name.length()>16||!code.isEmpty()&&!Emotes.paired(code)||remaining<0||remaining>EmotePairRules.INVITE_TICKS||code.isEmpty()&&remaining!=0)throw new IllegalArgumentException("Invalid pair notice");}
        static final Type<Notice> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath("animeoncraft","emote_pair_notice_v1"));
        static final StreamCodec<FriendlyByteBuf,Notice> CODEC=StreamCodec.of((b,p)->{b.writeUUID(p.sender);b.writeUtf(p.name,16);b.writeUtf(p.code,32);b.writeVarInt(p.remaining);},b->new Notice(b.readUUID(),b.readUtf(16),b.readUtf(32),b.readVarInt()));
        public Type<Notice> type(){return TYPE;}
    }
}
