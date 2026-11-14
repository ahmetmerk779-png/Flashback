package dev.flashback.replay;

import dev.flashback.FlashbackMod;
import dev.flashback.gui.ReplayControlsScreen;
import dev.flashback.mixin.ClientConnectionAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.Text;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.world.GameMode;
import net.minecraft.world.tick.TickManager;

import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public final class ReplayManager {
    public static final Freecam freecam = new Freecam();
    public static final CameraPath path = new CameraPath();

    private static ReplayFile file;
    private static ReplayServer server;
    private static volatile boolean active;
    private static boolean inWorld, timeoutRemoved, pendingEditor, dirty;
    private static boolean paused;
    private static double speed = 1.0;
    private static boolean pathPlaying;
    private static long restartAbs = -1, lastAbs;
    private static int restartDelay;

    // ---- dünya / görünüm geçersiz kılmaları (yalnızca istemci tarafında, kayıt dosyasını değiştirmez) ----
    /** 0 = kayıttaki, 1 = açık, 2 = yağmur, 3 = fırtına */
    public static int weather;
    /** null = kayıttaki oyun modu */
    public static GameMode forcedMode;
    private static Integer origFov;
    private static boolean hudHiddenByUs;

    // ---------- yaşam döngüsü ----------
    public static void begin(ReplayFile f) {
        path.clear();
        for (ReplayFile.Key k : f.meta.keyframes)
            path.add(new CameraPath.Keyframe(k.ms, k.x, k.y, k.z, k.yaw, k.pitch, k.fov > 0 ? k.fov : 70f));
        paused = false;
        speed = 1.0;
        pathPlaying = false;
        freecam.enabled = false;
        dirty = false;
        pendingEditor = true; // dünyaya girince düzenleme paneli açılsın
        file = f;
        launch(f.startMs());
    }

    private static void launch(long startAbs) {
        MinecraftClient mc = MinecraftClient.getInstance();
        closeServer();
        try {
            lastAbs = startAbs;
            server = new ReplayServer(file, startAbs, paused, speed);
            server.start();
            active = true;
            timeoutRemoved = false;
            inWorld = false;
            String addr = "127.0.0.1:" + server.port();
            ServerInfo info = new ServerInfo("Flashback: " + file.meta.name, addr, ServerInfo.ServerType.OTHER);
            mc.execute(() -> ConnectScreen.connect(new TitleScreen(), mc, ServerAddress.parse(addr), info, false, null));
        } catch (Exception e) {
            FlashbackMod.LOGGER.error("Tekrar başlatılamadı", e);
            stop();
        }
    }

    public static void stop() {
        MinecraftClient mc0 = MinecraftClient.getInstance();
        if (ExportManager.isActive()) ExportManager.cancel(mc0);
        if (origFov != null) { mc0.options.getFov().setValue(origFov); origFov = null; }
        if (hudHiddenByUs) { mc0.options.hudHidden = false; hudHiddenByUs = false; }
        weather = 0;
        forcedMode = null;
        active = false;
        inWorld = false;
        pathPlaying = false;
        pendingEditor = false;
        freecam.enabled = false;
        restartAbs = -1;
        closeServer();
        file = null;
    }

    private static void closeServer() {
        if (server != null) { server.close(); server = null; }
    }

    public static void onDisconnect(MinecraftClient mc) {
        inWorld = false;
        if (!active) return;
        if (restartAbs >= 0) { restartDelay = 5; closeServer(); }
        else stop();
    }

    public static void tick(MinecraftClient mc) {
        if (restartAbs >= 0 && mc.world == null && --restartDelay <= 0) {
            long ms = restartAbs;
            restartAbs = -1;
            launch(ms);
            return;
        }
        if (!active) return;
        if (server != null) lastAbs = server.clockMs();

        boolean now = mc.world != null && mc.getNetworkHandler() != null;
        if (now && !inWorld) onEnteredWorld(mc);
        inWorld = now;
        if (!now) return;

        if (pendingEditor && mc.currentScreen == null) {
            pendingEditor = false;
            mc.setScreen(new ReplayControlsScreen());
        }

        // Oyun zamanını tekrarın hız / duraklatma durumuna bağla.
        TickManager tm = mc.world.getTickManager();
        tm.setFrozen(paused);
        tm.setTickRate((float) (20.0 * speed));

        ClientWorld w = mc.world;
        if (weather != 0) {
            w.setRainGradient(weather >= 2 ? 1f : 0f);
            w.setThunderGradient(weather == 3 ? 1f : 0f);
        }
        if (forcedMode != null && mc.interactionManager != null && mc.interactionManager.getCurrentGameMode() != forcedMode) {
            mc.interactionManager.setGameMode(forcedMode);
        }

        // Kayıtlı yerel oyuncu hareketini uygula (sunucu bunu kendi oyuncumuza geri göndermez)
        ReplayFile.Local lc = localNow();
        if (lc != null && mc.player != null) applyLocal(mc.player, lc);

        if (pathPlaying && (absMs() >= path.end() || path.size() < 2)) pathPlaying = false;
    }

    private static void onEnteredWorld(MinecraftClient mc) {
        if (timeoutRemoved) return;
        try {
            ClientPlayNetworkHandler h = mc.getNetworkHandler();
            var ch = ((ClientConnectionAccessor) h.getConnection()).flashback$getChannel();
            if (ch.pipeline().get("timeout") != null) ch.pipeline().remove("timeout");
            timeoutRemoved = true;
        } catch (Throwable t) {
            FlashbackMod.LOGGER.warn("Zaman aşımı işleyicisi kaldırılamadı (duraklatınca bağlantı kopabilir)", t);
        }
    }

    // ---------- durum (UI: göreli süre = kırpılmış aralığın başından itibaren) ----------
    public static boolean isActive() { return active; }
    public static boolean inWorld() { return active && inWorld; }
    public static boolean freecamActive() { return active && inWorld && freecam.enabled; }
    public static boolean pathPlaying() { return pathPlaying; }
    public static boolean isPaused() { return paused; }
    public static double speed() { return speed; }
    public static boolean ended() { return server != null && server.ended(); }
    public static ReplayFile file() { return file; }
    public static boolean isDirty() { return dirty; }

    public static long startAbs() { return file == null ? 0 : file.startMs(); }
    public static long endAbs() { return file == null ? 0 : file.endMs(); }
    public static long absMs() { return server == null ? lastAbs : server.clockMs(); }
    public static long rel(long abs) { return abs - startAbs(); }
    public static long currentMs() { return Math.max(0, absMs() - startAbs()); }
    public static long durationMs() { return Math.max(0, endAbs() - startAbs()); }

    // ---------- oynatma kontrolü ----------
    public static void togglePause() { setPaused(!paused); }
    public static void setPaused(boolean p) { paused = p; if (server != null) server.setPaused(p); }
    public static void setSpeed(double s) { speed = s; if (server != null) server.setSpeed(s); }
    public static void skip(long deltaMs) { seekTo(currentMs() + deltaMs); }
    public static void seekTo(long relMs) { seekAbs(startAbs() + relMs); }

    public static void seekAbs(long abs) {
        if (!active || server == null) return;
        abs = Math.max(startAbs(), Math.min(abs, endAbs()));
        if (abs >= server.clockMs() - 50) { server.seek(abs); return; }
        ClientPlayNetworkHandler h = MinecraftClient.getInstance().getNetworkHandler();
        if (h == null) return;
        restartAbs = abs; // geri sarma: bağlantıyı kapat, hedef zamana kadar hızlıca yeniden akıt
        h.getConnection().disconnect(Text.literal("Flashback: geri sarılıyor…"));
    }

    // ---------- düzenleme ----------
    public static boolean setTrimStart() {
        long a = absMs();
        if (file == null || a >= endAbs() - 500) return false;
        file.meta.startMs = a;
        dirty = true;
        return true;
    }

    public static boolean setTrimEnd() {
        long a = absMs();
        if (file == null || a <= startAbs() + 500) return false;
        file.meta.endMs = a;
        dirty = true;
        return true;
    }

    public static void resetTrim() {
        if (file == null) return;
        file.meta.startMs = file.meta.origStartMs;
        file.meta.endMs = file.meta.origEndMs;
        dirty = true;
    }

    public static void addMarker() {
        if (file == null) return;
        file.meta.markers.add(new ReplayFile.Marker(absMs(), "İşaret " + (file.meta.markers.size() + 1)));
        file.meta.markers.sort(Comparator.comparingLong(m -> m.ms));
        dirty = true;
    }

    public static boolean removeMarkerNear() {
        if (file == null) return false;
        long a = absMs();
        ReplayFile.Marker best = null;
        for (ReplayFile.Marker m : file.meta.markers)
            if (Math.abs(m.ms - a) <= 3000 && (best == null || Math.abs(m.ms - a) < Math.abs(best.ms - a))) best = m;
        if (best == null) return false;
        file.meta.markers.remove(best);
        dirty = true;
        return true;
    }

    public static void addKeyframe() {
        if (!freecamActive()) return;
        path.add(new CameraPath.Keyframe(absMs(), freecam.x, freecam.y, freecam.z, freecam.yaw, freecam.pitch, (float) fov()));
        dirty = true;
    }

    public static void clearPath() { path.clear(); dirty = true; }

    public static void togglePath() {
        if (pathPlaying) { pathPlaying = false; return; }
        if (path.size() < 2) return;
        if (!freecam.enabled) freecam.toggle();
        seekAbs(path.start());
        pathPlaying = true;
    }

    public static void rename(String newName, Consumer<String> done) {
        ReplayFile f = file;
        if (f == null || f.meta.path == null) return;
        CompletableFuture.supplyAsync(() -> {
            try {
                f.meta.name = newName;
                f.meta.path = ReplayFile.renameOnDisk(f.meta.path, newName);
                return "Ad değiştirildi.";
            } catch (Exception e) { return "Hata: " + e.getMessage(); }
        }).thenAccept(msg -> MinecraftClient.getInstance().execute(() -> done.accept(msg)));
    }

    /** Düzenlemeleri (kırpma, işaretler, kamera yolu) .fbr dosyasına yazar. */
    public static void save(Consumer<String> done) {
        ReplayFile f = file;
        if (f == null || f.meta.path == null) return;
        f.meta.keyframes.clear();
        for (CameraPath.Keyframe k : path.all()) {
            ReplayFile.Key key = new ReplayFile.Key();
            key.ms = k.ms(); key.x = k.x(); key.y = k.y(); key.z = k.z(); key.yaw = k.yaw(); key.pitch = k.pitch(); key.fov = k.fov();
            f.meta.keyframes.add(key);
        }
        f.meta.durationMs = f.endMs() - f.startMs();
        CompletableFuture.supplyAsync(() -> {
            try {
                ReplayFile.saveMeta(f.meta.path, f.meta);
                return "Kaydedildi.";
            } catch (Exception e) { return "Hata: " + e.getMessage(); }
        }).thenAccept(msg -> MinecraftClient.getInstance().execute(() -> {
            if (msg.startsWith("Kaydedildi")) dirty = false;
            done.accept(msg);
        }));
    }

    // ---------- yerel oyuncu (kayıtlı hareket) ----------
    public static ReplayFile.Local localNow() { return file == null ? null : file.localAt(absMs()); }

    public static boolean localCameraActive() {
        return active && inWorld && !freecam.enabled && file != null && file.hasLocals();
    }

    private static void applyLocal(ClientPlayerEntity p, ReplayFile.Local l) {
        p.setPosition(l.x(), l.y(), l.z());
        p.setYaw(l.yaw());
        p.setPitch(l.pitch());
        p.setHeadYaw(l.headYaw());
        p.setBodyYaw(l.bodyYaw());
        p.setSneaking((l.flags() & 1) != 0);
        p.setSprinting((l.flags() & 2) != 0);
    }

    // ---------- dünya ve görünüm ----------
    public static void setWeather(int w) {
        boolean revert = w == 0 && weather != 0;
        weather = w;
        if (revert) reloadHere(); // kayıttaki durumu geri yüklemek için aynı ana yeniden akıt
    }

    public static void setGameMode(GameMode m) {
        boolean revert = m == null && forcedMode != null;
        forcedMode = m;
        if (revert) reloadHere();
    }

    /** Bağlantıyı kapatıp aynı zamana kadar yeniden akıtır (geçersiz kılmaları temizlemek için). */
    public static void reloadHere() {
        if (!active || server == null) return;
        ClientPlayNetworkHandler h = MinecraftClient.getInstance().getNetworkHandler();
        if (h == null) return;
        restartAbs = absMs();
        h.getConnection().disconnect(Text.literal("Flashback: yeniden yükleniyor…"));
    }

    public static int fov() { return MinecraftClient.getInstance().options.getFov().getValue(); }

    public static void changeFov(int delta) {
        var o = MinecraftClient.getInstance().options.getFov();
        if (origFov == null) origFov = o.getValue();
        o.setValue(Math.max(30, Math.min(110, o.getValue() + delta)));
    }

    public static boolean hudHidden() { return MinecraftClient.getInstance().options.hudHidden; }

    public static void toggleHud() {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.options.hudHidden = !mc.options.hudHidden;
        hudHiddenByUs = mc.options.hudHidden;
    }

    public static void removeKeyframeNear() {
        if (path.removeNear(absMs(), 3000)) dirty = true;
    }

    private ReplayManager() {}
}
