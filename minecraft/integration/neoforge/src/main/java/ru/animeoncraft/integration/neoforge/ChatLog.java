package ru.animeoncraft.integration.neoforge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Pure tab model of the client chat: which tab a line belongs to, unread counters and what is sent for each tab. */
final class ChatLog<T> {
    enum Tab {
        LOCAL("Ближний", 0x6EE7A8), GLOBAL("Общий", 0xFFC857), CLAN("Клановый", 0xC48CFF), INFO("Информация", 0x7FB2FF);
        final String label; final int color;
        Tab(String label, int color) { this.label = label; this.color = color; }
        int bit() { return 1 << ordinal(); }
        static Tab of(int channel) { return channel >= 0 && channel < values().length ? values()[channel] : INFO; }
    }
    /** Whispers and other player chat that has no channel of its own show up in every chat tab, never in Информация. */
    static final int CHAT_TABS = Tab.LOCAL.bit() | Tab.GLOBAL.bit() | Tab.CLAN.bit();
    static final int CAPACITY = 300;

    record Entry<T>(int mask, T line, long time, boolean mention) {
        boolean in(Tab tab) { return (mask & tab.bit()) != 0; }
    }

    private final List<Entry<T>> entries = new ArrayList<>();
    private final int[] unread = new int[Tab.values().length];
    private Tab active = Tab.LOCAL;

    Tab active() { return active; }
    void select(Tab tab) { active = tab; unread[tab.ordinal()] = 0; }
    Tab next(int step) { var all = Tab.values(); return all[Math.floorMod(active.ordinal() + step, all.length)]; }
    int unread(Tab tab) { return unread[tab.ordinal()]; }

    Entry<T> add(int mask, T line, long time, boolean mention) {
        if (mask == 0) mask = Tab.INFO.bit();
        var entry = new Entry<>(mask, line, time, mention);
        entries.add(entry);
        if (entries.size() > CAPACITY) entries.removeFirst();
        for (var tab : Tab.values()) if (tab != active && entry.in(tab)) unread[tab.ordinal()] = Math.min(999, unread[tab.ordinal()] + 1);
        return entry;
    }

    /** Lines of one tab, oldest first. */
    List<Entry<T>> view(Tab tab) { return entries.stream().filter(e -> e.in(tab)).toList(); }
    /** Everything received since the given time, oldest first (HUD while the chat is closed). */
    List<Entry<T>> since(long time, boolean info) {
        return entries.stream().filter(e -> e.time() >= time && (info || (e.mask() & CHAT_TABS) != 0)).toList();
    }
    void clear() { entries.clear(); java.util.Arrays.fill(unread, 0); active = Tab.LOCAL; }

    /** What the server receives for text typed in a tab. Commands pass unchanged; the server parses ! and !! prefixes. */
    static String outgoing(Tab tab, String text) {
        // An explicit old-style prefix wins in every tab, so "!! план" typed in Общий never leaks a clan line to everyone.
        if (text.isEmpty() || text.startsWith("/") || text.startsWith("!")) return text;
        return switch (tab) {
            case GLOBAL -> "! " + text;
            case CLAN -> "!! " + text;
            case LOCAL, INFO -> text;
        };
    }

    private static final Pattern URL = Pattern.compile("https?://[\\w.-]+(?::\\d{1,5})?(?:/[\\w\\-./?%&=+#~:@!,;]*)?", Pattern.CASE_INSENSITIVE);
    record Part(String text, boolean url) {}
    /** Splits text into plain parts and http(s) links, which the screen turns into clickable components. */
    static List<Part> parts(String text) {
        var result = new ArrayList<Part>(); var m = URL.matcher(text); int at = 0;
        while (m.find()) {
            if (m.start() > at) result.add(new Part(text.substring(at, m.start()), false));
            result.add(new Part(m.group(), true)); at = m.end();
        }
        if (at < text.length()) result.add(new Part(text.substring(at), false));
        return result;
    }

    /** True when the text names the player as a whole word or with @, case-insensitive. */
    static boolean mentions(String text, String name) {
        if (name == null || name.length() < 3) return false;
        return Pattern.compile("(?<![\\p{L}\\p{N}_])@?" + Pattern.quote(name.toLowerCase(Locale.ROOT)) + "(?![\\p{L}\\p{N}_])").matcher(text.toLowerCase(Locale.ROOT)).find();
    }
}
