package ru.animeoncraft.integration.neoforge;

import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

final class GameplayCommands {
    private GameplayCommands() {}
    static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("aoc-bossrace-cancel").requires(source->source.hasPermission(2))
            .executes(context->cancel(context.getSource(),"competitions/cancel",new JsonObject())));
        event.getDispatcher().register(Commands.literal("aoc-bossrace").requires(source -> source.hasPermission(2))
            .then(Commands.argument("minutes", IntegerArgumentType.integer(10, 120)).executes(context -> {
                var source = context.getSource();
                var player = source.getPlayerOrException();
                var identity = GameIdentity.PLAYERS.get(player.getUUID());
                if (identity == null) { source.sendFailure(Component.literal("Сначала войдите через лаунчер.")); return 0; }
                try {
                    var settings = ServerSettings.from(System.getenv());
                    JsonObject body = new JsonObject();
                    body.addProperty("server_id", settings.serverId().toString());
                    body.addProperty("actor_user_id", identity.userId().toString());
                    body.addProperty("duration_minutes", IntegerArgumentType.getInteger(context, "minutes"));
                    HttpJson.post(settings.heartbeatUrl().resolve("../competitions/start"), settings.key(), body)
                        .whenComplete((reply, failure) -> source.getServer().execute(() -> {
                            if (failure != null) source.sendFailure(Component.literal("Не удалось начать соревнование. Нужен аккаунт владельца; другое соревнование не должно быть активно."));
                            else {source.getServer().getPlayerList().broadcastSystemMessage(Component.literal("Объявлен общий мировой босс! Сбор — 2 минуты, бой — "+body.get("duration_minutes").getAsInt()+" мин. Координаты нейтральной арены появятся в чате."),false);WorldBossEncounter.refresh(source.getServer());}
                        }));
                    source.sendSuccess(() -> Component.literal("Проверяем и запускаем соревнование…"), false);
                    return 1;
                } catch (RuntimeException failure) {
                    source.sendFailure(Component.literal("Сервис соревнований недоступен.")); return 0;
                }
            })));
    }

    static int cancel(net.minecraft.commands.CommandSourceStack source,String route,JsonObject body) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var identity=GameIdentity.PLAYERS.get(source.getPlayerOrException().getUUID());
        if(identity==null){source.sendFailure(Component.literal("Войдите через лаунчер."));return 0;}
        try{
            var settings=ServerSettings.from(System.getenv());
            body.addProperty("server_id",settings.serverId().toString());body.addProperty("actor_user_id",identity.userId().toString());
            HttpJson.post(settings.heartbeatUrl().resolve("../"+route),settings.key(),body).whenComplete((reply,failure)->source.getServer().execute(()->{
                if(failure!=null)source.sendFailure(Component.literal("Не удалось отменить. Действие доступно владельцу проекта."));
                else source.sendSuccess(()->Component.literal(reply.get("cancelled").getAsBoolean()?"Отменено. Действующий рейд закрывается; его 24-часовой перерыв сохраняется.":"Активного события для отмены нет."),false);
            }));return 1;
        }catch(RuntimeException failure){source.sendFailure(Component.literal("Сервис недоступен."));return 0;}
    }
}
