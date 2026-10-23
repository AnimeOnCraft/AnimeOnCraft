package ru.animeoncraft.integration.contract;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AdmissionDecision(
        UUID decisionId,
        UUID connectionId,
        UUID requestNonce,
        boolean allowed,
        String reason,
        long accessEpoch,
        Instant issuedAt,
        Instant expiresAt,
        Instant leaseExpiresAt) {
    public AdmissionDecision {
        Objects.requireNonNull(decisionId, "decisionId");
        Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(requestNonce, "requestNonce");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
        if (accessEpoch < 0) throw new IllegalArgumentException("accessEpoch must be non-negative");
        if (expiresAt.isBefore(issuedAt) || leaseExpiresAt.isBefore(expiresAt)) {
            throw new IllegalArgumentException("invalid decision lifetime");
        }
    }
}
