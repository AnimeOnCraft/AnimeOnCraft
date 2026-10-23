package ru.animeoncraft.integration.neoforge;

import java.util.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

final class WardrobePayloads {
 private WardrobePayloads(){}
 static boolean valid(String kind,String code){return switch(kind){case "NONE"->code.isEmpty();case "CAPE"->code.matches("[a-z][a-z0-9-]{0,31}");case "BETA"->code.equals("RIFT_MANTLE");default->false;};}
 record Cape(String id,String name){Cape{if(!valid("CAPE",id)||name.isBlank()||name.length()>80||name.matches("(?s).*[\\p{Cc}\\p{Cf}§].*"))throw new IllegalArgumentException("Invalid wardrobe cape");}}
 record Request(String kind,String code,long revision) implements CustomPacketPayload {
  Request{if(!(kind.equals("READ")&&code.isEmpty()||valid(kind,code))||revision<0||revision>999999999999999999L)throw new IllegalArgumentException("Invalid wardrobe request");}
  static final Type<Request> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath("animeoncraft","wardrobe_request_v1"));
  static final StreamCodec<FriendlyByteBuf,Request> CODEC=StreamCodec.of((b,p)->{b.writeUtf(p.kind,4);b.writeUtf(p.code,32);b.writeVarLong(p.revision);},b->new Request(b.readUtf(4),b.readUtf(32),b.readVarLong()));
  public Type<Request> type(){return TYPE;}
 }
 record Snapshot(UUID player,boolean available,long revision,String kind,String code,List<Cape> capes) implements CustomPacketPayload {
  Snapshot{capes=List.copyOf(capes);if(revision<0||revision>999999999999999999L||!valid(kind,code)||capes.size()>32||capes.stream().map(Cape::id).distinct().count()!=capes.size()||kind.equals("CAPE")&&capes.stream().noneMatch(c->c.id.equals(code)))throw new IllegalArgumentException("Invalid wardrobe snapshot");}
  static final Type<Snapshot> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath("animeoncraft","wardrobe_snapshot_v1"));
  static final StreamCodec<FriendlyByteBuf,Snapshot> CODEC=StreamCodec.of((b,p)->{b.writeUUID(p.player);b.writeBoolean(p.available);b.writeVarLong(p.revision);b.writeUtf(p.kind,4);b.writeUtf(p.code,32);b.writeVarInt(p.capes.size());for(var c:p.capes){b.writeUtf(c.id,32);b.writeUtf(c.name,80);}},b->{var id=b.readUUID();boolean available=b.readBoolean();long revision=b.readVarLong();String kind=b.readUtf(4),code=b.readUtf(32);int n=b.readVarInt();if(n<0||n>32)throw new IllegalArgumentException("Too many capes");var capes=new ArrayList<Cape>();for(int i=0;i<n;i++)capes.add(new Cape(b.readUtf(32),b.readUtf(80)));return new Snapshot(id,available,revision,kind,code,capes);});
  public Type<Snapshot> type(){return TYPE;}
 }
}
