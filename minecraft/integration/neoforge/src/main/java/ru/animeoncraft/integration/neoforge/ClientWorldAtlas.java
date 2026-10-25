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
    private static int icons; // 0 waiting for Xaero, 1 own icon layer, -1 Xaero API mismatch: letter waypoints instead
    static void tick(ClientTickEvent.Post event) {
        var mods = net.neoforged.fml.ModList.get(); if (!mods.isLoaded("xaerominimap")) return;
        if (icons == 0) try {
            if (XaeroMinimapIcons.install() & (!mods.isLoaded("xaeroworldmap") || XaeroWorldMapIcons.install())) icons = 1;
        } catch (LinkageError failure) { icons = -1; System.getLogger("AnimeOnCraft").log(System.Logger.Level.WARNING, "AOC map icons unavailable, using Xaero waypoints", failure); }
        if (icons == -1) XaeroAtlasWaypoints.sync(active); else XaeroAtlasWaypoints.clear();
    }
    static boolean iconLayer() { return icons == 1; }
    static void clear() { active=null; }
    static void logout(ClientPlayerNetworkEvent.LoggingOut event) { clear(); if(net.neoforged.fml.ModList.get().isLoaded("xaerominimap"))XaeroAtlasWaypoints.clear(); }
}
