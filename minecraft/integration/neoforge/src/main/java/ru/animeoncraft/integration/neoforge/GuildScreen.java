package ru.animeoncraft.integration.neoforge;

import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** Guild board and adventurer card. Shows only the server's card; buttons send requests that the server checks again. */
final class GuildScreen extends Screen {
    private record Hit(int x, int y, int w, int h, Runnable action) { boolean contains(double a, double b) { return a >= x && a < x + w && b >= y && b < y + h; } }
    static final int[] RANK_COLORS = {0xffa8a8b3, 0xff6fdc7e, 0xff5aa8ff, 0xffb57bff, 0xffffa04a, 0xffff5a5a, 0xffffd75a};
    private final List<Hit> hits = new ArrayList<>();
    private boolean welcome; private int tab = -1, seenRank = -1; private final long opened = System.nanoTime();
    private double scale, left, top, mx, my;
    GuildScreen(boolean welcome) { super(Component.literal("Гильдия искателей приключений")); this.welcome = welcome; }
    boolean welcome() { return welcome; }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void tick() { if (minecraft.player == null || minecraft.getConnection() == null) { onClose(); return; } ClientGuild.request("GET", 0, 0); ClientGuild.tick(); }
    @Override public boolean mouseClicked(double x, double y, int button) {
        if (button != 0) return false; double a = (x - left) / scale, b = (y - top) / scale;
        for (int i = hits.size() - 1; i >= 0; i--) if (hits.get(i).contains(a, b)) { hits.get(i).action().run(); return true; }
        return false;
    }
    private static String rank(int r) { return GuildRules.Rank.of(r).name(); }
    /** The exact reward is rolled when the quest is taken; the board shows the middle of the range. */
    private static String range(long min, long max) { return min == max ? GuildRules.money(min) : "~" + GuildRules.money((min + max + 1) / 2); }
    private static String hours(int s) { return String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60); }
    private static String clock(int s) { return s / 60 + ":" + (s % 60 < 10 ? "0" : "") + s % 60; }

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        scale = Math.min(width / 1100d, height / 620d); left = (width - 1100 * scale) / 2; top = (height - 620 * scale) / 2; mx = (mouseX - left) / scale; my = (mouseY - top) / scale; hits.clear();
        renderBlurredBackground(delta); g.fill(0, 0, width, height, 0x95060010);
        g.pose().pushPose(); g.pose().translate(left, top, 0); g.pose().scale((float) scale, (float) scale, 1);
        var d = ClientGuild.data();
        if (d == null || !d.opened()) { center(g, d == null ? "Загружаем карточку…" : "Карточку выдают в зале гильдии", 550, 300, 16, 0xfff7efff); button(g, 956, 34, 89, 29, "Закрыть", this::onClose, false); }
        else if (welcome) renderWelcome(g, d);
        else renderBoard(g, d);
        g.pose().popPose();
    }

    private void renderWelcome(GuiGraphics g, GuildPayloads.Snapshot d) {
        float t = Math.min(1, (System.nanoTime() - opened) / 900_000_000f), ease = 1 - (1 - t) * (1 - t) * (1 - t);
        center(g, "ГИЛЬДИЯ ИСКАТЕЛЕЙ ПРИКЛЮЧЕНИЙ", 550, 70, 22, 0xffffd27a);
        center(g, "Добро пожаловать, " + minecraft.player.getGameProfile().getName() + "!", 550, 104, 13, 0xffcdb0f2);
        int w = 360, h = 250, x = 550 - w / 2, y = 140;
        g.pose().pushPose(); g.pose().translate(550, y + h / 2f, 0); g.pose().scale(.6f + .4f * ease, .6f + .4f * ease, 1); g.pose().translate(-550, -(y + h / 2f), 0);
        card(g, d, x, y, w, h, true);
        g.pose().popPose();
        if (t >= 1) {
            for (int r = 0; r < 7; r++) center(g, rank(r), 550 - 126 + r * 42, 410, 20, r == d.rank() ? RANK_COLORS[r] : 0xff5d4c6e);
            center(g, "семь рангов гильдии", 550, 440, 10, 0xffb9a6d0);
            center(g, "Выполняй задания с доски — карточка набирает опыт, ранг растёт, задания становятся сложнее.", 550, 492, 11, 0xfff2e8ff);
            center(g, "Ранги F–D открыты всем без ограничений. Перерыв между заданиями: 5 минут на ранге F.", 550, 512, 11, 0xffb9a6d0);
            button(g, 450, 540, 200, 34, "К доске заданий", () -> welcome = false, true);
        }
    }

    private void card(GuiGraphics g, GuildPayloads.Snapshot d, int x, int y, int w, int h, boolean big) {
        int color = RANK_COLORS[d.rank()];
        g.fill(x - 2, y - 2, x + w + 2, y + h + 2, color); g.fill(x, y, x + w, y + h, 0xf61a1226); g.fill(x, y, x + w, y + 34, 0xf62a1c3c);
        center(g, "КАРТОЧКА АВАНТЮРИСТА", x + w / 2, y + 11, 12, 0xffffd27a);
        text(g, minecraft.player.getGameProfile().getName(), x + 18, y + 50, 15, 0xfff7efff);
        text(g, "Гильдия Центральной Соры", x + 18, y + 72, 10, 0xffb9a6d0);
        int box = big ? 120 : 96, bx = x + w - box - 18, by = y + 48;
        g.fill(bx - 2, by - 2, bx + box + 2, by + box + 2, color); g.fill(bx, by, bx + box, by + box, 0xff120c1b);
        center(g, rank(d.rank()), bx + box / 2, by + box / 2 - (big ? 40 : 32), big ? 90 : 72, color);
        int ty = y + 100;
        text(g, "Ранг " + rank(d.rank()) + " · уровень " + (d.rank() + 1), x + 18, ty, 12, color); ty += 24;
        text(g, (d.limit() > 0 ? "Заданий: " : "Выполнено заданий: ") + d.completed() + (d.limit() > 0 ? " · сегодня " + d.done() + "/" + d.limit() : ""), x + 18, ty, 11, d.limit() > 0 && d.done() >= d.limit() ? 0xffffa04a : 0xfff2e8ff); ty += 20;
        fit(g, "Сегодня: " + GuildRules.money(d.earned()) + " из " + GuildRules.money(d.cap()), x + 18, ty, 11, bx - 12 - (x + 18), 0xfff2e8ff);
        ty = by + box + 22;
        if (!d.maxRank()) {
            text(g, "Опыт до ранга " + rank(d.rank() + 1) + ": " + d.xp() + " / " + d.next(), x + 18, ty, 11, 0xffcdb0f2);
            int bw = w - 36; g.fill(x + 18, ty + 18, x + 18 + bw, ty + 28, 0xff2a2036);
            g.fill(x + 18, ty + 18, x + 18 + (int) (bw * Math.min(1, d.xp() / (double) Math.max(1, d.next()))), ty + 28, color);
        } else text(g, "Высший ранг гильдии · опыт " + d.xp(), x + 18, ty, 11, 0xffcdb0f2);
    }

    private void renderBoard(GuiGraphics g, GuildPayloads.Snapshot d) {
        text(g, "ДОСКА ЗАДАНИЙ", 65, 37, 22, 0xfff7efff); text(g, "Гильдия искателей приключений · Центральная Сора", 65, 67, 11, 0xffb9a6d0);
        button(g, 956, 34, 89, 29, "Закрыть", this::onClose, false);
        card(g, d, 65, 108, 320, 206, false);
        panel(g, 65, 328, 320, 108); text(g, "РАНГИ ГИЛЬДИИ", 80, 338, 10, 0xffcdb0f2);
        for (int r = 0; r < 7; r++) {
            int x = 98 + r * 42; boolean open = r <= GuildRules.RANK_UP_TO.ordinal();
            if (r == d.rank()) { g.fill(x - 17, 354, x + 17, 386, RANK_COLORS[r]); g.fill(x - 15, 356, x + 15, 384, 0xff120c1b); }
            center(g, rank(r), x, 361, 18, r <= d.rank() ? RANK_COLORS[r] : open ? 0xff8d7aa0 : 0xff4a3d58);
            center(g, GuildRules.Rank.of(r).cooldownMinutes + "м", x, 391, 8, 0xff8d7aa0);
        }
        var up = GuildRules.Rank.of(d.rank()).up();
        text(g, up == null ? "Высший ранг" : d.maxRank() ? "Ранг " + rank(d.rank() + 1) + " и выше — в следующих обновлениях" : "Дальше " + up + ": " + d.next() + " опыта" + (d.exam() == 1 ? ", экзамен" : "") + " и взнос " + GuildRules.money(d.fee()), 80, 412, 10, 0xffb9a6d0);
        int cooldown = ClientGuild.cooldown();
        panel(g, 65, 450, 320, 58);
        if (cooldown > 0) { text(g, "Перерыв: ", 80, 458, 12, 0xffcdb0f2); text(g, clock(cooldown), 150, 456, 15, 0xffffd27a); }
        else text(g, d.active() == null ? "Можно брать задание" : "Задание в работе", 80, 458, 12, 0xff7bea8a);
        if (d.maxRank()) text(g, "Высший ранг гильдии", 80, 482, 10, 0xffb9a6d0);
        else if (d.exam() == 1 && d.xp() >= d.next()) button(g, 80, 478, 290, 24, d.active() == null ? "Экзамен на ранг " + rank(d.rank() + 1) : "Экзамен: сначала закончи задание", () -> ClientGuild.request("EXAM", 0, 0), d.active() == null);
        else button(g, 80, 478, 290, 24, d.canRankUp() ? "Повысить ранг · взнос " + GuildRules.money(d.fee()) : "Повышение: нужен опыт", () -> ClientGuild.request("RANK_UP", 0, 0), d.canRankUp());

        int open = Math.min(d.rank(), GuildRules.BOARD_UP_TO.ordinal()), teamFrom = GuildRules.TEAM_FROM.ordinal();
        if (seenRank != d.rank()) { tab = d.rank() >= teamFrom ? d.rank() : open; seenRank = d.rank(); }
        for (int r = 0; r < 7; r++) {
            int x = 435 + r * 87; final int pick = r;
            if (r < teamFrom && r <= open) button(g, x, 105, 80, 30, "Ранг " + rank(r), () -> tab = pick, r == tab);
            else if (r >= teamFrom && r <= d.rank() + 1) button(g, x, 105, 80, 30, rank(r) + " · команда", () -> tab = pick, r == tab);
            else { g.fill(x, 105, x + 80, 135, 0xc0201828); center(g, rank(r), x + 40, 115, 11, 0xff6d5a7d); }
        }
        if (tab >= teamFrom) renderTeam(g, d);
        var offers = tab >= teamFrom ? List.<GuildPayloads.Quest>of() : d.offers().stream().filter(q -> q.rank() == tab).toList();
        for (int i = 0; i < offers.size(); i++) {
            var q = offers.get(i); int y = 150 + i * 102; final int index = i;
            boolean current = d.active() != null && d.active().code().equals(q.code());
            panel(g, 435, y, 610, 92); g.fill(435, y, 439, y + 92, RANK_COLORS[q.rank()]);
            item(g, q.icon(), 455, y + 22, 3);
            text(g, q.name(), 520, y + 12, 14, 0xfff7efff);
            text(g, q.task() + (q.kill() ? "" : "  ×" + q.count()), 520, y + 36, 11, 0xffcdb0f2);
            text(g, "Награда: " + range(q.min(), q.max()) + "   ·   +" + q.xp() + " опыта карточки", 520, y + 60, 11, q.xp() > 0 ? 0xffffd27a : 0xffb9a6d0);
            if (current) center(g, "В работе", 975, y + 36, 12, 0xff7bea8a);
            else if (d.limit() > 0 && d.done() >= d.limit()) center(g, "Лимит дня", 975, y + 36, 12, 0xffffa04a);
            else if (d.active() == null && cooldown == 0) button(g, 915, y + 28, 115, 36, "Взять", () -> ClientGuild.request("TAKE", tab, index), true);
            else center(g, d.active() != null ? "Занят" : clock(cooldown), 975, y + 36, 12, 0xff8d7aa0);
        }
        String message = ClientGuild.message(); if (!message.isEmpty()) center(g, message, 740, 462, 12, 0xffffe2a6);
        else if (tab >= teamFrom && d.team().stream().anyMatch(q -> q.rank() == tab && q.count() == 1)) center(g, "Босса засчитывают тем, кто был рядом (" + GuildServer.BOSS_NEAR + " блоков) · Каждое задание набора — один раз", 740, 462, 11, 0xffb9a6d0);
        else if (tab >= teamFrom) center(g, "Убийства всех участников идут в общий счёт · Каждое задание набора — один раз", 740, 462, 11, 0xffb9a6d0);
        else if (tab < open) center(g, "Задания ниже ранга: монеты полностью, опыта меньше", 740, 462, 11, 0xffb9a6d0);

        if (tab < teamFrom) button(g, 845, 476, 200, 26, "Новые задания · " + GuildRules.money(d.reroll()), () -> ClientGuild.request("REROLL", 0, 0), false);

        panel(g, 65, 527, 980, 63);
        var a = d.active();
        if (a == null && tab >= teamFrom) {
            text(g, "Командные задания без перерыва и не входят в дневной лимит. Платят каждому участнику.", 80, 540, 12, 0xfff2e8ff);
            text(g, "Взнос за контракт: лидер платит " + d.teamEntry() + "% награды за каждого участника, монеты сгорают · Рангом ниже — половина · Набор раз в 2 часа", 80, 563, 10, 0xffb9a6d0);
            return;
        }
        if (a == null) {
            text(g, "Выбери задание на доске. После сдачи или отмены — перерыв " + GuildRules.Rank.of(d.rank()).cooldownMinutes + " мин.", 80, 540, 12, 0xfff2e8ff);
            text(g, (d.limit() > 0 ? "С ранга C — до " + d.limit() + " заданий в день · " : "") + "Мобы из спавнеров, яиц и команд не засчитываются · Предметы берутся при сдаче", 80, 563, 10, 0xffb9a6d0);
            return;
        }
        item(g, a.icon(), 80, 535, 3);
        text(g, a.name() + " · " + (a.max() == 0 ? "открывает ранг " + rank(d.rank() + 1) : GuildRules.money(a.min())), 140, 537, 13, 0xfff7efff);
        int bw = 380, done = Math.min(a.count(), d.progress());
        g.fill(140, 560, 140 + bw, 572, 0xff2a2036); g.fill(140, 560, 140 + bw * done / Math.max(1, a.count()), 572, done >= a.count() ? 0xff7bea8a : 0xffffd27a);
        text(g, (a.kill() ? "Убито " : "В инвентаре ") + done + " / " + a.count(), 532, 561, 11, 0xfff2e8ff);
        button(g, 723, 540, 145, 34, "Сдать", () -> ClientGuild.request("TURN_IN", 0, 0), done >= a.count());
        button(g, 881, 540, 149, 34, "Отказаться", () -> ClientGuild.request("ABANDON", 0, 0), false);
    }

    /** Team quests of the selected rank (the shared two-hour set) and the player's team. */
    private void renderTeam(GuiGraphics g, GuildPayloads.Snapshot d) {
        var quests = d.team().stream().filter(q -> q.rank() == tab).toList(); var ta = d.teamActive();
        for (int i = 0; i < quests.size(); i++) {
            var q = quests.get(i); int y = 150 + i * 102; final int index = i;
            panel(g, 435, y, 610, 92); g.fill(435, y, 439, y + 92, RANK_COLORS[q.rank()]);
            item(g, q.icon(), 455, y + 22, 3);
            text(g, q.name(), 520, y + 12, 14, 0xfff7efff);
            text(g, q.task() + " · команда от " + q.party(), 520, y + 36, 11, 0xffcdb0f2);
            text(g, "Награда: " + range(q.min(), q.max()) + " каждому" + (d.rank() < q.rank() ? " (½ — ранг ниже)" : "") + "   ·   +" + q.xp() + " опыта", 520, y + 60, 11, 0xffffd27a);
            String status = null;
            if (q.done()) status = "Выполнено";
            else if (ta != null && ta.code().equals(q.code())) status = "В работе";
            else if (d.members().isEmpty()) status = "Нужна команда";
            else if (!d.leader()) status = "Берёт лидер";
            else if (ta != null) status = "Команда занята";
            else if (d.members().size() < q.party()) status = "Нужно " + q.party() + "+";
            else if (d.rank() < q.rank()) status = "Лидер — ранг " + rank(q.rank());
            if (status == null) button(g, 915, y + 28, 115, 36, "Взять", () -> ClientGuild.request("TEAM_TAKE", tab, index), true);
            else center(g, status, 975, y + 36, 12, q.done() || status.equals("В работе") ? 0xff7bea8a : 0xff8d7aa0);
        }
        int y = 354; panel(g, 435, y, 610, 92);
        text(g, "КОМАНДА", 450, y + 9, 10, 0xffcdb0f2); text(g, "Новый набор через " + hours(ClientGuild.window()), 870, y + 9, 10, 0xffb9a6d0);
        text(g, d.members().isEmpty() ? "Ты не в команде" : String.join("   ", d.members()), 450, y + 27, 12, d.members().isEmpty() ? 0xff8d7aa0 : 0xfff7efff);
        if (ta != null) {
            int bw = 300, done = Math.min(ta.count(), d.teamProgress());
            text(g, ta.name() + " · ~" + GuildRules.money(ta.min()), 450, y + 50, 11, 0xffffd27a);
            g.fill(450, y + 70, 450 + bw, y + 80, 0xff2a2036); g.fill(450, y + 70, 450 + bw * done / Math.max(1, ta.count()), y + 80, done >= ta.count() ? 0xff7bea8a : 0xffffd27a);
            text(g, "Убито " + done + " / " + ta.count(), 760, y + 70, 11, 0xfff2e8ff);
            if (d.leader()) button(g, 905, y + 52, 125, 30, "Отменить", () -> ClientGuild.request("TEAM_ABANDON", tab, 0), false);
        } else text(g, "/aocparty invite <ник> — позвать · /aocparty leave — выйти · 2–4 игрока из любых кланов", 450, y + 58, 10, 0xffb9a6d0);
    }
    private static void item(GuiGraphics g, String id, int x, int y, float size) {
        var rl = ResourceLocation.tryParse(id); if (rl == null) return;
        g.pose().pushPose(); g.pose().translate(x, y, 0); g.pose().scale(size, size, 1); g.renderItem(new ItemStack(BuiltInRegistries.ITEM.get(rl)), 0, 0); g.pose().popPose();
    }
    private void button(GuiGraphics g, int x, int y, int w, int h, String title, Runnable action, boolean active) { boolean hover = mx >= x && mx < x + w && my >= y && my < y + h; g.fill(x, y, x + w, y + h, active ? 0xef7131b0 : hover ? 0xf044285f : 0xf0281b38); center(g, title, x + w / 2, y + (h - 11) / 2, 11, 0xfff2e8ff); hits.add(new Hit(x, y, w, h, action)); }
    private static void panel(GuiGraphics g, int x, int y, int w, int h) { g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xff684783); g.fill(x, y, x + w, y + h, 0xf1130d20); }
    /** Shrinks the text (down to size 8) so it stays left of the rank badge. */
    private static void fit(GuiGraphics g, String value, double x, double y, int size, int room, int color) {
        int width = Minecraft.getInstance().font.width(Component.literal(value).withStyle(s -> s.withFont(ResourceLocation.fromNamespaceAndPath("animeoncraft", "menu"))));
        while (size > 8 && width * size / 9f > room) size--;
        text(g, value, x, y + (11 - size) / 2.0, size, color);
    }
    private static void text(GuiGraphics g, String value, double x, double y, int size, int color) { g.pose().pushPose(); g.pose().translate(x, y, 0); g.pose().scale(size / 9f, size / 9f, 1); g.drawString(Minecraft.getInstance().font, Component.literal(value).withStyle(s -> s.withFont(ResourceLocation.fromNamespaceAndPath("animeoncraft", "menu"))), 0, 0, color, false); g.pose().popPose(); }
    private static void center(GuiGraphics g, String value, int x, int y, int size, int color) { var font = Minecraft.getInstance().font; var c = Component.literal(value).withStyle(s -> s.withFont(ResourceLocation.fromNamespaceAndPath("animeoncraft", "menu"))); text(g, value, x - font.width(c) * size / 18d, y, size, color); }
}
