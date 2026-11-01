package ru.animeoncraft.integration.neoforge;

import java.util.ArrayDeque;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client copy of the Rift Gate screen. Clicks are queued like the guild board; the server answers with a fresh snapshot. */
final class ClientDungeon {
    private static DungeonPayloads.Snapshot data;
    private static long requested, messageAt;
    private static String message = "";
    private static final ArrayDeque<DungeonPayloads.Request> pending = new ArrayDeque<>();
    private ClientDungeon() {}
    static boolean supported() { var c = Minecraft.getInstance().getConnection(); return c != null && c.hasChannel(DungeonPayloads.Request.TYPE.id()); }
    static DungeonPayloads.Snapshot data() { return data; }
    static String message() { return System.currentTimeMillis() - messageAt < 8000 ? message : ""; }
    static void request(String action, int dungeon, int difficulty, int target) {
        if (!supported()) return; var r = new DungeonPayloads.Request(action, dungeon, difficulty, target);
        if (!action.equals("GET")) { if (pending.size() < 6) pending.add(r); }
        else if (pending.isEmpty() && System.nanoTime() - requested > 2_000_000_000L) pending.add(r);
        tick();
    }
    static void tick() { if (!supported() || pending.isEmpty() || System.nanoTime() - requested < 300_000_000L) return; requested = System.nanoTime(); PacketDistributor.sendToServer(pending.remove()); }
    static void accept(DungeonPayloads.Snapshot packet, IPayloadContext c) {
        c.enqueueWork(() -> {
            var mc = Minecraft.getInstance();
            if (mc.player == null || mc.getConnection() == null || mc.getConnection().getConnection() != c.connection()) return;
            data = packet;
            if (!packet.message().isEmpty()) { message = packet.message(); messageAt = System.currentTimeMillis(); }
            if (packet.screen() == DungeonPayloads.SCREEN_OPEN && !(mc.screen instanceof DungeonScreen)) mc.setScreen(new DungeonScreen());
            if (packet.inRun() && mc.screen instanceof DungeonScreen) mc.setScreen(null);
        });
    }
    static void logout(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) { data = null; pending.clear(); requested = 0; message = ""; messageAt = 0; }
}
