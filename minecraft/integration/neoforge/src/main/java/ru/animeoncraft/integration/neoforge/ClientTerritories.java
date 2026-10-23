package ru.animeoncraft.integration.neoforge;

import java.util.List;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** No JourneyMap types here: the admission mod also works without the optional map. */
public final class ClientTerritories {
    static volatile List<Territory> regions = List.of();
    /** States world: clan regions as polygons (empty in the old world, where {@link #regions} is used). */
    static volatile List<RegionMapPayload.Region> polygons = List.of();
    private static volatile long revision;
    public static long revision() { return revision; }
    static void invalidate() { revision++; redraw.run(); }
    static Runnable redraw = () -> {};
    private ClientTerritories() {}

    static void accept(TerritoryPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> { regions = List.copyOf(payload.regions()); polygons = List.of(); revision++; redraw.run(); });
    }

    static void acceptPolygons(RegionMapPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> { polygons = List.copyOf(payload.regions()); regions = List.of(); revision++; redraw.run(); });
    }

    /** Polygon region containing the column, or null. */
    static RegionMapPayload.Region polygonAt(int x, int z) {
        for (var region : polygons) if (region.contains(x, z)) return region;
        return null;
    }

    static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        regions = List.of(); polygons = List.of();
        revision++;
        redraw.run();
    }
}
