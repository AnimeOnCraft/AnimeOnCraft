package ru.animeoncraft.integration.neoforge;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;

/** Vector outlines remain visible even outside Xaero's explored terrain/cache. Never writes terrain or textures. */
public final class XaeroClanMapOverlay {
    static long drawnRevision = Long.MIN_VALUE;
    static int drawnPolygons;
    private XaeroClanMapOverlay() {}

    public static void render(GuiGraphics graphics, double cameraX, double cameraZ, double scale) {
        var polygons = ClientTerritories.polygons;
        var rectangles = ClientTerritories.regions;
        drawnPolygons = polygons.size() + rectangles.size(); drawnRevision = ClientTerritories.revision();
        if (drawnPolygons == 0) return;
        graphics.flush();RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();RenderSystem.setShader(GameRenderer::getPositionColorShader);
        var buffer = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        var matrix = graphics.pose().last().pose(); double halfWidth = 0.6 / Math.max(scale, 0.001);
        for (var r : polygons) for (int i=0,j=r.xs().length-1;i<r.xs().length;j=i++)
            edge(buffer,matrix,r.xs()[j]-cameraX,r.zs()[j]-cameraZ,r.xs()[i]-cameraX,r.zs()[i]-cameraZ,halfWidth,r.color());
        for (var r : rectangles) {
            double x0=r.minX()-cameraX,z0=r.minZ()-cameraZ,x1=r.maxX()-cameraX,z1=r.maxZ()-cameraZ;
            edge(buffer,matrix,x0,z0,x1,z0,halfWidth,r.color());edge(buffer,matrix,x1,z0,x1,z1,halfWidth,r.color());
            edge(buffer,matrix,x1,z1,x0,z1,halfWidth,r.color());edge(buffer,matrix,x0,z1,x0,z0,halfWidth,r.color());
        }
        BufferUploader.drawWithShader(buffer.buildOrThrow());RenderSystem.enableCull();
    }
    /** Pulsing "you are here" halo under Xaero's own arrow, which is easy to lose among red radar dots. */
    public static void playerHalo(GuiGraphics graphics, double cameraX, double cameraZ, double scale) {
        var player = net.minecraft.client.Minecraft.getInstance().player; if (player == null) return;
        float partial = net.minecraft.client.Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true);
        double x = net.minecraft.util.Mth.lerp(partial, player.xo, player.getX()) - cameraX, z = net.minecraft.util.Mth.lerp(partial, player.zo, player.getZ()) - cameraZ;
        double pulse = (System.currentTimeMillis() % 1600) / 1600.0, unit = 1 / Math.max(scale, 0.001);
        graphics.flush(); RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc(); RenderSystem.disableCull(); RenderSystem.setShader(GameRenderer::getPositionColorShader);
        var buffer = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR); var matrix = graphics.pose().last().pose();
        ring(buffer, matrix, x, z, 0, 15 * unit, 0x55E8FF, 70);
        ring(buffer, matrix, x, z, 13 * unit, 15.5 * unit, 0xFFFFFF, 230);
        ring(buffer, matrix, x, z, (15 + 18 * pulse) * unit, (17 + 18 * pulse) * unit, 0x55E8FF, (int) (200 * (1 - pulse)));
        BufferUploader.drawWithShader(buffer.buildOrThrow()); RenderSystem.enableCull();
    }
    private static void ring(BufferBuilder b, org.joml.Matrix4f m, double x, double z, double inner, double outer, int rgb, int alpha) {
        for (int i = 0; i < 40; i++) {
            double a0 = i * Math.PI / 20, a1 = (i + 1) * Math.PI / 20;
            double ix0 = x + Math.cos(a0) * inner, iz0 = z + Math.sin(a0) * inner, ix1 = x + Math.cos(a1) * inner, iz1 = z + Math.sin(a1) * inner;
            double ox0 = x + Math.cos(a0) * outer, oz0 = z + Math.sin(a0) * outer, ox1 = x + Math.cos(a1) * outer, oz1 = z + Math.sin(a1) * outer;
            vertex(b, m, ix0, iz0, rgb, alpha); vertex(b, m, ox0, oz0, rgb, alpha); vertex(b, m, ox1, oz1, rgb, alpha);
            vertex(b, m, ix0, iz0, rgb, alpha); vertex(b, m, ox1, oz1, rgb, alpha); vertex(b, m, ix1, iz1, rgb, alpha);
        }
    }
    private static void vertex(BufferBuilder b, org.joml.Matrix4f m, double x, double z, int rgb, int alpha) {
        b.addVertex(m, (float) x, (float) z, 0).setColor(rgb >> 16 & 255, rgb >> 8 & 255, rgb & 255, alpha);
    }
    private static void edge(BufferBuilder b,org.joml.Matrix4f m,double x0,double z0,double x1,double z1,double halfWidth,int rgb) {
        double dx=x1-x0,dz=z1-z0,length=Math.hypot(dx,dz);if(length==0)return;
        double nx=-dz/length*halfWidth,nz=dx/length*halfWidth;
        vertex(b,m,x0+nx,z0+nz,rgb);vertex(b,m,x1+nx,z1+nz,rgb);vertex(b,m,x0-nx,z0-nz,rgb);
        vertex(b,m,x0-nx,z0-nz,rgb);vertex(b,m,x1+nx,z1+nz,rgb);vertex(b,m,x1-nx,z1-nz,rgb);
    }
    private static void vertex(BufferBuilder b,org.joml.Matrix4f m,double x,double z,int rgb) {
        b.addVertex(m,(float)x,(float)z,0).setColor(rgb>>16&255,rgb>>8&255,rgb&255,235);
    }
}
