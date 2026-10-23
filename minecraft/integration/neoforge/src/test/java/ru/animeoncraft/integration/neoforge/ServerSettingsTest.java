package ru.animeoncraft.integration.neoforge;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class ServerSettingsTest {
    @Test void permitsOnlyHttpsOrLoopbackIntegrationEndpointsWithoutRedirectParameters(){
        assertEquals("127.0.0.1",ServerSettings.validatedHeartbeat("http://127.0.0.1:3001/integration/v1/servers/heartbeat").getHost());
        assertEquals("https",ServerSettings.validatedHeartbeat("https://example.org/integration/v1/servers/heartbeat").getScheme());
        for(String url:java.util.List.of("http://example.org/integration/v1/servers/heartbeat","https://user:password@example.org/integration/v1/servers/heartbeat","https://example.org/integration/v1/servers/heartbeat?redirect=x","https://example.org/wrong"))
            assertThrows(IllegalArgumentException.class,()->ServerSettings.validatedHeartbeat(url));
    }
}
