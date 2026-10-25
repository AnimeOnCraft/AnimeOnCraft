package ru.animeoncraft.loading;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.neoforged.fml.earlydisplay.DisplayWindow;
import net.neoforged.fml.earlydisplay.RenderElement;
import net.neoforged.fml.earlydisplay.SimpleBufferBuilder;
import static org.lwjgl.glfw.GLFW.glfwMakeContextCurrent;

/** Retains FML's window, context locks, error handling and Minecraft handoff. Only the drawing changes. */
public final class AocLoadingWindow extends DisplayWindow {
    private static final System.Logger LOG=System.getLogger("AnimeOnCraftLoading");
    private volatile LoadingRenderer renderer;
    private volatile int mojangTexture;
    @Override public String name() { return "animeoncraft"; }

    private Object field(String name) throws ReflectiveOperationException {
        Field field=DisplayWindow.class.getDeclaredField(name); field.setAccessible(true); return field.get(this);
    }
    @Override public Runnable start(String minecraftVersion, String forgeVersion) {
        Runnable tick=super.start(minecraftVersion, forgeVersion);
        try {
            // FML initialization is queued at +1 ms; the same single render thread installs our element after it.
            ((ScheduledExecutorService)field("renderScheduler")).schedule(this::installTheme, 2, TimeUnit.MILLISECONDS);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            LOG.log(System.Logger.Level.WARNING,"Custom loading theme unavailable; retaining the standard loader",failure);
        }
        return tick;
    }
    private void installTheme() {
        try {
            long window=(long)field("window");
            glfwMakeContextCurrent(window);
            try {
                var next=new LoadingRenderer();
                try {
                    var element=element(next);
                    @SuppressWarnings("unchecked") var elements=(List<RenderElement>)field("elements");
                    // Do not touch progress meters or the scheduler; this replaces the stock artwork only.
                    elements.clear(); elements.add(element); renderer=next;
                } finally { if(renderer!=next)next.close(); }
                LOG.log(System.Logger.Level.INFO,"AOC_LOADING_THEME_READY background=1920x1080 real_progress=true");
            } finally { glfwMakeContextCurrent(0); }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            LOG.log(System.Logger.Level.WARNING,"Custom loading theme failed; retaining the standard loader",failure);
        }
    }
    private RenderElement element(LoadingRenderer next) throws ReflectiveOperationException {
        // FML 4 keeps these two functional interfaces package-private. Bridge them without a split package
        // or a patched loader JAR; pin and verify this adapter against the distributed FML version.
        Class<?> render=Class.forName("net.neoforged.fml.earlydisplay.RenderElement$Renderer");
        Class<?> init=Class.forName("net.neoforged.fml.earlydisplay.RenderElement$Initializer");
        Field alpha=RenderElement.class.getDeclaredField("globalAlpha"); alpha.setAccessible(true);
        Object drawing=Proxy.newProxyInstance(render.getClassLoader(),new Class<?>[]{render},(proxy,method,args)->{
            if(method.getName().equals("accept")) {
                next.render((SimpleBufferBuilder)args[0],(RenderElement.DisplayContext)args[1],(int)args[2],alpha.getInt(null),mojangTexture);
                return null;
            }
            return objectMethod(proxy,method.getName(),args);
        });
        Object initializer=Proxy.newProxyInstance(init.getClassLoader(),new Class<?>[]{init},(proxy,method,args)->
                method.getName().equals("get")?drawing:objectMethod(proxy,method.getName(),args));
        return RenderElement.class.getConstructor(init).newInstance(initializer);
    }
    private static Object objectMethod(Object proxy,String name,Object[] args) {
        return switch(name) { case "toString" -> "AnimeOnCraft loading artwork"; case "hashCode" -> System.identityHashCode(proxy); case "equals" -> proxy==args[0]; default -> null; };
    }
    @Override public void updateModuleReads(ModuleLayer layer) {
        // DisplayWindow's implementation uses getClass().getModule().addReads from the base module;
        // a subclass must establish its own read edge from its own module (Module.addReads is caller-sensitive).
        var game=layer.findModule("neoforge").orElseThrow();getClass().getModule().addReads(game);
        try {
            var overlay=Class.forName(game,"net.neoforged.neoforge.client.loading.NeoForgeLoadingOverlay");
            if(overlay==null)throw new ClassNotFoundException("NeoForgeLoadingOverlay");
            var factory=java.util.Arrays.stream(overlay.getMethods()).filter(m->m.getName().equals("newInstance")
                    &&java.lang.reflect.Modifier.isStatic(m.getModifiers())).findFirst().orElseThrow();
            Field bridge=DisplayWindow.class.getDeclaredField("loadingOverlay");bridge.setAccessible(true);bridge.set(this,factory);
        } catch(ReflectiveOperationException failure) { throw new IllegalStateException("NeoForge loading overlay adapter unavailable",failure); }
    }
    @Override public void addMojangTexture(int texture) { mojangTexture=texture;if(renderer==null)super.addMojangTexture(texture); }
    @Override public void close() {
        try { if(renderer!=null) { renderer.close(); renderer=null; } }
        finally { super.close(); }
        LOG.log(System.Logger.Level.INFO,"AOC_LOADING_THEME_CLOSED textures_released=true");
    }
}
