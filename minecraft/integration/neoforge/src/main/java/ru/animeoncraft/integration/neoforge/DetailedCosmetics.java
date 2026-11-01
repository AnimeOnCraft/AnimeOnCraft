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
        "STAR_CHAIN", "SHARD_CROWN", "ORBIT_HALO", "RIFT_MANTLE",
        "SAKURA_WINGS", "KITSUNE_SAKURA", "RIFT_HALF_MASK", "OMAMORI_CHARM");
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
    /** Beyond this the glow overlay is skipped; beyond {@link #HIDE} the accessory is not drawn at all. */
    static final double GLOW = 24 * 24, HIDE = 64 * 64;
    /** A part with its faces split once at load, so a frame never scans or filters the whole mesh. */
    private record Part(String name, int kind, int index, float px, float py, float pz, float[] solid, float[] glow) {}
    private record Loaded(Part[] parts, ResourceLocation texture) {}
    private static final Map<String, Loaded> loaded = new HashMap<>();
    static boolean draw(String code, PoseStack pose, MultiBufferSource buffers, int light,
            float age, float movement, boolean crouching) {
        return draw(code, pose, buffers, light, age, movement, crouching, 0);
    }
    /** distanceSquared is from the camera to the wearer, in blocks; the menu preview passes 0. */
    static boolean draw(String code, PoseStack pose, MultiBufferSource buffers, int light,
            float age, float movement, boolean crouching, double distanceSquared) {
        if (!CODES.contains(code) || distanceSquared > HIDE) return false;
        var resources = Minecraft.getInstance().getResourceManager();
        if (loadedResources != resources) {
            loadedResources = resources; meshes.clear(); unavailable.clear(); loaded.clear();
        }
        if (unavailable.contains(code)) return false;
        var model = loaded.get(code);
        if (model == null) {
            String stem = code.toLowerCase(java.util.Locale.ROOT);
            var geometry = ResourceLocation.fromNamespaceAndPath("animeoncraft", "cosmetics/" + stem + ".json");
            try (var input = resources.open(geometry);
                 var reader = new java.io.InputStreamReader(input, StandardCharsets.UTF_8)) {
                var mesh = CosmeticMesh.read(reader); meshes.put(code, mesh);
                model = new Loaded(split(mesh), ResourceLocation.fromNamespaceAndPath("animeoncraft", "textures/cosmetics/" + stem + ".png"));
                loaded.put(code, model);
                LOGGER.info("AOC detailed {} loaded: {} textured faces", code, mesh.faces().size());
            } catch (IOException | RuntimeException failure) {
                unavailable.add(code);
                LOGGER.warn("AOC detailed {} unavailable", code, failure);
                return false;
            }
        }
        var consumer = buffers.getBuffer(RenderType.entityCutoutNoCull(model.texture()));
        for (var part : model.parts()) {
            pose.pushPose();
            animate(pose, part, age, movement, crouching);
            emit(consumer, pose, part.solid(), light);
            pose.popPose();
        }
        if (distanceSquared <= GLOW) {
            consumer = buffers.getBuffer(RenderType.eyes(model.texture()));
            for (var part : model.parts()) {
                if (part.glow().length == 0) continue;
                pose.pushPose();
                animate(pose, part, age, movement, crouching);
                emit(consumer, pose, part.glow(), LightTexture.FULL_BRIGHT);
                pose.popPose();
            }
        }
        draws.merge(code, 1L, Long::sum);
        if (code.equals("FOX_EARS")) foxDraws++;
        return true;
    }
    private static final int STATIC = 0, PANEL = 1, SHARD = 2, ORBIT = 3, WING_R = 4, WING_L = 5, SWING = 6, DRIFT = 7;
    /** Packs every face as four vertices of x y z u v nx ny nz light-flag (block units), triangles repeat the last vertex. */
    private static Part[] split(CosmeticMesh mesh) {
        var out = new java.util.ArrayList<Part>();
        for (var entry : mesh.parts().entrySet()) {
            String name = entry.getKey(); var pivot = entry.getValue();
            int kind = name.equals("static") ? STATIC : name.matches("panel_[0-3]") ? PANEL : name.matches("shard_[0-4]") ? SHARD
                : name.equals("orbit") ? ORBIT : name.equals("wing_r") ? WING_R : name.equals("wing_l") ? WING_L
                : name.equals("drift") ? DRIFT
                : name.equals("lantern_swing") || name.equals("chain_star") || name.equals("charm_swing") ? SWING : STATIC;
            int index = kind == PANEL || kind == SHARD ? Integer.parseInt(name.substring(6)) : 0;
            var solid = mesh.faces().stream().filter(f -> f.part().equals(name)).toList();
            var glow = solid.stream().filter(CosmeticMesh.Face::emissive).toList();
            out.add(new Part(name, kind, index, pivot.x() / 16, pivot.y() / 16, pivot.z() / 16, pack(solid), pack(glow)));
        }
        return out.toArray(Part[]::new);
    }
    private static float[] pack(java.util.List<CosmeticMesh.Face> faces) {
        float[] data = new float[faces.size() * 4 * 9]; int n = 0;
        for (var face : faces) for (int i = 0; i < 4; i++) {
            var v = face.vertices().get(Math.min(i, face.vertices().size() - 1));
            data[n++] = v.x() / 16; data[n++] = v.y() / 16; data[n++] = v.z() / 16; data[n++] = v.u(); data[n++] = v.v();
            data[n++] = face.nx(); data[n++] = face.ny(); data[n++] = face.nz(); data[n++] = face.emissive() ? 1 : 0;
        }
        return data;
    }
    private static void emit(VertexConsumer consumer, PoseStack pose, float[] data, int light) {
        var last = pose.last();
        for (int n = 0; n < data.length; n += 9) {
            consumer.addVertex(last, data[n], data[n + 1], data[n + 2])
                .setColor(0xffffffff).setUv(data[n + 3], data[n + 4]).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(data[n + 8] > 0 ? LightTexture.FULL_BRIGHT : light).setNormal(last, data[n + 5], data[n + 6], data[n + 7]);
        }
    }
    private static void animate(PoseStack pose, Part part, float age, float movement, boolean crouching) {
        if (part.kind() == STATIC) return;
        pose.translate(part.px(), part.py(), part.pz());
        switch (part.kind()) {
            case PANEL -> {
                int index = part.index();
                pose.mulPose(Axis.XP.rotationDegrees(8 + movement * 12 + (float)Math.sin(age * .07 + index * .8) * (2 + movement * 2)));
                pose.mulPose(Axis.ZP.rotationDegrees((float)Math.sin(age * .05 + index) * 1.5f));
                if (crouching) pose.scale(1, .78f, 1);
            }
            case SHARD -> pose.translate(0, Math.sin(age * .035 + part.index()) * .12 / 16, 0);
            case ORBIT -> pose.mulPose(Axis.YP.rotationDegrees(age * .6f));
            case WING_R, WING_L -> {
                // Slow "breathing" at rest, wider strokes while moving, folded a little when crouching.
                float flap = (float)Math.sin(age * .07) * (5 + movement * 9) + (crouching ? -12 : 0);
                float lift = (float)Math.sin(age * .07 + .6) * (1.5f + movement * 2);
                boolean right = part.kind() == WING_R;
                pose.mulPose(Axis.YP.rotationDegrees(right ? flap : -flap));
                pose.mulPose(Axis.ZP.rotationDegrees(right ? lift : -lift));
            }
            case DRIFT -> {
                pose.translate(Math.sin(age * .031) * .05, Math.sin(age * .047) * .06, 0);
                pose.mulPose(Axis.ZP.rotationDegrees((float)Math.sin(age * .02) * 6));
            }
            case SWING -> pose.mulPose(Axis.ZP.rotationDegrees((float)Math.sin(age * .07) * (1 + movement * 5)));
            default -> {}
        }
        pose.translate(-part.px(), -part.py(), -part.pz());
    }
}
