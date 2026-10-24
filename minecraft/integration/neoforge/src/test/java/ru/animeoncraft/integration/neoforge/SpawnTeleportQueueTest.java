package ru.animeoncraft.integration.neoforge;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class SpawnTeleportQueueTest {
    private static final long NOW=1_800_000_000_000L;
    @Test void tenSecondsAndSpamCannotShortenOrRestartCountdown(){
        var queue=new SpawnTeleportQueue();var id=UUID.randomUUID();
        assertEquals(new SpawnTeleportQueue.Reply(SpawnTeleportQueue.Status.STARTED,10),queue.request(id,NOW));
        assertTrue(queue.advance(id,NOW+999).isEmpty());
        assertEquals(9,queue.advance(id,NOW+1000).orElseThrow());
        assertTrue(queue.advance(id,NOW+1001).isEmpty());
        assertEquals(new SpawnTeleportQueue.Reply(SpawnTeleportQueue.Status.PENDING,5),queue.request(id,NOW+5000));
        assertEquals(1,queue.advance(id,NOW+9999).orElseThrow());
        assertFalse(queue.succeeded(id,NOW+9999));
        assertEquals(0,queue.advance(id,NOW+10_000).orElseThrow());
        assertTrue(queue.succeeded(id,NOW+10_000));
        assertFalse(queue.succeeded(id,NOW+10_001));
    }
    @Test void fullFiveMinutesStartAfterSuccessfulTeleport(){
        var queue=new SpawnTeleportQueue();var id=UUID.randomUUID();queue.request(id,NOW);
        assertTrue(queue.succeeded(id,NOW+12_000)); // a delayed server tick must not shorten cooldown
        assertEquals(new SpawnTeleportQueue.Reply(SpawnTeleportQueue.Status.COOLDOWN,300),queue.request(id,NOW+12_001));
        assertEquals(new SpawnTeleportQueue.Reply(SpawnTeleportQueue.Status.COOLDOWN,1),queue.request(id,NOW+311_999));
        assertEquals(SpawnTeleportQueue.Status.STARTED,queue.request(id,NOW+312_000).status());
    }
    @Test void CancellationDoesNotSpendCooldown(){
        var queue=new SpawnTeleportQueue();var id=UUID.randomUUID();queue.request(id,NOW);queue.cancel(id);
        assertTrue(queue.pendingPlayers().isEmpty());assertFalse(queue.succeeded(id,NOW+20_000));
        assertEquals(SpawnTeleportQueue.Status.STARTED,queue.request(id,NOW+20_001).status());
        assertTrue(queue.cooldowns().isEmpty());
    }
    @Test void restoredCooldownSurvivesReconnectButPendingCountdownDoesNot(){
        var queue=new SpawnTeleportQueue();var id=UUID.randomUUID();var other=UUID.randomUUID();
        queue.request(id,NOW);queue.succeeded(id,NOW+10_000);queue.request(other,NOW);
        queue.cancel(id); // logout must not erase the cooldown
        var restored=new SpawnTeleportQueue();queue.cooldowns().forEach(restored::restore);
        assertTrue(restored.pendingPlayers().isEmpty());
        assertEquals(SpawnTeleportQueue.Status.COOLDOWN,restored.request(id,NOW+20_000).status());
        assertEquals(SpawnTeleportQueue.Status.STARTED,restored.request(other,NOW+20_000).status());
    }
    @Test void playersHaveIndependentDeadlinesAndExpiredCooldownIsUsable(){
        var queue=new SpawnTeleportQueue();var a=UUID.randomUUID();var b=UUID.randomUUID();
        queue.request(a,NOW);queue.request(b,NOW+4000);queue.succeeded(a,NOW+10_000);
        assertEquals(4,queue.advance(b,NOW+10_000).orElseThrow());
        assertEquals(SpawnTeleportQueue.Status.COOLDOWN,queue.request(a,NOW+10_000).status());
        queue.restore(b,NOW-1);queue.cancel(b);
        assertEquals(SpawnTeleportQueue.Status.STARTED,queue.request(b,NOW+10_000).status());
    }
}
