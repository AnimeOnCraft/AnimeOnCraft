package ru.animeoncraft.integration.core;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

public record IntegrationSettings(URI backendBaseUri, Duration leaseDuration, Duration renewalInterval) {
    public IntegrationSettings {
        Objects.requireNonNull(backendBaseUri, "backendBaseUri");
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        Objects.requireNonNull(renewalInterval, "renewalInterval");
        if (!"https".equalsIgnoreCase(backendBaseUri.getScheme())) {
            throw new IllegalArgumentException("backendBaseUri must use HTTPS");
        }
        if (leaseDuration.compareTo(Duration.ofSeconds(60)) > 0 || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("lease duration exceeds protocol limit");
        }
        if (renewalInterval.compareTo(leaseDuration) >= 0 || renewalInterval.isNegative()) {
            throw new IllegalArgumentException("renewal must occur before lease expiry");
        }
    }
}
