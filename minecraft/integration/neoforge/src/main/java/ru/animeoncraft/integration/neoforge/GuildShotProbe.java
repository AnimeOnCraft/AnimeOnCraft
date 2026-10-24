package ru.animeoncraft.integration.neoforge;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Disposable review client: real in-game screenshots of the Adventurers' Guild from fixed camera points. */
final class GuildShotProbe {
    /** name, camera feet x/y/z, look-at x/y/z. */
    record Shot(String name, double x, double y, double z, double tx, double ty, double tz) {}
    static final List<Shot> SHOTS = List.of(
        new Shot("01-north-entrance", 0.5, 92, 110, 0.5, 96, 142),
        new Shot("02-north-east", 28, 101, 116, 0.5, 96, 142),
        new Shot("03-east", 36, 95, 142.5, 0.5, 97, 142.5),
        new Shot("04-south-east", 26, 101, 170, 0.5, 96, 142),
        new Shot("05-south-back", 0.5, 94, 176, 0.5, 97, 142),
        new Shot("06-south-west", -25, 101, 170, 0.5, 96, 142),
        new Shot("07-west", -35, 95, 142.5, 0.5, 97, 142.5),
        new Shot("08-north-west", -27, 101, 116, 0.5, 96, 142),
        new Shot("09-aerial-ring", 0.5, 132, 96, 0.5, 92, 140),
        new Shot("10-inside-entrance-to-board", 0.5, 91, 135.5, 0.5, 93.5, 149),
        new Shot("11-inside-board-close", 0.5, 91, 144.5, 0.5, 93.5, 149),
        new Shot("12-inside-to-entrance-gallery", 0.5, 91, 147.5, 0.5, 95, 134),
        new Shot("13-from-gallery", 9.5, 96, 136.5, -4, 91, 147),
        new Shot("14-counter", -1.5, 91, 141.5, 9, 92, 143.5),
        new Shot("15-tables", 2.5, 91, 143.5, -9, 91.5, 144.5));
    private int ticks, index, wait; private boolean prepared;
    static void register() { new GuildShotProbe(); }
    private GuildShotProbe() { NeoForge.EVENT_BUS.addListener(this::tick); }
    private static boolean enabled() { return Boolean.getBoolean("animeoncraft.guildShotProbe") && Minecraft.getInstance().gameDirectory.getAbsolutePath().contains("guild-shots-"); }

    private void tick(ClientTickEvent.Post event) {
        if (!enabled()) return;
        var mc = Minecraft.getInstance(); ticks++;
        if (mc.player == null) {
            if (mc.screen != null && ticks % 30 == 0) for (var child : mc.screen.children())
                if (child instanceof Button b && b.active && java.util.Set.of("Proceed", "I know what I'm doing!", "Yes").contains(b.getMessage().getString())) b.onPress();
            return;
        }
        if (ticks < 200) return;
        var server = java.util.Objects.requireNonNull(mc.getSingleplayerServer()); var id = mc.player.getUUID();
        if (!prepared) {
            prepared = true; mc.options.hideGui = true; mc.setScreen(null);
            server.execute(() -> {
                var p = server.getPlayerList().getPlayer(id); var level = p.serverLevel();
                p.setGameMode(GameType.SPECTATOR);
                level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DAYLIGHT).set(false, server);
                level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_WEATHER_CYCLE).set(false, server);
                level.setDayTime(5000); level.setWeatherParameters(120000, 0, false, false);
            });
            move(mc, SHOTS.getFirst()); wait = 0; return;
        }
        if (index >= SHOTS.size()) return;
        if (mc.screen != null) mc.setScreen(null);
        if (++wait < (index == 0 ? 400 : 160)) return;
        var shot = SHOTS.get(index);
        Screenshot.grab(mc.gameDirectory, shot.name() + ".png", mc.getMainRenderTarget(), ignored -> {});
        System.out.println("AOC_GUILD_SHOT " + shot.name());
        index++; wait = 0;
        if (index < SHOTS.size()) move(mc, SHOTS.get(index));
        else { System.out.println("AOC_GUILD_SHOTS_DONE " + SHOTS.size()); mc.stop(); }
    }

    private static void move(Minecraft mc, Shot s) {
        double dx = s.tx() - s.x(), dz = s.tz() - s.z(), dy = s.ty() - (s.y() + 1.62);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz)), pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        var server = java.util.Objects.requireNonNull(mc.getSingleplayerServer()); var id = mc.player.getUUID();
        server.execute(() -> { var p = server.getPlayerList().getPlayer(id); p.teleportTo(p.serverLevel(), s.x(), s.y(), s.z(), yaw, pitch); });
    }
}
