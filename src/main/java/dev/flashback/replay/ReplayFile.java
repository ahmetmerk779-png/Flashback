package dev.flashback.replay;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * .fbr = zip: "stream.bin" (kare akışı) + "metadata.json".
 * Kare: long süreMs | byte faz (0 = yapılandırma, 1 = oyun) | int uzunluk | bayt[] (paketId + yük).
 * Kayıt, oturumun başından kayıt bitişine kadar olan akışı tutar; startMs/endMs izlenecek aralığı belirler.
 */
public final class ReplayFile {
    public static final String EXT = ".fbr";
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final class Marker {
        public long ms;
        public String label;
        public Marker(long ms, String label) { this.ms = ms; this.label = label; }
    }

    public static final class Key {
        public long ms;
        public double x, y, z;
        public float yaw, pitch, fov;
    }

    public static final class Meta {
        public String name = "", player = "", server = "", mcVersion = "";
        public long date, durationMs, sizeBytes;
        public int frames;
        /** İzlenecek aralık (akış zamanı, ms). Düzenleyici bunu daraltabilir. */
        public long startMs, endMs;
        /** Kayıt anındaki özgün aralık ("kırpmayı sıfırla" için). */
        public long origStartMs, origEndMs;
        public List<Marker> markers = new ArrayList<>();
        public List<Key> keyframes = new ArrayList<>();
        public transient Path path;
    }

    private record Frame(long t, int phase, byte[] d) {}

    /** Kayıt sırasında istemcinin kendi oyuncusundan alınan örnek (sunucu bunu geri göndermez). */
    public record Local(long t, double x, double y, double z, float yaw, float pitch, float headYaw, float bodyYaw, int flags, float eyeH) {}

    public final Meta meta;
    private final List<Frame> frames;
    private final long lastPlay;
    private Local[] locals = new Local[0];

    private ReplayFile(Meta meta, List<Frame> frames) {
        this.meta = meta;
        this.frames = frames;
        long last = 0;
        for (Frame f : frames) if (f.phase == 1) last = Math.max(last, f.t);
        this.lastPlay = last;
        List<Local> ls = new ArrayList<>();
        for (Frame f : frames) {
            if (f.phase != 2) continue;
            try {
                DataInputStream d = new DataInputStream(new ByteArrayInputStream(f.d));
                ls.add(new Local(f.t, d.readDouble(), d.readDouble(), d.readDouble(), d.readFloat(), d.readFloat(),
                        d.readFloat(), d.readFloat(), d.readUnsignedByte(), d.readFloat()));
            } catch (IOException ignored) {}
        }
        this.locals = ls.toArray(new Local[0]);
        if (meta.endMs <= 0) meta.endMs = last;
        if (meta.origEndMs <= 0) meta.origEndMs = meta.endMs;
    }

    public int count() { return frames.size(); }
    public boolean isPlay(int i) { return frames.get(i).phase == 1; }
    public int phase(int i) { return frames.get(i).phase; }
    public boolean hasLocals() { return locals.length > 0; }

    /** Verilen akış zamanı için yerel oyuncu durumunu iki örnek arasında enterpole eder. */
    public Local localAt(long abs) {
        Local[] a = locals;
        if (a.length == 0) return null;
        if (abs <= a[0].t()) return a[0];
        if (abs >= a[a.length - 1].t()) return a[a.length - 1];
        int lo = 0, hi = a.length - 1;
        while (hi - lo > 1) { int mid = (lo + hi) >>> 1; if (a[mid].t() <= abs) lo = mid; else hi = mid; }
        Local p = a[lo], q = a[hi];
        double u = (double) (abs - p.t()) / Math.max(1, q.t() - p.t());
        return new Local(abs, p.x() + (q.x() - p.x()) * u, p.y() + (q.y() - p.y()) * u, p.z() + (q.z() - p.z()) * u,
                ang(p.yaw(), q.yaw(), u), p.pitch() + (q.pitch() - p.pitch()) * (float) u,
                ang(p.headYaw(), q.headYaw(), u), ang(p.bodyYaw(), q.bodyYaw(), u), p.flags(),
                p.eyeH() + (q.eyeH() - p.eyeH()) * (float) u);
    }

    private static float ang(float a, float b, double u) {
        float d = b - a;
        while (d > 180) d -= 360;
        while (d < -180) d += 360;
        return a + d * (float) u;
    }
    public long time(int i) { return frames.get(i).t; }
    public byte[] data(int i) { return frames.get(i).d; }
    public long startMs() { return meta.startMs; }
    public long endMs() { return Math.min(meta.endMs <= 0 ? lastPlay : meta.endMs, Math.max(lastPlay, meta.startMs)); }

    // ---- disk ----
    public static Path dir() {
        Path p = FabricLoader.getInstance().getGameDir().resolve("flashback").resolve("replays");
        try { Files.createDirectories(p); } catch (IOException ignored) {}
        return p;
    }

    public static Path tmpDir() {
        Path p = FabricLoader.getInstance().getGameDir().resolve("flashback").resolve("tmp");
        try { Files.createDirectories(p); } catch (IOException ignored) {}
        return p;
    }

    public static void cleanTmp() {
        try (Stream<Path> s = Files.list(tmpDir())) {
            s.forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }

    public static String sanitize(String s) {
        String r = s.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        return r.isBlank() ? "kayit" : r;
    }

    public static List<Path> list() {
        try (Stream<Path> s = Files.list(dir())) {
            return s.filter(p -> p.getFileName().toString().endsWith(EXT))
                    .sorted(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed())
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    public static Meta readMeta(Path p) throws IOException {
        try (ZipFile z = new ZipFile(p.toFile())) {
            ZipEntry e = z.getEntry("metadata.json");
            if (e == null) return null;
            try (Reader r = new InputStreamReader(z.getInputStream(e), StandardCharsets.UTF_8)) {
                Meta m = GSON.fromJson(r, Meta.class);
                if (m == null) return null;
                m.sizeBytes = Files.size(p);
                m.path = p;
                if (m.markers == null) m.markers = new ArrayList<>();
                if (m.keyframes == null) m.keyframes = new ArrayList<>();
                return m;
            }
        }
    }

    public static void writeMeta(ZipOutputStream zip, Meta m) throws IOException {
        zip.putNextEntry(new ZipEntry("metadata.json"));
        zip.write(GSON.toJson(m).getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /** Tamponlanan ham akıştan ilk len baytı yeni bir .fbr dosyasına yazar. */
    public static void writeFromRaw(Path raw, long len, Meta m, Path target) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)));
             InputStream in = new BufferedInputStream(Files.newInputStream(raw), 1 << 16)) {
            zip.setLevel(6);
            zip.putNextEntry(new ZipEntry("stream.bin"));
            byte[] b = new byte[1 << 16];
            long left = len;
            while (left > 0) {
                int r = in.read(b, 0, (int) Math.min(b.length, left));
                if (r < 0) break;
                zip.write(b, 0, r);
                left -= r;
            }
            zip.closeEntry();
            writeMeta(zip, m);
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Akışı değiştirmeden yalnızca metadata.json'u günceller. */
    public static void saveMeta(Path p, Meta m) throws IOException {
        Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
        try (ZipFile z = new ZipFile(p.toFile());
             ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
            zip.setLevel(1);
            ZipEntry e = z.getEntry("stream.bin");
            if (e != null) {
                zip.putNextEntry(new ZipEntry("stream.bin"));
                try (InputStream is = z.getInputStream(e)) { is.transferTo(zip); }
                zip.closeEntry();
            }
            writeMeta(zip, m);
        }
        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
    }

    public static Path renameOnDisk(Path p, String newName) throws IOException {
        Meta m = readMeta(p);
        if (m == null) throw new IOException("metadata.json yok");
        m.name = newName;
        saveMeta(p, m);
        Path t = p.resolveSibling(sanitize(newName) + EXT);
        int n = 1;
        while (Files.exists(t) && !t.equals(p)) t = p.resolveSibling(sanitize(newName) + "_" + (n++) + EXT);
        if (!t.equals(p)) Files.move(p, t);
        return t;
    }

    public static ReplayFile load(Path p) throws IOException {
        Meta meta = readMeta(p);
        if (meta == null) throw new IOException("metadata.json yok");
        List<Frame> list = new ArrayList<>(Math.max(16, meta.frames));
        try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(Files.newInputStream(p), 1 << 16))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (!e.getName().equals("stream.bin")) continue;
                DataInputStream in = new DataInputStream(new BufferedInputStream(zin, 1 << 16));
                try {
                    while (true) {
                        long t = in.readLong();
                        int phase = in.readByte();
                        int len = in.readInt();
                        byte[] d = new byte[len];
                        in.readFully(d);
                        list.add(new Frame(t, phase, d));
                    }
                } catch (EOFException ignored) {}
                break;
            }
        }
        return new ReplayFile(meta, list);
    }

    public static void writeFrame(DataOutputStream out, long t, int phase, byte[] d) throws IOException {
        out.writeLong(t);
        out.writeByte(phase);
        out.writeInt(d.length);
        out.write(d);
    }
}
