package ru.animeoncraft.integration.neoforge.mixin;

import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.animeoncraft.integration.neoforge.ClientChat;

/** Suppress only the vanilla message history; input and command suggestions still render normally. */
@Mixin(ChatComponent.class)
abstract class VanillaChatMixin {
    @Inject(method="render",at=@At("HEAD"),cancellable=true)
    private void aoc$tabbedHistory(CallbackInfo ci) {
        if(ClientChat.replacesVanillaHistory())ci.cancel();
    }
}
