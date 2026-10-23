package ru.animeoncraft.integration.neoforge;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

record TerritoryPayload(List<Territory> regions) implements CustomPacketPayload {
    static final Type<TerritoryPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("animeoncraft", "clan_territories"));
    static final StreamCodec<FriendlyByteBuf, TerritoryPayload> CODEC = StreamCodec.of((buffer, value) -> {
        buffer.writeVarInt(value.regions().size());
        for (var region : value.regions()) {
            buffer.writeUtf(region.clan(), 12);
            buffer.writeUtf(region.owner(),12);
            buffer.writeInt(region.minX()).writeInt(region.minZ()).writeInt(region.maxX()).writeInt(region.maxZ()).writeInt(region.color());
        }
    }, buffer -> {
        int count = buffer.readVarInt();
        if (count < 0 || count > ClanCodes.MAX) throw new IllegalArgumentException("Invalid territory count");
        var regions = new ArrayList<Territory>();
        for (int i = 0; i < count; i++) {
            String clan=buffer.readUtf(12),owner=buffer.readUtf(12);
            regions.add(new Territory(clan, buffer.readInt(), buffer.readInt(), buffer.readInt(), buffer.readInt(), buffer.readInt(),owner));
        }
        return new TerritoryPayload(regions);
    });

    TerritoryPayload {
        regions = List.copyOf(regions);
        if (regions.size() > ClanCodes.MAX) throw new IllegalArgumentException("Too many clan territories");
        var names = new HashSet<String>();
        for (int i = 0; i < regions.size(); i++) {
            if (!names.add(regions.get(i).clan())) throw new IllegalArgumentException("Duplicate clan territory");
            for (int j = 0; j < i; j++) if (regions.get(i).overlaps(regions.get(j))) throw new IllegalArgumentException("Overlapping territories");
        }
    }

    @Override public Type<TerritoryPayload> type() { return TYPE; }
}
