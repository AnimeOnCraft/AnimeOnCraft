package ru.animeoncraft.integration.neoforge;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

final class ClientEmoteMenu {
    static final KeyMapping OPEN=new KeyMapping("key.animeoncraft.emotes",KeyConflictContext.IN_GAME,InputConstants.Type.KEYSYM,GLFW.GLFW_KEY_J,"key.categories.animeoncraft");
    private ClientEmoteMenu(){}
    static void register(RegisterKeyMappingsEvent e){e.register(OPEN);}
    static void tick(){var mc=Minecraft.getInstance();while(OPEN.consumeClick())if(mc.screen==null&&mc.player!=null&&ClientEmotes.supported()){ClientEmotes.read();mc.setScreen(new EmoteMenuScreen());}}
}
