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
