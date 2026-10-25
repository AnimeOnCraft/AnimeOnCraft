package ru.animeoncraft.integration.neoforge;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Disposable review client: real screenshots from camera points listed in a JSON file
 * (-Danimeoncraft.reviewShotProbe=<shots.json>, game directory .tools/review-shots-*, scripts/shoot-review.mjs).
 * Camera shot {name, x, y, z, tx, ty, tz}: feet at x/y/z looking at tx/ty/tz. Map shot {name, map: true, x, z, scale}:
 * the Xaero world map centred on x/z.
 */
final class ReviewShotProbe {
    record Shot(String name, boolean map, double x, double y, double z, double tx, double ty, double tz, double scale) {}
    private final List<Shot> shots = new ArrayList<>();
    private int ticks, index, wait; private boolean prepared;
    static void register() { new ReviewShotProbe(); }
    private ReviewShotProbe() { NeoForge.EVENT_BUS.addListener(this::tick); }
    private static boolean enabled() { return System.getProperty("animeoncraft.reviewShotProbe") != null && Minecraft.getInstance().gameDirectory.getAbsolutePath().contains("review-shots-"); }

    private void load() throws java.io.IOException {
        for (var e : JsonParser.parseString(Files.readString(Path.of(System.getProperty("animeoncraft.reviewShotProbe")))).getAsJsonArray()) {
            var o = e.getAsJsonObject(); boolean map = o.has("map") && o.get("map").getAsBoolean();
            double x = o.get("x").getAsDouble(), z = o.get("z").getAsDouble(), y = map ? 200 : o.get("y").getAsDouble();
            shots.add(new Shot(o.get("name").getAsString(), map, x, y, z, map ? x : o.get("tx").getAsDouble(), map ? 0 : o.get("ty").getAsDouble(),
                map ? z + 1 : o.get("tz").getAsDouble(), map ? o.get("scale").getAsDouble() : 0));
        }
    }

    private void tick(ClientTickEvent.Post event) {
        if (!enabled()) return;
        var mc = Minecraft.getInstance(); ticks++;
        if (mc.player == null) {
            if (mc.screen != null && ticks % 30 == 0) for (var child : mc.screen.children())
                if (child instanceof Button b && b.active && java.util.Set.of("Proceed", "I know what I'm doing!", "Yes").contains(b.getMessage().getString())) b.onPress();
            return;
        }
        if (ticks < 200) return;
        try {
            if (!prepared) {
                prepared = true; load(); mc.options.hideGui = true; mc.setScreen(null);
                var server = java.util.Objects.requireNonNull(mc.getSingleplayerServer()); var id = mc.player.getUUID();
                server.execute(() -> {
                    var p = server.getPlayerList().getPlayer(id); var level = p.serverLevel();
                    p.setGameMode(GameType.SPECTATOR);
                    level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DAYLIGHT).set(false, server);
                    level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_WEATHER_CYCLE).set(false, server);
                    level.setDayTime(5000); level.setWeatherParameters(120000, 0, false, false);
                });
                move(mc, shots.getFirst()); wait = 0; return;
            }
            if (index >= shots.size()) return;
            var shot = shots.get(index); int settle = index == 0 ? 500 : 300;
            if (!shot.map() && mc.screen != null) mc.setScreen(null);
            if (++wait == settle - 60 && shot.map()) { mc.options.hideGui = false; openMap(mc); }
            if (shot.map() && mc.screen != null && wait > settle - 60) view(mc, shot);
            if (wait < settle) return;
            Screenshot.grab(mc.gameDirectory, shot.name() + ".png", mc.getMainRenderTarget(), ignored -> {});
            System.out.println("AOC_REVIEW_SHOT " + shot.name());
            index++; wait = 0;
            if (index < shots.size()) { if (!shots.get(index).map()) { mc.setScreen(null); mc.options.hideGui = true; } move(mc, shots.get(index)); }
            else { System.out.println("AOC_REVIEW_SHOTS_DONE " + shots.size()); mc.stop(); }
        } catch (ReflectiveOperationException | java.io.IOException failure) { throw new IllegalStateException("Review shot probe failed", failure); }
    }

    private static void move(Minecraft mc, Shot s) {
        double dx = s.tx() - s.x(), dz = s.tz() - s.z(), dy = s.ty() - (s.y() + 1.62);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz)), pitch = s.map() ? 10 : (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        var server = java.util.Objects.requireNonNull(mc.getSingleplayerServer()); var id = mc.player.getUUID();
        server.execute(() -> { var p = server.getPlayerList().getPlayer(id); p.teleportTo(p.serverLevel(), s.x(), s.y(), s.z(), yaw, pitch); });
    }
    private static void openMap(Minecraft mc) throws ReflectiveOperationException {
        if (mc.screen != null && mc.screen.getClass().getName().equals("xaero.map.gui.GuiMap")) return;
        var processor = xaero.map.WorldMapSession.getCurrentSession().getMapProcessor();
        var screen = Class.forName("xaero.map.gui.GuiMap").getConstructor(net.minecraft.client.gui.screens.Screen.class, net.minecraft.client.gui.screens.Screen.class, xaero.map.MapProcessor.class, net.minecraft.world.entity.Entity.class).newInstance(null, null, processor, mc.player);
        mc.setScreen((net.minecraft.client.gui.screens.Screen) screen);
    }
    private static void view(Minecraft mc, Shot s) throws ReflectiveOperationException {
        var screen = mc.screen;
        for (String field : List.of("scale", "userScale", "destScale")) { var f = screen.getClass().getDeclaredField(field); f.setAccessible(true); f.setDouble(field.equals("destScale") ? null : screen, s.scale()); }
        var fx = screen.getClass().getDeclaredField("cameraX"); fx.setAccessible(true); fx.setDouble(screen, s.x());
        var fz = screen.getClass().getDeclaredField("cameraZ"); fz.setAccessible(true); fz.setDouble(screen, s.z());
        org.lwjgl.glfw.GLFW.glfwSetCursorPos(mc.getWindow().getWindow(), 5, mc.getWindow().getHeight() - 5);
    }
}
