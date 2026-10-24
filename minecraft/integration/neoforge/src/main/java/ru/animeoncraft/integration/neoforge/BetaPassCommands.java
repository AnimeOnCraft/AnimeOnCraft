package ru.animeoncraft.integration.neoforge;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;

/** Administrative pass progress uses the same world-owned store as earned quest XP. */
final class BetaPassCommands {
    private static final SimpleCommandExceptionType SINGLE_PLAYER = new SimpleCommandExceptionType(Component.literal("Укажите одного игрока."));
    private static final SimpleCommandExceptionType UNKNOWN_PLAYER = new SimpleCommandExceptionType(Component.literal("Игрок ещё не заходил на сервер. Проверьте ник."));
    private BetaPassCommands() {}

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = Commands.literal("aocbetapass").requires(s -> s.hasPermission(4));
        for (boolean open : new boolean[] {true, false}) root.then(Commands.literal(open ? "open" : "close").executes(c -> {
            var store = BetaPassStore.get(c.getSource().getServer()); store.open = open; store.setDirty();
            for (var p : c.getSource().getServer().getPlayerList().getPlayers()) BetaPassServer.send(p);
            c.getSource().sendSuccess(() -> Component.literal(open ? "Бета-пасс открыт" : "Бета-пасс закрыт. Полученная косметика сохранена."), true); return 1;
        }));
        root.then(Commands.literal("addlevels")
            .then(Commands.argument("player", GameProfileArgument.gameProfile())
                .then(Commands.argument("levels", IntegerArgumentType.integer(1, 10)).executes(c -> {
                    var profiles = GameProfileArgument.getGameProfiles(c, "player");
                    if (profiles.size() != 1) throw SINGLE_PLAYER.create();
                    var profile = profiles.iterator().next(); var source = c.getSource();
                    var online = source.getServer().getPlayerList().getPlayer(profile.getId());
                    var store = BetaPassStore.get(source.getServer()); var entry = store.existingPlayer(profile.getId());
                    // Offline-mode profile lookup can invent a UUID for a typo. Never create progress for it.
                    if (entry == null && online == null) throw UNKNOWN_PLAYER.create();
                    if (entry == null) entry = store.player(profile.getId());
                    int added = entry.addLevels(IntegerArgumentType.getInteger(c, "levels"));
                    if (added == 0) { source.sendFailure(Component.literal("У игрока " + profile.getName() + " уже максимальный уровень БП (10).")); return 0; }
                    store.setDirty(); int level = BetaPass.level(entry.xp);
                    String rewards = store.open ? "Награды можно забрать в меню БП." : "БП закрыт; прогресс сохранён до открытия.";
                    source.sendSuccess(() -> Component.literal("Бета-пасс " + profile.getName() + ": +" + added + " ур., теперь " + level + "/10. " + rewards), true);
                    if (online != null) {
                        BetaPassServer.send(online);
                        online.sendSystemMessage(Component.literal("Вам начислено " + added + " ур. бета-пасса. Уровень: " + level + "/10. " + rewards));
                    }
                    return added;
                }))));
        dispatcher.register(root);
    }
}
