package dev.flashback;

import dev.flashback.gui.FlashbackHud;
import dev.flashback.gui.ReplayControlsScreen;
import dev.flashback.gui.ReplayListScreen;
import dev.flashback.gui.Ui;
import dev.flashback.record.RecordingManager;
import dev.flashback.replay.ExportManager;
import dev.flashback.replay.ReplayFile;
import dev.flashback.replay.ReplayManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FlashbackMod implements ClientModInitializer {
    public static final String ID = "flashback";
    public static final Logger LOGGER = LoggerFactory.getLogger("Flashback");

    private static KeyBinding kList, kControls, kMarker, kRecord, kPause, kKeyframe, kPath;

    private static KeyBinding key(String name, int code, KeyBinding.Category cat) {
        return KeyBindingHelper.registerKeyBinding(new KeyBinding("key." + ID + "." + name, InputUtil.Type.KEYSYM, code, cat));
    }

    @Override
    public void onInitializeClient() {
        FlashbackConfig.load();
        ReplayFile.cleanTmp();

        KeyBinding.Category cat = KeyBinding.Category.create(Identifier.of(ID, "main"));
        kList = key("list", GLFW.GLFW_KEY_F6, cat);
        kControls = key("controls", GLFW.GLFW_KEY_F7, cat);
        kMarker = key("marker", GLFW.GLFW_KEY_F8, cat);
        kRecord = key("record", GLFW.GLFW_KEY_F9, cat);
        kPause = key("pause", GLFW.GLFW_KEY_F10, cat);
        kKeyframe = key("keyframe", GLFW.GLFW_KEY_RIGHT_BRACKET, cat);
        kPath = key("path", GLFW.GLFW_KEY_LEFT_BRACKET, cat);

        HudElementRegistry.addLast(Identifier.of(ID, "hud"), (ctx, tick) -> FlashbackHud.render(ctx));
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);

        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
            if (!ReplayManager.isActive()) RecordingManager.onJoin(mc);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
            RecordingManager.onDisconnect();
            ReplayManager.onDisconnect(mc);
        });

        ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
            int bx = w / 2 + 108, by = h / 2 - 10; // menü düğmelerinin sağ-orta tarafı

            if (screen instanceof TitleScreen) {
                // Ana menü: kamera düğmesi -> kayıt listesi
                ButtonWidget b = ButtonWidget.builder(Text.empty(), x -> mc.setScreen(new ReplayListScreen(screen)))
                        .dimensions(bx, by, 20, 20)
                        .tooltip(Tooltip.of(Text.literal("Kayıtlarım (Flashback)")))
                        .build();
                Screens.getButtons(screen).add(b);
                ScreenEvents.afterRender(screen).register((s, ctx, mx, my, d) -> Ui.cameraIcon(ctx, bx, by, false));

            } else if (screen instanceof GameMenuScreen) {
                if (ReplayManager.isActive()) {
                    // Tekrar izlenirken: düzenleyiciyi aç
                    ButtonWidget b = ButtonWidget.builder(Text.empty(), x -> mc.setScreen(new ReplayControlsScreen()))
                            .dimensions(bx, by, 20, 20)
                            .tooltip(Tooltip.of(Text.literal("Tekrar düzenleyicisi")))
                            .build();
                    Screens.getButtons(screen).add(b);
                    ScreenEvents.afterRender(screen).register((s, ctx, mx, my, d) -> Ui.cameraIcon(ctx, bx, by, false));
                } else {
                    // ESC menüsü: kamera düğmesi -> kaydı başlat / durdur
                    ButtonWidget[] ref = new ButtonWidget[1];
                    ref[0] = ButtonWidget.builder(Text.empty(), x -> {
                                toggleRecording(mc);
                                ref[0].setTooltip(Tooltip.of(Text.literal(recordTip())));
                            })
                            .dimensions(bx, by, 20, 20)
                            .tooltip(Tooltip.of(Text.literal(recordTip())))
                            .build();
                    Screens.getButtons(screen).add(ref[0]);
                    ScreenEvents.afterRender(screen).register((s, ctx, mx, my, d) -> {
                        boolean rec = RecordingManager.isRecording();
                        Ui.cameraIcon(ctx, bx, by, rec);
                        String t;
                        int col;
                        if (rec) { t = "● KAYIT  " + Ui.time(RecordingManager.elapsedMs()); col = Ui.DANGER; }
                        else if (RecordingManager.canRecord()) { t = "Kaydı başlat"; col = Ui.MUTED; }
                        else { t = "Kayıt kullanılamıyor"; col = Ui.WARN; }
                        Ui.text(ctx, mc.textRenderer, t, bx + 10 - mc.textRenderer.getWidth(t) / 2, by + 24, col);
                    });
                }
            }
        });
    }

    private static String recordTip() {
        if (RecordingManager.isRecording()) return "Kaydı durdur ve kaydet";
        if (RecordingManager.canRecord()) return "Kaydı başlat";
        return "Kayıt şu an kullanılamıyor (önce bir dünyaya/sunucuya gir)";
    }

    private static void toggleRecording(MinecraftClient mc) {
        if (RecordingManager.isRecording()) {
            RecordingManager.stopRecording();
            toast(mc, "Kayıt kaydedildi");
        } else if (RecordingManager.startRecording()) {
            toast(mc, "Kayıt başladı");
        } else {
            toast(mc, "Kayıt şu an kullanılamıyor");
        }
    }

    private void tick(MinecraftClient mc) {
        ReplayManager.tick(mc);
        ExportManager.tick(mc);
        RecordingManager.onClientTick(mc);

        while (kList.wasPressed()) {
            if (!ReplayManager.isActive()) mc.setScreen(new ReplayListScreen(mc.currentScreen));
        }
        while (kControls.wasPressed()) {
            if (ReplayManager.inWorld()) mc.setScreen(new ReplayControlsScreen());
        }
        while (kPause.wasPressed()) ReplayManager.togglePause();
        while (kKeyframe.wasPressed()) ReplayManager.addKeyframe();
        while (kPath.wasPressed()) ReplayManager.togglePath();
        while (kMarker.wasPressed()) {
            boolean ok = RecordingManager.addMarker("İşaret " + (RecordingManager.markerCount() + 1));
            toast(mc, ok ? "İşaret eklendi" : "Önce kaydı başlat");
        }
        while (kRecord.wasPressed()) {
            if (ExportManager.isActive()) ExportManager.cancel(mc);
            else if (!ReplayManager.isActive()) toggleRecording(mc);
        }
    }

    private static void toast(MinecraftClient mc, String s) {
        if (mc.player != null) mc.player.sendMessage(Text.literal("§9[Flashback]§r " + s), true);
    }
}
