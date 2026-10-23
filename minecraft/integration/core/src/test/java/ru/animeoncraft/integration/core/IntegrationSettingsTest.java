package ru.animeoncraft.integration.core;

import static org.junit.jupiter.api.Assertions.assertThrows;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

final class IntegrationSettingsTest {
    @Test
    void refusesPlainHttp() {
        assertThrows(IllegalArgumentException.class, () -> new IntegrationSettings(URI.create("http://localhost"), Duration.ofSeconds(60), Duration.ofSeconds(15)));
    }
}
