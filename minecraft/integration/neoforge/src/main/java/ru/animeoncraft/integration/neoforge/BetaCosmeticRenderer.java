package ru.animeoncraft.integration.neoforge;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.Items;

/** Player-attached cosmetic layers for both Steve and Alex; all geometry comes from Blockbench. */
final class BetaCosmeticRenderer {
    static long mantleDraws;
    private BetaCosmeticRenderer() {}
    static void register(net.neoforged.neoforge.client.event.EntityRenderersEvent.AddLayers event) {
        for (var skin : event.getSkins()) {
            net.minecraft.client.renderer.entity.player.PlayerRenderer renderer = event.getSkin(skin);
            renderer.addLayer(new Layer(renderer));
        }
    }
    private static final class Layer extends net.minecraft.client.renderer.entity.layers.RenderLayer<
            net.minecraft.client.player.AbstractClientPlayer,
            net.minecraft.client.model.PlayerModel<net.minecraft.client.player.AbstractClientPlayer>> {
        Layer(net.minecraft.client.renderer.entity.player.PlayerRenderer renderer) { super(renderer); }
        @Override public void render(PoseStack pose, MultiBufferSource buffers, int light,
                net.minecraft.client.player.AbstractClientPlayer player, float walk, float amount,
                float partial, float age, float yaw, float pitch) {
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.player == null || player.isInvisible() || player.isInvisibleTo(mc.player) || player.isSpectator()) return;
            var worn = ClientBetaPass.equipped(player.getUUID());
            if (worn.isEmpty()) return;
            float time = player.tickCount + partial, movement = Math.min(1, player.walkAnimation.speed(partial));
            pose.pushPose(); getParentModel().head.translateAndRotate(pose);
            for (String slot : new String[]{"HEAD", "FACE"}) {
                String code = worn.get(slot);
                if (code != null) DetailedCosmetics.draw(code, pose, buffers, light, time, movement, player.isCrouching());
            }
            pose.popPose();
            pose.pushPose(); getParentModel().body.translateAndRotate(pose);
            String belt = worn.get("BELT");
            if (belt != null) DetailedCosmetics.draw(belt, pose, buffers, light, time, movement, player.isCrouching());
            if ("RIFT_MANTLE".equals(worn.get("BACK")) &&
                    !player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).is(Items.ELYTRA) &&
                    DetailedCosmetics.draw("RIFT_MANTLE", pose, buffers, light, time, movement, player.isCrouching())) mantleDraws++;
            pose.popPose();
        }
    }
}
