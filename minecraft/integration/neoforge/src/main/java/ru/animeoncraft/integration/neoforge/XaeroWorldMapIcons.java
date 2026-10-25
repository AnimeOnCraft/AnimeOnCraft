package ru.animeoncraft.integration.neoforge;

import java.util.Iterator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.level.Level;
import xaero.map.WorldMap;
import xaero.map.element.render.ElementReader;
import xaero.map.element.render.ElementRenderInfo;
import xaero.map.element.render.ElementRenderLocation;
import xaero.map.element.render.ElementRenderProvider;
import xaero.map.element.render.ElementRenderer;
import xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider;

/** AOC place icons as a native world-map element layer: Xaero keeps culling, hover and draw order. */
final class XaeroWorldMapIcons extends ElementRenderer<WorldAtlasData.Point, XaeroWorldMapIcons.Context, XaeroWorldMapIcons> {
    static final class Context { boolean overworld; double scale = 1; }
    static int drawn;
    private static boolean installed;
    private XaeroWorldMapIcons() { super(new Context(), new Provider(), new Reader()); }
    /** False until Xaero has built its element handler. */
    static boolean install() {
        if (installed) return true; if (WorldMap.mapElementRenderHandler == null) return false;
        WorldMap.mapElementRenderHandler.add(new XaeroWorldMapIcons()); installed = true;
        System.getLogger("AnimeOnCraft").log(System.Logger.Level.INFO, "AOC world-map place icons registered"); return true;
    }
    static float size(WorldAtlasData.Point p, double scale) {
        float base = switch (MapIcons.style(p).priority()) { case 3 -> 42; case 2 -> 36; case 1 -> 31; default -> 27; };
        return scale < 0.1 ? base * 0.85f : base;
    }

    @Override public void preRender(ElementRenderInfo info, MultiBufferSource.BufferSource buffers, MultiTextureRenderTypeRendererProvider provider, boolean shadow) {
        context.overworld = Level.OVERWORLD.equals(info.mapDimension); context.scale = info.scale; buffers.endBatch();
        if (!shadow) drawn = 0;
    }
    @Override public void postRender(ElementRenderInfo info, MultiBufferSource.BufferSource buffers, MultiTextureRenderTypeRendererProvider provider, boolean shadow) {}
    @Override public void renderElementShadow(WorldAtlasData.Point p, boolean hovered, float optionalScale, double partialX, double partialY, ElementRenderInfo info, GuiGraphics g, MultiBufferSource.BufferSource buffers, MultiTextureRenderTypeRendererProvider provider) {}
    @Override public boolean renderElement(WorldAtlasData.Point p, boolean hovered, double depth, float optionalScale, double partialX, double partialY, ElementRenderInfo info, GuiGraphics g, MultiBufferSource.BufferSource buffers, MultiTextureRenderTypeRendererProvider provider) {
        var pose = g.pose(); pose.pushPose(); pose.translate(partialX, partialY, depth);
        float size = size(p, info.scale) * optionalScale * (hovered ? 1.15f : 1f);
        MapIcons.draw(g, p, size, 1f);
        if (hovered || MapIcons.style(p).priority() == 3) MapIcons.label(g, p, size / 2 + 4, (float) Minecraft.getInstance().getWindow().getGuiScale());
        pose.popPose(); drawn++; return true;
    }
    @Override public boolean shouldRender(ElementRenderLocation location, boolean shadow) { return location == ElementRenderLocation.WORLD_MAP && ClientWorldAtlas.active(); }
    @Override public int getOrder() { return 50; }

    static final class Provider extends ElementRenderProvider<WorldAtlasData.Point, Context> {
        private Iterator<WorldAtlasData.Point> iterator;
        @Override public void begin(ElementRenderLocation location, Context context) { iterator = MapIcons.points().iterator(); }
        @Override public boolean hasNext(ElementRenderLocation location, Context context) { return iterator != null && iterator.hasNext(); }
        @Override public WorldAtlasData.Point getNext(ElementRenderLocation location, Context context) { return iterator.next(); }
        @Override public void end(ElementRenderLocation location, Context context) { iterator = null; }
    }

    static final class Reader extends ElementReader<WorldAtlasData.Point, Context, XaeroWorldMapIcons> {
        @Override public boolean isHidden(WorldAtlasData.Point p, Context c) { return !c.overworld || c.scale < MapIcons.minScale(p); }
        @Override public double getRenderX(WorldAtlasData.Point p, Context c, float partialTicks) { return p.x() + 0.5; }
        @Override public double getRenderZ(WorldAtlasData.Point p, Context c, float partialTicks) { return p.z() + 0.5; }
        @Override public int getInteractionBoxLeft(WorldAtlasData.Point p, Context c, float partialTicks) { return -half(p, c); }
        @Override public int getInteractionBoxRight(WorldAtlasData.Point p, Context c, float partialTicks) { return half(p, c); }
        @Override public int getInteractionBoxTop(WorldAtlasData.Point p, Context c, float partialTicks) { return -half(p, c); }
        @Override public int getInteractionBoxBottom(WorldAtlasData.Point p, Context c, float partialTicks) { return half(p, c); }
        @Override public int getRenderBoxLeft(WorldAtlasData.Point p, Context c, float partialTicks) { return -24; }
        @Override public int getRenderBoxRight(WorldAtlasData.Point p, Context c, float partialTicks) { return 400; }
        @Override public int getRenderBoxTop(WorldAtlasData.Point p, Context c, float partialTicks) { return -40; }
        @Override public int getRenderBoxBottom(WorldAtlasData.Point p, Context c, float partialTicks) { return 40; }
        @Override public int getLeftSideLength(WorldAtlasData.Point p, Minecraft mc) { return 9 + mc.font.width(p.name()); }
        @Override public String getMenuName(WorldAtlasData.Point p) { return p.name(); }
        @Override public String getFilterName(WorldAtlasData.Point p) { return p.name(); }
        @Override public int getMenuTextFillLeftPadding(WorldAtlasData.Point p) { return 0; }
        @Override public int getRightClickTitleBackgroundColor(WorldAtlasData.Point p) { return 0xFF000000 | MapIcons.style(p).color(); }
        @Override public boolean shouldScaleBoxWithOptionalScale() { return true; }
        @Override public boolean isInteractable(ElementRenderLocation location, WorldAtlasData.Point p) { return location == ElementRenderLocation.WORLD_MAP; }
        private static int half(WorldAtlasData.Point p, Context c) { return Math.round(size(p, c.scale) / 2); }
    }
}
