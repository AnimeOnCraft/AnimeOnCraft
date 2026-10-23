package ru.animeoncraft.integration.neoforge;

/** Pure routing policy; clan membership is supplied by the server, never by message text. */
final class ChatChannels {
    static final int LOCAL_RADIUS = 100;
    enum Channel { LOCAL, GLOBAL, CLAN }
    record Message(Channel channel, String text) {}
    record Audience(String dimension, double x, double y, double z, String clan) {}
    private ChatChannels() {}

    static Message parse(String raw) {
        String text = raw.strip();
        if (text.startsWith("!!")) return new Message(Channel.CLAN, text.substring(2).strip());
        if (text.startsWith("!")) return new Message(Channel.GLOBAL, text.substring(1).strip());
        return new Message(Channel.LOCAL, text);
    }

    static boolean receives(Channel channel, Audience sender, Audience recipient) {
        return switch (channel) {
            case GLOBAL -> true;
            case CLAN -> sender.clan() != null && sender.clan().equals(recipient.clan());
            case LOCAL -> {
                double dx = sender.x() - recipient.x(), dy = sender.y() - recipient.y(), dz = sender.z() - recipient.z();
                yield sender.dimension().equals(recipient.dimension()) && dx * dx + dy * dy + dz * dz <= LOCAL_RADIUS * LOCAL_RADIUS;
            }
        };
    }
}
