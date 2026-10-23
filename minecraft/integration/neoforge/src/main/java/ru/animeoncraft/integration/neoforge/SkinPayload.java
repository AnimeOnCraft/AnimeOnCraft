package ru.animeoncraft.integration.neoforge;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
record SkinPayload(UUID player,byte[] png,boolean slim,String cape,String clan) implements CustomPacketPayload {
 static final Type<SkinPayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath("animeoncraft","player_appearance_v3"));
 static final StreamCodec<FriendlyByteBuf,SkinPayload> CODEC=StreamCodec.of((b,v)->{b.writeUUID(v.player());b.writeByteArray(v.png());b.writeBoolean(v.slim());b.writeUtf(v.cape(),32);b.writeUtf(v.clan(),12);},b->new SkinPayload(b.readUUID(),b.readByteArray(24000),b.readBoolean(),b.readUtf(32),b.readUtf(12)));
 SkinPayload {if(png.length>24000||(!cape.isEmpty()&&!cape.equals("beta"))||(!clan.isEmpty()&&!ClanCodes.valid(clan)))throw new IllegalArgumentException("Invalid appearance");}
 @Override public Type<SkinPayload> type(){return TYPE;}
}
