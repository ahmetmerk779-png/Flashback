package dev.flashback.replay;

import dev.flashback.FlashbackConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Anahtar karelerden oluşan kamera yolu (doğrusal veya Catmull-Rom ile yumuşak). */
public final class CameraPath {
    public record Keyframe(long ms, double x, double y, double z, float yaw, float pitch, float fov) {}
    public record Pose(double x, double y, double z, float yaw, float pitch, float fov) {}

    private final List<Keyframe> frames = new ArrayList<>();

    public synchronized void add(Keyframe k) {
        frames.removeIf(f -> Math.abs(f.ms() - k.ms()) < 250);
        frames.add(k);
        frames.sort(Comparator.comparingLong(Keyframe::ms));
    }
    public synchronized void remove(int i) { if (i >= 0 && i < frames.size()) frames.remove(i); }
    public synchronized boolean removeNear(long ms, long tol) {
        Keyframe best = null;
        for (Keyframe k : frames) if (Math.abs(k.ms() - ms) <= tol && (best == null || Math.abs(k.ms() - ms) < Math.abs(best.ms() - ms))) best = k;
        return best != null && frames.remove(best);
    }
    public synchronized void clear() { frames.clear(); }
    public synchronized int size() { return frames.size(); }
    public synchronized List<Keyframe> all() { return new ArrayList<>(frames); }
    public synchronized long start() { return frames.isEmpty() ? 0 : frames.get(0).ms(); }
    public synchronized long end() { return frames.isEmpty() ? 0 : frames.get(frames.size() - 1).ms(); }

    public synchronized Pose sample(long ms) {
        int n = frames.size();
        if (n == 0) return null;
        if (n == 1 || ms <= frames.get(0).ms()) return pose(frames.get(0));
        if (ms >= frames.get(n - 1).ms()) return pose(frames.get(n - 1));
        int i = 0;
        while (i < n - 2 && frames.get(i + 1).ms() <= ms) i++;
        Keyframe p1 = frames.get(i), p2 = frames.get(i + 1);
        Keyframe p0 = frames.get(Math.max(i - 1, 0)), p3 = frames.get(Math.min(i + 2, n - 1));
        double t = (double) (ms - p1.ms()) / Math.max(1, p2.ms() - p1.ms());

        double y1 = p1.yaw(), y2 = unwrap(p2.yaw(), y1), y0 = unwrap(p0.yaw(), y1), y3 = unwrap(p3.yaw(), y2);
        if (FlashbackConfig.smoothPath) {
            return new Pose(
                    cr(p0.x(), p1.x(), p2.x(), p3.x(), t),
                    cr(p0.y(), p1.y(), p2.y(), p3.y(), t),
                    cr(p0.z(), p1.z(), p2.z(), p3.z(), t),
                    (float) cr(y0, y1, y2, y3, t),
                    (float) cr(p0.pitch(), p1.pitch(), p2.pitch(), p3.pitch(), t),
                    (float) cr(p0.fov(), p1.fov(), p2.fov(), p3.fov(), t));
        }
        return new Pose(lerp(p1.x(), p2.x(), t), lerp(p1.y(), p2.y(), t), lerp(p1.z(), p2.z(), t),
                (float) lerp(y1, y2, t), (float) lerp(p1.pitch(), p2.pitch(), t), (float) lerp(p1.fov(), p2.fov(), t));
    }

    private static Pose pose(Keyframe k) { return new Pose(k.x(), k.y(), k.z(), k.yaw(), k.pitch(), k.fov()); }
    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }
    private static double unwrap(double a, double ref) {
        while (a - ref > 180) a -= 360;
        while (a - ref < -180) a += 360;
        return a;
    }
    private static double cr(double p0, double p1, double p2, double p3, double t) {
        return 0.5 * ((2 * p1) + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t * t
                + (-p0 + 3 * p1 - 3 * p2 + p3) * t * t * t);
    }
}
