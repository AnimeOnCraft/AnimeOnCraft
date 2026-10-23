package ru.animeoncraft.integration.neoforge;

import java.util.ArrayList;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

record WorldAtlasPayload(WorldAtlasData data) implements CustomPacketPayload {
    static final Type<WorldAtlasPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("animeoncraft", "world_atlas"));
    static final StreamCodec<FriendlyByteBuf, WorldAtlasPayload> CODEC = StreamCodec.of((buf, payload) -> {
        var data = payload.data(); buf.writeUtf(data.worldId(),64).writeUtf(data.imageSha256(),64).writeVarInt(data.points().size());
        for (var p : data.points()) buf.writeUtf(p.id(),64).writeUtf(p.name(),160).writeUtf(p.kind(),32)
            .writeInt(p.x()).writeInt(p.y()).writeInt(p.z()).writeVarInt(p.radius()).writeInt(p.color()).writeUtf(p.owner(),12).writeUtf(p.state(),32);
    }, buf -> {
        String world = buf.readUtf(64), hash = buf.readUtf(64); int count = buf.readVarInt();
        if (count < 0 || count > WorldAtlasData.MAX_POINTS) throw new IllegalArgumentException("Atlas point count");
        var points = new ArrayList<WorldAtlasData.Point>();
        for (int i=0;i<count;i++) points.add(new WorldAtlasData.Point(buf.readUtf(64),buf.readUtf(160),buf.readUtf(32),buf.readInt(),buf.readInt(),buf.readInt(),buf.readVarInt(),buf.readInt(),buf.readUtf(12),buf.readUtf(32)));
        return new WorldAtlasPayload(new WorldAtlasData(world,hash,points));
    });
    @Override public Type<WorldAtlasPayload> type() { return TYPE; }
}
