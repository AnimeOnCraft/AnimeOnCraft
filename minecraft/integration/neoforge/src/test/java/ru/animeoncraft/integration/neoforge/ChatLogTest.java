package ru.animeoncraft.integration.neoforge;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ChatLogTest {
    @Test
    void tabsRouteTextToTheServerPrefixes() {
        assertEquals("привет", ChatLog.outgoing(ChatLog.Tab.LOCAL, "привет"));
        assertEquals("! привет", ChatLog.outgoing(ChatLog.Tab.GLOBAL, "привет"));
        assertEquals("!! привет", ChatLog.outgoing(ChatLog.Tab.CLAN, "привет"));
        assertEquals("привет", ChatLog.outgoing(ChatLog.Tab.INFO, "привет"));
        assertEquals("/spawn", ChatLog.outgoing(ChatLog.Tab.CLAN, "/spawn"));
        // An explicit old prefix wins: a clan line typed in Общий must not reach everyone.
        assertEquals("!! план", ChatLog.outgoing(ChatLog.Tab.GLOBAL, "!! план"));
        assertEquals(ChatChannels.Channel.CLAN, ChatChannels.parse(ChatLog.outgoing(ChatLog.Tab.GLOBAL, "!! план")).channel());
        assertEquals(ChatChannels.Channel.GLOBAL, ChatChannels.parse(ChatLog.outgoing(ChatLog.Tab.GLOBAL, "всем")).channel());
        assertEquals(ChatChannels.Channel.CLAN, ChatChannels.parse(ChatLog.outgoing(ChatLog.Tab.CLAN, "свои")).channel());
        assertEquals("свои", ChatChannels.parse(ChatLog.outgoing(ChatLog.Tab.CLAN, "свои")).text());
        assertEquals(ChatChannels.Channel.LOCAL, ChatChannels.parse(ChatLog.outgoing(ChatLog.Tab.LOCAL, "рядом")).channel());
    }

    @Test
    void linesLandInTheirTabsAndCountAsUnreadElsewhere() {
        var log = new ChatLog<String>();
        log.add(ChatLog.Tab.GLOBAL.bit(), "g", 1, false);
        log.add(ChatLog.Tab.LOCAL.bit(), "l", 2, false);
        log.add(ChatLog.CHAT_TABS, "whisper", 3, false);
        log.add(0, "system", 4, false);
        assertEquals(java.util.List.of("l", "whisper"), log.view(ChatLog.Tab.LOCAL).stream().map(ChatLog.Entry::line).toList());
        assertEquals(java.util.List.of("g", "whisper"), log.view(ChatLog.Tab.GLOBAL).stream().map(ChatLog.Entry::line).toList());
        assertEquals(java.util.List.of("system"), log.view(ChatLog.Tab.INFO).stream().map(ChatLog.Entry::line).toList());
        assertEquals(0, log.unread(ChatLog.Tab.LOCAL));
        assertEquals(2, log.unread(ChatLog.Tab.GLOBAL));
        assertEquals(1, log.unread(ChatLog.Tab.CLAN));
        assertEquals(1, log.unread(ChatLog.Tab.INFO));
        log.select(ChatLog.Tab.GLOBAL);
        assertEquals(0, log.unread(ChatLog.Tab.GLOBAL));
        assertEquals(ChatLog.Tab.CLAN, log.next(1));
        assertEquals(ChatLog.Tab.LOCAL, log.next(-1));
        assertEquals(3, log.since(2, true).size());
        assertEquals(2, log.since(2, false).size());
    }

    @Test
    void capacityDropsTheOldestLines() {
        var log = new ChatLog<Integer>();
        for (int i = 0; i < ChatLog.CAPACITY + 5; i++) log.add(ChatLog.Tab.LOCAL.bit(), i, i, false);
        var view = log.view(ChatLog.Tab.LOCAL);
        assertEquals(ChatLog.CAPACITY, view.size());
        assertEquals(5, view.getFirst().line());
    }

    @Test
    void linksAndMentions() {
        var parts = ChatLog.parts("тест https://animeoncraft.github.io/#map ок");
        assertEquals(3, parts.size());
        assertTrue(parts.get(1).url());
        assertEquals("https://animeoncraft.github.io/#map", parts.get(1).text());
        assertEquals(1, ChatLog.parts("без ссылок").size());
        assertTrue(ChatLog.mentions("Матвей, @Charizma глянь", "charizma"));
        assertTrue(ChatLog.mentions("charizma1337 тут", "Charizma1337"));
        assertFalse(ChatLog.mentions("charizma13378", "Charizma1337"));
        assertFalse(ChatLog.mentions("ab", "ab"));
    }

    @Test
    void payloadRejectsUnsafeText() {
        var id = java.util.UUID.randomUUID();
        assertDoesNotThrow(() -> new ChatPayloads.Line(1, id, "Player_1", "", -1, "", "", false, "привет"));
        assertThrows(IllegalArgumentException.class, () -> new ChatPayloads.Line(1, id, "Player_1", "", -1, "", "", false, "§cкрасный"));
        assertThrows(IllegalArgumentException.class, () -> new ChatPayloads.Line(3, id, "Player_1", "", -1, "", "", false, "x"));
        assertThrows(IllegalArgumentException.class, () -> new ChatPayloads.Notice(1, 0, "a\nb"));
    }
}
