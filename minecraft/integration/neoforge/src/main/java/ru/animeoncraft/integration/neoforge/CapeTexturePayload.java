package ru.animeoncraft.integration.neoforge;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
/** Optional extension: old clients keep the existing appearance protocol. */
record CapeTexturePayload(UUID player,byte[] png) implements CustomPacketPayload {
 static final int MAX_BYTES=196608;
 static final Type<CapeTexturePayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath("animeoncraft","cape_texture_v1"));
 static final StreamCodec<FriendlyByteBuf,CapeTexturePayload> CODEC=StreamCodec.of((b,v)->{b.writeUUID(v.player());b.writeByteArray(v.png());},b->new CapeTexturePayload(b.readUUID(),b.readByteArray(MAX_BYTES)));
 CapeTexturePayload {if(png.length>MAX_BYTES)throw new IllegalArgumentException("Cape texture too large");}
 @Override public Type<CapeTexturePayload> type(){return TYPE;}
}
