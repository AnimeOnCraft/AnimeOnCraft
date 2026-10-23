package ru.animeoncraft.integration.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.ForkJoinPool;
import ru.animeoncraft.integration.contract.AdmissionDecision;
import ru.animeoncraft.integration.contract.AdmissionRequest;
import org.junit.jupiter.api.Test;

final class AdmissionGateTest {
    private static final Instant NOW = Instant.parse("2026-10-09T08:00:00Z");

    @Test
    void acceptsOnlyBoundFreshDecision() {
        var request = request();
        var decision = decision(request, request.connectionId(), request.requestNonce());
        var gate = new AdmissionGate((ignored, deadline) -> java.util.concurrent.CompletableFuture.completedFuture(decision), ForkJoinPool.commonPool(), Clock.fixed(NOW, ZoneOffset.UTC));
        assertEquals(decision, gate.authorize(request).toCompletableFuture().join());
    }

    @Test
    void rejectsResponseForDifferentConnection() {
        var request = request();
        var decision = decision(request, UUID.randomUUID(), request.requestNonce());
        var gate = new AdmissionGate((ignored, deadline) -> java.util.concurrent.CompletableFuture.completedFuture(decision), Runnable::run, Clock.fixed(NOW, ZoneOffset.UTC));
        assertThrows(java.util.concurrent.CompletionException.class, () -> gate.authorize(request).toCompletableFuture().join());
    }

    private static AdmissionRequest request() {
        return new AdmissionRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1);
    }

    private static AdmissionDecision decision(AdmissionRequest request, UUID connection, UUID nonce) {
        return new AdmissionDecision(UUID.randomUUID(), connection, nonce, true, "ALLOW", 1, NOW, NOW.plusSeconds(5), NOW.plusSeconds(60));
    }
}
