package ru.animeoncraft.integration.neoforge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.List;

final class HttpJson {
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();

    private HttpJson() {}
    private static final class LauncherUpdateRequired extends IllegalStateException {
        private static final long serialVersionUID=1L;
        LauncherUpdateRequired(){super("Launcher update required");}
    }
    static boolean updateRequired(Throwable failure){
        for(int depth=0;failure!=null&&depth<8;depth++,failure=failure.getCause())if(failure instanceof LauncherUpdateRequired)return true;
        return false;
    }

    static CompletableFuture<JsonObject> post(URI uri, String credential, JsonObject body) { return post(uri, credential, body, 40000); }

    /** Same as {@link #post(URI, String, JsonObject)} with a larger reply limit (the region catalog carries polygons). */
    static CompletableFuture<JsonObject> post(URI uri, String credential, JsonObject body, int limit) {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json").header("Authorization", "Bearer " + credential)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        return CLIENT.sendAsync(request, ignored -> new LimitedBody(limit))
                .orTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
                .thenApply(response -> {
                    if(response.statusCode()==426)throw new LauncherUpdateRequired();
                    if (response.statusCode() != 200) {
                        throw new IllegalStateException("Admission service rejected request");
                    }
                    // Do not include response bodies/exceptions in a game disconnect or log.
                    return JsonParser.parseString(response.body()).getAsJsonObject();
                });
    }

    static CompletableFuture<JsonObject> get(URI uri, String credential) {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + credential).GET().build();
        return CLIENT.sendAsync(request, ignored -> new LimitedBody(40000)).orTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
                .thenApply(response -> { if (response.statusCode() != 200) throw new IllegalStateException("Integration GET rejected"); return JsonParser.parseString(response.body()).getAsJsonObject(); });
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<String> {
        private final CompletableFuture<String> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        private final int limit;
        LimitedBody(int limit) { this.limit = limit; }

        @Override public CompletionStage<String> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(Long.MAX_VALUE); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (bytes.size() + buffer.remaining() > limit) {
                    subscription.cancel(); result.completeExceptionally(new IllegalStateException("Admission response too large")); return;
                }
                byte[] part = new byte[buffer.remaining()]; buffer.get(part); bytes.writeBytes(part);
            }
        }
        @Override public void onError(Throwable failure) { result.completeExceptionally(new IllegalStateException("Admission transport failed")); }
        @Override public void onComplete() { result.complete(bytes.toString(StandardCharsets.UTF_8)); }
    }
}
