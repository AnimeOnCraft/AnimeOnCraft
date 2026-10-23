package ru.animeoncraft.integration.neoforge;

import com.mojang.blaze3d.systems.RenderSystem;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/** Illustrated inventory artwork; independent of the wearable cosmetic models. */
final class BetaRewardIcons {
    private static final ResourceLocation ATLAS = ResourceLocation.fromNamespaceAndPath(
        "animeoncraft", "textures/gui/beta_rewards.png");
    private static final int WIDTH = 1983;
    private static final int HEIGHT = 793;

    // Tight source bounds retain each full silhouette, including the lantern chain and mantle tips.
    private static final Map<String, Sprite> SPRITES = Map.of(
        "BETA_SCOUT", new Sprite(74, 49, 305, 331),
        "FOX_EARS", new Sprite(432, 68, 342, 307),
        "ONI_MASK", new Sprite(828, 107, 357, 249),
        "RIFT_LANTERN", new Sprite(1271, 25, 216, 369),
        "SHARD_CROWN", new Sprite(1584, 68, 348, 280),
        "CYAN_VISOR", new Sprite(45, 461, 353, 254),
        "NEON_LEGEND", new Sprite(440, 419, 333, 328),
        "STAR_CHAIN", new Sprite(830, 438, 333, 290),
        "ORBIT_HALO", new Sprite(1203, 454, 346, 262),
        "RIFT_MANTLE", new Sprite(1565, 401, 388, 354)
    );

    private BetaRewardIcons() {}

    static void draw(GuiGraphics graphics, String code, int x, int y, int size) {
        Sprite sprite = SPRITES.get(code);
        if (sprite == null || size <= 0) return;
        float scale = size / (float) Math.max(sprite.width(), sprite.height());
        graphics.pose().pushPose();
        graphics.pose().translate(x + (size - sprite.width() * scale) / 2,
            y + (size - sprite.height() * scale) / 2, 0);
        graphics.pose().scale(scale, scale, 1);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        graphics.blit(ATLAS, 0, 0, (float) sprite.x(), (float) sprite.y(),
            sprite.width(), sprite.height(), WIDTH, HEIGHT);
        graphics.pose().popPose();
    }

    private record Sprite(int x, int y, int width, int height) {}
}
