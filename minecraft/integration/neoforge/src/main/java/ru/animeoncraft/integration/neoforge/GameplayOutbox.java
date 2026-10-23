package ru.animeoncraft.integration.neoforge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Small disk outbox: a successful API acknowledgement is the only reason to remove an event. */
final class GameplayOutbox {
    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean();
    private static long nextAttempt;
    private static final System.Logger LOG = System.getLogger("AnimeOnCraft");
    private GameplayOutbox() {}

    static void store(Path directory, JsonObject body) throws IOException {
        UUID id = UUID.fromString(body.get("event_id").getAsString());
        Files.createDirectories(directory);
        Path destination = directory.resolve(id + ".json");
        if (Files.exists(destination)) return;
        Path temporary = directory.resolve(id + ".pending");
        Files.writeString(temporary, body.toString());
        Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
    }

    static Optional<Path> first(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return Optional.empty();
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().matches("[a-f0-9-]{36}\\.json"))
                .sorted(Comparator.comparingLong(GameplayOutbox::modified).thenComparing(path -> path.getFileName().toString())).findFirst();
        }
    }
    private static long modified(Path path) {
        try { return Files.getLastModifiedTime(path).toMillis(); }
        catch (IOException ignored) { return Long.MAX_VALUE; }
    }

    static java.util.concurrent.CompletableFuture<Boolean> deliver(Path path,
        java.util.function.Function<JsonObject, java.util.concurrent.CompletableFuture<JsonObject>> sender) throws IOException {
        final JsonObject body;
        try {
            if(Files.size(path)>16384)throw new IllegalArgumentException("Oversized event");
            body=JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            UUID id=UUID.fromString(body.get("event_id").getAsString());
            if(!path.getFileName().toString().equals(id+".json"))throw new IllegalArgumentException("Mismatched event filename");
        }catch(RuntimeException invalid){
            quarantine(path,"invalid");
            return java.util.concurrent.CompletableFuture.completedFuture(false);
        }
        java.util.concurrent.CompletableFuture<JsonObject> request;
        try{request=sender.apply(body);}catch(RuntimeException failure){request=java.util.concurrent.CompletableFuture.failedFuture(failure);}
        return request.handle((reply, failure) -> {
            boolean confirmed=false;
            try{confirmed=failure==null&&reply!=null&&reply.has("accepted")&&reply.get("accepted").getAsBoolean()
                &&body.get("event_id").equals(reply.get("event_id"));}catch(RuntimeException invalidReply){ }
            if(confirmed){try{Files.deleteIfExists(path);return true;}catch(IOException ignored){return false;}}
            try{
                if(body.has("expires_at")&&java.time.Instant.now().isAfter(java.time.Instant.parse(body.get("expires_at").getAsString())))quarantine(path,"expired");
                else Files.setLastModifiedTime(path,java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
            }catch(IOException|RuntimeException ignored){ }
            return false;
        });
    }
    private static void quarantine(Path path,String reason)throws IOException{
        Files.move(path,path.resolveSibling(path.getFileName()+"."+reason+"-"+UUID.randomUUID()),StandardCopyOption.ATOMIC_MOVE);
        LOG.log(System.Logger.Level.WARNING,"Gameplay event retained for operator review: "+reason);
    }

    static void tick(ServerTickEvent.Post event) {
        long now = System.nanoTime();
        if (now < nextAttempt || !IN_FLIGHT.compareAndSet(false, true)) return;
        nextAttempt = now + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        try {
            Path directory = event.getServer().getWorldPath(LevelResource.ROOT).resolve("animeoncraft-gameplay-outbox");
            var pending = first(directory);
            if (pending.isEmpty()) { IN_FLIGHT.set(false); return; }
            Path path = pending.get();
            ServerSettings settings = ServerSettings.from(System.getenv());
            deliver(path, value -> {
                if(!value.has("server_id")||!settings.serverId().toString().equals(value.get("server_id").getAsString()))throw new IllegalStateException("Outbox server identity differs");
                return HttpJson.post(settings.heartbeatUrl().resolve(value.has("competition_id") ? "../competitions/world-boss/result" : value.has("capture_finish") ? "../capture/finish" : value.has("war_window_id") ? "../territories/capture" : "../events"), settings.key(), value);
            })
                .whenComplete((acknowledged, failure) -> IN_FLIGHT.set(false));
        } catch (IOException | RuntimeException failure) {
            IN_FLIGHT.set(false);
            LOG.log(System.Logger.Level.WARNING, "Gameplay outbox temporarily unavailable; pending events retained");
        }
    }
}
