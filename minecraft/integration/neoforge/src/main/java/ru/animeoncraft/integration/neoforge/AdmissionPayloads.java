package ru.animeoncraft.integration.neoforge;

import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

final class AdmissionPayloads {
    private AdmissionPayloads() {}

    record Challenge(UUID server, UUID connection) implements CustomPacketPayload {
        static final Type<Challenge> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("animeoncraft", "admission_challenge"));
        static final StreamCodec<FriendlyByteBuf, Challenge> CODEC = StreamCodec.of(
                (buffer, value) -> { buffer.writeUUID(value.server()); buffer.writeUUID(value.connection()); },
                buffer -> new Challenge(buffer.readUUID(), buffer.readUUID()));
        @Override public Type<Challenge> type() { return TYPE; }
    }

    record Proof(UUID connection, String ticket) implements CustomPacketPayload {
        static final Type<Proof> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("animeoncraft", "admission_proof"));
        static final StreamCodec<FriendlyByteBuf, Proof> CODEC = StreamCodec.of(
                (buffer, value) -> { buffer.writeUUID(value.connection()); buffer.writeUtf(value.ticket(), 46); },
                buffer -> new Proof(buffer.readUUID(), buffer.readUtf(46)));
        Proof {
            if (!ticket.matches("gt_[A-Za-z0-9_-]{43}")) throw new IllegalArgumentException("Invalid admission proof");
        }
        @Override public Type<Proof> type() { return TYPE; }
        @Override public String toString() { return "AdmissionProof[ticket=REDACTED]"; }
    }
}
