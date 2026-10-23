package ru.animeoncraft.integration.neoforge;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AdmissionHttpClientTest {
    private HttpServer server;
    private ServerSettings settings;
    private final UUID connection = UUID.randomUUID();
    private final UUID player = UUID.nameUUIDFromBytes("OfflinePlayer:aocgameprobe".getBytes(StandardCharsets.UTF_8));
    private volatile String response;
    private volatile int status;
    private volatile boolean credentialMatched;
    private volatile boolean requestMatched;

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        settings = new ServerSettings(UUID.randomUUID(), URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/game/admission/redeem"), "a".repeat(64));
        status = 200;
        response = accepted().toString();
        server.createContext("/api/v1/game/admission/redeem", exchange -> {
            credentialMatched = ("Bearer " + settings.key()).equals(exchange.getRequestHeaders().getFirst("Authorization"));
            var request = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            requestMatched = connection.toString().equals(request.get("connection_id").getAsString())
                    && "aocgameprobe".equals(request.get("game_name").getAsString()) && player.toString().equals(request.get("minecraft_uuid").getAsString());
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
    }

    @AfterEach void stop() { server.stop(0); }

    private JsonObject accepted() {
        JsonObject value = new JsonObject();
        value.addProperty("accepted", true); value.addProperty("connection_id", connection.toString());
        value.addProperty("game_name", "aocgameprobe"); value.addProperty("minecraft_uuid", player.toString());
        value.addProperty("user_id", UUID.randomUUID().toString());
        return value;
    }

    private void redeem() throws Exception {
        AdmissionHttpClient.redeem(settings, connection, "aocgameprobe", player, "gt_" + "x".repeat(43)).get(8, TimeUnit.SECONDS);
    }

    @Test void acceptsOnlyBoundSuccessAndSendsServerCredential() throws Exception {
        redeem(); assertTrue(credentialMatched); assertTrue(requestMatched);
    }

    @Test void rejectsDenialMalformedAndUnboundResponses() {
        for (String field : new String[]{"connection_id", "game_name", "minecraft_uuid", "user_id"}) {
            var value = accepted(); value.addProperty(field, "invalid"); response = value.toString();
            assertThrows(Exception.class, this::redeem);
        }
        var value = accepted(); value.addProperty("accepted", false); response = value.toString(); assertThrows(Exception.class, this::redeem);
        value.addProperty("accepted", "true"); response = value.toString(); assertThrows(Exception.class, this::redeem);
        response = "not json"; assertThrows(Exception.class, this::redeem);
        response = "x".repeat(8193); assertThrows(Exception.class, this::redeem);
    }

    @Test void rejectsErrorsAndRedirects() {
        for (int code : new int[]{302,401,403,500,503}) { status = code; assertThrows(Exception.class, this::redeem); }
    }

    @Test void distinguishesMandatoryUpdateWithoutExposingResponseBody(){
        status=426;response="private server detail";
        var failure=assertThrows(Exception.class,this::redeem);
        assertTrue(HttpJson.updateRequired(failure));assertFalse(failure.toString().contains(response));
        status=403;assertFalse(HttpJson.updateRequired(assertThrows(Exception.class,this::redeem)));
    }

    @Test void validatesEndpointAndRedactsCredential() {
        var env = Map.of("AOC_GAME_SERVER_ID", settings.serverId().toString(), "AOC_GAME_SERVER_KEY", settings.key(), "AOC_GAME_REDEEM_URL", settings.redeemUrl().toString());
        assertEquals(settings, ServerSettings.from(env));
        assertFalse(settings.toString().contains(settings.key()));
        for (String url : new String[]{"http://example.com/api/v1/game/admission/redeem", "https://example.com/other", "https://user@example.com/api/v1/game/admission/redeem", "https://example.com/api/v1/game/admission/redeem?q=x"}) {
            var bad = new java.util.HashMap<>(env); bad.put("AOC_GAME_REDEEM_URL", url);
            assertThrows(IllegalArgumentException.class, () -> ServerSettings.from(bad));
        }
        assertThrows(IllegalArgumentException.class, () -> ServerSettings.from(Map.of()));
    }
}
