package ru.animeoncraft.integration.neoforge.mixin;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.animeoncraft.integration.neoforge.XaeroClanMapOverlay;
import xaero.map.MapProcessor;

@Pseudo
@Mixin(targets="xaero.map.gui.GuiMap",remap=false)
public abstract class XaeroClanMapScreenMixin {
    @Shadow private double cameraX;
    @Shadow private double cameraZ;
    @Shadow private double scale;
    @Shadow private MapProcessor mapProcessor;
    @Inject(method="render",at=@At(value="INVOKE",target="Lxaero/map/element/MapElementRenderHandler;render(Lxaero/map/gui/GuiMap;Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lxaero/map/graphics/renderer/multitexture/MultiTextureRenderTypeRendererProvider;DDIIDDDDDFZLxaero/map/element/HoveredMapElementHolder;Lnet/minecraft/client/Minecraft;F)Lxaero/map/element/HoveredMapElementHolder;"),remap=false)
    private void animeoncraft$territoryBorders(GuiGraphics graphics,int mouseX,int mouseY,float partialTicks,CallbackInfo callback) {
        if(mapProcessor.getMapWorld()!=null&&Level.OVERWORLD.equals(mapProcessor.getMapWorld().getCurrentDimensionId()))XaeroClanMapOverlay.render(graphics,cameraX,cameraZ,scale);
    }
}
