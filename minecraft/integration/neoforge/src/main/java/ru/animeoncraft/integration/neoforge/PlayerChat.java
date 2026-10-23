package ru.animeoncraft.integration.neoforge;

import java.util.Collection;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/** Cancels the vanilla broadcast before delivering a message to its permitted audience. */
final class PlayerChat {
    private PlayerChat() {}

    static void chat(ServerChatEvent event) {
        if (!event.getPlayer().server.isDedicatedServer()) return;
        event.setCanceled(true);
        deliver(event.getPlayer(), event.getRawText(), event.getPlayer().server.getPlayerList().getPlayers());
    }

    static void deliver(ServerPlayer sender, String raw, Collection<ServerPlayer> players) {
        var identity = GameIdentity.PLAYERS.get(sender.getUUID());
        if (identity == null) {
            sender.sendSystemMessage(Component.literal("Чат недоступен: войдите через лаунчер AnimeOnCraft.").withStyle(ChatFormatting.RED));
            return;
        }
        var message = ChatChannels.parse(raw);
        if (message.text().isEmpty()) {
            sender.sendSystemMessage(Component.literal("Введите текст сообщения после префикса чата.").withStyle(ChatFormatting.YELLOW));
            return;
        }
        String clan = ClanCodes.known(identity.clan()) ? identity.clan() : null;
        if (message.channel() == ChatChannels.Channel.CLAN && clan == null) {
            sender.sendSystemMessage(Component.literal("Вы не состоите в клане. Клановый чат: !! текст").withStyle(ChatFormatting.YELLOW));
            return;
        }
        var origin = audience(sender, clan);
        String label = switch (message.channel()) {
            case GLOBAL -> "[Общий] ";
            case CLAN -> "[Клан " + clan + "] ";
            case LOCAL -> "[Рядом] ";
        };
        var color = switch (message.channel()) {
            case GLOBAL -> ChatFormatting.GOLD;
            case CLAN -> ChatFormatting.LIGHT_PURPLE;
            case LOCAL -> ChatFormatting.GREEN;
        };
        var formatted = Component.literal(label).withStyle(color)
            .append(Component.literal(sender.getGameProfile().getName() + ": ").withStyle(ChatFormatting.WHITE))
            .append(Component.literal(message.text()).withStyle(ChatFormatting.WHITE));
        int others = 0;
        for (var recipient : players) {
            var member = GameIdentity.PLAYERS.get(recipient.getUUID());
            if (member == null || recipient.getChatVisibility() != ChatVisiblity.FULL) continue;
            if (!ChatChannels.receives(message.channel(), origin, audience(recipient, member.clan()))) continue;
            // Deliver as a system component: never attach a modified body to the original chat signature.
            recipient.sendSystemMessage(formatted);
            if (!recipient.getUUID().equals(sender.getUUID())) others++;
        }
        if (message.channel() == ChatChannels.Channel.LOCAL && others == 0)
            sender.sendSystemMessage(Component.literal("В радиусе " + ChatChannels.LOCAL_RADIUS + " блоков вас никто не слышит. Общий чат: ! текст").withStyle(ChatFormatting.GRAY));
    }

    private static ChatChannels.Audience audience(ServerPlayer player, String clan) {
        return new ChatChannels.Audience(player.level().dimension().location().toString(), player.getX(), player.getY(), player.getZ(), clan);
    }

    static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && player.server.isDedicatedServer())
            player.sendSystemMessage(Component.literal("Чат: без префикса — рядом (100 блоков), ! текст — общий, !! текст — клан.").withStyle(ChatFormatting.GRAY));
    }
}
