package ru.animeoncraft.integration.neoforge;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Disposable review client: real screenshots of the AOC place icons on the Xaero minimap and world map. */
final class MapIconShotProbe {
    /** Minimap shot: stand at x/y/z looking at yaw. Map shot: world map centred on x/z at scale, cursor over hover (x/z) or none. */
    record Shot(String name, boolean map, double x, double y, double z, float yaw, double scale, int[] hover) {}
    private final List<Shot> shots = new ArrayList<>();
    private int ticks, index, wait; private boolean prepared;
    static void register() { new MapIconShotProbe(); }
    private MapIconShotProbe() { NeoForge.EVENT_BUS.addListener(this::tick); }
    private static boolean enabled() { return Boolean.getBoolean("animeoncraft.mapIconShotProbe") && Minecraft.getInstance().gameDirectory.getAbsolutePath().contains("map-icons-"); }

    private void plan(WorldAtlasData atlas) {
        var guild = atlas.points().stream().filter(p -> p.kind().equals("ADVENTURER_GUILD")).findFirst().orElseThrow();
        var market = atlas.points().stream().filter(p -> p.kind().equals("MARKET")).findFirst().orElse(guild);
        var fort = atlas.points().stream().filter(p -> p.kind().equals("FORT")).findFirst().orElse(guild);
        shots.add(new Shot("01-minimap-sora-guild", false, 0.5, 92, 100, 0, 0, null));
        shots.add(new Shot("02-minimap-market", false, market.x() + 0.5, 110, market.z() + 30.5, 180, 0, null));
        shots.add(new Shot("03-minimap-fort", false, fort.x() + 20.5, 160, fort.z() + 20.5, 135, 0, null));
        shots.add(new Shot("04-map-sora", true, 0.5, 92, 100, 0, 1.6, null));
        shots.add(new Shot("05-map-hover-guild", true, 0.5, 92, 100, 0, 1.6, new int[]{guild.x(), guild.z()}));
        shots.add(new Shot("06-map-region", true, 0.5, 92, 100, 0, 0.22, null));
        shots.add(new Shot("07-map-world", true, 0.5, 92, 100, 0, 0.07, null));
        System.out.println("AOC_MAP_ICON_POINTS " + atlas.points().size() + " guild=" + guild.x() + "," + guild.z() + " fort=" + fort.name());
    }

    private void tick(ClientTickEvent.Post event) {
        if (!enabled()) return;
        var mc = Minecraft.getInstance(); ticks++;
        if (mc.player == null) {
            if (mc.screen != null && ticks % 30 == 0) for (var child : mc.screen.children())
                if (child instanceof Button b && b.active && java.util.Set.of("Proceed", "I know what I'm doing!", "Yes").contains(b.getMessage().getString())) b.onPress();
            return;
        }
        if (ticks < 200 || !ClientWorldAtlas.active()) return;
        try {
            if (!prepared) {
                prepared = true; plan(ClientWorldAtlas.current()); mc.setScreen(null);
                var server = java.util.Objects.requireNonNull(mc.getSingleplayerServer()); var id = mc.player.getUUID();
                server.execute(() -> {
                    var p = server.getPlayerList().getPlayer(id); var level = p.serverLevel();
                    p.setGameMode(GameType.CREATIVE); p.getAbilities().flying = true; p.onUpdateAbilities();
                    level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DAYLIGHT).set(false, server);
                    level.setDayTime(5000); level.setWeatherParameters(120000, 0, false, false);
                });
                move(mc, shots.getFirst()); wait = 0; return;
            }
            if (index >= shots.size()) return;
            var shot = shots.get(index);
            if (++wait == (index == 0 ? 500 : 260) - 60 && shot.map()) openMap(mc, shot);
            if (shot.map() && mc.screen != null && wait > (index == 0 ? 500 : 260) - 60) view(mc, shot);
            if (wait < (index == 0 ? 500 : 260)) return;
            Screenshot.grab(mc.gameDirectory, shot.name() + ".png", mc.getMainRenderTarget(), ignored -> {});
            System.out.println("AOC_MAP_ICON_SHOT " + shot.name() + " minimap=" + XaeroMinimapIcons.drawn + " world=" + XaeroWorldMapIcons.drawn + " layer=" + ClientWorldAtlas.iconLayer());
            index++; wait = 0;
            if (index < shots.size()) { if (!shots.get(index).map()) mc.setScreen(null); move(mc, shots.get(index)); }
            else { System.out.println("AOC_MAP_ICON_SHOTS_DONE " + shots.size()); mc.stop(); }
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Map icon probe failed", failure); }
    }

    private static void move(Minecraft mc, Shot s) {
        var server = java.util.Objects.requireNonNull(mc.getSingleplayerServer()); var id = mc.player.getUUID();
        server.execute(() -> { var p = server.getPlayerList().getPlayer(id); p.teleportTo(p.serverLevel(), s.x(), s.y(), s.z(), s.yaw(), 10); });
    }
    private static void openMap(Minecraft mc, Shot s) throws ReflectiveOperationException {
        if (mc.screen != null && mc.screen.getClass().getName().equals("xaero.map.gui.GuiMap")) return;
        var processor = xaero.map.WorldMapSession.getCurrentSession().getMapProcessor();
        var screen = Class.forName("xaero.map.gui.GuiMap").getConstructor(net.minecraft.client.gui.screens.Screen.class, net.minecraft.client.gui.screens.Screen.class, xaero.map.MapProcessor.class, net.minecraft.world.entity.Entity.class).newInstance(null, null, processor, mc.player);
        mc.setScreen((net.minecraft.client.gui.screens.Screen) screen);
    }
    private static void view(Minecraft mc, Shot s) throws ReflectiveOperationException {
        var screen = mc.screen; double cx = s.hover() == null ? s.x() : s.hover()[0] + 0.5, cz = s.hover() == null ? s.z() : s.hover()[1] + 0.5 - 40 / s.scale();
        for (String field : List.of("scale", "userScale", "destScale")) { var f = screen.getClass().getDeclaredField(field); f.setAccessible(true); f.setDouble(field.equals("destScale") ? null : screen, s.scale()); }
        var fx = screen.getClass().getDeclaredField("cameraX"); fx.setAccessible(true); fx.setDouble(screen, cx);
        var fz = screen.getClass().getDeclaredField("cameraZ"); fz.setAccessible(true); fz.setDouble(screen, cz);
        var window = mc.getWindow();
        if (s.hover() != null) org.lwjgl.glfw.GLFW.glfwSetCursorPos(window.getWindow(), window.getWidth() / 2.0 + (s.hover()[0] + 0.5 - cx) * s.scale(), window.getHeight() / 2.0 + (s.hover()[1] + 0.5 - cz) * s.scale());
        else org.lwjgl.glfw.GLFW.glfwSetCursorPos(window.getWindow(), 5, window.getHeight() - 5);
    }
}
