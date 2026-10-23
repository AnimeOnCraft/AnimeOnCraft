package ru.animeoncraft.integration.neoforge;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

record ServerSettings(UUID serverId, URI redeemUrl, String key) {
    static ServerSettings from(Map<String, String> environment) {
        URI url = URI.create(environment.getOrDefault("AOC_GAME_REDEEM_URL", ""));
        boolean loopbackHttp = "http".equals(url.getScheme()) && "127.0.0.1".equals(url.getHost());
        if (!("https".equals(url.getScheme()) || loopbackHttp) || url.getHost() == null
                || url.getUserInfo() != null || url.getQuery() != null || url.getFragment() != null
                || !"/api/v1/game/admission/redeem".equals(url.getPath())) {
            throw new IllegalArgumentException("Invalid admission endpoint");
        }
        String key = environment.getOrDefault("AOC_GAME_SERVER_KEY", "");
        if (!key.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Missing server credential");
        return new ServerSettings(UUID.fromString(environment.getOrDefault("AOC_GAME_SERVER_ID", "")), url, key);
    }

    URI heartbeatUrl() {
        String configured = System.getenv("AOC_GAME_HEARTBEAT_URL");
        if (configured != null && !configured.isBlank()) return validatedHeartbeat(configured);
        String base = redeemUrl.getHost().equals("127.0.0.1") ? "http://127.0.0.1:3001" : redeemUrl.getScheme() + "://" + redeemUrl.getAuthority();
        return validatedHeartbeat(base + "/integration/v1/servers/heartbeat");
    }

    static URI validatedHeartbeat(String value){
        URI url=URI.create(value);
        boolean loopback="http".equals(url.getScheme())&&"127.0.0.1".equals(url.getHost());
        if(!(loopback||"https".equals(url.getScheme()))||url.getHost()==null||url.getUserInfo()!=null||url.getQuery()!=null||url.getFragment()!=null
            ||!"/integration/v1/servers/heartbeat".equals(url.getPath()))throw new IllegalArgumentException("Invalid integration endpoint");
        return url;
    }
    @Override public String toString() { return "ServerSettings[credential=REDACTED]"; }
}
