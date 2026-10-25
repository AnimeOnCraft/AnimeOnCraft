package ru.animeoncraft.integration.neoforge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.gui.ModListScreen;
import net.neoforged.neoforge.client.gui.ModMismatchDisconnectedScreen;

/** Only in the disposable probe JAR; never shipped to players. */
@Mod("aoctitlemenuprobe")
public final class TitleMenuClientProbe {
    private int ticks, phase, wait, decoded;
    private MainMenuScreen menu;
    public TitleMenuClientProbe() {
        if (!Minecraft.getInstance().gameDirectory.getAbsolutePath().contains("title-menu-client-"))
            throw new IllegalStateException("Title probe requires isolated game directory");
        NeoForge.EVENT_BUS.addListener(this::tick);
    }
    private static void check(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }
    private void click(String id) {
        var b = menu.button(id);
        check(b != null && b.active, "Active button " + id);
        check(menu.mouseClicked(b.getX() + b.getWidth() / 2.0, b.getY() + b.getHeight() / 2.0, 0), "Click " + id);
    }
    private void shot(String name) { var mc=Minecraft.getInstance(); Screenshot.grab(mc.gameDirectory,name+".png",mc.getMainRenderTarget(),ignored->{}); }
    private void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance(); ticks++;
        if (phase == 99) return;
        if (ticks > 2400) throw new IllegalStateException("Title probe timeout phase=" + phase);
        if (mc.getOverlay() != null || ticks < 80) return;
        if (++wait < 25) return;
        wait = 0;
        try {
            if (phase == 0) { mc.setScreen(new TitleScreen()); check(mc.screen instanceof MainMenuScreen, "Title replaced"); menu=(MainMenuScreen)mc.screen; phase++; }
            else if (phase == 1) {
                if (menu.video().completedLoops() < 1) return;
                check(menu.video().textures() == 2 && menu.video().decodedFrames() > 5, "JPEG animation renders on GPU");
                check(menu.video().frameWidth() == 1920 && menu.video().frameHeight() == 1080, "Real Full HD frames loaded");
                check(menu.video().smoothFiltering(), "Linear sampling survives animated texture uploads");
                check(menu.button("servers") == null, "No server browser button");
                decoded = menu.video().decodedFrames(); shot("01-main-menu");
                if (Boolean.getBoolean("animeoncraft.titleMenuReview")) { phase=99; return; }
                check(!menu.shouldCloseOnEsc(), "Escape cannot close title");
                click("settings"); check(mc.screen instanceof OptionsScreen, "Options opens"); check(menu.video().textures()==0, "Video released"); phase++;
            } else if (phase == 2) { shot("02-settings"); mc.screen.onClose(); check(mc.screen==menu,"Settings returns"); phase++; }
            else if (phase == 3) { check(menu.video().decodedFrames()>5,"Video restarts"); click("mods"); check(mc.screen instanceof ModListScreen,"Mods opens"); phase++; }
            else if (phase == 4) { shot("02b-mods"); mc.screen.onClose(); check(mc.screen==menu,"Mods returns"); phase++; }
            else if (phase == 5) { click("worlds"); check(mc.screen!=menu,"Worlds navigation starts"); phase++; }
            else if (phase == 6) {
                // Vanilla opens creation immediately when an installation has no saves.
                check(mc.screen instanceof SelectWorldScreen || mc.screen instanceof CreateWorldScreen,"World list or new world opens: "+mc.screen);
                shot("02c-worlds"); mc.screen.onClose();
                if (mc.screen instanceof SelectWorldScreen) mc.screen.onClose();
                check(mc.screen instanceof MainMenuScreen,"Worlds returns to branded title: "+mc.screen);
                menu=(MainMenuScreen)mc.screen; phase=9;
            }
            else if (phase == 9) { boolean before=menu.musicEnabled(); click("music"); check(menu.musicEnabled()!=before,"Music toggled"); mc.setScreen(new TitleScreen()); menu=(MainMenuScreen)mc.screen; check(menu.musicEnabled()!=before,"Music persisted"); click("music"); mc.options.guiScale().set(3); mc.resizeDisplay(); phase++; }
            else if (phase == 10) { check(menu.button("exit").getY()+menu.button("exit").getHeight()<menu.height-10,"Small GUI fits"); shot("03-small-gui"); mc.options.guiScale().set(2); mc.resizeDisplay(); phase++; }
            else if (phase == 11) { shot("04-animation"); check(menu.video().textures()==2,"Only two GPU textures after reopen"); click("play"); check(mc.screen!=menu,"Play attempts pinned loopback connection"); phase++; }
            else if (phase == 12) {
                if (mc.screen instanceof ConnectScreen) return;
                check(mc.screen instanceof DisconnectedScreen || mc.screen instanceof ModMismatchDisconnectedScreen,
                        "Loopback connection reaches error screen: "+mc.screen);
                var errorScreen=mc.screen;
                var back=errorScreen.children().stream().filter(child -> child instanceof net.minecraft.client.gui.components.Button b
                        && b.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t
                        && t.getKey().equals("gui.toMenu")).map(child -> (net.minecraft.client.gui.components.Button)child).findFirst().orElseThrow();
                back.onPress(); check(mc.screen instanceof MainMenuScreen,"Connection error returns to title");
                menu=(MainMenuScreen)mc.screen; phase++;
            }
            else if (phase == 13) { check(mc.screen==menu,"Returned title stays active"); check(menu.video().decodedFrames()>5,"Animation after failed connect"); check(menu.video().smoothFiltering(),"Linear sampling after reconnect, textures="+menu.video().textures()); shot("05-returned-menu"); System.out.println("AOC_TITLE_MENU_PASS animation=true full_hd=true linear_filter=true texture_limit=2 navigation=true music_preferences=true gui_scale=true pinned_play=true"); phase++; }
            else if (phase == 14) { click("exit"); }
        } catch (Exception failure) { System.out.println("AOC_TITLE_MENU_FAILED phase="+phase+" reason="+failure.getMessage()); throw new IllegalStateException(failure); }
    }
}
