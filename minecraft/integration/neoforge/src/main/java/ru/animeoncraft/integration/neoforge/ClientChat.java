package ru.animeoncraft.integration.neoforge;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientChatEvent;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.lwjgl.glfw.GLFW;

/** Tabbed chat in the AnimeOnCraft style: Ближний, Общий, Клановый and Информация.
 * Draws over the vanilla chat screen (vanilla keeps the input box, history and command suggestions)
 * and replaces only the chat HUD layer. The gear menu switches back to the vanilla chat at any time. */
public final class ClientChat {
    private ClientChat() {}

    /** ChatScreen renders vanilla history directly, outside the cancellable HUD layer. */
    public static boolean replacesVanillaHistory() {
        return SETTINGS.enabled && Minecraft.getInstance().screen instanceof ChatScreen;
    }

    /** One stored line: a structured player line, a server hint, or any other vanilla component. */
    record Item(ChatPayloads.Line line, ChatPayloads.Notice notice, Component vanilla) {}
    private record Badge(Component text, int color, int width) {}
    private record Rendered(Badge badge, List<FormattedCharSequence> rows, int indent) {}
    private record Hit(int x, int y, FormattedCharSequence row) {}
    private record Rect(int x, int y, int w, int h) { boolean in(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; } }
    private record Layout(int x, int w, int barTop, int boxTop, int boxBottom, int rows) {}

    static final ChatLog<Item> LOG = new ChatLog<>();
    static final ResourceLocation MENU = ResourceLocation.fromNamespaceAndPath("animeoncraft", "menu");
    static final ResourceLocation HEADING = ResourceLocation.fromNamespaceAndPath("animeoncraft", "menu_heading");
    private static final int ROW = 11, BAR = 20, BG = 0x1A1B2E, BORDER = 0x3A2F5C, TEXT = 0xF2F0FA, MUTED = 0xA9A6BD;
    private static final String[] EMOJI = {"☺", "☹", "♥", "★", "✿", "❀", "☀", "☁", "☂", "☃", "♪", "♫", "⚔", "☠", "✌", "✔", "✘", "⚡", "☕", "♛", "✦", "☯", "∞", "➜",
        "(=^･ω･^=)", "(◕‿◕)", "(＾▽＾)", "(｡•́︿•̀｡)", "ʕ•ᴥ•ʔ", "¯\\_(ツ)_/¯", "(╯°□°)╯︵ ┻━┻", "(ﾉ◕ヮ◕)ﾉ*:･ﾟ✧", "UwU", "OwO", "nya~", "GG"};

    static int scroll, maxScroll;
    static boolean gearOpen, emojiOpen;
    private static final List<Hit> hits = new ArrayList<>();
    private static GuiMessage lastVanilla;
    private static Field allMessages, inputField;
    private static boolean reflectionFailed;
    static final Settings SETTINGS = Settings.load();

    /** Per-player options, saved in config/animeoncraft-chat.properties. */
    static final class Settings {
        boolean enabled = true, time = false, infoHud = true, mentionSound = true, menuFont = true;
        static Path file() { return FMLPaths.CONFIGDIR.get().resolve("animeoncraft-chat.properties"); }
        static Settings load() {
            var s = new Settings();
            try (var in = Files.newBufferedReader(file())) {
                var p = new Properties(); p.load(in);
                s.enabled = !"false".equals(p.getProperty("enabled")); s.time = "true".equals(p.getProperty("time"));
                s.infoHud = !"false".equals(p.getProperty("info_hud")); s.mentionSound = !"false".equals(p.getProperty("mention_sound"));
                s.menuFont = !"false".equals(p.getProperty("menu_font"));
            } catch (Exception missing) { /* defaults */ }
            return s;
        }
        void save() {
            var p = new Properties();
            p.setProperty("enabled", "" + enabled); p.setProperty("time", "" + time); p.setProperty("info_hud", "" + infoHud);
            p.setProperty("mention_sound", "" + mentionSound); p.setProperty("menu_font", "" + menuFont);
            try (var out = Files.newBufferedWriter(file())) { p.store(out, "AnimeOnCraft chat"); } catch (Exception ignored) { /* not critical */ }
        }
        String[] labels() { return new String[]{"Новый чат AnimeOnCraft", "Время у сообщений", "Информация поверх игры", "Звук при упоминании", "Шрифт AnimeOnCraft"}; }
        boolean get(int i) { return switch (i) { case 0 -> enabled; case 1 -> time; case 2 -> infoHud; case 3 -> mentionSound; default -> menuFont; }; }
        void toggle(int i) { switch (i) { case 0 -> enabled = !enabled; case 1 -> time = !time; case 2 -> infoHud = !infoHud; case 3 -> mentionSound = !mentionSound; default -> menuFont = !menuFont; } save(); }
    }

    // ---------- receiving ----------

    private static boolean current(IPayloadContext c) { var conn = Minecraft.getInstance().getConnection(); return conn != null && conn.getConnection() == c.connection(); }

    static void line(ChatPayloads.Line p, IPayloadContext c) {
        c.enqueueWork(() -> {
            if (!current(c)) return;
            var mc = Minecraft.getInstance();
            if (!SETTINGS.enabled) { mc.gui.getChat().addMessage(plain(p)); return; }
            boolean mention = mc.player != null && !p.sender().equals(mc.player.getUUID()) && ChatLog.mentions(p.text(), mc.player.getGameProfile().getName());
            add(ChatLog.Tab.of(p.channel()).bit(), new Item(p, null, null), mention);
        });
    }

    static void notice(ChatPayloads.Notice p, IPayloadContext c) {
        c.enqueueWork(() -> {
            if (!current(c)) return;
            if (!SETTINGS.enabled) { Minecraft.getInstance().gui.getChat().addMessage(Component.literal(p.text()).withColor(severityColor(p.severity()))); return; }
            add(ChatLog.Tab.of(p.channel()).bit(), new Item(null, p, null), false);
        });
    }

    /** Everything else the server sends: system messages go to Информация, whispers and other player chat to every chat tab. */
    static void received(ClientChatReceivedEvent event) {
        if (!SETTINGS.enabled || event.getMessage() == null) return;
        if (event instanceof ClientChatReceivedEvent.System overlay && overlay.isOverlay()) return;
        var mc = Minecraft.getInstance();
        boolean system = event instanceof ClientChatReceivedEvent.System;
        boolean mention = !system && mc.player != null && event.getSender() != null && !mc.player.getUUID().equals(event.getSender())
            && ChatLog.mentions(event.getMessage().getString(), mc.player.getGameProfile().getName());
        event.setCanceled(true);
        add(system ? ChatLog.Tab.INFO.bit() : ChatLog.CHAT_TABS, new Item(null, null, event.getMessage()), mention);
    }

    private static void add(int mask, Item item, boolean mention) {
        LOG.add(mask, item, System.currentTimeMillis(), mention);
        if (mention && SETTINGS.mentionSound) Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.6F, 0.6F));
    }

    /** The same text the server would have sent as a plain message (vanilla chat mode). */
    static Component plain(ChatPayloads.Line p) {
        String label = switch (p.channel()) { case 1 -> "[Общий] "; case 2 -> "[Клан " + ClanCodes.display(p.clan()) + "] "; default -> "[Рядом] "; };
        int color = switch (p.channel()) { case 1 -> 0xFFAA00; case 2 -> 0xFF55FF; default -> 0x55FF55; };
        return Component.literal(label).withColor(color).append(Component.literal(p.name() + ": " + p.text()).withColor(0xFFFFFF));
    }

    /** Messages that other code adds straight to the vanilla chat (screenshots, client hints) move into Информация. */
    static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (!(mc.screen instanceof ChatScreen)) { scroll = 0; gearOpen = false; emojiOpen = false; }
        List<GuiMessage> all = vanillaMessages(mc.gui.getChat());
        if (all == null) return;
        GuiMessage newest = all.isEmpty() ? null : all.getFirst();
        if (!SETTINGS.enabled || newest == null || newest == lastVanilla) { lastVanilla = newest; return; }
        var fresh = new ArrayList<Component>();
        for (var message : all) { if (message == lastVanilla || fresh.size() >= 50) break; fresh.add(message.content()); }
        lastVanilla = newest;
        Collections.reverse(fresh);
        for (var content : fresh) add(ChatLog.Tab.INFO.bit(), new Item(null, null, content), false);
    }

    @SuppressWarnings("unchecked")
    private static List<GuiMessage> vanillaMessages(ChatComponent chat) {
        if (reflectionFailed) return null;
        try {
            if (allMessages == null) { allMessages = ChatComponent.class.getDeclaredField("allMessages"); allMessages.setAccessible(true); }
            return (List<GuiMessage>) allMessages.get(chat);
        } catch (ReflectiveOperationException | RuntimeException failed) { reflectionFailed = true; return null; }
    }

    private static String input(ChatScreen screen) {
        try {
            if (inputField == null) { inputField = ChatScreen.class.getDeclaredField("input"); inputField.setAccessible(true); }
            var box = (EditBox) inputField.get(screen);
            return box == null ? "" : box.getValue();
        } catch (ReflectiveOperationException | RuntimeException failed) { return ""; }
    }

    static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        LOG.clear(); scroll = 0; maxScroll = 0; gearOpen = false; emojiOpen = false; hits.clear();
        var all = vanillaMessages(Minecraft.getInstance().gui.getChat());
        lastVanilla = all == null || all.isEmpty() ? null : all.getFirst();
    }

    /** Typed text is routed by the open tab; commands are untouched. */
    static void sending(ClientChatEvent event) {
        if (!SETTINGS.enabled) return;
        String out = ChatLog.outgoing(LOG.active(), event.getMessage());
        if (!out.equals(event.getMessage())) event.setMessage(out);
    }

    // ---------- building lines ----------

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
    static int severityColor(int severity) { return severity == 2 ? 0xFF6B6B : severity == 1 ? 0xFFD166 : MUTED; }

    static MutableComponent ui(String text, int color) { return Component.literal(text).withStyle(s -> s.withFont(MENU).withColor(color)); }

    /** Player text with clickable http(s) links. */
    static MutableComponent text(String value, int color) {
        var result = Component.empty();
        for (var part : ChatLog.parts(value)) {
            if (!part.url()) { result.append(Component.literal(part.text()).withColor(color)); continue; }
            result.append(Component.literal(part.text()).withStyle(s -> s.withColor(0x8EC5FF).withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, part.text()))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Открыть ссылку")))));
        }
        return result;
    }

    private static Badge badge(ChatPayloads.Line line, Font font, long now) {
        Component text = null; int color = 0;
        if (!line.betaTitle().isEmpty()) { text = ClientBetaPass.shimmer(line.betaTitle(), now); color = 0xF7A8D8; }
        if ((text == null || text.getString().isEmpty()) && !line.title().isEmpty()) {
            var title = ProfileProgression.find(line.title());
            if (title != null) { text = Component.literal(title.title()).withColor(title.color()); color = title.color(); }
        }
        if (text == null || text.getString().isEmpty()) return null;
        var styled = Component.empty().withStyle(s -> s.withFont(MENU)).append(text);
        return new Badge(styled, color & 0xFFFFFF, font.width(styled) + 8);
    }

    private static Component body(ChatLog.Entry<Item> entry) {
        var item = entry.line();
        var body = Component.empty();
        if (SETTINGS.time) body.append(Component.literal(CLOCK.format(Instant.ofEpochMilli(entry.time())) + " ").withColor(0x7D7F99));
        if (item.line() != null) {
            var p = item.line(); int clanColor = ClientNameTags.clanColor(p.clan());
            var hover = Component.literal(p.name()).withColor(clanColor)
                .append(Component.literal("\nКанал: " + ChatLog.Tab.of(p.channel()).label).withColor(MUTED));
            if (!p.clan().isEmpty()) hover.append(Component.literal("\nКлан: " + ClanCodes.display(p.clan())).withColor(clanColor));
            if (p.rank() >= 0) hover.append(Component.literal("\nРанг гильдии: " + GuildRules.Rank.of(p.rank()).name()).withColor(GuildScreen.RANK_COLORS[p.rank()] & 0xFFFFFF));
            hover.append(Component.literal("\nНажми, чтобы написать лично").withColor(0x7D7F99));
            body.append(Component.literal(p.name()).withStyle(s -> s.withColor(clanColor)
                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/msg " + p.name() + " "))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover))));
            if (p.staff()) body.append(Component.literal(" ♛").withColor(0xFFD166));
            if (p.rank() >= 0) body.append(Component.literal(" " + GuildRules.Rank.of(p.rank()).name()).withStyle(s -> s.withColor(GuildScreen.RANK_COLORS[p.rank()] & 0xFFFFFF).withBold(true)));
            body.append(Component.literal(": ").withColor(0xB9B4CC));
            body.append(text(p.text(), TEXT));
        } else if (item.notice() != null) {
            var n = item.notice();
            body.append(Component.literal("• ").withColor(ChatLog.Tab.of(n.channel()).color));
            body.append(text(n.text(), severityColor(n.severity())).withStyle(s -> s.withItalic(n.severity() == 0)));
        } else {
            body.append(item.vanilla());
        }
        return SETTINGS.menuFont ? Component.empty().withStyle(s -> s.withFont(MENU)).append(body) : body;
    }

    private static Rendered render(ChatLog.Entry<Item> entry, Font font, int width, long now) {
        var badge = entry.line().line() == null ? null : badge(entry.line().line(), font, now);
        int indent = badge == null ? 0 : badge.width() + 3;
        var rows = font.split(body(entry), Math.max(40, width - indent));
        return new Rendered(badge, rows, indent);
    }

    /** Lines drawn bottom-up from (x, bottom); each row is ROW high. Returns how many rows were available in total (capped). */
    private static int drawRows(GuiGraphics g, Font font, List<ChatLog.Entry<Item>> entries, int x, int width, int bottom, int visible, int skip, long now, int alpha, boolean hud) {
        int row = 0;
        for (int i = entries.size() - 1; i >= 0 && row < skip + visible; i--) {
            var entry = entries.get(i);
            int a = alpha;
            if (hud) { long age = now - entry.time(); a = age > 9000 ? (int) (alpha * Math.max(0, 10000 - age) / 1000) : alpha; if (a < 12) continue; }
            var r = render(entry, font, width - (hud ? 6 : 0), now);
            for (int k = r.rows().size() - 1; k >= 0 && row < skip + visible; k--, row++) {
                if (row < skip) continue;
                int y = bottom - (row - skip + 1) * ROW;
                if (hud) g.fill(x - 3, y - 1, x + width + 3, y + ROW - 1, (a / 2) << 24 | BG);
                if (hud && k == 0) g.fill(x - 3, y - 1, x - 1, y + ROW - 1, a << 24 | stripe(entry));
                if (entry.mention()) g.fill(x - 2, y - 1, x + width, y + ROW - 1, (a / 4) << 24 | 0xFFC857);
                int tx = x + (hud ? 1 : 0) + r.indent();
                if (k == 0 && r.badge() != null) {
                    var b = r.badge(); int bx = x + (hud ? 1 : 0);
                    g.fill(bx, y, bx + b.width(), y + ROW - 2, (a * 3 / 10) << 24 | b.color());
                    g.fill(bx, y + ROW - 3, bx + b.width(), y + ROW - 2, (a * 7 / 10) << 24 | b.color());
                    g.drawString(font, b.text(), bx + 4, y + 1, a << 24 | 0xFFFFFF, false);
                }
                g.drawString(font, r.rows().get(k), tx, y + 1, a << 24 | TEXT, false);
                if (!hud) hits.add(new Hit(tx, y, r.rows().get(k)));
            }
        }
        return row;
    }

    private static int stripe(ChatLog.Entry<Item> entry) {
        for (var tab : ChatLog.Tab.values()) if (entry.mask() == tab.bit()) return tab.color;
        return 0xF7A8D8; // whispers and other player chat
    }

    // ---------- layout ----------

    private record Popup(Rect panel, List<Rect> cells) {}

    private static Layout layout(Screen s) {
        var chat = Minecraft.getInstance().gui.getChat();
        int w = Math.max(240, Math.min(s.width - 8, chat.getWidth() + 60));
        int rows = Math.max(4, Math.min(Math.min(16, Math.max(6, chat.getLinesPerPage())), (s.height - 17 - BAR - 11 - 30) / ROW));
        int bottom = s.height - 17, top = bottom - rows * ROW - 8, bar = top - 3 - BAR;
        return new Layout(4, w, bar, top, bottom, rows);
    }

    private static List<Rect> tabRects(Layout l, Font font) {
        var result = new ArrayList<Rect>(); int x = l.x() + 3;
        for (var tab : ChatLog.Tab.values()) { int w = font.width(ui(tab.label, 0)) + 14; result.add(new Rect(x, l.barTop() + 2, w, 16)); x += w + 3; }
        return result;
    }

    private static Rect gearRect(Screen s, Layout l) { return SETTINGS.enabled ? new Rect(l.x() + l.w() - 18, l.barTop() + 2, 16, 16) : new Rect(s.width - 16, s.height - 14, 14, 12); }
    private static Rect emojiRect(Screen s) { return new Rect(s.width - 16, s.height - 14, 14, 12); }

    private static Popup settingsPopup(Screen s, Layout l) {
        int pw = 190, n = SETTINGS.labels().length, ph = 20 + n * 15 + 2;
        int px = SETTINGS.enabled ? l.x() + l.w() - pw : s.width - 2 - pw, py = Math.max(2, (SETTINGS.enabled ? l.barTop() - 3 : s.height - 17) - ph);
        var cells = new ArrayList<Rect>(); for (int i = 0; i < n; i++) cells.add(new Rect(px + 4, py + 18 + i * 15, pw - 8, 14));
        return new Popup(new Rect(px, py, pw, ph), cells);
    }

    private static Popup emojiPopup(Screen s, Font font) {
        int pw = Math.min(232, s.width - 8), px = s.width - 2 - pw, cx = px + 4, cy = 0;
        var cells = new ArrayList<Rect>();
        for (var e : EMOJI) { int w = font.width(ui(e, 0)) + 10; if (cx + w > px + pw - 4) { cx = px + 4; cy += 15; } cells.add(new Rect(cx, cy, w, 14)); cx += w + 2; }
        int ph = cy + 14 + 8, py = Math.max(2, s.height - 17 - ph);
        var placed = new ArrayList<Rect>(); for (var c : cells) placed.add(new Rect(c.x(), c.y() + py + 4, c.w(), c.h()));
        return new Popup(new Rect(px, py, pw, ph), placed);
    }

    // ---------- drawing ----------

    private static void outline(GuiGraphics g, int x, int y, int w, int h, int c) {
        g.fill(x + 1, y, x + w - 1, y + 1, c); g.fill(x + 1, y + h - 1, x + w - 1, y + h, c); g.fill(x, y + 1, x + 1, y + h - 1, c); g.fill(x + w - 1, y + 1, x + w, y + h - 1, c);
    }
    private static void box(GuiGraphics g, int x, int y, int w, int h, int fill, int border) { g.fill(x + 1, y + 1, x + w - 1, y + h - 1, fill); outline(g, x, y, w, h, border); }
    private static void button(GuiGraphics g, Font font, Rect r, String glyph, boolean active, int mx, int my) {
        int accent = SETTINGS.enabled ? LOG.active().color : 0xC48CFF;
        box(g, r.x(), r.y(), r.w(), r.h(), active ? 0x90 << 24 | accent : r.in(mx, my) ? 0xC0 << 24 | 0x2E2F4F : 0xC0 << 24 | BG, 0xFF << 24 | (active ? accent : BORDER));
        g.drawString(font, glyph, r.x() + (r.w() - font.width(glyph)) / 2 + 1, r.y() + (r.h() - 8) / 2, 0xFFFFFFFF, false);
    }
    private static Component logo() {
        String text = "AnimeOnCraft"; var result = Component.empty();
        for (int i = 0; i < text.length(); i++) {
            float t = i / (float) (text.length() - 1);
            int r = (int) (0xF7 + (0xA9 - 0xF7) * t), gr = (int) (0xA8 + (0x70 - 0xA8) * t), b = (int) (0xD8 + (0xFF - 0xD8) * t);
            int color = r << 16 | gr << 8 | b;
            result.append(Component.literal(text.substring(i, i + 1)).withStyle(s -> s.withFont(HEADING).withColor(color)));
        }
        return result;
    }

    /** Closed chat: recent lines fade out after ten seconds, like vanilla, from every tab with a coloured stripe. */
    static void layer(RenderGuiLayerEvent.Pre event) {
        if (!SETTINGS.enabled || !event.getName().equals(VanillaGuiLayers.CHAT)) return;
        event.setCanceled(true);
        var mc = Minecraft.getInstance();
        if (mc.screen instanceof ChatScreen) return;
        var visibility = mc.options.chatVisibility().get();
        if (visibility == ChatVisiblity.HIDDEN) return;
        long now = System.currentTimeMillis(); var g = event.getGuiGraphics();
        var list = LOG.since(now - 10_000, SETTINGS.infoHud || visibility == ChatVisiblity.SYSTEM);
        if (visibility == ChatVisiblity.SYSTEM) list = list.stream().filter(e -> e.in(ChatLog.Tab.INFO)).toList();
        int alpha = (int) (255 * Math.max(0.25, Math.min(1, mc.options.chatOpacity().get() * 0.9 + 0.1)));
        int w = Math.min(g.guiWidth() - 16, mc.gui.getChat().getWidth() + 40);
        drawRows(g, mc.font, list, 8, w, g.guiHeight() - 40, 10, 0, now, alpha, true);
    }

    /** Open chat: tab bar and message box above the vanilla input line. Drawn before the screen, so command suggestions stay on top. */
    static void screenPre(ScreenEvent.Render.Pre event) {
        if (!SETTINGS.enabled || !(event.getScreen() instanceof ChatScreen s)) return;
        var mc = Minecraft.getInstance(); var g = event.getGuiGraphics(); var font = mc.font; var l = layout(s); long now = System.currentTimeMillis();
        int mx = event.getMouseX(), my = event.getMouseY();
        box(g, l.x(), l.barTop(), l.w(), BAR, 0xE6 << 24 | BG, 0xFF << 24 | BORDER);
        var tabs = tabRects(l, font); int right = l.x();
        for (int i = 0; i < tabs.size(); i++) {
            var tab = ChatLog.Tab.values()[i]; var r = tabs.get(i); boolean active = tab == LOG.active();
            box(g, r.x(), r.y(), r.w(), r.h(), active ? 0x70 << 24 | tab.color : r.in(mx, my) ? 0x60 << 24 | 0x34355A : 0x50 << 24 | 0x2A2B45, active ? 0xFF << 24 | tab.color : 0x90 << 24 | BORDER);
            if (active) g.fill(r.x() + 5, r.y() + r.h() - 3, r.x() + r.w() - 5, r.y() + r.h() - 2, 0xFF << 24 | tab.color);
            g.drawString(font, ui(tab.label, active ? 0xFFFFFF : MUTED), r.x() + 7, r.y() + 4, 0xFFFFFFFF, false);
            int unread = LOG.unread(tab);
            if (unread > 0 && !active) {
                String t = unread > 99 ? "99+" : String.valueOf(unread); int tw = font.width(t) + 4, bx = r.x() + r.w() - tw + 3, by = r.y() - 4;
                box(g, bx, by, tw, 10, 0xFF << 24 | 0xFF5C8A, 0xFF << 24 | 0xFF5C8A);
                g.drawString(font, t, bx + 2, by + 1, 0xFFFFFFFF, false);
            }
            right = r.x() + r.w();
        }
        var logo = logo(); int lx = l.x() + l.w() - 24 - font.width(logo);
        if (lx > right + 8) g.drawString(font, logo, lx, l.barTop() + 6, 0xFFFFFFFF, false);
        box(g, l.x(), l.boxTop(), l.w(), l.boxBottom() - l.boxTop(), 0xD8 << 24 | BG, 0xFF << 24 | BORDER);
        g.fill(l.x() + 3, l.boxTop() + 4, l.x() + 5, l.boxBottom() - 4, 0xFF << 24 | LOG.active().color);
        hits.clear();
        var entries = LOG.view(LOG.active()); int inner = l.x() + 10, width = l.w() - 18;
        if (entries.isEmpty()) g.drawString(font, ui(LOG.active() == ChatLog.Tab.INFO ? "Здесь появятся сообщения сервера" : "Пока тихо… Напиши первым!", MUTED), inner, l.boxBottom() - 4 - ROW + 1, 0xFFFFFFFF, false);
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        int total = drawRows(g, font, entries, inner, width, l.boxBottom() - 4, l.rows(), scroll, now, 255, false);
        maxScroll = total >= scroll + l.rows() ? scroll + l.rows() : Math.max(0, total - l.rows());
        if (scroll > 0) { var hint = ui("▼ к новым", MUTED); g.drawString(font, hint, l.x() + l.w() - 8 - font.width(hint), l.boxBottom() - 4 - ROW + 1, 0xFFFFFFFF, false); }
        if (input(s).startsWith("/")) g.fill(l.x(), l.boxTop(), l.x() + l.w(), l.boxBottom(), 0x50000000);
    }

    /** Input frame, placeholder, gear and emoji buttons, popups and link/name tooltips, drawn after the screen. */
    static void screenPost(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof ChatScreen s)) return;
        var mc = Minecraft.getInstance(); var g = event.getGuiGraphics(); var font = mc.font; var l = layout(s);
        int mx = event.getMouseX(), my = event.getMouseY();
        if (SETTINGS.enabled) {
            outline(g, 2, s.height - 14, s.width - 4, 12, 0xC0 << 24 | LOG.active().color);
            if (input(s).isEmpty()) {
                var target = LOG.active() == ChatLog.Tab.INFO ? ChatLog.Tab.LOCAL : LOG.active();
                g.drawString(font, ui("Сообщение в «" + target.label + "»…", 0x6E6A85), 5, s.height - 11, 0xFFFFFFFF, false);
            }
            button(g, font, emojiRect(s), "☺", emojiOpen, mx, my);
        }
        button(g, font, gearRect(s, l), "⚙", gearOpen, mx, my);
        if (gearOpen) {
            var p = settingsPopup(s, l); var panel = p.panel();
            g.pose().pushPose(); g.pose().translate(0, 0, 300);
            box(g, panel.x(), panel.y(), panel.w(), panel.h(), 0xF2 << 24 | BG, 0xFF << 24 | 0xC48CFF);
            g.drawString(font, ui("Настройки чата", 0xFFFFFF), panel.x() + 6, panel.y() + 5, 0xFFFFFFFF, false);
            var labels = SETTINGS.labels();
            for (int i = 0; i < labels.length; i++) {
                var r = p.cells().get(i); boolean on = SETTINGS.get(i);
                if (r.in(mx, my)) g.fill(r.x(), r.y(), r.x() + r.w(), r.y() + r.h(), 0x40FFFFFF);
                box(g, r.x() + 2, r.y() + 2, 10, 10, on ? 0xFF << 24 | 0x6EE7A8 : 0xFF << 24 | 0x2A2B45, 0xFF << 24 | BORDER);
                if (on) g.drawString(font, "✔", r.x() + 3, r.y() + 3, 0xFF1A1B2E, false);
                g.drawString(font, ui(labels[i], on ? 0xFFFFFF : MUTED), r.x() + 16, r.y() + 3, 0xFFFFFFFF, false);
            }
            g.pose().popPose();
        }
        if (emojiOpen && SETTINGS.enabled) {
            var p = emojiPopup(s, font); var panel = p.panel();
            g.pose().pushPose(); g.pose().translate(0, 0, 300);
            box(g, panel.x(), panel.y(), panel.w(), panel.h(), 0xF2 << 24 | BG, 0xFF << 24 | LOG.active().color);
            for (int i = 0; i < EMOJI.length; i++) {
                var r = p.cells().get(i);
                box(g, r.x(), r.y(), r.w(), r.h(), r.in(mx, my) ? 0xA0 << 24 | 0x3A3B66 : 0x60 << 24 | 0x2A2B45, 0x80 << 24 | BORDER);
                g.drawString(font, ui(EMOJI[i], TEXT), r.x() + 5, r.y() + 3, 0xFFFFFFFF, false);
            }
            g.pose().popPose();
        }
        if (SETTINGS.enabled && !gearOpen && !emojiOpen) {
            var style = styleAt(font, mx, my);
            if (style != null && style.getHoverEvent() != null) g.renderComponentHoverEffect(font, style, mx, my);
        }
    }

    private static Style styleAt(Font font, double mx, double my) {
        for (var hit : hits) if (my >= hit.y() && my < hit.y() + ROW && mx >= hit.x()) return font.getSplitter().componentStyleAtWidth(hit.row(), (int) (mx - hit.x()));
        return null;
    }

    // ---------- input ----------

    static void click(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!(event.getScreen() instanceof ChatScreen s) || event.getButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;
        var font = Minecraft.getInstance().font; var l = layout(s); double mx = event.getMouseX(), my = event.getMouseY();
        if (gearRect(s, l).in(mx, my)) { gearOpen = !gearOpen; emojiOpen = false; event.setCanceled(true); return; }
        if (gearOpen) {
            var p = settingsPopup(s, l);
            for (int i = 0; i < p.cells().size(); i++) if (p.cells().get(i).in(mx, my)) { toggle(i); event.setCanceled(true); return; }
            if (p.panel().in(mx, my)) { event.setCanceled(true); return; }
            gearOpen = false;
        }
        if (!SETTINGS.enabled) return;
        if (emojiOpen) {
            var p = emojiPopup(s, font);
            for (int i = 0; i < EMOJI.length; i++) if (p.cells().get(i).in(mx, my)) {
                for (var child : s.children()) if (child instanceof EditBox box) { box.insertText(EMOJI[i]); break; }
                event.setCanceled(true); return;
            }
            if (p.panel().in(mx, my)) { event.setCanceled(true); return; }
            emojiOpen = false;
        }
        if (emojiRect(s).in(mx, my)) { emojiOpen = !emojiOpen; gearOpen = false; event.setCanceled(true); return; }
        var tabs = tabRects(l, font);
        for (int i = 0; i < tabs.size(); i++) if (tabs.get(i).in(mx, my)) { LOG.select(ChatLog.Tab.values()[i]); scroll = 0; event.setCanceled(true); return; }
        if (new Rect(l.x(), l.barTop(), l.w(), l.boxBottom() - l.barTop()).in(mx, my) && !input(s).startsWith("/")) {
            var style = styleAt(font, mx, my);
            if (style != null && style.getClickEvent() != null) s.handleComponentClicked(style);
            event.setCanceled(true);
        }
    }

    private static void toggle(int index) {
        boolean wasEnabled = SETTINGS.enabled;
        SETTINGS.toggle(index);
        if (wasEnabled && !SETTINGS.enabled) {
            // Back to vanilla: hand the recent history over so nothing disappears.
            var chat = Minecraft.getInstance().gui.getChat(); var all = LOG.since(0, true);
            // Replace the mirror instead of appending it on every toggle.
            // false keeps the player's sent-command/input history intact.
            chat.clearMessages(false);
            for (var entry : all.subList(Math.max(0, all.size() - 50), all.size())) {
                var item = entry.line();
                chat.addMessage(item.line() != null ? plain(item.line()) : item.notice() != null ? Component.literal(item.notice().text()).withColor(severityColor(item.notice().severity())) : item.vanilla());
            }
            var mirrored = vanillaMessages(chat);
            lastVanilla = mirrored == null || mirrored.isEmpty() ? null : mirrored.getFirst();
            emojiOpen = false;
        }
    }

    static void scrolled(ScreenEvent.MouseScrolled.Pre event) {
        if (!SETTINGS.enabled || !(event.getScreen() instanceof ChatScreen s)) return;
        var l = layout(s);
        if (!new Rect(l.x(), l.boxTop(), l.w(), l.boxBottom() - l.boxTop()).in(event.getMouseX(), event.getMouseY()) || input(s).startsWith("/")) return;
        int step = (int) Math.signum(event.getScrollDeltaY()) * (Screen.hasShiftDown() ? 1 : 3);
        scroll = Math.max(0, Math.min(maxScroll, scroll + step));
        event.setCanceled(true);
    }

    static void key(ScreenEvent.KeyPressed.Pre event) {
        if (!SETTINGS.enabled || !(event.getScreen() instanceof ChatScreen s)) return;
        int key = event.getKeyCode();
        if (key == GLFW.GLFW_KEY_TAB && Screen.hasControlDown()) { LOG.select(LOG.next(Screen.hasShiftDown() ? -1 : 1)); scroll = 0; event.setCanceled(true); }
        else if (key == GLFW.GLFW_KEY_ESCAPE && (gearOpen || emojiOpen)) { gearOpen = false; emojiOpen = false; event.setCanceled(true); }
        else if (key == GLFW.GLFW_KEY_PAGE_UP || key == GLFW.GLFW_KEY_PAGE_DOWN) {
            int page = Math.max(1, layout(s).rows() - 1);
            scroll = Math.max(0, Math.min(maxScroll, scroll + (key == GLFW.GLFW_KEY_PAGE_UP ? page : -page)));
            event.setCanceled(true);
        }
    }
}
