package ru.animeoncraft.integration.neoforge;

/** Read-only diagnostics for Xaero's independently selected native cave layers. */
@SuppressWarnings("deprecation") // The pinned minimap still exposes its session through this facade.
public final class XaeroAtlasLayers {
    private XaeroAtlasLayers() {}

    public static boolean surfaceWorldMap() {
        if (net.minecraft.client.Minecraft.getInstance() == null) return true;
        var session = xaero.map.WorldMapSession.getCurrentSession();
        return session == null || session.getMapProcessor().getCurrentCaveLayer() == Integer.MAX_VALUE;
    }

    public static boolean surfaceMinimap() {
        if (net.minecraft.client.Minecraft.getInstance() == null) return true;
        var session = xaero.common.XaeroMinimapSession.getCurrentSession();
        return session == null || !session.getMinimapProcessor().isCaveModeDisplayed();
    }
}
