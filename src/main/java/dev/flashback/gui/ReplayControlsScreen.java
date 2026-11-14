package dev.flashback.gui;

import dev.flashback.FlashbackConfig;
import dev.flashback.gui.Ui.Rect;
import dev.flashback.replay.CameraPath;
import dev.flashback.replay.ExportManager;
import dev.flashback.replay.ReplayFile;
import dev.flashback.replay.ReplayManager;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import net.minecraft.world.GameMode;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/** Tekrar düzenleyicisi: zaman çizelgesi + sekmeler (oynatma, kamera, düzenle, dünya, dışa aktar). */
public class ReplayControlsScreen extends Screen {
    private record Btn(Rect r, String label, Runnable action, boolean enabled, int accent) {}
    private record Lbl(int x, int y, String s, int color) {}
    private enum Tab {
        PLAY("Oynatma"), CAMERA("Kamera"), EDIT("Düzenle"), WORLD("Dünya"), EXPORT("Dışa Aktar");
        final String label;
        Tab(String l) { label = l; }
    }

    private static final double[] SPEEDS = {0.1, 0.25, 0.5, 1, 2, 4, 8};
    private static final int[][] RES = {{1280, 720}, {1920, 1080}, {2560, 1440}, {3840, 2160}};
    private static final String[] RES_NAMES = {"720p", "1080p", "1440p", "4K"};
    private static final int[] FPS = {24, 30, 60, 120};
    private static final double[] RSPEED = {0.1, 0.25, 0.5, 1.0};
    private static final String[] WEATHER = {"Kayıttaki", "Açık", "Yağmur", "Fırtına"};
    private static final GameMode[] MODES = {null, GameMode.SURVIVAL, GameMode.CREATIVE, GameMode.ADVENTURE, GameMode.SPECTATOR};
    private static final String[] MODE_NAMES = {"Kayıttaki", "Survival", "Creative", "Adventure", "Spectator"};

    private static Tab tab = Tab.PLAY;
    private final List<Btn> btns = new ArrayList<>();
    private final List<Lbl> lbls = new ArrayList<>();
    private Rect panel, timeline;
    private boolean dragging;
    private long dragMs;
    private String status = "";
    private int statusColor = Ui.MUTED;

    public ReplayControlsScreen() { super(Text.literal("Flashback Düzenleyici")); }

    @Override
    protected void init() {
        int w = Math.min(width - 24, 780), h = 192;
        panel = new Rect((width - w) / 2, height - h - 12, w, h);
        timeline = new Rect(panel.x() + 12, panel.y() + 42, w - 24, 16);
    }

    private long dur() { return Math.max(1, ReplayManager.durationMs()); }
    private void say(String s, int c) { status = s; statusColor = c; }
    private static String sp(double s) { return (s < 1 ? String.valueOf(s) : String.valueOf((int) s)) + "x"; }

    private void add(int[] cx, int y, int w, String label, Runnable a, boolean en, int accent) {
        btns.add(new Btn(new Rect(cx[0], y, w, 22), label, a, en, accent));
        cx[0] += w + 4;
    }

    private void rebuild() {
        btns.clear();
        lbls.clear();
        int x0 = panel.x() + 12, ty = panel.y() + 64, r1 = panel.y() + 90, r2 = panel.y() + 116, r3 = panel.y() + 142;
        ReplayFile f = ReplayManager.file();
        boolean fc = ReplayManager.freecam.enabled;
        boolean hasMk = f != null && !f.meta.markers.isEmpty();
        boolean exporting = ExportManager.isActive();

        int tx = x0;
        for (Tab t : Tab.values()) {
            btns.add(new Btn(new Rect(tx, ty, 92, 20), t.label, () -> tab = t, true, tab == t ? Ui.ACCENT : 0));
            tx += 96;
        }
        btns.add(new Btn(new Rect(panel.right() - 12 - 56, ty, 56, 20), "Kapat", this::close, true, 0));

        int[] cx = {x0};
        switch (tab) {
            case PLAY -> {
                add(cx, r1, 52, "-10 sn", () -> ReplayManager.skip(-10_000), true, 0);
                add(cx, r1, 84, ReplayManager.isPaused() ? "▶ Oynat" : "❚❚ Duraklat", ReplayManager::togglePause, true, Ui.ACCENT);
                add(cx, r1, 52, "+10 sn", () -> ReplayManager.skip(10_000), true, 0);
                cx[0] += 12;
                for (double s : SPEEDS)
                    add(cx, r1, 44, sp(s), () -> ReplayManager.setSpeed(s), true, Math.abs(ReplayManager.speed() - s) < 1e-6 ? Ui.OK : 0);
                cx[0] = x0;
                add(cx, r2, 70, "« İşaret", () -> jumpMarker(-1), hasMk, 0);
                add(cx, r2, 70, "İşaret »", () -> jumpMarker(1), hasMk, 0);
                add(cx, r2, 80, "Başa Dön", () -> ReplayManager.seekTo(0), true, 0);
                lbls.add(new Lbl(x0, r3 + 6, "Hız, hem oyun zamanını hem de dünya tick hızını değiştirir.", Ui.MUTED));
            }
            case CAMERA -> {
                add(cx, r1, 130, "Kamera: " + (fc ? "Serbest" : "Oyuncu"), ReplayManager.freecam::toggle, true, fc ? Ui.ACCENT : 0);
                add(cx, r1, 112, "+ Anahtar Kare", ReplayManager::addKeyframe, fc, 0);
                add(cx, r1, 112, "− Anahtar Kare", ReplayManager::removeKeyframeNear, ReplayManager.path.size() > 0, 0);
                boolean can = ReplayManager.path.size() >= 2;
                add(cx, r1, 112, ReplayManager.pathPlaying() ? "■ Yolu Durdur" : "▶ Yolu Oynat", ReplayManager::togglePath, can, can ? Ui.OK : 0);
                add(cx, r1, 96, "Yolu Temizle", ReplayManager::clearPath, ReplayManager.path.size() > 0, 0);
                cx[0] = x0;
                add(cx, r2, 150, "Yol: " + (FlashbackConfig.smoothPath ? "Yumuşak" : "Doğrusal"), () -> FlashbackConfig.smoothPath = !FlashbackConfig.smoothPath, true, 0);
                lbls.add(new Lbl(x0 + 160, r2 + 7, "Kamera hızı", Ui.MUTED));
                cx[0] = x0 + 232;
                add(cx, r2, 26, "-", () -> FlashbackConfig.freecamSpeed = Math.max(1, FlashbackConfig.freecamSpeed - 2), true, 0);
                add(cx, r2, 62, String.format("%.0f b/sn", FlashbackConfig.freecamSpeed), () -> {}, false, 0);
                add(cx, r2, 26, "+", () -> FlashbackConfig.freecamSpeed = Math.min(60, FlashbackConfig.freecamSpeed + 2), true, 0);
                lbls.add(new Lbl(x0, r3 + 6, "Anahtar kare: serbest kamerada konumu seç, ] ile ekle. İki+ kareyle yol oynatılabilir.", Ui.MUTED));
            }
            case EDIT -> {
                add(cx, r1, 100, "⟦ Başlangıç", () -> say(ReplayManager.setTrimStart() ? "Başlangıç buraya alındı." : "Başlangıç bitişten önce olmalı.", Ui.OK), true, 0);
                add(cx, r1, 90, "Bitiş ⟧", () -> say(ReplayManager.setTrimEnd() ? "Bitiş buraya alındı." : "Bitiş başlangıçtan sonra olmalı.", Ui.OK), true, 0);
                add(cx, r1, 88, "Sıfırla", () -> { ReplayManager.resetTrim(); say("Kırpma sıfırlandı.", Ui.OK); }, true, 0);
                add(cx, r1, 84, "+ İşaret", () -> { ReplayManager.addMarker(); say("İşaret eklendi.", Ui.OK); }, true, 0);
                add(cx, r1, 84, "− İşaret", () -> say(ReplayManager.removeMarkerNear() ? "İşaret silindi." : "Yakında işaret yok (±3 sn).", Ui.WARN), hasMk, 0);
                cx[0] = x0;
                add(cx, r2, 130, "Yeniden Adlandır", this::rename, f != null, 0);
                boolean dirty = ReplayManager.isDirty();
                add(cx, r2, 100, dirty ? "Kaydet *" : "Kaydet", () -> {
                    say("Kaydediliyor…", Ui.MUTED);
                    ReplayManager.save(m -> say(m, m.startsWith("Hata") ? Ui.DANGER : Ui.OK));
                }, dirty, dirty ? Ui.ACCENT : 0);
                lbls.add(new Lbl(x0, r3 + 6, "Kırpma yalnızca izlenecek aralığı daraltır; Kaydet ile dosyaya yazılır.", Ui.MUTED));
            }
            case WORLD -> {
                lbls.add(new Lbl(x0, r1 + 7, "Hava durumu", Ui.TEXT));
                cx[0] = x0 + 92;
                for (int i = 0; i < WEATHER.length; i++) {
                    int k = i;
                    add(cx, r1, 80, WEATHER[i], () -> ReplayManager.setWeather(k), true, ReplayManager.weather == i ? Ui.ACCENT : 0);
                }
                lbls.add(new Lbl(x0, r2 + 7, "Oyun modu", Ui.TEXT));
                cx[0] = x0 + 92;
                for (int i = 0; i < MODES.length; i++) {
                    int k = i;
                    add(cx, r2, 84, MODE_NAMES[i], () -> ReplayManager.setGameMode(MODES[k]), true, ReplayManager.forcedMode == MODES[i] ? Ui.ACCENT : 0);
                }
                lbls.add(new Lbl(x0, r3 + 7, "Görünüm", Ui.TEXT));
                cx[0] = x0 + 92;
                add(cx, r3, 100, "HUD: " + (ReplayManager.hudHidden() ? "Gizli" : "Açık"), ReplayManager::toggleHud, true, 0);
                cx[0] += 10;
                add(cx, r3, 26, "-", () -> ReplayManager.changeFov(-5), true, 0);
                add(cx, r3, 70, "FOV " + ReplayManager.fov(), () -> {}, false, 0);
                add(cx, r3, 26, "+", () -> ReplayManager.changeFov(5), true, 0);
                lbls.add(new Lbl(cx[0] + 8, r3 + 7, "“Kayıttaki” seçilince sahne yeniden yüklenir.", Ui.MUTED));
            }
            case EXPORT -> {
                lbls.add(new Lbl(x0, r1 + 7, "Çözünürlük", Ui.TEXT));
                cx[0] = x0 + 84;
                for (int i = 0; i < RES.length; i++) {
                    int k = i;
                    add(cx, r1, 56, RES_NAMES[i], () -> { ExportManager.width = RES[k][0]; ExportManager.height = RES[k][1]; },
                            !exporting, ExportManager.width == RES[i][0] ? Ui.ACCENT : 0);
                }
                lbls.add(new Lbl(cx[0] + 10, r1 + 7, "FPS", Ui.TEXT));
                cx[0] += 40;
                for (int v : FPS) add(cx, r1, 40, String.valueOf(v), () -> ExportManager.fps = v, !exporting, ExportManager.fps == v ? Ui.ACCENT : 0);

                lbls.add(new Lbl(x0, r2 + 7, "Kalite", Ui.TEXT));
                cx[0] = x0 + 84;
                for (ExportManager.Quality q : ExportManager.Quality.values())
                    add(cx, r2, 60, q.label, () -> ExportManager.quality = q, !exporting, ExportManager.quality == q ? Ui.ACCENT : 0);
                cx[0] += 8;
                add(cx, r2, 70, ExportManager.h265 ? "H.265" : "H.264", () -> ExportManager.h265 = !ExportManager.h265, !exporting && ExportManager.backend == ExportManager.Backend.FFMPEG, 0);
                add(cx, r2, 96, "HUD: " + (ExportManager.hideHud ? "Gizle" : "Göster"), () -> ExportManager.hideHud = !ExportManager.hideHud, !exporting, 0);
                add(cx, r2, 150, "Gerçek çöz.: " + (ExportManager.forceRes ? "Açık" : "Kapalı"), () -> ExportManager.forceRes = !ExportManager.forceRes, !exporting, 0);

                lbls.add(new Lbl(x0, r3 + 7, "Render hızı", Ui.TEXT));
                cx[0] = x0 + 84;
                add(cx, r3, 56, sp(ExportManager.renderSpeed), () -> {
                    int i = 0;
                    for (int j = 0; j < RSPEED.length; j++) if (RSPEED[j] == ExportManager.renderSpeed) i = j;
                    ExportManager.renderSpeed = RSPEED[(i + 1) % RSPEED.length];
                }, !exporting, 0);
                add(cx, r3, 190, "Çıktı: " + ExportManager.backend.label, () -> {
                    ExportManager.Backend[] bs = ExportManager.Backend.values();
                    ExportManager.backend = bs[(ExportManager.backend.ordinal() + 1) % bs.length];
                }, !exporting, Ui.ACCENT_DIM);
                long frames = ReplayManager.durationMs() * ExportManager.fps / 1000;
                lbls.add(new Lbl(cx[0] + 6, r3 + 7, "≈ " + frames + " kare", Ui.MUTED));
                if (exporting)
                    btns.add(new Btn(new Rect(panel.right() - 12 - 150, r3, 150, 22), "■ İptal", () -> ExportManager.cancel(client), true, Ui.DANGER));
                else
                    btns.add(new Btn(new Rect(panel.right() - 12 - 150, r3, 150, 22), "▶ Dışa Aktar", () -> ExportManager.start(client), ReplayManager.inWorld(), Ui.OK));
            }
        }
    }

    private void rename() {
        ReplayFile f = ReplayManager.file();
        if (f == null) return;
        client.setScreen(new RenameScreen(this, f.meta.name, n -> ReplayManager.rename(n, m -> say(m, m.startsWith("Hata") ? Ui.DANGER : Ui.OK))));
    }

    private void jumpMarker(int dir) {
        ReplayFile f = ReplayManager.file();
        if (f == null) return;
        long cur = ReplayManager.absMs(), best = -1;
        for (ReplayFile.Marker m : f.meta.markers) {
            if (m.ms < ReplayManager.startAbs() || m.ms > ReplayManager.endAbs()) continue;
            if (dir > 0 && m.ms > cur + 500 && (best < 0 || m.ms < best)) best = m.ms;
            if (dir < 0 && m.ms < cur - 1500 && m.ms > best) best = m.ms;
        }
        if (best >= 0) ReplayManager.seekAbs(best);
    }

    private int xFor(long rel) { return timeline.x() + (int) (timeline.w() * Math.max(0, Math.min(1.0, (double) rel / dur()))); }
    private long msFor(double mx) { return (long) (dur() * Math.max(0, Math.min(1, (mx - timeline.x()) / timeline.w()))); }

    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        Ui.panel(c, panel, Ui.PANEL);
        c.fill(panel.x() + 1, panel.y() + 1, panel.right() - 1, panel.y() + 3, Ui.ACCENT);

        ReplayFile f = ReplayManager.file();
        Ui.text(c, textRenderer, Ui.clip(textRenderer, f != null ? f.meta.name : "Tekrar", panel.w() / 2), panel.x() + 12, panel.y() + 10, Ui.TEXT);
        long cur = dragging ? dragMs : ReplayManager.currentMs();
        String t = Ui.time(cur) + " / " + Ui.time(ReplayManager.durationMs());
        Ui.text(c, textRenderer, t, panel.right() - 12 - textRenderer.getWidth(t), panel.y() + 10, Ui.TEXT);
        String state = ExportManager.isActive() ? "DIŞA AKTARILIYOR…" : ReplayManager.ended() ? "BİTTİ" : ReplayManager.isPaused() ? "DURAKLATILDI" : "OYNATILIYOR  " + ReplayManager.speed() + "x";
        Ui.text(c, textRenderer, state, panel.x() + panel.w() / 2 - textRenderer.getWidth(state) / 2, panel.y() + 24,
                ExportManager.isActive() ? Ui.DANGER : ReplayManager.isPaused() ? Ui.WARN : Ui.OK);

        // zaman çizelgesi
        c.fill(timeline.x(), timeline.y() + 5, timeline.right(), timeline.y() + 11, 0xFF0F1118);
        int px = xFor(cur);
        c.fill(timeline.x(), timeline.y() + 5, px, timeline.y() + 11, Ui.ACCENT);
        if (ReplayManager.path.size() >= 2)
            c.fill(xFor(ReplayManager.rel(ReplayManager.path.start())), timeline.y() + 12, xFor(ReplayManager.rel(ReplayManager.path.end())), timeline.y() + 14, Ui.OK);
        String tip = null;
        int tipX = 0;
        boolean overBar = my >= timeline.y() - 4 && my <= timeline.bottom() + 4;
        if (f != null) for (ReplayFile.Marker m : f.meta.markers) {
            long r = ReplayManager.rel(m.ms);
            if (r < 0 || r > dur()) continue;
            int x = xFor(r);
            c.fill(x - 1, timeline.y() + 1, x + 1, timeline.y() + 15, Ui.WARN);
            if (Math.abs(mx - x) <= 3 && overBar) { tip = Ui.time(r) + " " + m.label; tipX = x; }
        }
        for (CameraPath.Keyframe k : ReplayManager.path.all()) {
            long r = ReplayManager.rel(k.ms());
            if (r < 0 || r > dur()) continue;
            int x = xFor(r);
            c.fill(x - 3, timeline.y() + 3, x + 3, timeline.y() + 9, 0xFFFFFFFF);
            c.fill(x - 2, timeline.y() + 4, x + 2, timeline.y() + 8, Ui.OK);
            if (Math.abs(mx - x) <= 4 && overBar) { tip = "Anahtar kare " + Ui.time(r); tipX = x; }
        }
        c.fill(px - 1, timeline.y(), px + 1, timeline.bottom(), 0xFFFFFFFF);
        if (timeline.contains(mx, my) && tip == null) { tip = Ui.time(msFor(mx)); tipX = mx; }
        if (tip != null) {
            int tw = textRenderer.getWidth(tip) + 8;
            int tx = Math.max(panel.x(), Math.min(panel.right() - tw, tipX - tw / 2));
            c.fill(tx, timeline.y() - 16, tx + tw, timeline.y() - 4, 0xFF000000);
            Ui.text(c, textRenderer, tip, tx + 4, timeline.y() - 14, Ui.TEXT);
        }

        rebuild();
        for (Btn b : btns) Ui.button(c, textRenderer, b.r, b.label, mx, my, b.enabled, b.accent);
        for (Lbl l : lbls) Ui.text(c, textRenderer, Ui.clip(textRenderer, l.s, panel.right() - l.x - 12), l.x, l.y, l.color);
        Ui.text(c, textRenderer, Ui.clip(textRenderer, status.isEmpty()
                        ? "Boşluk: duraklat • ←/→: ±5 sn (Shift ±30) • Serbest kamerada WASD/Space/Shift + fare"
                        : status, panel.w() - 24),
                panel.x() + 12, panel.bottom() - 11, status.isEmpty() ? Ui.MUTED : statusColor);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double x = click.x(), y = click.y();
        if (click.button() != 0) return super.mouseClicked(click, doubled);
        if (new Rect(timeline.x(), timeline.y() - 4, timeline.w(), timeline.h() + 8).contains(x, y)) {
            dragging = true;
            dragMs = msFor(x);
            return true;
        }
        rebuild();
        for (Btn b : new ArrayList<>(btns)) if (b.enabled && b.r.contains(x, y)) { b.action.run(); return true; }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(Click click, double dx, double dy) {
        if (dragging) { dragMs = msFor(click.x()); return true; }
        return super.mouseDragged(click, dx, dy);
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (dragging) {
            dragging = false;
            ReplayManager.seekTo(msFor(click.x()));
            return true;
        }
        return super.mouseReleased(click);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        boolean shift = (input.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0;
        switch (input.key()) {
            case GLFW.GLFW_KEY_SPACE -> { ReplayManager.togglePause(); return true; }
            case GLFW.GLFW_KEY_LEFT -> { ReplayManager.skip(shift ? -30_000 : -5_000); return true; }
            case GLFW.GLFW_KEY_RIGHT -> { ReplayManager.skip(shift ? 30_000 : 5_000); return true; }
            default -> { return super.keyPressed(input); }
        }
    }

    @Override
    public void close() {
        FlashbackConfig.save();
        super.close();
    }

    @Override
    public boolean shouldPause() { return false; }
}
