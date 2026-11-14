package dev.flashback.gui;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/** Ortak tema ve çizim yardımcıları. */
public final class Ui {
    public static final int BG = 0xB0080A10, PANEL = 0xF01A1D26, PANEL_2 = 0xF0222633, BORDER = 0xFF2C3140;
    public static final int ACCENT = 0xFF5B8CFF, ACCENT_DIM = 0xFF3A5BAA;
    public static final int TEXT = 0xFFE6E9F2, MUTED = 0xFF8A91A6;
    public static final int DANGER = 0xFFE5534B, OK = 0xFF3FB950, WARN = 0xFFE3B341;

    public record Rect(int x, int y, int w, int h) {
        public boolean contains(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
        public int right() { return x + w; }
        public int bottom() { return y + h; }
    }

    public static void panel(DrawContext c, Rect r, int fill) {
        c.fill(r.x, r.y, r.right(), r.bottom(), BORDER);
        c.fill(r.x + 1, r.y + 1, r.right() - 1, r.bottom() - 1, fill);
    }

    public static void text(DrawContext c, TextRenderer f, String s, int x, int y, int color) {
        c.drawText(f, Text.literal(s), x, y, color, true);
    }

    public static void centered(DrawContext c, TextRenderer f, String s, int cx, int y, int color) {
        c.drawText(f, Text.literal(s), cx - f.getWidth(s) / 2, y, color, true);
    }

    public static String clip(TextRenderer f, String s, int maxW) {
        if (f.getWidth(s) <= maxW) return s;
        while (s.length() > 1 && f.getWidth(s + "…") > maxW) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    public static void button(DrawContext c, TextRenderer f, Rect r, String label, double mx, double my,
                              boolean enabled, int accent) {
        boolean hover = enabled && r.contains(mx, my);
        int base = accent != 0 ? (hover ? accent : dim(accent)) : (hover ? 0xFF2F3548 : 0xFF252A3A);
        if (!enabled) base = 0xFF1B1E29;
        c.fill(r.x, r.y, r.right(), r.bottom(), BORDER);
        c.fill(r.x + 1, r.y + 1, r.right() - 1, r.bottom() - 1, base);
        int col = enabled ? TEXT : 0xFF5A6075;
        centered(c, f, label, r.x + r.w / 2, r.y + (r.h - 8) / 2, col);
    }

    private static int dim(int argb) {
        int r = ((argb >> 16) & 0xFF) * 3 / 4, g = ((argb >> 8) & 0xFF) * 3 / 4, b = (argb & 0xFF) * 3 / 4;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }


    /** 20x20 düğme üzerine çizilen piksel kamera simgesi; kayıttayken kırmızı nokta yanıp söner. */
    public static void cameraIcon(DrawContext c, int x, int y, boolean recording) {
        c.fill(x + 6, y + 5, x + 11, y + 7, 0xFFE6E9F2);          // üst çıkıntı
        c.fill(x + 3, y + 7, x + 17, y + 15, 0xFFE6E9F2);         // gövde
        c.fill(x + 8, y + 8, x + 13, y + 14, 0xFF2B3040);         // lens dış
        c.fill(x + 9, y + 9, x + 12, y + 13, ACCENT);             // lens iç
        c.fill(x + 14, y + 8, x + 16, y + 10, 0xFFFFD34D);        // flaş
        if (recording && (System.currentTimeMillis() / 500) % 2 == 0)
            c.fill(x + 14, y + 2, x + 18, y + 6, DANGER);         // REC noktası
    }

    public static String time(long ms) {
        long s = Math.max(0, ms) / 1000, h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, sec) : String.format("%02d:%02d", m, sec);
    }

    public static String size(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format("%.1f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format("%.1f MB", b / 1048576.0);
        return String.format("%.2f GB", b / 1073741824.0);
    }

    private Ui() {}
}
