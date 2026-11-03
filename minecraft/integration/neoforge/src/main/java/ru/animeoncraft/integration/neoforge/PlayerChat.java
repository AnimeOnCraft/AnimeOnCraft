package ru.animeoncraft.integration.neoforge;

import java.util.Collection;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Cancels the vanilla broadcast before delivering a message to its permitted audience.
 * Clients with the tabbed chat get a structured line; any other client gets the same plain system message as before. */
final class PlayerChat {
    private PlayerChat() {}

    static void chat(ServerChatEvent event) {
        if (!event.getPlayer().server.isDedicatedServer()) return;
        event.setCanceled(true);
        deliver(event.getPlayer(), event.getRawText(), event.getPlayer().server.getPlayerList().getPlayers());
    }

    static void deliver(ServerPlayer sender, String raw, Collection<ServerPlayer> players) {
        var identity = GameIdentity.PLAYERS.get(sender.getUUID());
        var message = ChatChannels.parse(raw);
        int tab = message.channel().ordinal();
        if (identity == null) {
            notice(sender, tab, 2, Component.literal("Чат недоступен: войдите через лаунчер AnimeOnCraft.").withStyle(ChatFormatting.RED));
            return;
        }
        if (message.text().isEmpty()) {
            notice(sender, tab, 1, Component.literal("Введите текст сообщения после префикса чата.").withStyle(ChatFormatting.YELLOW));
            return;
        }
        String clan = ClanCodes.known(identity.clan()) ? identity.clan() : null;
        if (message.channel() == ChatChannels.Channel.CLAN && clan == null) {
            notice(sender, tab, 1, Component.literal("Вы не состоите в клане. Клановый чат: !! текст").withStyle(ChatFormatting.YELLOW));
            return;
        }
        var origin = audience(sender, clan);
        String label = switch (message.channel()) {
            case GLOBAL -> "[Общий] ";
            case CLAN -> "[Клан " + ClanCodes.display(clan) + "] ";
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
        var line = line(sender, tab, clan, message.text());
        int others = 0;
        for (var recipient : players) {
            var member = GameIdentity.PLAYERS.get(recipient.getUUID());
            if (member == null || recipient.getChatVisibility() != ChatVisiblity.FULL) continue;
            if (!ChatChannels.receives(message.channel(), origin, audience(recipient, member.clan()))) continue;
            // Deliver as a system component or our own payload: never attach a modified body to the original chat signature.
            if (line != null && tabbed(recipient)) PacketDistributor.sendToPlayer(recipient, line);
            else recipient.sendSystemMessage(formatted);
            if (!recipient.getUUID().equals(sender.getUUID())) others++;
        }
        if (message.channel() == ChatChannels.Channel.LOCAL && others == 0)
            notice(sender, tab, 0, Component.literal("В радиусе " + ChatChannels.LOCAL_RADIUS + " блоков вас никто не слышит. Общий чат: ! текст").withStyle(ChatFormatting.GRAY));
    }

    /** Builds the structured line; null (plain fallback for everyone) if anything about it is unexpected. */
    private static ChatPayloads.Line line(ServerPlayer sender, int tab, String clan, String text) {
        try {
            var server = sender.server;
            var card = GuildStore.get(server).existing(sender.getUUID());
            int rank = card == null || !card.opened ? -1 : card.rank;
            String title = PlayerProfileStore.get(server).player(sender.getUUID()).selected;
            if (title == null || ProfileProgression.find(title) == null) title = "";
            String beta = BetaPassStore.get(server).player(sender.getUUID()).equipped.getOrDefault("TITLE", "");
            if (beta.length() > 40) beta = "";
            return new ChatPayloads.Line(tab, sender.getUUID(), sender.getGameProfile().getName(), clan == null ? "" : clan, rank, title, beta, sender.hasPermissions(4), text);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    static boolean tabbed(ServerPlayer player) {
        try { return player.connection != null && player.connection.hasChannel(ChatPayloads.Line.TYPE.id()); }
        catch (RuntimeException fake) { return false; }
    }

    /** A hint for one player: into the given chat tab for the tabbed chat, otherwise a system message. */
    static void notice(ServerPlayer player, int tab, int severity, Component text) {
        if (tabbed(player)) {
            try { PacketDistributor.sendToPlayer(player, new ChatPayloads.Notice(tab, severity, text.getString())); return; }
            catch (RuntimeException invalid) { /* fall through to the plain message */ }
        }
        player.sendSystemMessage(text);
    }

    private static ChatChannels.Audience audience(ServerPlayer player, String clan) {
        return new ChatChannels.Audience(player.level().dimension().location().toString(), player.getX(), player.getY(), player.getZ(), clan);
    }

    static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !player.server.isDedicatedServer()) return;
        if (tabbed(player))
            notice(player, 3, 0, Component.literal("Чат: вкладки Ближний (100 блоков), Общий, Клановый и Информация. Переключение: клик по вкладке или Ctrl+Tab."));
        else
            player.sendSystemMessage(Component.literal("Чат: без префикса — рядом (100 блоков), ! текст — общий, !! текст — клан.").withStyle(ChatFormatting.GRAY));
    }
}
