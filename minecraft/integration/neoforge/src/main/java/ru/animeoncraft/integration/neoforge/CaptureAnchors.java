package ru.animeoncraft.integration.neoforge;

import com.google.gson.JsonObject;
import java.io.IOException;
import net.minecraft.server.MinecraftServer;

/** Standing floor saved by the builder. Never infer it from a tower roof or an unloaded heightmap. */
final class CaptureAnchors {
    private CaptureAnchors() {}
    static int height(MinecraftServer server, CaptureSites.Point point) {
        if (point.y() != null) return point.y();
        var progress=LayoutJobs.progress(server);
        if(!progress.has("world_id")||!progress.get("world_id").getAsString().equals(CaptureSites.view().worldId()))return server.overworld().getMinBuildHeight()-1000;
        var floors = progress.getAsJsonObject("floors");
        return floors != null && floors.has(point.code()) ? floors.get(point.code()).getAsInt() : server.overworld().getMinBuildHeight() - 1000;
    }
    static void record(MinecraftServer server, String code, int y) {
        try {
            var progress = LayoutJobs.progress(server);
            if (!progress.has("floors")) progress.add("floors", new JsonObject());
            progress.getAsJsonObject("floors").addProperty(code,y);
            ResourcePits.write(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("animeoncraft-layout.json"),progress);
        } catch (IOException failure) { throw new IllegalStateException("Could not save POI floor",failure); }
    }
}
