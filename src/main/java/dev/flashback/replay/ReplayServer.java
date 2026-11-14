package dev.flashback.replay;

import dev.flashback.FlashbackMod;

import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * 127.0.0.1 üzerinde çalışan sahte "çevrimdışı" sunucu. Oyun normal katılma akışıyla bağlanır;
 * giriş (login) cevaplanır, ardından kayıtlı yapılandırma + oyun kareleri zamanlamaya uygun akıtılır.
 */
public final class ReplayServer implements Runnable {
    private enum Stage { HANDSHAKE, LOGIN, CONFIG, PLAY }

    private final ReplayFile file;
    private final ServerSocket socket;
    private volatile boolean running = true;
    private volatile boolean paused;
    private volatile double speed;
    private volatile double clock;
    private volatile long seekTarget = -1;
    private volatile boolean ended;

    private volatile Stage stage = Stage.HANDSHAKE;
    private volatile boolean helloReceived, loginAck, finishAck;
    private volatile UUID uuid = UUID.randomUUID();
    private volatile String name = "Replay";
    private OutputStream out;

    public ReplayServer(ReplayFile file, long startMs, boolean paused, double speed) throws IOException {
        this.file = file;
        this.clock = startMs;
        this.paused = paused;
        this.speed = speed;
        this.socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
    }

    public int port() { return socket.getLocalPort(); }
    public void start() {
        Thread t = new Thread(this, "Flashback-ReplayServer");
        t.setDaemon(true);
        t.start();
    }

    public long clockMs() { return (long) clock; }
    public boolean ended() { return ended; }
    public void setPaused(boolean p) { paused = p; }
    public void setSpeed(double s) { speed = s; }
    /** Yalnızca ileri sarma; geri sarma bağlantıyı yeniden kurar (ReplayManager). */
    public void seek(long ms) { seekTarget = ms; }

    public void close() {
        running = false;
        try { socket.close(); } catch (IOException ignored) {}
    }

    @Override
    public void run() {
        try (Socket s = socket.accept()) {
            s.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(s.getInputStream());
            out = new BufferedOutputStream(s.getOutputStream(), 1 << 16);
            Thread reader = new Thread(() -> readLoop(in), "Flashback-ReplayReader");
            reader.setDaemon(true);
            reader.start();

            if (!await(() -> helloReceived, 15000)) return;
            sendLoginSuccess();
            if (!await(() -> loginAck, 15000)) return;

            int i = 0;
            while (i < file.count() && file.phase(i) == 0) { sendFrame(file.data(i)); i++; }
            out.flush();
            await(() -> finishAck, 10000);
            stage = Stage.PLAY;

            long last = System.nanoTime();
            while (running) {
                long now = System.nanoTime();
                double dtMs = (now - last) / 1e6;
                last = now;
                long st = seekTarget;
                long end = file.endMs();
                if (st >= 0) { clock = Math.min(st, end); seekTarget = -1; }
                else if (!paused) clock = Math.min(end, clock + dtMs * speed);

                boolean sent = false;
                while (i < file.count() && file.time(i) <= clock) {
                    if (file.isPlay(i)) { sendFrame(file.data(i)); sent = true; }
                    i++;
                }
                if (sent) out.flush();
                ended = clock >= end;
                Thread.sleep(2);
            }
        } catch (IOException | InterruptedException e) {
            if (running) FlashbackMod.LOGGER.debug("Replay sunucusu kapandı: {}", e.toString());
        } finally {
            close();
        }
    }

    // ---------------- gelen (client -> server) ----------------
    private void readLoop(InputStream in) {
        try {
            while (running) {
                byte[] frame = readFrame(in);
                if (frame == null) break;
                ByteBuffer b = ByteBuffer.wrap(frame);
                int id = readVarInt(b);
                switch (stage) {
                    case HANDSHAKE -> stage = Stage.LOGIN;
                    case LOGIN -> {
                        if (id == 0) {
                            name = readString(b);
                            uuid = new UUID(b.getLong(), b.getLong());
                            helloReceived = true;
                        } else if (id == 3) {
                            stage = Stage.CONFIG;
                            loginAck = true;
                        }
                    }
                    case CONFIG -> { if (id == 3) finishAck = true; }
                    default -> {}
                }
            }
        } catch (Exception ignored) {
        } finally {
            running = false;
        }
    }

    private static byte[] readFrame(InputStream in) throws IOException {
        int len = 0, shift = 0, b;
        do {
            b = in.read();
            if (b < 0) return null;
            len |= (b & 0x7F) << shift;
            shift += 7;
            if (shift > 35) throw new IOException("VarInt çok uzun");
        } while ((b & 0x80) != 0);
        if (len < 0 || len > (1 << 22)) throw new IOException("Kare çok büyük");
        byte[] d = new byte[len];
        int off = 0;
        while (off < len) {
            int r = in.read(d, off, len - off);
            if (r < 0) return null;
            off += r;
        }
        return d;
    }

    // ---------------- giden (server -> client) ----------------
    private void sendLoginSuccess() throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(bo);
        writeVarInt(d, 2);                       // login_finished
        d.writeLong(uuid.getMostSignificantBits());
        d.writeLong(uuid.getLeastSignificantBits());
        byte[] n = name.getBytes(StandardCharsets.UTF_8);
        writeVarInt(d, n.length);
        d.write(n);
        writeVarInt(d, 0);                       // profil özellikleri: yok
        sendFrame(bo.toByteArray());
        out.flush();
    }

    private void sendFrame(byte[] data) throws IOException {
        DataOutputStream d = new DataOutputStream(out);
        writeVarInt(d, data.length);
        d.write(data);
    }

    private static void writeVarInt(DataOutputStream d, int v) throws IOException {
        while ((v & ~0x7F) != 0) { d.writeByte((v & 0x7F) | 0x80); v >>>= 7; }
        d.writeByte(v);
    }

    private static int readVarInt(ByteBuffer b) {
        int r = 0, shift = 0, x;
        do { x = b.get(); r |= (x & 0x7F) << shift; shift += 7; } while ((x & 0x80) != 0 && shift < 35);
        return r;
    }

    private static String readString(ByteBuffer b) {
        int n = readVarInt(b);
        byte[] s = new byte[n];
        b.get(s);
        return new String(s, StandardCharsets.UTF_8);
    }

    private boolean await(BooleanSupplier c, long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (running && !c.getAsBoolean()) {
            if (System.currentTimeMillis() > end) return false;
            Thread.sleep(5);
        }
        return running;
    }
}
