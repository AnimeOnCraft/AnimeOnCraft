package ru.animeoncraft.integration.neoforge;

import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.nio.file.Files;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameRules;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@net.neoforged.fml.common.EventBusSubscriber(modid = "animeoncraft", value = net.neoforged.api.distmarker.Dist.DEDICATED_SERVER)
public final class ServerEcology {
    private static final String DEATH_UNTIL = "aoc_cleanup_death_until";
    private static final String DEATH_DIMENSION = "aoc_cleanup_death_dimension";
    private static NightRules night = new NightRules(false, 1);
    private static int deathProtectionTicks;

    private ServerEcology() {}

    @net.neoforged.bus.api.SubscribeEvent
    public static void starting(ServerStartingEvent event) {
        reset();
        if (!event.getServer().isDedicatedServer()) return;
        var file = FMLPaths.CONFIGDIR.get().resolve("animeoncraft-ecology.json");
        if (!Files.isRegularFile(file)) return;
        try (var reader = Files.newBufferedReader(file)) {
            var config = JsonParser.parseReader(reader).getAsJsonObject();
            if (config.get("format_version").getAsInt() != 1) throw new IllegalArgumentException("Unsupported ecology config");
            boolean enabled = config.get("enabled").getAsBoolean();
            night = new NightRules(enabled, config.get("night_speed").getAsInt());
            int seconds = config.get("death_drop_protection_seconds").getAsInt();
            if (seconds < 0 || seconds > 3600) throw new IllegalArgumentException("Invalid death drop protection duration");
            deathProtectionTicks = enabled ? seconds * 20 : 0;
            LogUtils.getLogger().info("AOC_ECOLOGY_READY night_speed={} death_drop_protection_seconds={}", night.speed(), seconds);
        } catch (Exception exception) {
            reset();
            throw new IllegalStateException("Invalid server ecology config: " + file, exception);
        }
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        var level = event.getServer().overworld();
        long extra = night.extraTicks(level.getDayTime(), level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT));
        if (extra > 0) level.setDayTime(level.getDayTime() + extra);
    }

    @net.neoforged.bus.api.SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOWEST)
    public static void drops(LivingDropsEvent event) {
        if (deathProtectionTicks <= 0 || !(event.getEntity() instanceof ServerPlayer player)) return;
        for (var item : event.getDrops()) {
            item.getPersistentData().putLong(DEATH_UNTIL, player.serverLevel().getGameTime() + deathProtectionTicks);
            item.getPersistentData().putString(DEATH_DIMENSION, player.level().dimension().location().toString());
        }
    }

    public static boolean protectedDeathDrop(Entity entity) {
        var data = entity.getPersistentData();
        return data.getString(DEATH_DIMENSION).equals(entity.level().dimension().location().toString())
            && data.getLong(DEATH_UNTIL) > entity.level().getGameTime();
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void stopped(ServerStoppedEvent event) { reset(); }
    private static void reset() { night = new NightRules(false, 1); deathProtectionTicks = 0; }
}
