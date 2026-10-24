package ru.animeoncraft.integration.neoforge.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.animeoncraft.integration.neoforge.ClientTerritories;
import xaero.map.highlight.DimensionHighlighterHandler;

@Pseudo
@Mixin(targets="xaero.map.highlight.DimensionHighlighterHandler", remap=false)
public abstract class XaeroWorldRefreshMixin {
    @Unique private long animeoncraft$revision = Long.MIN_VALUE;
    @Inject(method="getRegionHash", at=@At("HEAD"), remap=false)
    private synchronized void animeoncraft$refresh(int x, int z, CallbackInfoReturnable<Integer> callback) {
        long revision = ClientTerritories.revision();
        if (animeoncraft$revision == revision) return;
        ((DimensionHighlighterHandler)(Object)this).clearCachedHashes();
        animeoncraft$revision = revision;
    }
}
