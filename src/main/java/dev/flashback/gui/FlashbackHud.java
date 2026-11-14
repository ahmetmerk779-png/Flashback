package dev.flashback.gui;

import dev.flashback.FlashbackConfig;
import dev.flashback.record.RecordingManager;
import dev.flashback.replay.ReplayManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

public final class FlashbackHud {
    public static void render(DrawContext c) {
        if (!FlashbackConfig.showHud || dev.flashback.replay.ExportManager.isActive()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        TextRenderer f = mc.textRenderer;

        if (ReplayManager.inWorld()) {
            if (mc.currentScreen != null) return; // kontrol paneli kendi çubuğunu çizer
            int w = Math.min(c.getScaledWindowWidth() - 40, 420);
            int x = (c.getScaledWindowWidth() - w) / 2, y = c.getScaledWindowHeight() - 44;
            c.fill(x - 6, y - 8, x + w + 6, y + 26, 0xA0000000);
            long cur = ReplayManager.currentMs(), dur = Math.max(1, ReplayManager.durationMs());
            c.fill(x, y + 14, x + w, y + 18, 0xFF0F1118);
            c.fill(x, y + 14, x + (int) (w * Math.min(1.0, (double) cur / dur)), y + 18, Ui.ACCENT);
            String state = ReplayManager.isPaused() ? "❚❚" : "▶ " + ReplayManager.speed() + "x";
            Ui.text(c, f, state + "   " + Ui.time(cur) + " / " + Ui.time(dur), x, y, ReplayManager.isPaused() ? Ui.WARN : Ui.TEXT);
            String cam = (ReplayManager.freecamActive() ? "Serbest kamera" : "Oyuncu kamerası")
                    + (ReplayManager.pathPlaying() ? "  •  Yol" : "");
            Ui.text(c, f, cam, x + w - f.getWidth(cam), y, Ui.MUTED);
            Ui.text(c, f, "F7: kontroller", x, y + 21, 0xFF6C7490);
            return;
        }

        if (RecordingManager.isRecording()) {
            boolean blink = (System.currentTimeMillis() / 600) % 2 == 0;
            String s = "REC  " + Ui.time(RecordingManager.elapsedMs()) + "  •  " + Ui.size(RecordingManager.bytes());
            int x = 6, y = 6, w = f.getWidth(s) + 22;
            c.fill(x, y, x + w, y + 16, 0xA0000000);
            if (blink) c.fill(x + 5, y + 4, x + 13, y + 12, Ui.DANGER);
            Ui.text(c, f, s, x + 17, y + 4, Ui.TEXT);
        }
    }

    private FlashbackHud() {}
}
