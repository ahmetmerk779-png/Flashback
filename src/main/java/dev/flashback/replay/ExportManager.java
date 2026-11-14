package dev.flashback.replay;

import dev.flashback.FlashbackConfig;
import dev.flashback.FlashbackMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.stb.STBIWriteCallback;
import org.lwjgl.stb.STBImageWrite;
import org.lwjgl.system.MemoryUtil;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.stream.Stream;

/**
 * Tekrarı videoya çevirir. Her çizilen kare okunur, sabit kare hızına (CFR) göre çoğaltılıp/atlanarak
 * seçilen çıktıya yazılır. Oyun "render hızı" kadar yavaşlatılarak oynatılır.
 *
 * Çıktılar:
 *  - AVI (MJPEG): tamamen Java + LWJGL-STB; harici program yok -> telefonda da çalışır.
 *  - PNG dizisi: kare kare PNG.
 *  - MP4 (ffmpeg): ffmpeg kuruluysa (masaüstü); bulunamazsa otomatik AVI'ye düşer.
 */
public final class ExportManager {
    public enum Backend {
        AVI("AVI (kurulumsuz)", ".avi"), PNG("PNG dizisi", ""), FFMPEG("MP4 (ffmpeg)", ".mp4");
        public final String label, ext;
        Backend(String l, String e) { label = l; ext = e; }
    }

    public enum Quality {
        LOW("Düşük", 28, 70), MEDIUM("Orta", 23, 85), HIGH("Yüksek", 18, 95);
        public final String label;
        public final int crf, jpeg;
        Quality(String l, int c, int j) { label = l; crf = c; jpeg = j; }
    }

    private record Item(byte[] data, int w, int h, int count) {}

    // ---- kullanıcı ayarları ----
    public static int width = 1920, height = 1080, fps = 30;
    public static Quality quality = Quality.MEDIUM;
    public static Backend backend = Backend.AVI;
    public static boolean h265 = false, hideHud = true, forceRes = false;
    public static double renderSpeed = 0.25;

    // ---- durum ----
    private static volatile boolean active;
    private static volatile String error;
    private static volatile boolean cancelled;
    private static boolean awaitingStart, sinkStarted, inFlight, throttled;
    private static double nextOutMs;
    private static long frames;
    private static Process proc;
    private static BlockingQueue<Item> queue;
    private static final Item END = new Item(null, 0, 0, 0);
    private static Path outFile;
    private static final StringBuilder ffLog = new StringBuilder();
    private static double oldSpeed;
    private static boolean oldHud;
    private static int titleTick, lastLogPct = -1;
    private static Backend usedBackend;

    public static boolean isActive() { return active; }
    public static boolean forcing() { return active && forceRes; }

    public static void start(MinecraftClient mc) {
        if (active || !ReplayManager.inWorld()) return;
        ReplayFile f = ReplayManager.file();
        usedBackend = backend;
        try {
            outFile = uniqueOut(f, usedBackend);
        } catch (IOException e) {
            chat(mc, "§c[Flashback] Çıktı klasörü oluşturulamadı: " + e.getMessage());
            return;
        }
        queue = new ArrayBlockingQueue<>(4);
        nextOutMs = 0; frames = 0; error = null; cancelled = false; proc = null;
        sinkStarted = false; inFlight = false; throttled = false; awaitingStart = true; titleTick = 0; lastLogPct = -1;
        ffLog.setLength(0);
        oldSpeed = ReplayManager.speed();
        oldHud = mc.options.hudHidden;

        active = true;
        if (hideHud) mc.options.hudHidden = true;
        mc.setScreen(null);
        if (forceRes) mc.onResolutionChanged();
        ReplayManager.setSpeed(renderSpeed);
        ReplayManager.setPaused(false);
        ReplayManager.seekAbs(ReplayManager.startAbs()); // baştan başla
        chat(mc, "§9[Flashback]§r Dışa aktarma başladı (" + usedBackend.label + ", " + fps + " fps). İptal: ESC / duraklat düğmesi");
    }

    private static Path uniqueOut(ReplayFile f, Backend b) throws IOException {
        Path dir = ReplayFile.dir().getParent().resolve("exports");
        Files.createDirectories(dir);
        String base = ReplayFile.sanitize(f.meta.name) + "_" + width + "x" + height + "_" + fps + "fps";
        Path p = dir.resolve(base + b.ext);
        for (int n = 1; Files.exists(p); n++) p = dir.resolve(base + "_" + n + b.ext);
        return p;
    }

    public static void tick(MinecraftClient mc) {
        if (!active) return;
        if (error != null) {
            String e = error;
            cancel(mc);
            chat(mc, "§c[Flashback] Dışa aktarma durdu: " + e);
            return;
        }
        if (!ReplayManager.inWorld()) return; // geri sarma sırasında yeniden bağlanıyor

        // ESC / duraklat düğmesi = iptal (klavyesiz cihazlar için)
        if (mc.currentScreen instanceof GameMenuScreen) { cancel(mc); return; }
        if (mc.currentScreen != null) mc.setScreen(null);

        if (awaitingStart) {
            if (ReplayManager.absMs() - ReplayManager.startAbs() > 1500) return;
            awaitingStart = false;
        }

        boolean need = queue.size() >= 3;
        if (need != throttled) { throttled = need; ReplayManager.setPaused(need); }

        long dur = Math.max(1, ReplayManager.durationMs());
        int pct = (int) Math.min(100, ReplayManager.currentMs() * 100 / dur);
        if (++titleTick % 10 == 0) {
            GLFW.glfwSetWindowTitle(mc.getWindow().getHandle(), "Flashback dışa aktarma %" + pct + "  (" + frames + " kare)");
        }
        if (pct / 10 != lastLogPct / 10) { lastLogPct = pct; FlashbackMod.LOGGER.info("Dışa aktarma %{} ({} kare)", pct, frames); }

        if (sinkStarted && (ReplayManager.absMs() >= ReplayManager.endAbs() - 10 || ReplayManager.ended())) finish(mc);
    }

    /** GameRenderer.render sonunda çağrılır. */
    public static void onFrameEnd(MinecraftClient mc) {
        if (!active || awaitingStart || inFlight || mc.world == null || !ReplayManager.inWorld()) return;
        long g = ReplayManager.absMs() - ReplayManager.startAbs();
        if (g < 0 || queue.remainingCapacity() == 0) return;
        inFlight = true;
        try {
            ScreenshotRecorder.takeScreenshot(mc.getFramebuffer(), image -> {
                try { handle(image, g); }
                catch (Throwable t) { error = t.getMessage() != null ? t.getMessage() : t.toString(); }
                finally { image.close(); inFlight = false; }
            });
        } catch (Throwable t) {
            inFlight = false;
            error = "Kare okunamadı: " + t;
        }
    }

    private static void handle(NativeImage image, long g) throws Exception {
        if (!active) return;
        int w = image.getWidth(), h = image.getHeight();
        if (!sinkStarted) startSink(w, h);
        boolean bgra = usedBackend == Backend.FFMPEG;
        byte[] data = new byte[w * h * (bgra ? 4 : 3)];
        int i = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = image.getColorArgb(x, y);
                if (bgra) {
                    data[i++] = (byte) argb; data[i++] = (byte) (argb >> 8); data[i++] = (byte) (argb >> 16); data[i++] = (byte) 0xFF;
                } else {
                    data[i++] = (byte) (argb >> 16); data[i++] = (byte) (argb >> 8); data[i++] = (byte) argb;
                }
            }
        }
        int count = 0;
        while (nextOutMs <= g) { count++; nextOutMs += 1000.0 / fps; }
        if (count > 0 && active) { queue.put(new Item(data, w, h, count)); frames += count; }
    }

    // ---------------- çıktı yazıcıları ----------------
    private static void startSink(int w, int h) throws IOException {
        Process p = null;
        if (usedBackend == Backend.FFMPEG) {
            try {
                p = startFfmpeg(w, h);
            } catch (IOException e) {
                // ffmpeg yok (ör. telefon): kurulumsuz AVI'ye düş
                usedBackend = Backend.AVI;
                outFile = uniqueOut(ReplayManager.file(), Backend.AVI);
                MinecraftClient mc = MinecraftClient.getInstance();
                mc.execute(() -> chat(mc, "§e[Flashback] ffmpeg bulunamadı; AVI (kurulumsuz) olarak devam ediliyor."));
            }
        }
        proc = p;
        Backend b = usedBackend;
        Path out = outFile;
        BlockingQueue<Item> q = queue;
        Process fp = p;
        int fpsLocal = fps;
        int jq = quality.jpeg;
        Thread t = new Thread(() -> runWriter(b, q, out, fp, w, h, fpsLocal, jq), "Flashback-Export-Writer");
        t.setDaemon(true);
        t.start();
        sinkStarted = true;
    }

    private static Process startFfmpeg(int w, int h) throws IOException {
        List<String> cmd = new ArrayList<>(List.of(FlashbackConfig.ffmpegPath, "-y", "-loglevel", "error",
                "-f", "rawvideo", "-pix_fmt", "bgra", "-s", w + "x" + h, "-r", String.valueOf(fps), "-i", "-", "-an"));
        if (w != width || h != height) { cmd.add("-vf"); cmd.add("scale=" + width + ":" + height + ":flags=lanczos"); }
        if (h265) cmd.addAll(List.of("-c:v", "libx265", "-preset", "medium", "-crf", String.valueOf(quality.crf + 5), "-tag:v", "hvc1"));
        else cmd.addAll(List.of("-c:v", "libx264", "-preset", "medium", "-crf", String.valueOf(quality.crf)));
        cmd.addAll(List.of("-pix_fmt", "yuv420p", "-movflags", "+faststart", outFile.toString()));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        Thread drain = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null)
                    synchronized (ffLog) { ffLog.append(line).append('\n'); if (ffLog.length() > 2000) ffLog.delete(0, ffLog.length() - 2000); }
            } catch (IOException ignored) {}
        }, "Flashback-ffmpeg-log");
        drain.setDaemon(true);
        drain.start();
        return p;
    }

    private static void runWriter(Backend b, BlockingQueue<Item> q, Path out, Process p, int w, int h, int fps, int jpegQ) {
        boolean ok = true;
        try {
            switch (b) {
                case FFMPEG -> {
                    OutputStream in = new BufferedOutputStream(p.getOutputStream(), 1 << 20);
                    while (true) {
                        Item it = q.take();
                        if (it == END) break;
                        for (int c = 0; c < it.count; c++) in.write(it.data);
                    }
                    in.close();
                    int code = p.waitFor();
                    if (code != 0) { ok = false; synchronized (ffLog) { error = "ffmpeg hata kodu " + code + "\n" + ffLog; } }
                }
                case AVI -> {
                    try (AviMjpegWriter avi = new AviMjpegWriter(out, w, h, fps)) {
                        while (true) {
                            Item it = q.take();
                            if (it == END) break;
                            byte[] jpg = encode(true, it.data, it.w, it.h, jpegQ);
                            for (int c = 0; c < it.count; c++) avi.writeFrame(jpg);
                        }
                    }
                }
                case PNG -> {
                    Files.createDirectories(out);
                    int n = 0;
                    while (true) {
                        Item it = q.take();
                        if (it == END) break;
                        byte[] png = encode(false, it.data, it.w, it.h, 0);
                        for (int c = 0; c < it.count; c++) Files.write(out.resolve(String.format("%06d.png", ++n)), png);
                    }
                }
            }
        } catch (Throwable e) {
            ok = false;
            if (!cancelled) error = "Yazma hatası: " + e;
        }
        if (ok && !cancelled) {
            MinecraftClient mc = MinecraftClient.getInstance();
            mc.execute(() -> chat(mc, "§a[Flashback] Hazır: §r" + out));
        }
    }

    /** RGB bayt dizisini LWJGL-STB ile JPEG/PNG'ye kodlar (ek kütüphane gerekmez). */
    private static byte[] encode(boolean jpeg, byte[] rgb, int w, int h, int quality) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(Math.max(1024, rgb.length / 8));
        STBIWriteCallback cb = STBIWriteCallback.create((ctx, data, size) -> {
            byte[] t = new byte[size];
            MemoryUtil.memByteBuffer(data, size).get(t);
            bo.write(t, 0, size);
        });
        ByteBuffer buf = MemoryUtil.memAlloc(rgb.length);
        try {
            buf.put(rgb).flip();
            boolean ok = jpeg ? asBool(STBImageWrite.stbi_write_jpg_to_func(cb, 0L, w, h, 3, buf, quality))
                              : asBool(STBImageWrite.stbi_write_png_to_func(cb, 0L, w, h, 3, buf, w * 3));
            if (!ok) throw new IllegalStateException("Kare kodlanamadı");
        } finally {
            cb.free();
            MemoryUtil.memFree(buf);
        }
        return bo.toByteArray();
    }

    private static boolean asBool(boolean b) { return b; }
    private static boolean asBool(int i) { return i != 0; }

    // ---------------- bitiş / iptal ----------------
    private static void finish(MinecraftClient mc) {
        active = false;
        proc = null;
        try { queue.put(END); } catch (InterruptedException ignored) {}
        restore(mc);
        chat(mc, "§9[Flashback]§r Dosya tamamlanıyor…");
    }

    public static void cancel(MinecraftClient mc) {
        if (!active) return;
        cancelled = true;
        active = false;
        if (proc != null) proc.destroyForcibly();
        proc = null;
        if (queue != null) { queue.clear(); queue.offer(END); }
        try {
            if (outFile != null && Files.isDirectory(outFile)) {
                try (Stream<Path> s = Files.walk(outFile)) { s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); }
            } else if (outFile != null) Files.deleteIfExists(outFile);
        } catch (IOException ignored) {}
        restore(mc);
        chat(mc, "§9[Flashback]§r Dışa aktarma iptal edildi.");
    }

    private static void restore(MinecraftClient mc) {
        mc.options.hudHidden = oldHud;
        ReplayManager.setSpeed(oldSpeed);
        ReplayManager.setPaused(true);
        throttled = false;
        mc.onResolutionChanged();
        mc.updateWindowTitle();
    }

    private static void chat(MinecraftClient mc, String s) {
        if (mc.inGameHud != null) mc.inGameHud.getChatHud().addMessage(Text.literal(s));
    }

    private ExportManager() {}
}
