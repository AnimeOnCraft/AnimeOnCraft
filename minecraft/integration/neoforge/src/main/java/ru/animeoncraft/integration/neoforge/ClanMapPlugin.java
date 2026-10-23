package ru.animeoncraft.integration.neoforge;

import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.IClientPlugin;
import journeymap.api.v2.client.display.Context;
import journeymap.api.v2.client.display.PolygonOverlay;
import journeymap.api.v2.client.event.MappingEvent;
import journeymap.api.v2.client.model.MapPolygon;
import journeymap.api.v2.client.model.ShapeProperties;
import journeymap.api.v2.common.JourneyMapPlugin;
import journeymap.api.v2.common.event.ClientEventRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** Discovered by JourneyMap; never class-loaded by the dedicated server. */
@JourneyMapPlugin(apiVersion = "2.0.0")
@SuppressWarnings("deprecation") // API 2.0.0 still requires Context enums in its overlay methods.
public final class ClanMapPlugin implements IClientPlugin {
    private static final String MOD_ID = "animeoncraft";
    private static final org.apache.logging.log4j.Logger LOGGER = org.apache.logging.log4j.LogManager.getLogger("AnimeOnCraft");
    private IClientAPI api;
    private boolean mapping;
    private final java.util.List<PolygonOverlay> overlays = new java.util.ArrayList<>();
    private boolean availabilityLogged;

    @Override public String getModId() { return MOD_ID; }

    @Override public void initialize(IClientAPI clientApi) {
        api = clientApi;
        ClientTerritories.redraw = this::redraw;
        ClientEventRegistry.MAPPING_EVENT.subscribe(MOD_ID, event -> {
            mapping = event.getStage() == MappingEvent.Stage.MAPPING_STARTED;
            redraw();
        });
        // Registration and HUD availability can be checked even while the game
        // window is in the background and JourneyMap's screen projection is not ready.
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientTickEvent.Post event) -> {
            if (!mapping || availabilityLogged || overlays.isEmpty()) return;
            var state = api.getUIState(Context.UI.Minimap);
            if (state.active && overlays.stream().allMatch(overlay -> api.exists(overlay) && overlay.isActiveIn(state) && overlay.isInZoomRange(state))) {
                availabilityLogged = true;
                LOGGER.info("AOC clan map available: count={} minimap_active=true", overlays.size());
            }
        });
    }

    private void redraw() {
        api.removeAll(MOD_ID);
        overlays.clear();
        availabilityLogged = false;
        if (!mapping) return;
        for (var region : ClientTerritories.polygons) {
            var shape = new ShapeProperties().setFillColor(region.color()).setFillOpacity(0.16f)
                    .setStrokeColor(region.color()).setStrokeOpacity(0.95f).setStrokeWidth(2.5f);
            var points = new java.util.ArrayList<BlockPos>();
            for (int i = 0; i < region.xs().length; i++) points.add(new BlockPos(region.xs()[i], 64, region.zs()[i]));
            var overlay = new PolygonOverlay(MOD_ID, Level.OVERWORLD, shape, new MapPolygon(points));
            overlay.setLabel(region.owner()).setTitle("Земли " + region.clan() + " · владелец " + region.owner())
                    .setOverlayGroupName("Территории кланов AnimeOnCraft")
                    .setActiveUIs(Context.UI.Fullscreen, Context.UI.Minimap)
                    .setActiveMapTypes(Context.MapType.values());
            try { api.show(overlay); overlays.add(overlay); }
            catch (Exception failure) { LOGGER.error("AOC clan region could not be displayed: {}", region.clan(), failure); }
        }
        for (var region : ClientTerritories.regions) {
            var shape = new ShapeProperties().setFillColor(region.color()).setFillOpacity(0.16f)
                    .setStrokeColor(region.color()).setStrokeOpacity(0.95f).setStrokeWidth(2.5f);
            var polygon = new MapPolygon(new BlockPos(region.minX(), 64, region.minZ()),
                    new BlockPos(region.maxX(), 64, region.minZ()), new BlockPos(region.maxX(), 64, region.maxZ()),
                    new BlockPos(region.minX(), 64, region.maxZ()));
            var overlay = new PolygonOverlay(MOD_ID, Level.OVERWORLD, shape, polygon);
            int width = region.maxX() - region.minX();
            overlay.setLabel(region.owner()).setTitle(region.clan() + " · владелец " + region.owner() + " · " + width + "×" + width + " блоков")
                    .setOverlayGroupName("Территории кланов AnimeOnCraft")
                    .setActiveUIs(Context.UI.Fullscreen, Context.UI.Minimap)
                    .setActiveMapTypes(Context.MapType.values());
            try {
                api.show(overlay);
                overlays.add(overlay);
                LOGGER.info("AOC clan territory mapped: clan={} bounds={},{},{},{}", region.clan(), region.minX(), region.minZ(), region.maxX(), region.maxZ());
            } catch (Exception failure) {
                LOGGER.error("AOC clan territory could not be displayed: {}", region.clan(), failure);
            }
        }
    }
}
