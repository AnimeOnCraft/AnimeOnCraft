package ru.animeoncraft.integration.neoforge;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GameplayOutboxTest {
    @TempDir Path directory;

    @Test void survivesRestartAndPreservesOriginalEventOnReplay() throws Exception {
        JsonObject body = new JsonObject();
        String id = UUID.randomUUID().toString();
        body.addProperty("event_id", id);
        body.addProperty("clan_code", "NYA");
        GameplayOutbox.store(directory, body);
        Path saved = GameplayOutbox.first(directory).orElseThrow();
        assertEquals(body.toString(), Files.readString(saved));
        body.addProperty("clan_code", "SCAM");
        GameplayOutbox.store(directory, body);
        assertTrue(Files.readString(saved).contains("NYA"));
        Files.writeString(directory.resolve(UUID.randomUUID() + ".pending"), "incomplete write");
        assertEquals(saved, GameplayOutbox.first(directory).orElseThrow());
        Files.delete(saved);
        assertTrue(GameplayOutbox.first(directory).isEmpty());
    }

    @Test void rejectsPathTraversalBeforeCreatingAnyFile() {
        JsonObject body = new JsonObject();
        body.addProperty("event_id", "../escape");
        assertThrows(IllegalArgumentException.class, () -> GameplayOutbox.store(directory, body));
    }

    @Test void retainsOnNetworkFailureOrWrongAcknowledgementAndDeletesOnlyMatchingSuccess() throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("event_id", UUID.randomUUID().toString());
        GameplayOutbox.store(directory, body);
        Path saved = GameplayOutbox.first(directory).orElseThrow();
        assertFalse(GameplayOutbox.deliver(saved, value -> java.util.concurrent.CompletableFuture.failedFuture(new IOException())).join());
        assertTrue(Files.exists(saved));
        JsonObject reply = new JsonObject();
        reply.addProperty("accepted", true);
        reply.addProperty("event_id", UUID.randomUUID().toString());
        assertFalse(GameplayOutbox.deliver(saved, value -> java.util.concurrent.CompletableFuture.completedFuture(reply)).join());
        assertTrue(Files.exists(saved));
        reply.add("event_id", body.get("event_id"));
        assertTrue(GameplayOutbox.deliver(saved, value -> java.util.concurrent.CompletableFuture.completedFuture(reply)).join());
        assertFalse(Files.exists(saved));
    }
    @Test void quarantinesCorruptAndExpiredEventsWithoutDeletingEvidenceOrBlockingTheQueue() throws Exception {
        Path corrupt=directory.resolve(UUID.randomUUID()+".json");
        Files.writeString(corrupt,"incomplete {");
        assertFalse(GameplayOutbox.deliver(corrupt,value->{fail("Corrupt JSON must not be sent");return null;}).join());
        assertFalse(Files.exists(corrupt));
        JsonObject expired=new JsonObject();expired.addProperty("event_id",UUID.randomUUID().toString());
        expired.addProperty("expires_at",java.time.Instant.now().minusSeconds(1).toString());
        GameplayOutbox.store(directory,expired);
        Path saved=GameplayOutbox.first(directory).orElseThrow();
        assertFalse(GameplayOutbox.deliver(saved,value->java.util.concurrent.CompletableFuture.failedFuture(new IOException())).join());
        assertTrue(GameplayOutbox.first(directory).isEmpty());
        try(var files=Files.list(directory)){assertEquals(2,files.count(),"Both rejected events must remain available to the operator");}
    }
}
