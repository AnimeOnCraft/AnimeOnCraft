package ru.animeoncraft.integration.neoforge;

import java.util.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

final class EmotePayloads {
    private EmotePayloads(){}
    record Request(String code) implements CustomPacketPayload {
        Request {if(!code.equals("READ")&&!code.isEmpty()&&Emotes.find(code)==null)throw new IllegalArgumentException("Unknown emote");}
        static final Type<Request> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath("animeoncraft","emote_request_v1"));
        static final StreamCodec<FriendlyByteBuf,Request> CODEC=StreamCodec.of((b,p)->b.writeUtf(p.code,32),b->new Request(b.readUtf(32)));
        public Type<Request> type(){return TYPE;}
    }
    record Inventory(boolean available,List<String> owned) implements CustomPacketPayload {
        Inventory {owned=List.copyOf(owned);if(owned.size()>128||owned.stream().distinct().count()!=owned.size()||owned.stream().anyMatch(c->Emotes.find(c)==null)||!available&&!owned.isEmpty())throw new IllegalArgumentException("Invalid emote inventory");}
        static final Type<Inventory> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath("animeoncraft","emote_inventory_v1"));
        static final StreamCodec<FriendlyByteBuf,Inventory> CODEC=StreamCodec.of((b,p)->{b.writeBoolean(p.available);b.writeVarInt(p.owned.size());p.owned.forEach(c->b.writeUtf(c,32));},b->{boolean a=b.readBoolean();int n=b.readVarInt();if(n<0||n>128)throw new IllegalArgumentException("Emote inventory too large");var list=new ArrayList<String>();for(int i=0;i<n;i++)list.add(b.readUtf(32));return new Inventory(a,list);});
        public Type<Inventory> type(){return TYPE;}
    }
    record State(UUID player,String code,int elapsed) implements CustomPacketPayload {
        State {var e=Emotes.find(code);if(!code.isEmpty()&&e==null||elapsed<0||elapsed>(e==null?0:e.ticks()))throw new IllegalArgumentException("Invalid emote state");}
        static final Type<State> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath("animeoncraft","emote_state_v1"));
        static final StreamCodec<FriendlyByteBuf,State> CODEC=StreamCodec.of((b,p)->{b.writeUUID(p.player);b.writeUtf(p.code,32);b.writeVarInt(p.elapsed);},b->new State(b.readUUID(),b.readUtf(32),b.readVarInt()));
        public Type<State> type(){return TYPE;}
    }
}
