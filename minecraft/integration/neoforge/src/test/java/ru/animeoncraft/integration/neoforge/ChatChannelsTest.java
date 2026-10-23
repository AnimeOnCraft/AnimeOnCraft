package ru.animeoncraft.integration.neoforge;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ChatChannelsTest {
    private static ChatChannels.Audience at(String dimension, double x, double y, double z, String clan) {
        return new ChatChannels.Audience(dimension, x, y, z, clan);
    }
    @Test void longestPrefixWinsAndEmptyMessagesStayInTheirChannel() {
        assertEquals(new ChatChannels.Message(ChatChannels.Channel.CLAN, "Привет!"), ChatChannels.parse("  !! Привет!  "));
        assertEquals(new ChatChannels.Message(ChatChannels.Channel.GLOBAL, "всем"), ChatChannels.parse("!всем"));
        assertEquals(new ChatChannels.Message(ChatChannels.Channel.LOCAL, "привет !"), ChatChannels.parse("привет !"));
        assertEquals(new ChatChannels.Message(ChatChannels.Channel.CLAN, "!"), ChatChannels.parse("!!!"));
        assertEquals(new ChatChannels.Message(ChatChannels.Channel.CLAN, ""), ChatChannels.parse("!!  "));
        assertEquals("", ChatChannels.parse("! ").text());
        assertEquals("", ChatChannels.parse("   ").text());
    }
    @Test void localRadiusIsThreeDimensionalAndNeverCrossesDimensions() {
        var sender = at("overworld", 0, 64, 0, "NYA");
        assertTrue(ChatChannels.receives(ChatChannels.Channel.LOCAL, sender, at("overworld", 100, 64, 0, null)));
        assertFalse(ChatChannels.receives(ChatChannels.Channel.LOCAL, sender, at("overworld", 100.01, 64, 0, "NYA")));
        assertTrue(ChatChannels.receives(ChatChannels.Channel.LOCAL, sender, at("overworld", 60, 64, 80, "SCAM")));
        assertFalse(ChatChannels.receives(ChatChannels.Channel.LOCAL, sender, at("overworld", 0, 165, 0, "NYA")));
        assertFalse(ChatChannels.receives(ChatChannels.Channel.LOCAL, sender, at("nether", 0, 64, 0, "NYA")));
    }
    @Test void clanCannotLeakToOtherClansOrClanlessPlayers() {
        var sender = at("overworld", 0, 64, 0, "NYA");
        assertTrue(ChatChannels.receives(ChatChannels.Channel.CLAN, sender, at("nether", 2000, 64, 3000, "NYA")));
        assertFalse(ChatChannels.receives(ChatChannels.Channel.CLAN, sender, at("overworld", 0, 64, 0, "SCAM")));
        assertFalse(ChatChannels.receives(ChatChannels.Channel.CLAN, sender, at("overworld", 0, 64, 0, null)));
        assertFalse(ChatChannels.receives(ChatChannels.Channel.CLAN, at("overworld", 0, 0, 0, null), at("overworld", 0, 0, 0, null)));
        assertTrue(ChatChannels.receives(ChatChannels.Channel.GLOBAL, sender, at("end", 30000, 0, 30000, null)));
    }
}
