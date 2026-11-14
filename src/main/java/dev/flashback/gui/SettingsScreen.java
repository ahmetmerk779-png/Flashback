package dev.flashback.gui;

import dev.flashback.FlashbackConfig;
import dev.flashback.gui.Ui.Rect;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

public class SettingsScreen extends Screen {
    private final Screen parent;
    private Rect panel, tAuto, tHud, tSmooth, spMinus, spPlus, bDone;

    public SettingsScreen(Screen parent) {
        super(Text.literal("Flashback Ayarları"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int w = 360, h = 200;
        panel = new Rect((width - w) / 2, (height - h) / 2, w, h);
        int bx = panel.right() - 120, y = panel.y() + 50;
        tAuto = new Rect(bx, y, 108, 22);
        tHud = new Rect(bx, y + 32, 108, 22);
        tSmooth = new Rect(bx, y + 64, 108, 22);
        spMinus = new Rect(bx, y + 96, 26, 22); spPlus = new Rect(bx + 82, y + 96, 26, 22);
        
        bDone = new Rect(panel.x() + 12, panel.bottom() - 34, w - 24, 24);
    }

    @Override
    public void render(DrawContext c, int mx, int my, float d) {
        c.fill(0, 0, width, height, Ui.BG);
        Ui.panel(c, panel, Ui.PANEL);
        c.fill(panel.x() + 1, panel.y() + 1, panel.right() - 1, panel.y() + 3, Ui.ACCENT);
        Ui.text(c, textRenderer, "Ayarlar", panel.x() + 12, panel.y() + 14, Ui.TEXT);

        int lx = panel.x() + 14, y = panel.y() + 55;
        Ui.text(c, textRenderer, "Kayıt tamponu", lx, y, Ui.TEXT);
        Ui.text(c, textRenderer, "HUD göstergeleri", lx, y + 32, Ui.TEXT);
        Ui.text(c, textRenderer, "Yumuşak kamera yolu", lx, y + 64, Ui.TEXT);
        Ui.text(c, textRenderer, "Serbest kamera hızı", lx, y + 96 + 3, Ui.TEXT);

        toggle(c, tAuto, FlashbackConfig.autoRecord, mx, my);
        toggle(c, tHud, FlashbackConfig.showHud, mx, my);
        toggle(c, tSmooth, FlashbackConfig.smoothPath, mx, my);
        Ui.button(c, textRenderer, spMinus, "-", mx, my, true, 0);
        Ui.button(c, textRenderer, spPlus, "+", mx, my, true, 0);
        Ui.centered(c, textRenderer, String.format("%.0f b/sn", FlashbackConfig.freecamSpeed), spMinus.x() + 54, spMinus.y() + 7, Ui.TEXT);
        Ui.text(c, textRenderer, "Tampon kapalıysa kayıt başlatılamaz.", lx, panel.bottom() - 52, Ui.MUTED);
        Ui.button(c, textRenderer, bDone, "Bitti", mx, my, true, Ui.ACCENT);
    }

    private void toggle(DrawContext c, Rect r, boolean on, int mx, int my) {
        Ui.button(c, textRenderer, r, on ? "AÇIK" : "KAPALI", mx, my, true, on ? Ui.OK : 0);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double x = click.x(), y = click.y();
        if (tAuto.contains(x, y)) FlashbackConfig.autoRecord = !FlashbackConfig.autoRecord;
        else if (tHud.contains(x, y)) FlashbackConfig.showHud = !FlashbackConfig.showHud;
        else if (tSmooth.contains(x, y)) FlashbackConfig.smoothPath = !FlashbackConfig.smoothPath;
        else if (spMinus.contains(x, y)) FlashbackConfig.freecamSpeed = Math.max(1, FlashbackConfig.freecamSpeed - 1);
        else if (spPlus.contains(x, y)) FlashbackConfig.freecamSpeed = Math.min(60, FlashbackConfig.freecamSpeed + 1);
        else if (bDone.contains(x, y)) { close(); return true; }
        else return super.mouseClicked(click, doubled);
        return true;
    }

    @Override
    public void close() {
        FlashbackConfig.save();
        client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() { return false; }
}
