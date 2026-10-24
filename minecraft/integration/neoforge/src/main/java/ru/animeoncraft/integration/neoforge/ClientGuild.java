package ru.animeoncraft.integration.neoforge;

import java.util.*;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client copy of the adventurer card. Clicks are queued like the beta pass; the server answers with a fresh card. */
final class ClientGuild {
    private static GuildPayloads.Snapshot data;
    private static long received, requested, messageAt;
    private static String message = "";
    private static final ArrayDeque<GuildPayloads.Request> pending = new ArrayDeque<>();
    private static final Map<UUID, Integer> ranks = new HashMap<>();
    private ClientGuild() {}
    static boolean supported() { var c = Minecraft.getInstance().getConnection(); return c != null && c.hasChannel(GuildPayloads.Request.TYPE.id()); }
    static GuildPayloads.Snapshot data() { return data; }
    /** Guild rank of a player as the server last showed it; -1 without a card or unknown. */
    static int rank(UUID player) { return ranks.getOrDefault(player, -1); }
    /** Cooldown counted down locally from the last card. */
    static int cooldown() { return data == null ? 0 : (int) Math.max(0, data.cooldown() - (System.currentTimeMillis() - received) / 1000); }
    static int window() { return data == null ? 0 : (int) Math.max(0, data.window() - (System.currentTimeMillis() - received) / 1000); }
    static String message() { return System.currentTimeMillis() - messageAt < 8000 ? message : ""; }
    static void request(String action, int rank, int index) {
        if (!supported()) return; var r = new GuildPayloads.Request(action, rank, index);
        if (!action.equals("GET")) { if (pending.size() < 6) pending.add(r); }
        else if (pending.isEmpty() && System.nanoTime() - requested > 3_000_000_000L) pending.add(r);
        tick();
    }
    static void tick() { if (!supported() || pending.isEmpty() || System.nanoTime() - requested < 300_000_000L) return; requested = System.nanoTime(); PacketDistributor.sendToServer(pending.remove()); }
    static void accept(GuildPayloads.Snapshot packet, IPayloadContext c) {
        c.enqueueWork(() -> {
            var mc = Minecraft.getInstance();
            if (mc.player == null || mc.getConnection() == null || mc.getConnection().getConnection() != c.connection()) return;
            data = packet; received = System.currentTimeMillis();
            if (!packet.message().isEmpty()) { message = packet.message(); messageAt = received; }
            if (packet.screen() == GuildPayloads.SCREEN_WELCOME) mc.setScreen(new GuildScreen(true));
            else if (packet.screen() == GuildPayloads.SCREEN_BOARD && !(mc.screen instanceof GuildScreen)) mc.setScreen(new GuildScreen(false));
        });
    }
    static void badge(GuildPayloads.Badge packet, IPayloadContext c) {
        c.enqueueWork(() -> {
            var mc = Minecraft.getInstance();
            if (mc.player == null || mc.getConnection() == null || mc.getConnection().getConnection() != c.connection()) return;
            if (packet.rank() < 0) ranks.remove(packet.player()); else if (ranks.size() < 4096 || ranks.containsKey(packet.player())) ranks.put(packet.player(), packet.rank());
        });
    }
    static void logout(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) { data = null; pending.clear(); ranks.clear(); requested = 0; message = ""; messageAt = 0; }
}
