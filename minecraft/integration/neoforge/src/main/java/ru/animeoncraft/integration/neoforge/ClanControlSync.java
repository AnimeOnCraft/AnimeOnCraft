package ru.animeoncraft.integration.neoforge;

import com.google.gson.JsonObject;
import java.util.Map;
import net.minecraft.server.MinecraftServer;

final class ClanControlSync {
    private static volatile Map<String,String> controllers = Map.of();
    private static final java.util.concurrent.atomic.AtomicBoolean IN_FLIGHT = new java.util.concurrent.atomic.AtomicBoolean();
    private static long nextRefreshTick;
    private static volatile boolean ready;
    static void reset(){controllers=Map.of();ready=false;nextRefreshTick=0;}
    static boolean ready(){return ready;}
    static void accept(Map<String,String> replacement){controllers=Map.copyOf(replacement);ready=true;}
    private ClanControlSync() {}

    static void refresh(MinecraftServer server) {
        if (!IN_FLIGHT.compareAndSet(false,true)) return;
        try {
            ServerSettings settings = ServerSettings.from(System.getenv());
            HttpJson.get(settings.heartbeatUrl().resolve("../territories/control"), settings.key()).thenAccept(reply -> {
                if (!reply.has("items") || !reply.get("items").isJsonArray()) return;
                var replacement = new java.util.HashMap<String,String>();
                for (var value : reply.getAsJsonArray("items")) {
                    JsonObject row = value.getAsJsonObject();
                    String territory=row.get("territory_code").getAsString(), owner=row.get("short_code").getAsString();
                    if (!ClanCodes.known(territory) || !ClanCodes.known(owner)) throw new IllegalArgumentException("Invalid territory controller");
                    replacement.put(territory,owner);
                }
                WarWindows.replace(reply.getAsJsonArray("wars"));
                server.execute(()->{
                    boolean changed=!ready||!controllers.equals(replacement);
                    accept(replacement);
                    if(changed)ClanTerritories.broadcast(server);
                });
            }).whenComplete((ignored,failure)->IN_FLIGHT.set(false));
        } catch (RuntimeException ignored) { IN_FLIGHT.set(false); }
    }

    static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() < nextRefreshTick) return;
        nextRefreshTick = server.getTickCount() + 600;
        refresh(server);
    }

    static String owner(String configuredOwner) { return configuredOwner == null ? null : controllers.getOrDefault(configuredOwner, configuredOwner); }
}
