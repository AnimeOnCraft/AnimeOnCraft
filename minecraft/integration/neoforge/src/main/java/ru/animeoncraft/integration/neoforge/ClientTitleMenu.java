package ru.animeoncraft.integration.neoforge;

import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.neoforge.client.event.ScreenEvent;

/** Registered only on the physical client. Vanilla navigation always returns to our title. */
final class ClientTitleMenu {
    private ClientTitleMenu() {}
    static void opening(ScreenEvent.Opening event) {
        if (event.getNewScreen() instanceof TitleScreen) event.setNewScreen(new MainMenuScreen());
    }
}
