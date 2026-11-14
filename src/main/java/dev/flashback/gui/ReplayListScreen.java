package dev.flashback.gui;

import dev.flashback.gui.Ui.Rect;
import dev.flashback.replay.ReplayFile;
import dev.flashback.replay.ReplayManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class ReplayListScreen extends Screen {
    private record Entry(Path path, ReplayFile.Meta meta) {}

    private static final int ROW_H = 46;
    private final Screen parent;
    private final List<Entry> entries = new ArrayList<>();
    private int selected = -1;
    private double scroll;
    private String status = "";
    private int statusColor = Ui.MUTED;
    private boolean confirmDelete, loading;

    private Rect panel, list, detail, bPlay, bRename, bDelete, bFolder, bClose, bSettings;

    public ReplayListScreen(Screen parent) {
        super(Text.literal("Flashback"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        reload();
        int w = Math.min(width - 24, 760), h = height - 24;
        panel = new Rect((width - w) / 2, 12, w, h);
        int detW = Math.min(230, w / 3);
        list = new Rect(panel.x() + 10, panel.y() + 44, w - detW - 30, h - 44 - 58);
        detail = new Rect(list.right() + 10, list.y(), detW, list.h());
        int by = panel.bottom() - 40, bw = (w - 20 - 4 * 6) / 5;
        int x = panel.x() + 10;
        bPlay = new Rect(x, by, bw, 28);
        bRename = new Rect(x += bw + 6, by, bw, 28);
        bDelete = new Rect(x += bw + 6, by, bw, 28);
        bFolder = new Rect(x += bw + 6, by, bw, 28);
        bClose = new Rect(x + bw + 6, by, bw, 28);
        bSettings = new Rect(panel.right() - 90, panel.y() + 10, 80, 20);
    }

    private void reload() {
        entries.clear();
        for (Path p : ReplayFile.list()) {
            try {
                ReplayFile.Meta m = ReplayFile.readMeta(p);
                if (m != null) entries.add(new Entry(p, m));
            } catch (IOException ignored) {}
        }
        selected = entries.isEmpty() ? -1 : Math.min(Math.max(selected, 0), entries.size() - 1);
        confirmDelete = false;
    }

    private void setStatus(String s, int color) { status = s; statusColor = color; }

    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        c.fill(0, 0, width, height, Ui.BG);
        Ui.panel(c, panel, Ui.PANEL);
        c.fill(panel.x() + 1, panel.y() + 1, panel.right() - 1, panel.y() + 3, Ui.ACCENT);
        Ui.text(c, textRenderer, "Flashback", panel.x() + 12, panel.y() + 14, Ui.TEXT);
        Ui.text(c, textRenderer, "Kayıtlarım  •  " + entries.size() + " kayıt", panel.x() + 12 + 62, panel.y() + 14, Ui.MUTED);

        // liste
        Ui.panel(c, list, Ui.PANEL_2);
        c.enableScissor(list.x() + 1, list.y() + 1, list.right() - 1, list.bottom() - 1);
        if (entries.isEmpty()) {
            Ui.centered(c, textRenderer, "Henüz kayıt yok.", list.x() + list.w() / 2, list.y() + 30, Ui.MUTED);
            Ui.centered(c, textRenderer, "Bir sunucuda ESC menüsündeki kamera düğmesine bas.", list.x() + list.w() / 2, list.y() + 44, Ui.MUTED);
        }
        SimpleDateFormat df = new SimpleDateFormat("dd.MM.yyyy HH:mm");
        for (int i = 0; i < entries.size(); i++) {
            int ry = list.y() + 2 + i * ROW_H - (int) scroll;
            if (ry + ROW_H < list.y() || ry > list.bottom()) continue;
            ReplayFile.Meta m = entries.get(i).meta();
            boolean sel = i == selected, hov = list.contains(mx, my) && my >= ry && my < ry + ROW_H - 2;
            c.fill(list.x() + 3, ry, list.right() - 3, ry + ROW_H - 3, sel ? 0xFF2B3A66 : hov ? 0xFF2A2F41 : 0xFF1E2230);
            if (sel) c.fill(list.x() + 3, ry, list.x() + 6, ry + ROW_H - 3, Ui.ACCENT);
            int maxW = list.w() - 28;
            Ui.text(c, textRenderer, Ui.clip(textRenderer, m.server.isBlank() ? m.name : m.server, maxW), list.x() + 14, ry + 6, Ui.TEXT);
            Ui.text(c, textRenderer, df.format(new Date(m.date)) + "  •  " + Ui.time(m.durationMs), list.x() + 14, ry + 19, Ui.MUTED);
            Ui.text(c, textRenderer, m.player + "  •  " + Ui.size(m.sizeBytes) + "  •  " + m.mcVersion
                    + (m.markers.isEmpty() ? "" : "  •  " + m.markers.size() + " işaret"), list.x() + 14, ry + 31, 0xFF6C7490);
        }
        c.disableScissor();
        int total = entries.size() * ROW_H;
        if (total > list.h()) {
            int bh = Math.max(20, list.h() * list.h() / total);
            int by = list.y() + (int) ((list.h() - bh) * (scroll / (double) (total - list.h())));
            c.fill(list.right() - 4, by, list.right() - 2, by + bh, Ui.ACCENT_DIM);
        }

        // ayrıntı paneli
        Ui.panel(c, detail, Ui.PANEL_2);
        if (selected >= 0) renderDetail(c, entries.get(selected).meta(), df);
        else Ui.centered(c, textRenderer, "Bir kayıt seç", detail.x() + detail.w() / 2, detail.y() + 20, Ui.MUTED);

        // alt butonlar
        boolean has = selected >= 0 && !loading;
        Ui.button(c, textRenderer, bPlay, loading ? "Yükleniyor…" : "▶ Aç / Düzenle", mx, my, has, Ui.ACCENT);
        Ui.button(c, textRenderer, bRename, "Yeniden Adlandır", mx, my, selected >= 0, 0);
        Ui.button(c, textRenderer, bDelete, confirmDelete ? "Emin misin?" : "Sil", mx, my, selected >= 0, confirmDelete ? Ui.DANGER : 0);
        Ui.button(c, textRenderer, bFolder, "Klasörü Aç", mx, my, true, 0);
        Ui.button(c, textRenderer, bClose, "Kapat", mx, my, true, 0);
        Ui.button(c, textRenderer, bSettings, "Ayarlar", mx, my, true, 0);
        if (!status.isEmpty()) Ui.text(c, textRenderer, Ui.clip(textRenderer, status, panel.w() - 24), panel.x() + 12, panel.bottom() - 54, statusColor);
    }

    private void renderDetail(DrawContext c, ReplayFile.Meta m, SimpleDateFormat df) {
        int x = detail.x() + 8, y = detail.y() + 8, w = detail.w() - 16;
        Ui.text(c, textRenderer, Ui.clip(textRenderer, m.name, w), x, y, Ui.TEXT); y += 14;
        String[][] rows = {
                {"Sunucu", m.server}, {"Oyuncu", m.player}, {"Tarih", df.format(new Date(m.date))},
                {"Süre", Ui.time(m.durationMs)}, {"Boyut", Ui.size(m.sizeBytes)}, {"Kare", String.valueOf(m.frames)},
                {"Sürüm", m.mcVersion}};
        for (String[] r : rows) {
            Ui.text(c, textRenderer, r[0], x, y, Ui.MUTED);
            Ui.text(c, textRenderer, Ui.clip(textRenderer, r[1], w - 50), x + 48, y, Ui.TEXT);
            y += 12;
        }
        if (!m.mcVersion.equals(MinecraftClient.getInstance().getGameVersion())) {
            y += 4;
            Ui.text(c, textRenderer, "⚠ Sürüm farklı!", x, y, Ui.WARN); y += 12;
            Ui.text(c, textRenderer, Ui.clip(textRenderer, "Oynatma bozulabilir.", w), x, y, Ui.WARN); y += 12;
        }
        if (!m.markers.isEmpty()) {
            y += 6;
            Ui.text(c, textRenderer, "İşaretler", x, y, Ui.ACCENT); y += 12;
            for (int i = 0; i < Math.min(8, m.markers.size()); i++) {
                ReplayFile.Marker mk = m.markers.get(i);
                Ui.text(c, textRenderer, Ui.time(mk.ms) + "  " + Ui.clip(textRenderer, mk.label, w - 44), x, y, Ui.TEXT);
                y += 11;
            }
        }
    }

    // ---- girdi (1.21.9+ girdi API: Click) ----
    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double mx = click.x(), my = click.y();
        if (click.button() != 0) return super.mouseClicked(click, doubled);

        if (list.contains(mx, my)) {
            int i = (int) ((my - list.y() - 2 + scroll) / ROW_H);
            if (i >= 0 && i < entries.size()) {
                selected = i;
                confirmDelete = false;
                if (doubled) play();
            }
            return true;
        }
        if (bPlay.contains(mx, my) && selected >= 0 && !loading) { play(); return true; }
        if (bFolder.contains(mx, my)) { Util.getOperatingSystem().open(ReplayFile.dir()); return true; }
        if (bDelete.contains(mx, my) && selected >= 0) {
            if (!confirmDelete) { confirmDelete = true; return true; }
            try {
                Files.deleteIfExists(entries.get(selected).path());
                setStatus("Kayıt silindi.", Ui.OK);
            } catch (IOException e) { setStatus("Silinemedi: " + e.getMessage(), Ui.DANGER); }
            reload();
            return true;
        }
        if (bRename.contains(mx, my) && selected >= 0) {
            Entry e = entries.get(selected);
            client.setScreen(new RenameScreen(this, e.meta().name, n -> {
                try { ReplayFile.renameOnDisk(e.path(), n); setStatus("Ad değiştirildi.", Ui.OK); }
                catch (IOException ex) { setStatus("Hata: " + ex.getMessage(), Ui.DANGER); }
                reload();
            }));
            return true;
        }
        if (bSettings.contains(mx, my)) { client.setScreen(new SettingsScreen(this)); return true; }
        if (bClose.contains(mx, my)) { close(); return true; }
        confirmDelete = false;
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        double max = Math.max(0, entries.size() * ROW_H - list.h());
        scroll = Math.max(0, Math.min(max, scroll - v * 24));
        return true;
    }

    private void play() {
        if (selected < 0) return;
        if (client.world != null) { setStatus("Önce mevcut dünyadan çık.", Ui.DANGER); return; }
        Entry e = entries.get(selected);
        loading = true;
        setStatus("Kayıt belleğe yükleniyor…", Ui.MUTED);
        CompletableFuture.supplyAsync(() -> {
            try { return ReplayFile.load(e.path()); } catch (IOException ex) { throw new RuntimeException(ex); }
        }).whenComplete((rf, err) -> client.execute(() -> {
            loading = false;
            if (err != null) { setStatus("Yüklenemedi: " + err.getMessage(), Ui.DANGER); return; }
            ReplayManager.begin(rf);
        }));
    }

    @Override
    public void close() { client.setScreen(parent); }

    @Override
    public boolean shouldPause() { return false; }
}
