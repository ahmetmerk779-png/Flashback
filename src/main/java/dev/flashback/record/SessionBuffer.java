package dev.flashback.record;

import dev.flashback.FlashbackMod;
import dev.flashback.replay.ReplayFile;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Oturumun tüm paketlerini geçici bir dosyada tutar (bağlantı bitince silinir).
 * Kayıt başlatıldığında dünya durumu zaten tamponda olduğu için anlık görüntü gerekmez;
 * kayıt bitince tampon, başlangıç/bitiş zamanlarıyla yeni bir .fbr dosyasına kopyalanır.
 */
final class SessionBuffer {
    private record Frame(long t, int phase, byte[] d) {}
    record Save(ReplayFile.Meta meta, Path target) {}
    private static final Object END = new Object();

    private final BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
    private final Path raw;
    private long playStartNs = -1;

    volatile boolean seenPlay;
    volatile String server = "bilinmiyor", player = "", mcVersion = "";
    volatile long totalBytes;

    SessionBuffer() {
        raw = ReplayFile.tmpDir().resolve("session-" + System.currentTimeMillis() + ".raw");
        Thread t = new Thread(this::writerLoop, "Flashback-Writer");
        t.setDaemon(false);
        t.start();
    }

    /** phase: 0 = yapılandırma paketi, 1 = oyun paketi, 2 = yerel oyuncu örneği. */
    void write(int phase, byte[] d) {
        long t = 0;
        if (phase >= 1) {
            long now = System.nanoTime();
            if (playStartNs < 0) playStartNs = now;
            t = (now - playStartNs) / 1_000_000L;
            if (phase == 1) seenPlay = true;
        }
        totalBytes += d.length;
        queue.offer(new Frame(t, phase, d));
    }

    long elapsedMs() { return playStartNs < 0 ? 0 : (System.nanoTime() - playStartNs) / 1_000_000L; }
    void save(Save s) { queue.offer(s); }
    void close() { queue.offer(END); }

    private void writerLoop() {
        long pos = 0;
        int count = 0;
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(raw), 1 << 16))) {
            while (true) {
                Object o = queue.take();
                if (o == END) break;
                if (o instanceof Frame f) {
                    ReplayFile.writeFrame(out, f.t, f.phase, f.d);
                    pos += 13 + f.d.length;
                    count++;
                } else if (o instanceof Save s) {
                    out.flush();
                    s.meta().frames = count;
                    try {
                        ReplayFile.writeFromRaw(raw, pos, s.meta(), s.target());
                        FlashbackMod.LOGGER.info("Kayıt kaydedildi: {}", s.target().getFileName());
                    } catch (Exception e) {
                        FlashbackMod.LOGGER.error("Kayıt yazılamadı", e);
                    }
                }
            }
        } catch (Exception e) {
            FlashbackMod.LOGGER.error("Tampon hatası", e);
        } finally {
            try { Files.deleteIfExists(raw); } catch (Exception ignored) {}
        }
    }
}
