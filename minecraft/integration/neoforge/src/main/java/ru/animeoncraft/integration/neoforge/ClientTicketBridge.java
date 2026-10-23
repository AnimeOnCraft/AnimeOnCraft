package ru.animeoncraft.integration.neoforge;

import com.google.gson.JsonObject;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.SocketAddress;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

final class ClientTicketBridge {
    private ClientTicketBridge() {}

    static CompletableFuture<String> ticket(AdmissionPayloads.Challenge challenge, SocketAddress remote) {
        Map<String, String> env = System.getenv();
        // The game destination is pinned by the launcher, while the credential bridge stays loopback-only.
        int targetPort = Integer.parseInt(env.getOrDefault("AOC_GAME_TARGET_PORT", "0"));
        String targetHost=env.getOrDefault("AOC_GAME_TARGET_HOST","127.0.0.1");
        if (!(remote instanceof InetSocketAddress address) || address.getAddress() == null
                || address.getPort() != targetPort || !matchesHost(targetHost,address)) {
            throw new IllegalArgumentException("Unapproved game destination");
        }
        int port = Integer.parseInt(env.getOrDefault("AOC_GAME_BRIDGE_PORT", "0"));
        String key = env.getOrDefault("AOC_GAME_BRIDGE_KEY", "");
        if (port < 1 || port > 65535 || !key.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Launcher bridge unavailable");
        JsonObject body = new JsonObject();
        body.addProperty("server_id", challenge.server().toString());
        body.addProperty("connection_id", challenge.connection().toString());
        return HttpJson.post(URI.create("http://127.0.0.1:" + port + "/ticket"), key, body).thenApply(reply -> {
            String ticket = reply.get("ticket").getAsString();
            if (!ticket.matches("gt_[A-Za-z0-9_-]{43}")) throw new IllegalStateException("Invalid launcher ticket");
            return ticket;
        });
    }
    private static boolean matchesHost(String host,InetSocketAddress address){
        try{return java.util.Arrays.asList(java.net.InetAddress.getAllByName(host)).contains(address.getAddress());}
        catch(java.net.UnknownHostException failure){return false;}
    }
}
