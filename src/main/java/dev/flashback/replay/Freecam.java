package dev.flashback.replay;

import dev.flashback.FlashbackConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;

public final class Freecam {
    public volatile boolean enabled;
    public boolean inited;
    public double x, y, z;
    public float yaw, pitch;
    /** Kamera yolu oynarken anahtar karelerden gelen FOV (yoksa -1). */
    public float fov = -1;
    private long lastNs;

    public void init(double x, double y, double z, float yaw, float pitch) {
        this.x = x; this.y = y; this.z = z; this.yaw = yaw; this.pitch = pitch;
        this.inited = true;
        this.lastNs = System.nanoTime();
    }

    public void toggle() {
        enabled = !enabled;
        inited = false; // açılırken mevcut kameradan başla
    }

    public void look(double dx, double dy) {
        if (ReplayManager.pathPlaying()) return;
        yaw += (float) (dx * 0.15);
        pitch = Math.max(-90f, Math.min(90f, pitch + (float) (dy * 0.15)));
    }

    /** Her karede çağrılır (gerçek zamanlı, tick hızından bağımsız = akıcı). */
    public void frame() {
        long now = System.nanoTime();
        double dt = Math.min(0.1, (now - lastNs) / 1e9);
        lastNs = now;

        if (ReplayManager.pathPlaying()) {
            CameraPath.Pose p = ReplayManager.path.sample(ReplayManager.absMs());
            if (p != null) { x = p.x(); y = p.y(); z = p.z(); yaw = p.yaw(); pitch = p.pitch(); fov = p.fov(); }
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.currentScreen != null) return;
        GameOptions o = mc.options;
        double fwd = (o.forwardKey.isPressed() ? 1 : 0) - (o.backKey.isPressed() ? 1 : 0);
        double str = (o.rightKey.isPressed() ? 1 : 0) - (o.leftKey.isPressed() ? 1 : 0);
        double up = (o.jumpKey.isPressed() ? 1 : 0) - (o.sneakKey.isPressed() ? 1 : 0);
        double sp = FlashbackConfig.freecamSpeed * (o.sprintKey.isPressed() ? 3.0 : 1.0) * dt;

        double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
        double fx = -Math.sin(yr) * Math.cos(pr), fy = -Math.sin(pr), fz = Math.cos(yr) * Math.cos(pr);
        double rx = -Math.cos(yr), rz = -Math.sin(yr);
        x += (fx * fwd + rx * str) * sp;
        y += (fy * fwd + up) * sp;
        z += (fz * fwd + rz * str) * sp;
    }
}
