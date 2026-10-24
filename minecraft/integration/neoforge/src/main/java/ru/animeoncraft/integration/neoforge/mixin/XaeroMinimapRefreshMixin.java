package ru.animeoncraft.integration.neoforge.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.animeoncraft.integration.neoforge.ClientTerritories;
import xaero.common.minimap.highlight.DimensionHighlighterHandler;

@Pseudo
@Mixin(targets="xaero.common.minimap.highlight.DimensionHighlighterHandler", remap=false)
public abstract class XaeroMinimapRefreshMixin {
    @Unique private long animeoncraft$revision = Long.MIN_VALUE;
    @Inject(method="getVersion", at=@At("HEAD"), remap=false)
    private void animeoncraft$refresh(CallbackInfoReturnable<Integer> callback) {
        long revision = ClientTerritories.revision();
        if (animeoncraft$revision == revision) return;
        ((DimensionHighlighterHandler)(Object)this).requestRefresh();
        animeoncraft$revision = revision;
    }
}
