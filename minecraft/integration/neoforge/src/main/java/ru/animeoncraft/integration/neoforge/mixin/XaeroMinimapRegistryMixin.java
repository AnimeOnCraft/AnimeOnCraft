package ru.animeoncraft.integration.neoforge.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.animeoncraft.integration.neoforge.XaeroClanHighlighter;
import xaero.common.minimap.highlight.HighlighterRegistry;
import xaero.common.mods.WorldMapHighlighter;

@Pseudo
@Mixin(targets="xaero.common.minimap.highlight.HighlighterRegistry", remap=false)
public abstract class XaeroMinimapRegistryMixin {
    @Inject(method="<init>", at=@At("RETURN"), remap=false)
    private void animeoncraft$clans(CallbackInfo callback) {
        if (!net.neoforged.fml.ModList.get().isLoaded("xaeroworldmap")) return;
        ((HighlighterRegistry)(Object)this).register(new WorldMapHighlighter(new XaeroClanHighlighter(true)));
    }
}
