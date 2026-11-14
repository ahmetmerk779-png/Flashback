package dev.flashback.gui;

import dev.flashback.gui.Ui.Rect;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;

public class RenameScreen extends Screen {
    private final Screen parent;
    private final String initial;
    private final Consumer<String> onOk;
    private TextFieldWidget field;
    private Rect panel, bOk, bCancel;

    public RenameScreen(Screen parent, String initial, Consumer<String> onOk) {
        super(Text.literal("Yeniden Adlandır"));
        this.parent = parent;
        this.initial = initial;
        this.onOk = onOk;
    }

    @Override
    protected void init() {
        int w = 320, h = 110;
        panel = new Rect((width - w) / 2, (height - h) / 2, w, h);
        field = new TextFieldWidget(textRenderer, panel.x() + 12, panel.y() + 38, w - 24, 20, Text.literal("Ad"));
        field.setMaxLength(60);
        field.setText(initial);
        addDrawableChild(field);
        setInitialFocus(field);
        bOk = new Rect(panel.x() + 12, panel.bottom() - 34, (w - 30) / 2, 22);
        bCancel = new Rect(bOk.right() + 6, panel.bottom() - 34, (w - 30) / 2, 22);
    }

    private void ok() {
        String s = field.getText().trim();
        if (!s.isEmpty()) onOk.accept(s);
        client.setScreen(parent);
    }

    @Override
    public void render(DrawContext c, int mx, int my, float d) {
        if (parent instanceof ReplayListScreen) c.fill(0, 0, width, height, Ui.BG);
        else c.fill(0, 0, width, height, 0x80000000);
        Ui.panel(c, panel, Ui.PANEL);
        c.fill(panel.x() + 1, panel.y() + 1, panel.right() - 1, panel.y() + 3, Ui.ACCENT);
        Ui.text(c, textRenderer, "Kaydın yeni adı", panel.x() + 12, panel.y() + 14, Ui.TEXT);
        field.render(c, mx, my, d);
        Ui.button(c, textRenderer, bOk, "Kaydet", mx, my, true, Ui.ACCENT);
        Ui.button(c, textRenderer, bCancel, "İptal", mx, my, true, 0);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (bOk.contains(click.x(), click.y())) { ok(); return true; }
        if (bCancel.contains(click.x(), click.y())) { close(); return true; }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (input.key() == GLFW.GLFW_KEY_ENTER || input.key() == GLFW.GLFW_KEY_KP_ENTER) { ok(); return true; }
        return super.keyPressed(input);
    }

    @Override
    public void close() { client.setScreen(parent); }

    @Override
    public boolean shouldPause() { return false; }
}
