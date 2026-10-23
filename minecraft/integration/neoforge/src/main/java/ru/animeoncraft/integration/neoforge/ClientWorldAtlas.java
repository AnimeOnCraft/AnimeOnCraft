package ru.animeoncraft.integration.neoforge;

import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Public map markers only. Terrain and cave layers are entirely owned by Xaero. */
public final class ClientWorldAtlas {
    private static volatile WorldAtlasData active;
    private ClientWorldAtlas() {}
    static WorldAtlasData current() { return active; }
    public static boolean active() { return active != null; }
    static void accept(WorldAtlasPayload payload, IPayloadContext context) { context.enqueueWork(() -> {
        var connection=net.minecraft.client.Minecraft.getInstance().getConnection();
        if(connection!=null&&connection.getConnection()==context.connection())apply(payload.data());
    }); }
    static void apply(WorldAtlasData data) {
        if (data.equals(active)) return;
        if (!WorldAtlasAsset.BOUNDS.worldId().equals(data.worldId()) || !WorldAtlasAsset.BOUNDS.sha256().equals(data.imageSha256())) { clear(); return; }
        active=data;
    }
    static void tick(ClientTickEvent.Post event) {
        if (net.neoforged.fml.ModList.get().isLoaded("xaerominimap")) XaeroAtlasWaypoints.sync(active);
    }
    static void clear() { active=null; }
    static void logout(ClientPlayerNetworkEvent.LoggingOut event) { clear(); if(net.neoforged.fml.ModList.get().isLoaded("xaerominimap"))XaeroAtlasWaypoints.clear(); }
}
