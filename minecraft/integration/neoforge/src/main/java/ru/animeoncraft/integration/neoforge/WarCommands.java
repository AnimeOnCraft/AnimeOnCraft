package ru.animeoncraft.integration.neoforge;

import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

final class WarCommands {
    private WarCommands() {}
    static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("aoc-war-cancel").requires(source->source.hasPermission(2))
            .then(Commands.argument("attacker",StringArgumentType.word())
            .then(Commands.argument("defender",StringArgumentType.word()).executes(context->{
                var body=new JsonObject();body.addProperty("attacker",StringArgumentType.getString(context,"attacker"));body.addProperty("defender",StringArgumentType.getString(context,"defender"));
                return GameplayCommands.cancel(context.getSource(),"wars/cancel",body);
            }))));
        event.getDispatcher().register(Commands.literal("aoc-war").requires(source -> source.hasPermission(2))
            .then(Commands.argument("attacker", StringArgumentType.word()).suggests((context,builder)->net.minecraft.commands.SharedSuggestionProvider.suggest(ClanCodes.all(),builder))
            .then(Commands.argument("defender", StringArgumentType.word()).suggests((context,builder)->net.minecraft.commands.SharedSuggestionProvider.suggest(ClanCodes.all(),builder))
            .then(Commands.argument("preparation", IntegerArgumentType.integer(10,1440))
            .then(Commands.argument("raid", IntegerArgumentType.integer(10,120)).executes(context -> {
                var source=context.getSource();var player=source.getPlayerOrException();
                var identity=GameIdentity.PLAYERS.get(player.getUUID());
                if(identity==null){source.sendFailure(Component.literal("Войдите через лаунчер."));return 0;}
                try {
                    var settings=ServerSettings.from(System.getenv());var body=new JsonObject();
                    body.addProperty("server_id",settings.serverId().toString());body.addProperty("actor_user_id",identity.userId().toString());
                    body.addProperty("attacker",StringArgumentType.getString(context,"attacker"));body.addProperty("defender",StringArgumentType.getString(context,"defender"));
                    body.addProperty("preparation_minutes",IntegerArgumentType.getInteger(context,"preparation"));body.addProperty("raid_minutes",IntegerArgumentType.getInteger(context,"raid"));
                    HttpJson.post(settings.heartbeatUrl().resolve("../wars/schedule"),settings.key(),body).whenComplete((reply,failure)->source.getServer().execute(()->{
                        if(failure!=null)source.sendFailure(Component.literal("Война не назначена: нужен владелец; кланы должны быть свободны от войны и 24-часового перерыва."));
                        else source.getServer().getPlayerList().broadcastSystemMessage(Component.literal("Назначена война "+body.get("attacker").getAsString()+" → "+body.get("defender").getAsString()+". Подготовка: "+body.get("preparation_minutes").getAsInt()+" мин., рейд: "+body.get("raid_minutes").getAsInt()+" мин."),false);
                    }));return 1;
                }catch(RuntimeException failure){source.sendFailure(Component.literal("Сервис войн недоступен."));return 0;}
            }))))));
    }
}

