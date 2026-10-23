package ru.animeoncraft.fixtures.adventure;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Separate test-only JAR. Not included in launcher/bootstrap or production mod. */
@Mod(value="aocclientprobe",dist=net.neoforged.api.distmarker.Dist.CLIENT)
public final class AdventureClientProbe {
    private int ticks;
    private boolean requestedBook;
    private boolean mapOpened;
    private int mapFrames;
    public AdventureClientProbe(){NeoForge.EVENT_BUS.addListener(this::tick);}
    private void tick(ClientTickEvent.Post event){
        var mc=Minecraft.getInstance();
        if(!Boolean.getBoolean("animeoncraft.adventureClientProbe")||!mc.gameDirectory.getAbsolutePath().contains("adventure-client-"))return;
        if(mapOpened){
            if(++mapFrames==80){
                net.minecraft.client.Screenshot.grab(mc.gameDirectory,"xaero-clan-map.png",mc.getMainRenderTarget(),message->{});
                System.out.println("AOC_XAERO_RENDER_PASS world_map_open=true screenshot=true");
                try {
                    verifyLighting(mc);
                    var options=Class.forName("net.caffeinemc.mods.sodium.client.gui.SodiumOptionsGUI").getMethod("createScreen",net.minecraft.client.gui.screens.Screen.class).invoke(null,new Object[]{null});
                    mc.setScreen((net.minecraft.client.gui.screens.Screen)options);
                } catch(ReflectiveOperationException failure){throw new IllegalStateException("Graphics addons probe failed",failure);}
            }
            if(mapFrames==120){
                net.minecraft.client.Screenshot.grab(mc.gameDirectory,"graphics-options.png",mc.getMainRenderTarget(),message->{});
                System.out.println("AOC_GRAPHICS_OPTIONS_PROBE_PASS sodium_options_open=true extras_loaded=true");
                mc.stop();
            }
            return;
        }
        if(mc.player==null&&mc.screen!=null&&++ticks%30==0){
            for(var child:mc.screen.children())if(child instanceof Button button&&button.active&&java.util.Set.of("Proceed","I know what I'm doing!","Yes").contains(button.getMessage().getString())){button.onPress();return;}
        }
        if(mc.player==null||++ticks%60!=0)return;
        try {
            if(net.neoforged.fml.ModList.get().isLoaded("emi"))throw new IllegalStateException("Retired EMI is still loaded");
            var optional=(java.util.Optional<?>)Class.forName("mezz.jei.common.Internal").getMethod("getOptionalJeiRuntime").invoke(null);
            if(optional.isEmpty())return;
            var runtime=optional.get();
            var manager=Class.forName("mezz.jei.api.runtime.IJeiRuntime").getMethod("getRecipeManager").invoke(runtime);
            var lookup=Class.forName("mezz.jei.api.recipe.IRecipeManager").getMethod("createRecipeCategoryLookup").invoke(manager);
            var categories=(java.util.stream.Stream<?>)Class.forName("mezz.jei.api.recipe.IRecipeCategoriesLookup").getMethod("get").invoke(lookup);
            int found=0,create=0,magic=0;
            for(var category:categories.toList()){
                var type=Class.forName("mezz.jei.api.recipe.category.IRecipeCategory").getMethod("getRecipeType").invoke(category);
                var id=Class.forName("mezz.jei.api.recipe.RecipeType").getMethod("getUid").invoke(type).toString();
                if(id.startsWith("jeresources:"))found++;
                if(id.startsWith("create:"))create++;
                if(id.startsWith("irons_spellbooks:"))magic++;
            }
            if(found>0){
                if(!requestedBook){
                    if(create==0||magic==0)throw new IllegalStateException("Create or magic JEI categories missing");
                    System.out.println("AOC_JER_JEI_PROBE_PASS categories="+found+" create="+create+" magic="+magic+" world=true emi=false");
                    requestedBook=true;
                    mc.player.connection.sendCommand("ftbquests open_book");
                }
                Object gui=mc.screen;
                if(gui!=null&&gui.getClass().getSimpleName().equals("ScreenWrapper"))gui=gui.getClass().getMethod("getGui").invoke(gui);
                if(gui!=null&&gui.getClass().getSimpleName().equals("QuestScreen")){
                    var file=Class.forName("dev.ftb.mods.ftbquests.client.ClientQuestFile").getField("INSTANCE").get(null);
                    var chapters=(java.util.Collection<?>)file.getClass().getMethod("getAllChapters").invoke(file);
                    int quests=0;for(var chapter:chapters)quests+=((java.util.Collection<?>)chapter.getClass().getMethod("getQuests").invoke(chapter)).size();
                    if(chapters.size()!=5||quests!=22)throw new IllegalStateException("Incomplete client quest book");
                    System.out.println("AOC_PERSONAL_QUEST_CLIENT_PASS chapters=5 quests=22 book_open=true");
                    net.minecraft.client.Screenshot.grab(mc.gameDirectory,"adventure-world.png",mc.getMainRenderTarget(),message->{});
                    if(net.neoforged.fml.ModList.get().isLoaded("xaeroworldmap")){
                        verifyMaps(mc);
                    }else mc.stop();
                }
            }
        } catch(ReflectiveOperationException failure){throw new IllegalStateException("JER JEI integration probe failed",failure);}
    }

    private void verifyLighting(Minecraft mc) throws ReflectiveOperationException {
        var lights=Class.forName("toni.sodiumdynamiclights.SodiumDynamicLights");
        int torch=(Integer)lights.getMethod("getLuminanceFromItemStack",net.minecraft.world.item.ItemStack.class,boolean.class).invoke(null,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.TORCH),false);
        if(torch!=14)throw new IllegalStateException("Torch luminance incorrect: "+torch);
        mc.player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.TORCH));
        var source=Class.forName("toni.sodiumdynamiclights.DynamicLightSource");
        source.getMethod("sdl$dynamicLightTick").invoke(mc.player);
        int luminance=(Integer)source.getMethod("sdl$getLuminance").invoke(mc.player);
        if(luminance<=0)throw new IllegalStateException("Held torch did not light the player");
        var instance=lights.getMethod("get").invoke(null);
        lights.getMethod("addLightSource",source).invoke(instance,mc.player);
        double level=(Double)lights.getMethod("getDynamicLightLevel",net.minecraft.core.BlockPos.class).invoke(instance,mc.player.blockPosition());
        if(level<=0)throw new IllegalStateException("Dynamic light not propagated");
        System.out.println("AOC_DYNAMIC_LIGHT_PROBE_PASS held_torch=true source_level="+luminance+" sampled_light="+level);
    }

    @SuppressWarnings("deprecation") // Pinned Xaero version exposes the active writer via this facade.
    private void verifyMaps(Minecraft mc) throws ReflectiveOperationException {
        var session=xaero.map.WorldMapSession.getCurrentSession();
        if(session==null||session.getMapProcessor().getMapWorld()==null)throw new IllegalStateException("Xaero session unavailable");
        var processor=session.getMapProcessor();
        if(processor.getHighlighterRegistry().getHighlighters().stream().anyMatch(h->h.getClass().getName().startsWith("ru.animeoncraft")))throw new IllegalStateException("Unexpected custom terrain overlay");
        if(xaero.common.XaeroMinimapSession.getCurrentSession()==null)throw new IllegalStateException("Native minimap unavailable");
        System.out.println("AOC_XAERO_NATIVE_PROBE_PASS world_map=true minimap=true markers_only=true");
        var mapScreen=Class.forName("xaero.map.gui.GuiMap").getConstructor(net.minecraft.client.gui.screens.Screen.class,net.minecraft.client.gui.screens.Screen.class,xaero.map.MapProcessor.class,net.minecraft.world.entity.Entity.class).newInstance(null,null,processor,mc.player);
        mc.setScreen((net.minecraft.client.gui.screens.Screen)mapScreen);
        mapOpened=true;
    }

    private void setTerritories(java.util.List<?> values) throws ReflectiveOperationException {
        var payloadClass=Class.forName("ru.animeoncraft.integration.neoforge.TerritoryPayload");
        var constructor=payloadClass.getDeclaredConstructor(java.util.List.class);constructor.setAccessible(true);
        var payload=constructor.newInstance(java.util.List.copyOf(values));
        var contextClass=net.neoforged.neoforge.network.handling.IPayloadContext.class;
        var context=java.lang.reflect.Proxy.newProxyInstance(contextClass.getClassLoader(),new Class<?>[]{contextClass},(proxy,method,args)->{
            if(method.getName().equals("enqueueWork")){((Runnable)args[0]).run();return java.util.concurrent.CompletableFuture.completedFuture(null);}
            throw new UnsupportedOperationException(method.getName());
        });
        var accept=ru.animeoncraft.integration.neoforge.ClientTerritories.class.getDeclaredMethod("accept",payloadClass,contextClass);accept.setAccessible(true);accept.invoke(null,payload,context);
    }
}
