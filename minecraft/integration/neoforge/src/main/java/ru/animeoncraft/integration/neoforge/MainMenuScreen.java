package ru.animeoncraft.integration.neoforge;

import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.Music;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.client.gui.ModListScreen;

/** The supplied movie already contains the logo. Buttons use native focus, click sounds and narration. */
final class MainMenuScreen extends Screen {
    private static final ResourceLocation FONT = ResourceLocation.fromNamespaceAndPath("animeoncraft", "menu");
    private static final Music THEME = music("title_theme"), SILENCE = music("title_silence");
    private final TitleMenuVideo video = new TitleMenuVideo();
    private final Map<String, MenuButton> buttons = new LinkedHashMap<>();
    private boolean musicEnabled = true;
    private TitleAnimationTimeline.Layout layout;
    private record Preferences(boolean music) {}

    MainMenuScreen() { super(Component.literal("AnimeOnCraft")); }
    private static Music music(String name) {
        return new Music(Holder.direct(SoundEvent.createVariableRangeEvent(
                ResourceLocation.fromNamespaceAndPath("animeoncraft", name))), 0, 0, true);
    }
    private Path preferences() { return minecraft.gameDirectory.toPath().resolve("config/animeoncraft-main-menu.json"); }
    private static Component text(String value) { return Component.literal(value).withStyle(s -> s.withFont(FONT)); }
    @Override protected void init() {
        buttons.clear();
        try {
            if (Files.isRegularFile(preferences())) {
                var prefs = new Gson().fromJson(Files.readString(preferences()), Preferences.class);
                if (prefs != null) musicEnabled = prefs.music();
            }
        } catch (Exception ignored) { /* A corrupt cosmetic preference cannot prevent entering the game. */ }
        layout = TitleAnimationTimeline.layout(width, height);
        int x = layout.x(), y = layout.y(), w = layout.width(), gap = layout.gap(), half = layout.half();
        add("play", x, y, w, layout.play(), "Играть", this::play, true);
        y += layout.play() + gap;
        add("worlds", x, y, w, layout.row(), "Одиночная игра", () -> minecraft.setScreen(new SelectWorldScreen(this)), false);
        y += layout.row() + gap;
        add("settings", x, y, half, layout.row(), "Настройки", () -> minecraft.setScreen(new OptionsScreen(this, minecraft.options)), false);
        add("mods", x + half + gap, y, w - half - gap, layout.row(), "Моды", () -> minecraft.setScreen(new ModListScreen(this)), false);
        y += layout.row() + gap;
        add("exit", x, y, w, layout.row(), "Выйти из игры", () -> minecraft.stop(), false);
        add("music", width - 110, 10, 100, 20, musicLabel(), this::toggleMusic, false);
    }
    private String musicLabel() { return "Музыка: " + (musicEnabled ? "вкл." : "выкл."); }
    private void toggleMusic() {
        musicEnabled = !musicEnabled;
        buttons.get("music").setMessage(text(musicLabel()));
        minecraft.getMusicManager().stopPlaying();
        try {
            Files.createDirectories(preferences().getParent());
            Files.writeString(preferences(), new Gson().toJson(new Preferences(musicEnabled)));
        } catch (java.io.IOException ignored) { /* Session toggle still works on read-only installations. */ }
    }
    private void add(String id, int x, int y, int w, int h, String label, Runnable action, boolean primary) {
        var button = new MenuButton(id, x, y, w, h, text(label), ignored -> action.run(), primary);
        buttons.put(id, addRenderableWidget(button));
    }
    private void play() {
        String host = System.getenv("AOC_GAME_TARGET_HOST"), port = System.getenv("AOC_GAME_TARGET_PORT");
        if (host == null || port == null) { minecraft.setScreen(new JoinMultiplayerScreen(this)); return; }
        try {
            int number = Integer.parseInt(port);
            if (number < 1 || number > 65535 || host.isBlank() || host.chars().anyMatch(Character::isWhitespace)) throw new IllegalArgumentException();
            String address = (host.contains(":") ? "[" + host + "]" : host) + ":" + number;
            ConnectScreen.startConnecting(this, minecraft, ServerAddress.parseString(address),
                    new ServerData("AnimeOnCraft", address, ServerData.Type.OTHER), false, null);
        } catch (IllegalArgumentException invalid) { minecraft.setScreen(new JoinMultiplayerScreen(this)); }
    }
    @Override public boolean shouldCloseOnEsc() { return false; }
    @Override public boolean isPauseScreen() { return false; }
    @Override public Music getBackgroundMusic() { return musicEnabled ? THEME : SILENCE; }
    @Override public void removed() { video.close(); minecraft.getMusicManager().stopPlaying(); }
    @Override public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) { video.render(g, width, height); }
    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        renderBackground(g, mouseX, mouseY, delta);
        g.fillGradient(0, height / 3, width, height, 0x00090313, 0xa010071c);
        g.fill(layout.x() - 10, layout.y() - 10, layout.x() + layout.width() + 10, layout.bottom() + 10, 0x500a0713);
        super.render(g, mouseX, mouseY, delta);
        if (width >= 420) g.drawString(font, text(minecraft.getUser().getName()), 12, height - 17, 0xffd2c2e4, false);
        var version = text("Minecraft 1.21.1 · NeoForge");
        g.drawString(font, version, width - font.width(version) - 12, height - 17, 0xffbdaccf, false);
    }
    MenuButton button(String id) { return buttons.get(id); }
    TitleMenuVideo video() { return video; }
    boolean musicEnabled() { return musicEnabled; }

    static final class MenuButton extends Button {
        final String id;
        private final boolean primary;
        MenuButton(String id, int x, int y, int w, int h, Component label, OnPress action, boolean primary) {
            super(x, y, w, h, label, action, DEFAULT_NARRATION); this.id = id; this.primary = primary;
        }
        @Override protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float delta) {
            boolean hover = isHoveredOrFocused();
            int x = getX(), y = getY(), w = getWidth(), h = getHeight();
            g.fill(x + 2, y + 3, x + w + 2, y + h + 3, 0x45000000);
            g.fillGradient(x, y, x + w, y + h, primary ? (hover ? 0xffb06aff : 0xff9a49ef) : (hover ? 0xeb493062 : 0xcf21152f),
                    primary ? (hover ? 0xff8136ce : 0xff6f27bd) : (hover ? 0xeb30203f : 0xe3140d21));
            int border = hover ? 0xffd6abff : primary ? 0xffb979f2 : 0xff68427e;
            g.fill(x, y, x + w, y + 1, border); g.fill(x, y + h - 1, x + w, y + h, border);
            g.fill(x, y, x + 1, y + h, border); g.fill(x + w - 1, y, x + w, y + h, border);
            g.drawCenteredString(Minecraft.getInstance().font, getMessage(), x + w / 2, y + (h - 9) / 2, 0xfff8efff);
            if (primary) {
                int center = x + w / 2 - Minecraft.getInstance().font.width(getMessage()) / 2 - 16;
                for (int i = 0; i < 6; i++) g.fill(center + i, y + h / 2 - 5 + i, center + i + 1, y + h / 2 + 6 - i, 0xfff4e6ff);
            }
        }
    }
}
