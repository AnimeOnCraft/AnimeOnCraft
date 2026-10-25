package ru.animeoncraft.integration.neoforge;

import java.util.UUID;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
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
            }))));
    }
}
