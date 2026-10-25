package ru.animeoncraft.integration.neoforge;

import com.mojang.blaze3d.systems.RenderSystem;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/** AOC place icons shared by both Xaero maps: disc tinted by place/clan colour, frame, white glyph, state badge. Textures only. */
final class MapIcons {
    /** priority: 3 always shown, lower ones give way when the world map is zoomed out. */
    record Style(String glyph, String title, int color, boolean gold, int priority) {}
    private static final Set<ResourceLocation> FILTERED = new HashSet<>();
    private static WorldAtlasData sortedFor; private static List<WorldAtlasData.Point> sorted = List.of();
    private MapIcons() {}

    static Style style(WorldAtlasData.Point p) {
        boolean owned = !p.owner().isEmpty();
        return switch (p.kind()) {
            case "CAPITAL" -> new Style("capital","Столица · спавн",0xF2C14E,true,3);
            case "ADVENTURER_GUILD" -> new Style("guild","Гильдия авантюристов",0xE0793A,false,2);
            case "EVENT_ZONE" -> new Style("event","Ивент-зона",0x9B5DE5,false,2);
            case "BOSS_LAIR" -> new Style("boss","Логово босса",0xB3202A,false,2);
            case "CLAN_CAPITAL" -> new Style("clan","Столица клана",owned?p.color():0xD94B6A,false,2);
            case "MARKET" -> new Style("market","Рынок клана",p.color(),false,0);
            case "RESOURCE_SITE" -> new Style("resource","Ресурсная точка",owned?p.color():0x3FBF7F,false,1);
            case "FORT" -> new Style("fort","Форт",owned?p.color():0xC9973A,false,1);
            case "OUTPOST" -> new Style("outpost","Аванпост",owned?p.color():0xB98B57,false,1);
            case "PORT" -> new Style("port","Порт",owned?p.color():0x3A9BD9,false,1);
            case "WATCHTOWER" -> new Style("tower","Сторожевая башня",owned?p.color():0x8FA3B8,false,0);
            case "VILLAGE" -> new Style("village","Поселение",owned?p.color():0x7CBF5A,false,0);
            case "BRIDGE" -> new Style("bridge","Мост",owned?p.color():0xA39482,false,0);
            case "PASS" -> new Style("pass","Перевал",owned?p.color():0x9C8670,false,0);
            default -> new Style("village","Место",owned?p.color():0x9A9AA8,false,0);
        };
    }
    static String badge(WorldAtlasData.Point p) {
        return switch (p.state()) { case "CONTESTED", "CAPTURING" -> "battle"; case "PROTECTED" -> "shield"; case "COOLDOWN" -> "cooldown"; default -> null; };
    }
    static String detail(WorldAtlasData.Point p) {
        var s = style(p); String state = switch (p.state()) { case "CONTESTED", "CAPTURING" -> " · идёт бой"; case "PROTECTED" -> " · защищено"; case "COOLDOWN" -> " · перезарядка"; default -> ""; };
        return s.title() + (p.owner().isEmpty() || "MARKET".equals(p.kind()) ? "" : " · клан " + p.owner()) + state;
    }
    /** Least important first, so the capital and guild end up on top. */
    static List<WorldAtlasData.Point> points() {
        var atlas = ClientWorldAtlas.current(); if (atlas == null) return List.of();
        if (atlas != sortedFor) { sorted = atlas.points().stream().sorted(Comparator.comparingInt(p -> style(p).priority())).toList(); sortedFor = atlas; }
        return sorted;
    }
    /** World-map zoom (screen pixels per block) below which a place is left out to keep the map readable. */
    static double minScale(WorldAtlasData.Point p) {
        if ("MARKET".equals(p.kind())) return 0.8;
        return switch (style(p).priority()) { case 3 -> 0; case 2 -> 0.05; case 1 -> 0.12; default -> 0.3; };
    }

    /** Draws centred on the current pose origin; size in pose units. The texture set follows the real on-screen size. */
    static void draw(GuiGraphics g, WorldAtlasData.Point p, float size, float alpha) {
        float pixels = size * g.pose().last().pose().getScale(new org.joml.Vector3f()).x * (float) Minecraft.getInstance().getWindow().getGuiScale();
        var s = style(p); boolean small = pixels < 40; int tex = small ? 32 : 64; String dir = small ? "small/" : "";
        var pose = g.pose(); pose.pushPose(); pose.scale(size / 64f, size / 64f, 1);
        RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc();
        int c = s.color(); RenderSystem.setShaderColor((c >> 16 & 255) / 255f, (c >> 8 & 255) / 255f, (c & 255) / 255f, alpha);
        blit(g, dir + "disc", -32, -32, 64, tex);
        RenderSystem.setShaderColor(1, 1, 1, alpha);
        blit(g, dir + (s.gold() ? "frame_gold" : "frame"), -32, -32, 64, tex);
        blit(g, dir + "glyph_" + s.glyph(), -19, -20, 38, tex);
        var badge = badge(p); if (badge != null) blit(g, dir + "badge_" + badge, 10, 8, 30, tex);
        RenderSystem.setShaderColor(1, 1, 1, 1); pose.popPose();
    }
    /** Name and a grey detail line to the right of an icon; scale is pose units per GUI pixel. */
    static void label(GuiGraphics g, WorldAtlasData.Point p, float offsetX, float scale) {
        var font = Minecraft.getInstance().font; String detail = detail(p);
        int w = Math.max(font.width(p.name()), font.width(detail));
        var pose = g.pose(); pose.pushPose(); pose.translate(offsetX, 0, 2); pose.scale(scale, scale, 1);
        g.fill(-3, -11, w + 3, 11, 0xB0120D1A); g.fill(-3, -11, -2, 11, 0xFF000000 | style(p).color());
        g.drawString(font, p.name(), 1, -9, 0xFFFFFFFF, false);
        g.drawString(font, detail, 1, 1, 0xFFC8C0D8, false);
        g.flush(); pose.popPose();
    }
    private static void blit(GuiGraphics g, String name, int x, int y, int size, int tex) {
        var id = ResourceLocation.fromNamespaceAndPath("animeoncraft", "textures/map/" + name + ".png");
        if (FILTERED.add(id)) Minecraft.getInstance().getTextureManager().getTexture(id).setFilter(true, false);
        g.blit(id, x, y, size, size, 0, 0, tex, tex, tex, tex);
    }
}
