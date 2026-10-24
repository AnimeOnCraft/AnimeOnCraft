package ru.animeoncraft.integration.neoforge;

import java.util.Arrays;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import xaero.map.highlight.AbstractHighlighter;

/** Server-supplied clan borders over Xaero's native terrain, including its cave layers. No terrain image. */
public final class XaeroClanHighlighter extends AbstractHighlighter {
    private final ThreadLocal<int[]> pixels = ThreadLocal.withInitial(() -> new int[256]);
    private final int borderWidth;

    public XaeroClanHighlighter() { this(false); }
    public XaeroClanHighlighter(boolean minimap) { super(true); borderWidth = minimap ? 1 : 16; }

    private static boolean intersects(RegionMapPayload.Region r, long x, long z, int size) {
        return r.minX() < x + size && r.maxX() > x && r.minZ() < z + size && r.maxZ() > z;
    }
    private static boolean intersects(Territory r, long x, long z, int size) {
        return r.minX() < x + size && r.maxX() > x && r.minZ() < z + size && r.maxZ() > z;
    }
    private static RegionMapPayload.Region at(List<RegionMapPayload.Region> regions, int x, int z) {
        for (var r : regions) if (r.contains(x, z)) return r;
        return null;
    }
    private static Territory rectangleAt(int x, int z) {
        for (var r : ClientTerritories.regions) if (r.contains(x, z)) return r;
        return null;
    }
    private static boolean crosses(RegionMapPayload.Region r, int x, int z, int size) {
        for(int i=0,j=r.xs().length-1;i<r.xs().length;j=i++)
            if(RegionPolicy.segmentHitsBox(r.xs()[j],r.zs()[j],r.xs()[i],r.zs()[i],x,z,x+size,z+size))return true;
        return false;
    }
    private static boolean nearBorder(RegionMapPayload.Region r, double x, double z, int width) {
        double limit=(double)width*width;
        for(int i=0,j=r.xs().length-1;i<r.xs().length;j=i++) {
            double dx=r.xs()[i]-r.xs()[j],dz=r.zs()[i]-r.zs()[j],length=dx*dx+dz*dz;
            double t=length==0?0:Math.clamp(((x-r.xs()[j])*dx+(z-r.zs()[j])*dz)/length,0,1);
            double distanceX=x-(r.xs()[j]+t*dx),distanceZ=z-(r.zs()[j]+t*dz);
            if(distanceX*distanceX+distanceZ*distanceZ<=limit)return true;
        }
        return false;
    }

    @Override public int calculateRegionHash(ResourceKey<Level> dimension, int x, int z) {
        if (!Level.OVERWORLD.equals(dimension)) return 0;
        int hash = 0;
        for (var r : ClientTerritories.polygons) if (intersects(r, (long)x * 512, (long)z * 512, 512)) {
            hash = 31 * hash + r.clan().hashCode(); hash = 31 * hash + r.owner().hashCode();
            hash = 31 * hash + r.color(); hash = 31 * hash + Arrays.hashCode(r.xs()); hash = 31 * hash + Arrays.hashCode(r.zs());
        }
        for (var r : ClientTerritories.regions) if (intersects(r, (long)x * 512, (long)z * 512, 512)) hash = 31 * hash + r.hashCode();
        return hash;
    }

    @Override public boolean regionHasHighlights(ResourceKey<Level> dimension, int x, int z) {
        if (!Level.OVERWORLD.equals(dimension)) return false;
        for (var r : ClientTerritories.polygons) if (intersects(r, (long)x * 512, (long)z * 512, 512)) return true;
        for (var r : ClientTerritories.regions) if (intersects(r, (long)x * 512, (long)z * 512, 512)) return true;
        return false;
    }

    @Override public boolean chunkIsHighlit(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        if (!Level.OVERWORLD.equals(dimension)) return false;
        int bx = chunkX << 4, bz = chunkZ << 4;
        for (var r : ClientTerritories.polygons) if (intersects(r, bx, bz, 16)) {
            if(r.contains(bx+8,bz+8))return true;
            if(!crosses(r,bx,bz,16))continue;
            // Checking just corners and centre loses thin polygon tips at the edge of a state.
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) if (r.contains(bx + x, bz + z)) return true;
        }
        for (var r : ClientTerritories.regions) if (intersects(r, bx, bz, 16)) return true;
        return false;
    }

    @Override public int[] getChunkHighlitColor(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        if (!Level.OVERWORLD.equals(dimension)) return null;
        int bx = chunkX << 4, bz = chunkZ << 4;
        var candidates = ClientTerritories.polygons.stream().filter(r -> intersects(r, (long)bx - 1, (long)bz - 1, 18)).toList();
        var result = pixels.get(); boolean any = false;
        if (!ClientTerritories.polygons.isEmpty()) {
            for(var r:candidates)if(r.contains(bx+8,bz+8)&&!crosses(r,bx-borderWidth,bz-borderWidth,16+2*borderWidth)){
                Arrays.fill(result,Integer.reverseBytes(r.color())|41);return result;
            }
            var grid = new RegionMapPayload.Region[18 * 18];
            for (int z = -1; z <= 16; z++) for (int x = -1; x <= 16; x++) grid[(z + 1) * 18 + x + 1] = at(candidates, bx + x, bz + z);
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                var r = grid[(z + 1) * 18 + x + 1];
                if (r == null) { result[(z << 4) | x] = 0; continue; }
                boolean border = grid[z * 18 + x + 1] != r || grid[(z + 2) * 18 + x + 1] != r
                    || grid[(z + 1) * 18 + x] != r || grid[(z + 1) * 18 + x + 2] != r;
                if(!border&&borderWidth>1)border=nearBorder(r,bx+x+0.5,bz+z+0.5,borderWidth);
                // Xaero packs colours as BBGGRRAA. A light tint keeps native terrain readable.
                result[(z << 4) | x] = Integer.reverseBytes(r.color()) | (border ? 242 : 41); any = true;
            }
        } else {
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                var r = rectangleAt(bx + x, bz + z);
                if (r == null) { result[(z << 4) | x] = 0; continue; }
                boolean border = bx + x - r.minX() < borderWidth || r.maxX() - 1 - bx - x < borderWidth
                    || bz + z - r.minZ() < borderWidth || r.maxZ() - 1 - bz - z < borderWidth;
                result[(z << 4) | x] = Integer.reverseBytes(r.color()) | (border ? 242 : 41); any = true;
            }
        }
        return any ? result : null;
    }

    @Override public Component getBlockHighlightSubtleTooltip(ResourceKey<Level> dimension, int x, int z) {
        if (!Level.OVERWORLD.equals(dimension)) return null;
        var polygon = at(ClientTerritories.polygons, x, z);
        if (polygon != null) return Component.literal("Земли " + polygon.clan() + " · владелец " + polygon.owner()).withColor(polygon.color());
        var rectangle = rectangleAt(x, z);
        return rectangle == null ? null : Component.literal("Земли " + rectangle.clan() + " · владелец " + rectangle.owner()).withColor(rectangle.color());
    }
    @Override public Component getBlockHighlightBluntTooltip(ResourceKey<Level> dimension, int x, int z) {
        return getBlockHighlightSubtleTooltip(dimension, x, z);
    }
    @Override public void addMinimapBlockHighlightTooltips(List<Component> tooltips, ResourceKey<Level> dimension, int x, int y, int z) {
        var text = getBlockHighlightSubtleTooltip(dimension, x, z); if (text != null) tooltips.add(text);
    }
}
