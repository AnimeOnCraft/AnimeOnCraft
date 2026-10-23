package ru.animeoncraft.integration.contract;

import java.util.Objects;
import java.util.UUID;

public record AdmissionRequest(
        UUID serverId,
        UUID bootId,
        UUID connectionId,
        UUID requestNonce,
        UUID minecraftUuid,
        int protocolMajor) {
    public AdmissionRequest {
        Objects.requireNonNull(serverId, "serverId");
        Objects.requireNonNull(bootId, "bootId");
        Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(requestNonce, "requestNonce");
        Objects.requireNonNull(minecraftUuid, "minecraftUuid");
        if (protocolMajor != 1) throw new IllegalArgumentException("unsupported protocol major");
    }
}
