package ru.animeoncraft.integration.neoforge;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ArrayBlockingQueue;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Local review client for a recording of the summon page: real local server, backend and admission.
 * Gives crystals with the operator command, opens the summon tab, rolls through the reveal animation, trades fragments, wears the set and shows it.
 * Frames are read from the game's own framebuffer and piped to FFmpeg (-Danimeoncraft.gachaVideoFfmpeg/-Output). */
final class GachaVideoProbe {
    private static final int FPS = 20;
    private int ticks, start = -1, step;
    private Process ffmpeg; private Thread writer; private long recordStart, frames; private int width, height;
    private final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(8);
    static void register() { new GachaVideoProbe(); }
    private GachaVideoProbe() { NeoForge.EVENT_BUS.addListener(this::tick); NeoForge.EVENT_BUS.addListener(this::frame); }
    private static boolean enabled() { return Boolean.getBoolean("animeoncraft.gachaVideoProbe") && Minecraft.getInstance().gameDirectory.getAbsolutePath().contains("gacha-local"); }

    private void tick(ClientTickEvent.Post event) {
        if (!enabled()) return;
        var mc = Minecraft.getInstance(); ticks++;
        if (mc.player == null || mc.getConnection() == null || !ClientGacha.supported()) return;
        if (start < 0) { start = ticks; System.out.println("AOC_GACHA_VIDEO_JOINED"); }
        int t = ticks - start;
        if (step == 0 && t >= 80) { step++; record(mc); mc.player.connection.sendCommand("aoccrystals giveall 5000"); }
        else if (step == 1 && t >= 160) { step++; mc.setScreen(new ServerMenuScreen()); }
        else if (step == 2 && t >= 240) { step++; open(mc); }
        else if (step == 3 && t >= 380) { step++; ClientGacha.roll(1); }
        else if (step == 4 && t >= 560) { step++; ClientGacha.roll(10); }
        else if (step == 5 && t >= 800) { step++; ClientGacha.roll(10); }
        else if (step == 6 && t >= 1040) { step++; ClientGacha.roll(10); }
        else if (step == 7 && t >= 1280) { step++; ClientGacha.roll(10); }
        else if (step == 8 && t >= 1520) { step++; var d = ClientGacha.data(); if (d != null && d.shards().get(0) >= d.shards().get(1)) ClientGacha.rollWithFragments(1); }
        else if (step == 9 && t >= 1680) {
            step++; var d = ClientGacha.data();
            if (d != null) for (var item : d.items()) if (item.owned() && BetaPassStore.GACHA_SLOTS.containsKey(item.code())) ClientGacha.wear(item.code(), true);
        }
        else if (step == 10 && t >= 1780) { step++; mc.setScreen(null); mc.options.setCameraType(CameraType.THIRD_PERSON_BACK); }
        else if (step == 11 && t >= 1940) { step++; mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT); }
        else if (step == 12 && t >= 2100) { step++; mc.options.setCameraType(CameraType.FIRST_PERSON); finish(); System.out.println("AOC_GACHA_VIDEO_DONE"); }
        else if (step == 13 && t >= 2140) { step++; mc.stop(); }
        // Each reveal stays on screen for two seconds after the last card, then the probe presses on, as a player would.
        if (mc.screen instanceof ServerMenuScreen s && s.revealShownFor(2)) s.advanceReveal();
        if (step >= 3 && step <= 9 && !(mc.screen instanceof ServerMenuScreen)) open(mc);
    }

    private static void open(Minecraft mc) { var s = new ServerMenuScreen(); s.show(ServerMenuLogic.Page.SUMMON, 0); mc.setScreen(s); ClientGacha.read(); }

    private void record(Minecraft mc) {
        var target = mc.getMainRenderTarget(); width = target.width & ~1; height = target.height & ~1;
        try {
            ffmpeg = new ProcessBuilder(System.getProperty("animeoncraft.gachaVideoFfmpeg"), "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgba",
                "-s", width + "x" + height, "-r", String.valueOf(FPS), "-i", "-", "-vf", "scale=1600:-2", "-c:v", "libx264", "-preset", "veryfast", "-crf", "21",
                "-pix_fmt", "yuv420p", "-movflags", "+faststart", System.getProperty("animeoncraft.gachaVideoOutput")).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        } catch (java.io.IOException e) { System.out.println("AOC_GACHA_VIDEO_FFMPEG_FAILED " + e.getMessage()); return; }
        OutputStream out = ffmpeg.getOutputStream();
        writer = new Thread(() -> { try { while (true) { var f = queue.take(); if (f.length == 0) break; out.write(f); } out.close(); } catch (Exception ignored) {} }, "aoc-gacha-video");
        writer.start(); recordStart = System.nanoTime(); System.out.println("AOC_GACHA_VIDEO_RECORD " + width + "x" + height);
    }

    /** Keeps wall-clock timing: a slow frame is repeated rather than speeding up the video. */
    private void frame(RenderFrameEvent.Post event) {
        if (ffmpeg == null || writer == null || !writer.isAlive() || step > 12) return;
        long due = (System.nanoTime() - recordStart) * FPS / 1_000_000_000L + 1;
        if (frames >= due) return;
        var target = Minecraft.getInstance().getMainRenderTarget();
        if ((target.width & ~1) != width || (target.height & ~1) != height) return;
        try (var image = Screenshot.takeScreenshot(target)) {
            var buffer = ByteBuffer.allocate(width * height * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) buffer.putInt(image.getPixelRGBA(x, y));
            var bytes = buffer.array();
            while (frames < due) { if (!queue.offer(bytes)) break; frames++; }
        }
    }

    private void finish() {
        if (ffmpeg == null) return;
        try { queue.put(new byte[0]); writer.join(20000); ffmpeg.waitFor(60, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        System.out.println("AOC_GACHA_VIDEO_SAVED frames=" + frames);
    }
}
