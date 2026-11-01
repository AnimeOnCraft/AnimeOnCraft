package ru.animeoncraft.integration.neoforge;

import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** Rift Gate: dungeons on the left, difficulties, loot and rules on the right, the team and the start buttons below. */
final class DungeonScreen extends Screen {
    private record Hit(int x, int y, int w, int h, Runnable action) { boolean contains(double a, double b) { return a >= x && a < x + w && b >= y && b < y + h; } }
    private static final int[] DIFFICULTY_COLORS = {0xff7bea8a, 0xff5aa8ff, 0xffb57bff, 0xffff5a5a, 0xffffd75a, 0xffffffff};
    private final List<Hit> hits = new ArrayList<>();
    private int dungeon, difficulty; private boolean picked;
    private double scale, left, top, mx, my;
    DungeonScreen() { super(Component.literal("Врата Разлома")); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void tick() { if (minecraft.player == null || minecraft.getConnection() == null) { onClose(); return; } ClientDungeon.request("GET", 0, 0, 0); ClientDungeon.tick(); }
    @Override public boolean mouseClicked(double x, double y, int button) {
        if (button != 0) return false; double a = (x - left) / scale, b = (y - top) / scale;
        for (int i = hits.size() - 1; i >= 0; i--) if (hits.get(i).contains(a, b)) { hits.get(i).action().run(); return true; }
        return false;
    }
    private static String rank(int r) { return r < 0 ? "—" : GuildRules.Rank.of(r).name(); }
    private static String times(int tenths) { return "×" + (tenths % 10 == 0 ? String.valueOf(tenths / 10) : tenths / 10 + "." + tenths % 10); }

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        scale = Math.min(width / 1100d, height / 620d); left = (width - 1100 * scale) / 2; top = (height - 620 * scale) / 2; mx = (mouseX - left) / scale; my = (mouseY - top) / scale; hits.clear();
        renderBlurredBackground(delta); g.fill(0, 0, width, height, 0xa0080016);
        g.pose().pushPose(); g.pose().translate(left, top, 0); g.pose().scale((float) scale, (float) scale, 1);
        var d = ClientDungeon.data();
        if (d == null || d.dungeons().isEmpty()) { center(g, "Врата пробуждаются…", 550, 300, 16, 0xfff7efff); button(g, 956, 34, 89, 29, "Закрыть", this::onClose, false); }
        else draw(g, d);
        g.pose().popPose();
    }
    private void draw(GuiGraphics g, DungeonPayloads.Snapshot d) {
        if (!picked && d.lobbyDungeon() >= 0) { dungeon = d.lobbyDungeon(); difficulty = d.lobbyDifficulty(); }
        if (!picked) { picked = true; while (dungeon < d.dungeons().size() - 1 && !d.dungeons().get(dungeon).open()) dungeon++; }
        dungeon = Math.min(dungeon, d.dungeons().size() - 1); difficulty = Math.min(difficulty, d.difficulties().size() - 1);
        boolean inLobby = d.lobbyDungeon() >= 0; if (inLobby) { dungeon = d.lobbyDungeon(); difficulty = d.lobbyDifficulty(); }
        text(g, "ВРАТА РАЗЛОМА", 65, 37, 22, 0xfff7efff);
        text(g, "Ранг гильдии: " + rank(d.rank()) + "  ·  наград сегодня: " + Math.min(d.chests(), d.limit()) + " из " + d.limit() + "  ·  в инвентаре " + GuildRules.money(d.coins()), 65, 67, 11, 0xffcdb0f2);
        button(g, 956, 34, 89, 29, "Закрыть", this::onClose, false);
        // Dungeons.
        for (int i = 0; i < d.dungeons().size(); i++) {
            var x = d.dungeons().get(i); int y = 105 + i * 92; final int pick = i;
            boolean selected = i == dungeon, hover = mx >= 65 && mx < 385 && my >= y && my < y + 84;
            g.fill(64, y - 1, 386, y + 85, selected ? 0xffb57bff : 0xff4a3560); g.fill(65, y, 385, y + 84, selected ? 0xf6301f48 : hover ? 0xf2231732 : 0xf1130d20);
            text(g, x.name(), 80, y + 12, 15, x.open() ? 0xfff7efff : 0xff8d7aa0);
            text(g, x.theme() + " · " + (x.rooms() + 1) + "–" + (x.rooms() + 2) + " комнат" + (x.open() ? "" : " · скоро"), 80, y + 36, 11, x.open() ? 0xffcdb0f2 : 0xff6d5a7d);
            text(g, x.description(), 80, y + 56, 10, 0xffb9a6d0);
            for (int f = 0; f < d.difficulties().size(); f++) if ((x.cleared() & (1 << f)) != 0) g.fill(360 - f * 12, y + 12, 368 - f * 12, y + 20, DIFFICULTY_COLORS[f]);
            if (!inLobby) hits.add(new Hit(65, y, 320, 84, () -> dungeon = pick));
        }
        var x = d.dungeons().get(dungeon);
        // Difficulties.
        for (int i = 0; i < d.difficulties().size(); i++) {
            var f = d.difficulties().get(i); int bx = 410 + i * 160; final int pick = i; boolean selected = i == difficulty;
            g.fill(bx - 1, 104, bx + 151, 152, selected ? DIFFICULTY_COLORS[i] : 0xff4a3560); g.fill(bx, 105, bx + 150, 151, selected ? 0xf6301f48 : 0xf1130d20);
            center(g, f.name(), bx + 75, 113, 13, f.access() == 2 ? 0xff8d7aa0 : DIFFICULTY_COLORS[i]);
            center(g, f.access() == 2 ? "с ранга " + rank(f.minRank()) : f.fee() == 0 ? "вход бесплатно" : "вход " + GuildRules.money(f.fee()), bx + 75, 133, 10, 0xffcdb0f2);
            if (!inLobby) hits.add(new Hit(bx, 105, 150, 46, () -> difficulty = pick));
        }
        var f = d.difficulties().get(difficulty); var tier = difficulty < x.tiers().size() ? x.tiers().get(difficulty) : null;
        panel(g, 410, 166, 630, 160);
        text(g, "ВОЗМОЖНЫЙ ЛУТ · " + f.name().toUpperCase(Locale.ROOT), 425, 176, 10, 0xffcdb0f2);
        if (tier != null) for (int i = 0; i < tier.icons().size(); i++) { int ix = 428 + i * 62; g.fill(ix - 4, 196, ix + 44, 244, 0xff21172f); item(g, tier.icons().get(i), ix, 200, 2.5f); }
        String boss = tier == null ? "?" : tier.boss();
        text(g, "Мобы " + times(f.health()) + " здоровья, " + times(f.damage()) + " урона  ·  " + (f.lives() == 0 ? "без жизней: умер — наблюдаешь" : f.lives() + (f.teamLives() ? " на команду" : " на игрока") + " " + lives(f.lives())) + "  ·  босс: " + boss, 425, 252, 11, 0xfff2e8ff);
        text(g, "Смерть забирает " + f.loss() + "% стаков инвентаря (броня, Curios, кошелёк и рюкзаки целы)  ·  команда от " + f.minPlayers()
            + (f.timeLimit() > 0 ? "  ·  на забег " + (f.timeLimit() % 60 == 0 ? f.timeLimit() / 60 + " мин" : DungeonRun.clock(f.timeLimit())) : ""), 425, 271, 10, 0xffffa04a);
        boolean firstClear = (x.cleared() & (1 << difficulty)) == 0;
        text(g, (firstClear ? "Первая зачистка: +" + f.crystals() + " ◆ Кристаллов Разлома" : "Уже пройдено · кристаллы за первую зачистку получены") + "  ·  монеты — только через задания гильдии", 425, 288, 10, firstClear ? 0xfff07aa6 : 0xffb9a6d0);
        if (f.affixes()) text(g, d.affix().isEmpty() ? "Аффикс недели: нет" : "Аффикс недели: " + d.affix() + " (меняется по понедельникам)", 425, 305, 10, 0xffff7a7a);
        // Team.
        panel(g, 410, 340, 630, 120);
        if (inLobby) {
            text(g, "КОМАНДА · " + d.members().size() + "/4", 425, 350, 10, 0xffcdb0f2);
            for (int i = 0; i < 4; i++) {
                int sx = 425 + i * 152; g.fill(sx, 370, sx + 142, 412, 0xff21172f);
                if (i < d.members().size()) { var m = d.members().get(i); text(g, (i == 0 ? "★ " : "") + m.name(), sx + 8, 378, 12, 0xfff7efff);
                    text(g, rank(m.rank()) + " · " + (m.ready() ? "готов" : "ждём"), sx + 8, 396, 10, m.ready() ? 0xff7bea8a : 0xffb9a6d0); }
                else center(g, "свободно", sx + 71, 386, 10, 0xff6d5a7d);
            }
            text(g, "Позови игроков: им пришла кнопка «Присоединиться». Команда /aocparty входит сразу.", 425, 425, 10, 0xffb9a6d0);
        } else {
            text(g, "ОТКРЫТЫЕ КОМАНДЫ У ВРАТ", 425, 350, 10, 0xffcdb0f2);
            if (d.lobbies().isEmpty()) text(g, "Пока никого. Набери свою команду или зайди один.", 425, 376, 12, 0xff8d7aa0);
            for (int i = 0; i < Math.min(3, d.lobbies().size()); i++) {
                var l = d.lobbies().get(i); int ly = 368 + i * 30; final int index = l.index();
                var dn = l.dungeon() < d.dungeons().size() ? d.dungeons().get(l.dungeon()).name() : "?"; var fn = l.difficulty() < d.difficulties().size() ? d.difficulties().get(l.difficulty()).name() : "?";
                text(g, l.leader() + " · " + dn + " · " + fn + " · " + l.members() + "/" + l.max(), 425, ly + 6, 12, 0xfff7efff);
                button(g, 890, ly, 135, 26, "Присоединиться", () -> ClientDungeon.request("JOIN", 0, 0, index), l.members() < l.max());
            }
        }
        // Buttons.
        boolean open = x.open() && f.access() != 2;
        if (!inLobby) {
            button(g, 410, 478, 200, 40, "Войти одному", () -> ClientDungeon.request("SOLO", dungeon, difficulty, 0), open && f.minPlayers() <= 1);
            button(g, 625, 478, 200, 40, "Набрать команду", () -> ClientDungeon.request("CREATE", dungeon, difficulty, 0), x.open());
        } else {
            boolean meReady = d.members().stream().anyMatch(m -> m.name().equals(minecraft.player.getGameProfile().getName()) && m.ready());
            long ready = d.members().stream().filter(DungeonPayloads.Member::ready).count();
            button(g, 410, 478, 160, 40, meReady ? "Не готов" : "Готов", () -> ClientDungeon.request("READY", dungeon, difficulty, 0), !meReady);
            if (d.leader()) button(g, 585, 478, 200, 40, "Старт (" + ready + "/" + d.members().size() + ")", () -> ClientDungeon.request("START", dungeon, difficulty, 0), ready == d.members().size() && d.members().size() >= f.minPlayers());
            button(g, 800, 478, 160, 40, "Выйти из команды", () -> ClientDungeon.request("LEAVE", dungeon, difficulty, 0), false);
        }
        String message = ClientDungeon.message();
        if (!message.isEmpty()) center(g, message, 725, 540, 12, 0xffffe2a6);
        else if (!x.open()) center(g, "«" + x.name() + "» откроется в следующих обновлениях", 725, 540, 11, 0xffb9a6d0);
        else if (f.access() == 2) center(g, "Нужен ранг гильдии " + rank(f.minRank()) + ". Рангом ниже — только в команде, половина лута.", 725, 540, 11, 0xffffa04a);
        else if (f.access() == 1) center(g, "Ранг ниже нужного: зайдёшь только в команду с рангом " + rank(f.minRank()) + " и получишь половину лута.", 725, 540, 11, 0xffffa04a);
        else center(g, "Лимит: " + d.limit() + " сундука в день на все подземелья. Дальше можно ходить, но без сундука.", 725, 540, 11, 0xffb9a6d0);
        text(g, "Внутри: блоки не ставятся и не ломаются, жемчуг, хорус, вейстоуны и /spawn не работают. Выход — /aocdungeon leave.", 65, 585, 10, 0xff8d7aa0);
    }
    private static String lives(int n) { return n == 1 ? "жизнь" : n < 5 ? "жизни" : "жизней"; }
    private static void item(GuiGraphics g, String id, int x, int y, float size) {
        var rl = ResourceLocation.tryParse(id); if (rl == null) return;
        g.pose().pushPose(); g.pose().translate(x, y, 0); g.pose().scale(size, size, 1); g.renderItem(new ItemStack(BuiltInRegistries.ITEM.get(rl)), 0, 0); g.pose().popPose();
    }
    private void button(GuiGraphics g, int x, int y, int w, int h, String title, Runnable action, boolean active) { boolean hover = mx >= x && mx < x + w && my >= y && my < y + h; g.fill(x, y, x + w, y + h, active ? 0xef7131b0 : hover ? 0xf044285f : 0xf0281b38); center(g, title, x + w / 2, y + (h - 11) / 2, 11, 0xfff2e8ff); hits.add(new Hit(x, y, w, h, action)); }
    private static void panel(GuiGraphics g, int x, int y, int w, int h) { g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xff684783); g.fill(x, y, x + w, y + h, 0xf1130d20); }
    private static void text(GuiGraphics g, String value, double x, double y, int size, int color) { g.pose().pushPose(); g.pose().translate(x, y, 0); g.pose().scale(size / 9f, size / 9f, 1); g.drawString(Minecraft.getInstance().font, Component.literal(value).withStyle(s -> s.withFont(ResourceLocation.fromNamespaceAndPath("animeoncraft", "menu"))), 0, 0, color, false); g.pose().popPose(); }
    private static void center(GuiGraphics g, String value, int x, int y, int size, int color) { var font = Minecraft.getInstance().font; var c = Component.literal(value).withStyle(s -> s.withFont(ResourceLocation.fromNamespaceAndPath("animeoncraft", "menu"))); text(g, value, x - font.width(c) * size / 18d, y, size, color); }
}
