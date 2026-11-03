package ru.animeoncraft.integration.neoforge;

import java.util.UUID;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.SharedSuggestionProvider;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/** Operator gift of crystals to every player account. The backend pays and audits it; one gift id per command,
 * so a lost reply cannot pay twice. The operator must be in game through the launcher. */
final class PremiumCommands {
    private static final SimpleCommandExceptionType NO_LAUNCHER = new SimpleCommandExceptionType(Component.literal("Команду нужно выполнять в игре, войдя через лаунчер."));
    private static boolean inFlight;
    private PremiumCommands() {}

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("aoccrystals").requires(s -> s.hasPermission(4))
            .then(Commands.literal("giveall").then(Commands.argument("amount", IntegerArgumentType.integer(1, 5000)).executes(c -> {
                var source = c.getSource(); var p = source.getPlayerOrException(); var identity = GameIdentity.PLAYERS.get(p.getUUID());
                if (identity == null) throw NO_LAUNCHER.create();
                if (inFlight) { source.sendFailure(Component.literal("Предыдущая выдача ещё выполняется.")); return 0; }
                int amount = IntegerArgumentType.getInteger(c, "amount"); var server = source.getServer();
                var settings = ServerSettings.from(System.getenv()); var body = new JsonObject();
                body.addProperty("server_id", settings.serverId().toString()); body.addProperty("connection_id", identity.connection().toString());
                body.addProperty("minecraft_uuid", p.getUUID().toString()); body.addProperty("amount", amount); body.addProperty("gift_id", UUID.randomUUID().toString());
                inFlight = true; source.sendSuccess(() -> Component.literal("Выдаём " + amount + " ◆ всем игрокам…"), false);
                HttpJson.post(settings.heartbeatUrl().resolve("../premium/grant-all"), settings.key(), body, 40000).whenComplete((reply, failure) -> server.execute(() -> {
                    inFlight = false;
                    int players;
                    try { if (failure != null) throw new IllegalStateException(failure); players = reply.get("players").getAsInt(); }
                    catch (RuntimeException e) { source.sendFailure(Component.literal("Сервер кристаллов не подтвердил выдачу. Проверьте журнал, прежде чем повторять.")); return; }
                    source.sendSuccess(() -> Component.literal("Выдано " + amount + " ◆ игрокам: " + players + "."), true);
                    System.out.println("AOC_CRYSTALS GIFT_ALL by " + p.getUUID() + " +" + amount + " players=" + players);
                    for (var online : server.getPlayerList().getPlayers()) {
                        online.sendSystemMessage(Component.literal("Подарок от администрации: +" + amount + " ◆ Кристаллов Разлома!").withColor(0xf07aa6));
                        PremiumCrystals.balances.remove(online.getUUID());
                        if (GameIdentity.PLAYERS.containsKey(online.getUUID())) GachaServer.request(online, new GachaPayloads.Request("READ", 0));
                    }
                }));
                return 1;
            })))
            // One player by game name; the backend finds the account, so the player may be offline.
            .then(Commands.literal("give").then(Commands.argument("player", StringArgumentType.word())
                .suggests((c, b) -> SharedSuggestionProvider.suggest(c.getSource().getOnlinePlayerNames(), b))
                .then(Commands.argument("amount", IntegerArgumentType.integer(1, 5000)).executes(c -> {
                var source = c.getSource(); var p = source.getPlayerOrException(); var identity = GameIdentity.PLAYERS.get(p.getUUID());
                if (identity == null) throw NO_LAUNCHER.create();
                String name = StringArgumentType.getString(c, "player");
                if (!name.matches("[A-Za-z0-9_]{3,16}")) { source.sendFailure(Component.literal("Неверный ник: " + name)); return 0; }
                if (inFlight) { source.sendFailure(Component.literal("Предыдущая выдача ещё выполняется.")); return 0; }
                int amount = IntegerArgumentType.getInteger(c, "amount"); var server = source.getServer();
                var settings = ServerSettings.from(System.getenv()); var body = new JsonObject();
                body.addProperty("server_id", settings.serverId().toString()); body.addProperty("connection_id", identity.connection().toString());
                body.addProperty("minecraft_uuid", p.getUUID().toString()); body.addProperty("amount", amount); body.addProperty("gift_id", UUID.randomUUID().toString());
                body.addProperty("player", name);
                inFlight = true; source.sendSuccess(() -> Component.literal("Выдаём " + amount + " ◆ игроку " + name + "…"), false);
                HttpJson.post(settings.heartbeatUrl().resolve("../premium/grant-one"), settings.key(), body, 40000).whenComplete((reply, failure) -> server.execute(() -> {
                    inFlight = false;
                    String player; UUID target; int total; boolean found;
                    try {
                        if (failure != null) throw new IllegalStateException(failure);
                        found = reply.get("found").getAsBoolean();
                        player = found ? reply.get("player").getAsString() : name; target = found ? UUID.fromString(reply.get("minecraft_uuid").getAsString()) : null;
                        total = found ? reply.getAsJsonObject("wallet").get("total").getAsInt() : 0;
                    } catch (RuntimeException e) {
                        source.sendFailure(Component.literal("Сервер кристаллов не подтвердил выдачу. Проверьте журнал, прежде чем повторять."));
                        return;
                    }
                    if (!found) { source.sendFailure(Component.literal("Игрок " + name + " не найден: он ещё ни разу не заходил через лаунчер.")); return; }
                    source.sendSuccess(() -> Component.literal("Выдано " + amount + " ◆ игроку " + player + ". Теперь у него " + total + " ◆."), true);
                    System.out.println("AOC_CRYSTALS GIFT_ONE by " + p.getUUID() + " to " + target + " +" + amount);
                    var online = server.getPlayerList().getPlayer(target);
                    if (online != null) {
                        online.sendSystemMessage(Component.literal("Подарок от администрации: +" + amount + " ◆ Кристаллов Разлома!").withColor(0xf07aa6));
                        PremiumCrystals.balances.remove(online.getUUID());
                        if (GameIdentity.PLAYERS.containsKey(online.getUUID())) GachaServer.request(online, new GachaPayloads.Request("READ", 0));
                    }
                }));
                return 1;
            })))));
    }
}
