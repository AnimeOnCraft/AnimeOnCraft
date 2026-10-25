package ru.animeoncraft.integration.neoforge;

import com.google.gson.Gson;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/** JPEG frames decoded one at a time: no video codec or full movie kept in RAM. Render-thread GPU ownership. */
final class TitleMenuVideo {
    private static final ResourceLocation POSTER = id("dynamic/title_poster"), VIDEO = id("dynamic/title_video");
    private static final org.apache.logging.log4j.Logger LOG = org.apache.logging.log4j.LogManager.getLogger("AnimeOnCraft");
    private record Frame(int number, NativeImage pixels) {}
    private TitleAnimationTimeline timeline;
    private TitleMediaArchive media;
    private DynamicTexture poster, texture;
    private Frame ready;
    private boolean open, loading, failed;
    private long epoch, started;
    private int displayed, decoded;

    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("animeoncraft", path); }
    private NativeImage read(int frame, TitleMediaArchive source) throws java.io.IOException {
        try (var input = source==null ? Minecraft.getInstance().getResourceManager().open(id("textures/title/poster.jpg")) : source.open(frame)) {
            // NativeImage.read validates a PNG header, so decode JPEG through the bundled STB instead.
            byte[] bytes = input.readNBytes(1_048_577);
            if (bytes.length > 1_048_576) throw new java.io.IOException("Title frame is too large");
            var encoded = org.lwjgl.system.MemoryUtil.memAlloc(bytes.length);
            try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
                encoded.put(bytes).flip();
                var w = stack.mallocInt(1); var h = stack.mallocInt(1); var channels = stack.mallocInt(1);
                if (!org.lwjgl.stb.STBImage.stbi_info_from_memory(encoded, w, h, channels)
                        || w.get(0) != timeline.width() || h.get(0) != timeline.height())
                    throw new java.io.IOException("Unexpected title frame dimensions");
                var rgba = org.lwjgl.stb.STBImage.stbi_load_from_memory(encoded, w, h, channels, 4);
                if (rgba == null) throw new java.io.IOException("Cannot decode title frame");
                try {
                    var pixels = new NativeImage(w.get(0), h.get(0), false);
                    var ints = rgba.order(java.nio.ByteOrder.nativeOrder()).asIntBuffer();
                    for (int y = 0; y < h.get(0); y++) for (int x = 0; x < w.get(0); x++)
                        pixels.setPixelRGBA(x, y, ints.get());
                    return pixels;
                } finally { org.lwjgl.stb.STBImage.stbi_image_free(rgba); }
            } finally { org.lwjgl.system.MemoryUtil.memFree(encoded); }
        }
    }
    private void start() {
        open = true; failed = false; epoch++; displayed = 0; decoded = 0; started = Util.getMillis();
        try (var stream = Minecraft.getInstance().getResourceManager().open(id("textures/title/animation.json"));
             var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            timeline = new Gson().fromJson(reader, TitleAnimationTimeline.class);
            poster = new DynamicTexture(read(0,null)); decoded++;
            Minecraft.getInstance().getTextureManager().register(POSTER, poster);
            poster.setFilter(true, false);
            try(var manifest=Minecraft.getInstance().getResourceManager().open(id("textures/title/media.json"));
                var json=new InputStreamReader(manifest,StandardCharsets.UTF_8)) {
                media=new TitleMediaArchive(Minecraft.getInstance().gameDirectory.toPath(),new Gson().fromJson(json,TitleMediaArchive.Manifest.class));
            }
        } catch (Exception failure) {
            failed = true; LOG.warn("AnimeOnCraft title animation could not be loaded; using plain background", failure);
        }
    }
    private synchronized void request(int frame) {
        if (!open || loading || failed || frame == displayed) return;
        loading = true;
        long generation = epoch;
        TitleMediaArchive source=media;
        CompletableFuture.supplyAsync(() -> {
            try { return new Frame(frame, read(frame,source)); }
            catch (java.io.IOException failure) { throw new java.util.concurrent.CompletionException(failure); }
        }, Util.backgroundExecutor()).whenComplete((next, failure) -> {
            synchronized (this) {
                if (!open || epoch != generation) { if (next != null) next.pixels().close(); return; }
                loading = false;
                if (failure != null) { failed = true; LOG.warn("Title animation paused at its last frame", failure); return; }
                if (ready != null) ready.pixels().close();
                ready = next; decoded++;
            }
        });
    }
    private synchronized Frame take() { var next = ready; ready = null; return next; }
    void render(GuiGraphics g, int width, int height) {
        if (!open) start();
        if (poster == null) { g.fillGradient(0, 0, width, height, 0xff20112f, 0xff080710); return; }
        var next = take();
        if (next != null) {
            if (texture == null) {
                texture = new DynamicTexture(next.pixels());
                Minecraft.getInstance().getTextureManager().register(VIDEO, texture);
                texture.setFilter(true, false);
            } else {
                texture.setPixels(next.pixels()); texture.upload();
                // NativeImage.upload resets the sampler to NEAREST on every frame.
                texture.setFilter(true, false);
            }
            displayed = next.number();
        }
        long elapsed = Util.getMillis() - started;
        request(timeline.frame(elapsed));
        // Top anchoring keeps the supplied logo visible on ultrawide displays.
        float scale = Math.max((float) width / timeline.width(), (float) height / timeline.height());
        g.pose().pushPose();
        g.pose().translate((width - timeline.width() * scale) / 2, 0, 0);
        g.pose().scale(scale, scale, 1);
        RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1, 1, 1, 1);
        g.blit(POSTER, 0, 0, 0f, 0f, timeline.width(), timeline.height(), timeline.width(), timeline.height());
        if (texture != null) {
            RenderSystem.setShaderColor(1, 1, 1, timeline.opacity(elapsed));
            g.blit(VIDEO, 0, 0, 0f, 0f, timeline.width(), timeline.height(), timeline.width(), timeline.height());
        }
        RenderSystem.setShaderColor(1, 1, 1, 1); RenderSystem.disableBlend();
        g.pose().popPose();
    }
    synchronized void close() {
        open = false; epoch++; loading = false;
        if (ready != null) { ready.pixels().close(); ready = null; }
        var manager = Minecraft.getInstance().getTextureManager();
        if (texture != null) { manager.release(VIDEO); texture = null; }
        if (poster != null) { manager.release(POSTER); poster = null; }
        if(media!=null) { try { media.close(); } catch(java.io.IOException failure) { LOG.warn("Cannot close title media",failure); } media=null; }
    }
    int displayedFrame() { return displayed; }
    int frameWidth() { return timeline == null ? 0 : timeline.width(); }
    int frameHeight() { return timeline == null ? 0 : timeline.height(); }
    boolean smoothFiltering() {
        if (texture == null) return false;
        texture.bind();
        return org.lwjgl.opengl.GL11.glGetTexParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
                org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER) == org.lwjgl.opengl.GL11.GL_LINEAR;
    }
    long completedLoops() { return timeline == null || !open ? 0 : (Util.getMillis() - started) / timeline.durationMillis(); }
    synchronized int decodedFrames() { return decoded; }
    int textures() { return (poster == null ? 0 : 1) + (texture == null ? 0 : 1); }
}
