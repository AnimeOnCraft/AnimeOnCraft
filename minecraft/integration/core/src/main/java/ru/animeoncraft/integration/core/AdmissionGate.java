package ru.animeoncraft.integration.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import ru.animeoncraft.integration.contract.AdmissionDecision;
import ru.animeoncraft.integration.contract.AdmissionRequest;

public final class AdmissionGate {
    public static final Duration DEADLINE = Duration.ofSeconds(3);
    private final AdmissionTransport transport;
    private final Executor networkExecutor;
    private final Clock clock;

    public AdmissionGate(AdmissionTransport transport, Executor networkExecutor, Clock clock) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.networkExecutor = Objects.requireNonNull(networkExecutor, "networkExecutor");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public CompletionStage<AdmissionDecision> authorize(AdmissionRequest request) {
        Objects.requireNonNull(request, "request");
        return CompletableFuture.supplyAsync(() -> request, networkExecutor)
                .thenCompose(value -> transport.authorize(value, DEADLINE))
                .thenApply(decision -> validateBinding(request, decision, clock.instant()))
                .orTimeout(DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static AdmissionDecision validateBinding(
            AdmissionRequest request, AdmissionDecision decision, Instant now) {
        if (!request.connectionId().equals(decision.connectionId())
                || !request.requestNonce().equals(decision.requestNonce())) {
            throw new IllegalStateException("admission response binding mismatch");
        }
        if (!now.isBefore(decision.expiresAt())) {
            throw new IllegalStateException("admission response expired");
        }
        return decision;
    }
}
