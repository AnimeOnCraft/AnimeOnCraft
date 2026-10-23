package ru.animeoncraft.integration.core;

import java.time.Duration;
import java.util.concurrent.CompletionStage;
import ru.animeoncraft.integration.contract.AdmissionDecision;
import ru.animeoncraft.integration.contract.AdmissionRequest;

@FunctionalInterface
public interface AdmissionTransport {
    CompletionStage<AdmissionDecision> authorize(AdmissionRequest request, Duration deadline);
}
