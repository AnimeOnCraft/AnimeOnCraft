package ru.animeoncraft.integration.neoforge;

import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Player and owner commands of the capture engine. Players never type technical point codes: they stand
 * in the zone. Owner-only actions are checked by the backend (project OWNER account), not by the OP flag.
 *
 *   /aoc capture             — what point is here, its owner, state and battle
 *   /aoc capture points      — the nearest capturable points
 *   /aoc capture declare     — declare a capture of the point you stand in
 *   /aoc capture release     — clan manager gives up the point you stand in
 *   /aoc resource collect    — owner clan takes the stash of a cache site you stand in
 *   /aoc capture cancel <point> [reason], /aoc resource reset|refresh <point>, transfer <point> <clan> — project owner (OP)
 */
final class CaptureCommands {
    private CaptureCommands() {}
    static final Map<String, String> REASONS = Map.ofEntries(
        Map.entry("NO_WORLD", "в этом мире нет системы захвата."),
        Map.entry("UNKNOWN_POINT", "точка не найдена."),
        Map.entry("NOT_CAPTURABLE", "эту точку нельзя захватить."),
        Map.entry("NOT_NEUTRAL", "точка уже занята или захват начат другим кланом."),
        Map.entry("NOT_MEMBER", "нужно состоять в официальном клане."),
        Map.entry("ROLE", "объявлять захват может только руководство клана."),
        Map.entry("RUNNING", "за эту точку уже идёт бой."),
        Map.entry("PROTECTED", "точка под защитой после захвата."),
        Map.entry("OWN_POINT", "точка уже ваша; продлить аренду можно, когда она истечёт."),
        Map.entry("CLAN_LIMIT", "у клана уже два объявленных захвата."),
        Map.entry("COOLDOWN", "после неудачной попытки нужно подождать."),
        Map.entry("INFLUENCE", "не хватает влияния: отпустите одну из точек клана (/aoc capture release)."),
        Map.entry("TOO_FEW_PLAYERS", "в зоне мало игроков клана."));
    static final Map<String, String> STATES = Map.of("NEUTRAL", "ничья", "CONTESTED", "объявлен захват", "CAPTURING", "идёт бой",
        "CONTROLLED", "под контролем", "PROTECTED", "под защитой", "AVAILABLE", "аренда истекла");

    static void register(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> capture = Commands.literal("capture")
            .executes(context -> status(context.getSource()))
            .then(Commands.literal("points").executes(context -> nearest(context.getSource())))
            .then(Commands.literal("declare").executes(context -> declare(context.getSource())))
            .then(Commands.literal("menu-declare").executes(context -> {
                var player=context.getSource().getPlayerOrException();
                if(!ServerMenuSync.captureMatches(player)){context.getSource().sendFailure(Component.literal("Точка изменилась или карточка устарела. Откройте меню заново."));return 0;}
                return declare(context.getSource());
            }))
            .then(Commands.literal("release").executes(context -> admin(context.getSource(), "RELEASE", here(context.getSource()), null, null)))
            .then(Commands.literal("cancel").requires(source -> source.hasPermission(2))
                .then(Commands.argument("point", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(CaptureSites.view().points().keySet(), b))
                    .executes(context -> admin(context.getSource(), "CANCEL", StringArgumentType.getString(context, "point"), null, null))
                    .then(Commands.argument("reason", StringArgumentType.greedyString())
                        .executes(context -> admin(context.getSource(), "CANCEL", StringArgumentType.getString(context, "point"), null, StringArgumentType.getString(context, "reason"))))));
        LiteralArgumentBuilder<CommandSourceStack> resource = Commands.literal("resource")
            .then(Commands.literal("collect").executes(context -> collect(context.getSource())));
        for (String action : new String[] {"reset", "refresh"})
            resource.then(Commands.literal(action).requires(source -> source.hasPermission(2))
                .then(Commands.argument("point", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(CaptureSites.view().points().keySet(), b))
                    .executes(context -> admin(context.getSource(), action.toUpperCase(java.util.Locale.ROOT), StringArgumentType.getString(context, "point"), null, null))));
        resource.then(Commands.literal("transfer").requires(source -> source.hasPermission(2))
            .then(Commands.argument("point", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(CaptureSites.view().points().keySet(), b))
                .then(Commands.argument("clan", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(ClanCodes.all(), b))
                    .executes(context -> admin(context.getSource(), "TRANSFER", StringArgumentType.getString(context, "point"), StringArgumentType.getString(context, "clan"), null)))));
        event.getDispatcher().register(Commands.literal("aoc").then(capture).then(resource));
    }

    static String duration(int seconds) { return seconds >= 60 ? (seconds / 60) + " мин" + (seconds % 60 == 0 ? "" : " " + seconds % 60 + " с") : seconds + " с"; }
    /** Reason text with the numbers of the active rules (window, players), never hardcoded. */
    static String explain(String reason, JsonObject rules) {
        if (rules != null && "OUT_OF_WINDOW".equals(reason)) {
            var ranges = new java.util.ArrayList<String>();
            for (var range : rules.getAsJsonObject("window").getAsJsonArray("daily")) ranges.add(range.getAsJsonArray().get(0).getAsString() + "–" + range.getAsJsonArray().get(1).getAsString());
            return "захват объявляют только в " + String.join(", ", ranges) + " (" + rules.get("timezone").getAsString() + ").";
        }
        if (rules != null && "TOO_FEW_PLAYERS".equals(reason)) {
            var declare = rules.getAsJsonObject("declare");
            return "в зоне должно быть не меньше " + declare.get("min_players").getAsInt() + " игроков клана (для T1 — " + declare.get("min_players_t1").getAsInt() + ").";
        }
        return REASONS.getOrDefault(reason, "отказано (" + reason + ").");
    }

    private static String here(CommandSourceStack source) {
        var point = source.getEntity() instanceof ServerPlayer player ? CaptureEntry.here(source.getServer(),player,CaptureSites.view())
            : CaptureSites.view().at(source.getPosition().x,source.getPosition().z);
        return point == null ? "" : point.code();
    }
    private static String left(Instant until) {
        if (until == null) return "";
        long seconds = Math.max(0, Duration.between(Instant.now(), until).getSeconds());
        return seconds >= 3600 ? (seconds / 3600) + " ч " + (seconds % 3600 / 60) + " мин" : (seconds / 60) + " мин " + (seconds % 60) + " с";
    }

    static int status(CommandSourceStack source) {
        var view = CaptureSites.view();
        var point = source.getEntity() instanceof ServerPlayer player ? CaptureEntry.here(source.getServer(),player,view) : view.at(source.getPosition().x,source.getPosition().z);
        if (point == null) { source.sendSuccess(() -> Component.literal("Здесь нет точки захвата. Ближайшие: /aoc capture points"), false); return 0; }
        var text = new StringBuilder("«" + point.name() + "»: " + STATES.getOrDefault(point.state(), point.state()));
        if (point.owner() != null) text.append(", владелец ").append(point.owner());
        if ("PROTECTED".equals(point.state())) text.append(", защита ещё ").append(left(point.protectionUntil()));
        if ("CONTROLLED".equals(point.state())) text.append(", аренда ещё ").append(left(point.leaseUntil()));
        if (point.resource() != null) text.append(". Ресурс: ").append(point.resource().label()).append(" T").append(point.resource().tier())
            .append(" · ").append(ResourcePits.status(source.getServer(),CaptureArena.players(source.getServer()),point,Instant.now().getEpochSecond()));
        var event = view.eventFor(point.code());
        if (event != null) {
            var battle = CaptureArena.battle(event.id());
            text.append(". Захват ").append(event.declaredBy()).append(": ")
                .append("PREPARING".equals(event.state()) ? "бой через " + left(event.activeStart()) : battle == null ? "бой идёт" : battle.summary());
        }
        if (!CaptureSites.fresh()) text.append(" (нет связи с сервером захвата — данные могут быть устаревшими)");
        source.sendSuccess(() -> Component.literal(text.toString()), false);
        return 1;
    }

    static int nearest(CommandSourceStack source) {
        var position = source.getPosition();
        var list = CaptureSites.view().points().values().stream()
            .sorted(Comparator.comparingDouble(p -> Math.hypot(p.x() - position.x, p.z() - position.z))).limit(5).toList();
        if (list.isEmpty()) { source.sendSuccess(() -> Component.literal("В этом мире нет точек захвата."), false); return 0; }
        for (var point : list) source.sendSuccess(() -> Component.literal("«" + point.name() + "» — " + point.x() + ", " + point.z()
            + " (" + Math.round(Math.hypot(point.x() - position.x, point.z() - position.z)) + " бл.), " + STATES.getOrDefault(point.state(), point.state())
            + (point.owner() == null ? "" : ", " + point.owner())), false);
        return list.size();
    }

    static int declare(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        var identity = GameIdentity.PLAYERS.get(player.getUUID());
        if (identity == null || identity.clan() == null) { source.sendFailure(Component.literal("Захват объявляет участник официального клана, вошедший через лаунчер.")); return 0; }
        var view = CaptureSites.view();
        var point = CaptureEntry.here(source.getServer(),player,view);
        if (point == null) { source.sendFailure(Component.literal("Встаньте в зону точки захвата (/aoc capture points).")); return 0; }
        if (view.rules() == null || !CaptureSites.fresh()) { source.sendFailure(Component.literal("Сервер захвата недоступен, попробуйте позже.")); return 0; }
        var server = source.getServer();
        long now = Instant.now().getEpochSecond();
        int height = view.rules().getAsJsonObject("presence").get("height").getAsInt();
        int y = CaptureAnchors.height(server, point);
        if (!CaptureArena.eligible(player,server,now) || !CaptureArena.inZone(player,point,y,height)) {
            source.sendFailure(Component.literal("Для захвата встаньте на площадку в выживании и дождитесь окончания задержки после входа/телепортации.")); return 0;
        }
        int inZone = CaptureArena.present(server, CaptureArena.players(server), point, y, height, now).getOrDefault(identity.clan(), 0);
        int defenders = point.owner() == null ? 0 : (int) CaptureArena.players(server).stream()
            .map(p -> GameIdentity.PLAYERS.get(p.getUUID())).filter(i -> i != null && point.owner().equals(i.clan())).count();
        return send(source, "declare", body -> {
            body.addProperty("point_code", point.code()); body.addProperty("actor_user_id", identity.userId().toString());
            body.addProperty("clan_code", identity.clan()); body.addProperty("players_in_zone", inZone); body.addProperty("defenders_online", defenders);
        }, reply -> {
            if (reply.get("accepted").getAsBoolean()) {
                var event = reply.getAsJsonObject("event");
                int battle = CaptureSites.view().rules() == null ? 0 : CaptureSites.view().rules().getAsJsonObject("timing").get("battle_seconds").getAsInt();
                source.sendSuccess(() -> Component.literal("Захват «" + point.name() + "» объявлен. Подготовка " + duration(event.get("preparation_seconds").getAsInt())
                    + ", затем бой до " + duration(battle) + ". Удерживайте зону!"), false);
                CaptureSites.refresh(server);
            } else {
                String reason = reply.get("reason").getAsString();
                var rules = CaptureSites.view().rules();
                String retry = reply.has("retry_at") && !reply.get("retry_at").isJsonNull() ? " Осталось: " + left(Instant.parse(reply.get("retry_at").getAsString())) + "." : "";
                source.sendFailure(Component.literal("Захват не объявлен: " + explain(reason, rules) + retry));
            }
        });
    }

    static int admin(CommandSourceStack source, String action, String point, String clan, String reason) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var player = source.getPlayerOrException();
        var identity = GameIdentity.PLAYERS.get(player.getUUID());
        if (identity == null) { source.sendFailure(Component.literal("Войдите через лаунчер.")); return 0; }
        if (point.isEmpty()) { source.sendFailure(Component.literal("Встаньте в зону точки.")); return 0; }
        return send(source, "admin", body -> {
            body.addProperty("action", action); body.addProperty("actor_user_id", identity.userId().toString()); body.addProperty("point_code", point);
            if (clan != null) body.addProperty("clan_code", clan);
            if (reason != null) body.addProperty("reason", reason);
        }, reply -> {
            source.sendSuccess(() -> Component.literal(reply.get("changed").getAsBoolean() ? "Готово: " + action.toLowerCase(java.util.Locale.ROOT) + " " + point + "." : "Изменений нет."), true);
            CaptureSites.refresh(source.getServer());
        });
    }

    static int collect(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var player = source.getPlayerOrException();
        var identity = GameIdentity.PLAYERS.get(player.getUUID());
        var point = CaptureEntry.here(source.getServer(),player,CaptureSites.view());
        if (!CaptureSites.fresh()) { source.sendFailure(Component.literal("Нет связи с сервером ресурсов.")); return 0; }
        if (point == null || point.resource() == null || !"CACHE".equals(point.resource().kind())) { source.sendFailure(Component.literal("Здесь нет тайника ресурсной точки.")); return 0; }
        if (identity == null || point.owner() == null || !point.owner().equals(identity.clan()) || !point.held()) {
            source.sendFailure(Component.literal("Тайник открывает только клан-владелец точки.")); return 0;
        }
        try {
            int given = ResourcePits.collect(source.getServer(), player, point);
            source.sendSuccess(() -> Component.literal(given == 0 ? "Тайник пуст или в инвентаре нет места." : "Из тайника получено предметов: " + given + "."), false);
            return given;
        } catch (java.io.IOException failure) { source.sendFailure(Component.literal("Тайник временно недоступен.")); return 0; }
    }

    private static int send(CommandSourceStack source, String route, java.util.function.Consumer<JsonObject> fill, java.util.function.Consumer<JsonObject> done) {
        try {
            var settings = ServerSettings.from(System.getenv());
            var body = new JsonObject(); body.addProperty("server_id", settings.serverId().toString());
            fill.accept(body);
            HttpJson.post(settings.heartbeatUrl().resolve("../capture/" + route), settings.key(), body).whenComplete((reply, failure) -> source.getServer().execute(() -> {
                if (failure != null) source.sendFailure(Component.literal("Действие отклонено сервером захвата (нужны права или точка недоступна)."));
                else done.accept(reply);
            }));
            return 1;
        } catch (RuntimeException unavailable) { source.sendFailure(Component.literal("Сервер захвата недоступен.")); return 0; }
    }
}
