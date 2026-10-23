package ru.animeoncraft.integration.neoforge;

import com.google.gson.JsonObject;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

final class AdmissionHttpClient {
    private AdmissionHttpClient() {}

    static CompletableFuture<GameIdentity> redeem(ServerSettings settings, UUID connection, String name, UUID uuid, String ticket) {
        JsonObject body = new JsonObject();
        body.addProperty("ticket", ticket);
        body.addProperty("game_name", name);
        body.addProperty("minecraft_uuid", uuid.toString());
        body.addProperty("connection_id", connection.toString());
        return HttpJson.post(settings.redeemUrl(), settings.key(), body).thenApply(reply -> {
            if (!reply.has("accepted") || !reply.get("accepted").isJsonPrimitive()
                    || !reply.getAsJsonPrimitive("accepted").isBoolean() || !reply.get("accepted").getAsBoolean()
                    || !connection.toString().equals(reply.get("connection_id").getAsString())
                    || !name.equals(reply.get("game_name").getAsString())
                    || !uuid.toString().equals(reply.get("minecraft_uuid").getAsString())) {
                throw new IllegalStateException("Admission response binding mismatch");
            }
            UUID.fromString(reply.get("user_id").getAsString());
            return GameIdentity.parse(reply);
        });
    }
}
