package ru.animeoncraft.integration.neoforge;

import com.mojang.blaze3d.systems.RenderSystem;
import java.util.Iterator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.level.Level;
import xaero.common.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider;
import xaero.hud.minimap.element.render.MinimapElementReader;
import xaero.hud.minimap.element.render.MinimapElementRenderInfo;
import xaero.hud.minimap.element.render.MinimapElementRenderLocation;
import xaero.hud.minimap.element.render.MinimapElementRenderProvider;
import xaero.hud.minimap.element.render.MinimapElementRenderer;

/** AOC place icons over the minimap. Off-map places stay hidden, except Sora and the guild pinned to the rim. */
final class XaeroMinimapIcons extends MinimapElementRenderer<WorldAtlasData.Point, XaeroMinimapIcons.Context> {
    static final class Context { boolean overworld; }
    static int drawn;
    private static boolean installed;
    private XaeroMinimapIcons() { super(new Reader(), new Provider(), new Context()); }
    /** False until Xaero has built its minimap. */
    static boolean install() {
        if (installed) return true;
        var mod = xaero.common.HudMod.INSTANCE; if (mod == null || mod.getMinimap() == null || mod.getMinimap().getOverMapRendererHandler() == null) return false;
        mod.getMinimap().getOverMapRendererHandler().add(new XaeroMinimapIcons()); installed = true;
        System.getLogger("AnimeOnCraft").log(System.Logger.Level.INFO, "AOC minimap place icons registered"); return true;
    }

    @Override public boolean renderElement(WorldAtlasData.Point p, boolean highlighted, boolean outOfBounds, double depth, float optionalScale, double partialX, double partialY, MinimapElementRenderInfo info, GuiGraphics g, MultiBufferSource.BufferSource buffers) {
        boolean pinned = MapIcons.style(p).priority() == 3 || "ADVENTURER_GUILD".equals(p.kind());
        if (outOfBounds && !pinned) return false;
        float size = switch (MapIcons.style(p).priority()) { case 3 -> 26; case 2 -> 22; case 1 -> 20; default -> 18; } * (outOfBounds ? 0.8f : 1f);
        var pose = g.pose(); pose.pushPose(); pose.translate(partialX, partialY, depth);
        MapIcons.draw(g, p, size, outOfBounds ? 0.85f : 1f);
        pose.popPose(); drawn++; return true;
    }
    @Override public void preRender(MinimapElementRenderInfo info, MultiBufferSource.BufferSource buffers, MultiTextureRenderTypeRendererProvider provider) {
        RenderSystem.disableDepthTest(); buffers.endBatch(); context.overworld = Level.OVERWORLD.equals(info.mapDimension); drawn = 0;
    }
    @Override public void postRender(MinimapElementRenderInfo info, MultiBufferSource.BufferSource buffers, MultiTextureRenderTypeRendererProvider provider) {}
    @Override public boolean shouldRender(MinimapElementRenderLocation location) { return location == MinimapElementRenderLocation.OVER_MINIMAP && ClientWorldAtlas.active(); }

    static final class Provider extends MinimapElementRenderProvider<WorldAtlasData.Point, Context> {
        private Iterator<WorldAtlasData.Point> iterator;
        @Override public void begin(MinimapElementRenderLocation location, Context context) { iterator = MapIcons.points().iterator(); }
        @Override public boolean hasNext(MinimapElementRenderLocation location, Context context) { return iterator != null && iterator.hasNext(); }
        @Override public WorldAtlasData.Point getNext(MinimapElementRenderLocation location, Context context) { return iterator.next(); }
        @Override public void end(MinimapElementRenderLocation location, Context context) { iterator = null; }
    }

    static final class Reader extends MinimapElementReader<WorldAtlasData.Point, Context> {
        @Override public boolean isHidden(WorldAtlasData.Point p, Context c) { return !c.overworld; }
        @Override public double getRenderX(WorldAtlasData.Point p, Context c, float partialTicks) { return p.x() + 0.5; }
        @Override public double getRenderY(WorldAtlasData.Point p, Context c, float partialTicks) { return p.y(); }
        @Override public double getRenderZ(WorldAtlasData.Point p, Context c, float partialTicks) { return p.z() + 0.5; }
        @Override public int getInteractionBoxLeft(WorldAtlasData.Point p, Context c, float partialTicks) { return -6; }
        @Override public int getInteractionBoxRight(WorldAtlasData.Point p, Context c, float partialTicks) { return 6; }
        @Override public int getInteractionBoxTop(WorldAtlasData.Point p, Context c, float partialTicks) { return -6; }
        @Override public int getInteractionBoxBottom(WorldAtlasData.Point p, Context c, float partialTicks) { return 6; }
        @Override public int getRenderBoxLeft(WorldAtlasData.Point p, Context c, float partialTicks) { return -8; }
        @Override public int getRenderBoxRight(WorldAtlasData.Point p, Context c, float partialTicks) { return 8; }
        @Override public int getRenderBoxTop(WorldAtlasData.Point p, Context c, float partialTicks) { return -8; }
        @Override public int getRenderBoxBottom(WorldAtlasData.Point p, Context c, float partialTicks) { return 8; }
        @Override public int getLeftSideLength(WorldAtlasData.Point p, Minecraft mc) { return 9 + mc.font.width(p.name()); }
        @Override public String getMenuName(WorldAtlasData.Point p) { return p.name(); }
        @Override public String getFilterName(WorldAtlasData.Point p) { return p.name(); }
        @Override public int getMenuTextFillLeftPadding(WorldAtlasData.Point p) { return 0; }
        @Override public int getRightClickTitleBackgroundColor(WorldAtlasData.Point p) { return 0xFF000000 | MapIcons.style(p).color(); }
        @Override public boolean shouldScaleBoxWithOptionalScale() { return false; }
    }
}
