package ru.animeoncraft.integration.neoforge.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.animeoncraft.integration.neoforge.XaeroClanHighlighter;
import xaero.map.highlight.HighlighterRegistry;

@Pseudo
@Mixin(targets="xaero.map.highlight.HighlighterRegistry", remap=false)
public abstract class XaeroWorldRegistryMixin {
    @Inject(method="<init>", at=@At("RETURN"), remap=false)
    private void animeoncraft$clans(CallbackInfo callback) {
        ((HighlighterRegistry)(Object)this).register(new XaeroClanHighlighter());
    }
}
