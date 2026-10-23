package ru.animeoncraft.integration.neoforge;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Textured Blockbench meshes, small local animations, and no gameplay entities or world lights. */
final class DetailedCosmetics {
    private static final Logger LOGGER = LoggerFactory.getLogger("AnimeOnCraft");
    static final Set<String> CODES = Set.of("FOX_EARS", "ONI_MASK", "CYAN_VISOR", "RIFT_LANTERN",
        "STAR_CHAIN", "SHARD_CROWN", "ORBIT_HALO", "RIFT_MANTLE");
    private static final Map<String, CosmeticMesh> meshes = new HashMap<>();
    private static final Set<String> unavailable = new java.util.HashSet<>();
    static final Map<String, Long> draws = new HashMap<>();
    static long foxDraws;
    private static Object loadedResources;

    private DetailedCosmetics() {}
    static void reload(net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((net.minecraft.server.packs.resources.ResourceManagerReloadListener) manager -> {
            meshes.clear(); unavailable.clear(); loadedResources = manager;
        });
    }
    static boolean draw(String code, PoseStack pose, MultiBufferSource buffers, int light,
            float age, float movement, boolean crouching) {
        if (!CODES.contains(code)) return false;
        var resources = Minecraft.getInstance().getResourceManager();
        if (loadedResources != resources) {
            loadedResources = resources; meshes.clear(); unavailable.clear();
        }
        if (unavailable.contains(code)) return false;
        String stem = code.toLowerCase(java.util.Locale.ROOT);
        if (!meshes.containsKey(code)) {
            var geometry = ResourceLocation.fromNamespaceAndPath("animeoncraft", "cosmetics/" + stem + ".json");
            try (var input = resources.open(geometry);
                 var reader = new java.io.InputStreamReader(input, StandardCharsets.UTF_8)) {
                var mesh = CosmeticMesh.read(reader); meshes.put(code, mesh);
                LOGGER.info("AOC detailed {} loaded: {} textured faces", code, mesh.faces().size());
            } catch (IOException | RuntimeException failure) {
                unavailable.add(code);
                LOGGER.warn("AOC detailed {} unavailable", code, failure);
                return false;
            }
        }
        var mesh = meshes.get(code);
        var texture = ResourceLocation.fromNamespaceAndPath("animeoncraft", "textures/cosmetics/" + stem + ".png");
        for (boolean emissive : new boolean[]{false, true}) {
            var consumer = buffers.getBuffer(emissive ? RenderType.eyes(texture) : RenderType.entityCutoutNoCull(texture));
            for (var part : mesh.parts().entrySet()) {
                pose.pushPose();
                animate(pose, part.getKey(), part.getValue(), age, movement, crouching);
                for (var face : mesh.faces()) {
                    if (!face.part().equals(part.getKey()) || emissive && !face.emissive()) continue;
                    emit(consumer, pose, face, face.emissive() ? LightTexture.FULL_BRIGHT : light);
                }
                pose.popPose();
            }
        }
        draws.merge(code, 1L, Long::sum);
        if (code.equals("FOX_EARS")) foxDraws++;
        return true;
    }
    private static void emit(VertexConsumer consumer, PoseStack pose, CosmeticMesh.Face face, int light) {
        for (int i = 0; i < 4; i++) {
            var vertex = face.vertices().get(Math.min(i, face.vertices().size() - 1));
            consumer.addVertex(pose.last(), vertex.x() / 16, vertex.y() / 16, vertex.z() / 16)
                .setColor(0xffffffff).setUv(vertex.u(), vertex.v()).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light).setNormal(pose.last(), face.nx(), face.ny(), face.nz());
        }
    }
    private static void animate(PoseStack pose, String part, CosmeticMesh.Pivot pivot, float age,
            float movement, boolean crouching) {
        if (part.equals("static")) return;
        pose.translate(pivot.x() / 16, pivot.y() / 16, pivot.z() / 16);
        if (part.matches("panel_[0-3]")) {
            int index = Integer.parseInt(part.substring(6));
            pose.mulPose(Axis.XP.rotationDegrees(8 + movement * 12 + (float)Math.sin(age * .07 + index * .8) * (2 + movement * 2)));
            pose.mulPose(Axis.ZP.rotationDegrees((float)Math.sin(age * .05 + index) * 1.5f));
            if (crouching) pose.scale(1, .78f, 1);
        } else if (part.matches("shard_[0-4]")) {
            int index = Integer.parseInt(part.substring(6));
            pose.translate(0, Math.sin(age * .035 + index) * .12 / 16, 0);
        } else if (part.equals("orbit")) {
            pose.mulPose(Axis.YP.rotationDegrees(age * .6f));
        } else if (part.equals("lantern_swing") || part.equals("chain_star")) {
            pose.mulPose(Axis.ZP.rotationDegrees((float)Math.sin(age * .07) * (1 + movement * 5)));
        }
        pose.translate(-pivot.x() / 16, -pivot.y() / 16, -pivot.z() / 16);
    }
}
