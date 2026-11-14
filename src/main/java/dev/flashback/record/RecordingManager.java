package dev.flashback.record;

import dev.flashback.FlashbackConfig;
import dev.flashback.FlashbackMod;
import dev.flashback.replay.ReplayFile;
import dev.flashback.replay.ReplayManager;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.network.NetworkPhase;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.BundlePacket;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.state.NetworkState;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public final class RecordingManager {
    private static SessionBuffer buf;
    private static boolean recording;
    private static long recStart, recStartBytes;
    private static boolean warnedEncode;
    private static final List<ReplayFile.Marker> markers = new ArrayList<>();

    // ---------------- paket yakalama (netty iş parçacığı) ----------------
    public static void onPacket(NetworkState<?> st, Packet<?> pkt) {
        if (ReplayManager.isActive() || !FlashbackConfig.autoRecord) return;
        NetworkPhase ph = st.id();
        if (ph != NetworkPhase.CONFIGURATION && ph != NetworkPhase.PLAY) return;
        List<byte[]> out = new ArrayList<>(2);
        try {
            encode(st, pkt, out);
        } catch (Throwable t) {
            if (!warnedEncode) { warnedEncode = true; FlashbackMod.LOGGER.warn("Bir paket kodlanamadı, atlanıyor", t); }
            return;
        }
        boolean play = ph == NetworkPhase.PLAY;
        for (byte[] d : out) onFrameBytes(play, d);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void encode(NetworkState<?> st, Packet<?> p, List<byte[]> out) {
        PacketCodec codec = st.codec();
        if (p instanceof BundlePacket<?> && st.bundleHandler() != null) {
            // paket demeti: ayraç + alt paketler + ayraç olarak aç (oynatmada ağdan geldiği gibi akar)
            st.bundleHandler().forEachPacket(p, sub -> encodeOne(codec, sub, out));
        } else {
            encodeOne(codec, p, out);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void encodeOne(PacketCodec codec, Packet<?> p, List<byte[]> out) {
        ByteBuf b = Unpooled.buffer(256);
        try {
            codec.encode(b, p);
            byte[] d = new byte[b.readableBytes()];
            b.readBytes(d);
            out.add(d);
        } finally {
            b.release();
        }
    }

    private static synchronized void onFrameBytes(boolean play, byte[] d) {
        if (buf != null && !play && buf.seenPlay) {
            // Sunucu yeniden yapılandırma başlattı (ör. proxy sunucu değişimi): yeni oturum.
            endSession();
        }
        if (buf == null) {
            if (play) return; // oturum her zaman yapılandırma fazıyla başlamalı
            buf = new SessionBuffer();
            fillInfo();
        }
        buf.write(play ? 1 : 0, d);
    }

    /** İstemci tick'i: kayıt sahibi oyuncunun kendi durumunu ayrı kanala yazar (sunucu bunu geri göndermez). */
    public static synchronized void onClientTick(MinecraftClient mc) {
        if (buf == null || !buf.seenPlay || ReplayManager.isActive()) return;
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) return;
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(64);
            DataOutputStream d = new DataOutputStream(bo);
            d.writeDouble(p.getX()); d.writeDouble(p.getY()); d.writeDouble(p.getZ());
            d.writeFloat(p.getYaw()); d.writeFloat(p.getPitch());
            d.writeFloat(p.getHeadYaw()); d.writeFloat(p.getBodyYaw());
            d.writeByte((p.isSneaking() ? 1 : 0) | (p.isSprinting() ? 2 : 0) | (p.isOnGround() ? 4 : 0));
            d.writeFloat((float) (p.getEyeY() - p.getY()));
            buf.write(2, bo.toByteArray());
        } catch (Exception ignored) {}
    }

    private static void fillInfo() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (buf == null) return;
        buf.mcVersion = mc.getGameVersion();
        buf.player = mc.getSession().getUsername();
        ServerInfo si = mc.getCurrentServerEntry();
        if (si != null) buf.server = si.address;
        else if (mc.isIntegratedServerRunning()) buf.server = "Tek oyunculu";
    }

    public static synchronized void onJoin(MinecraftClient mc) { fillInfo(); }
    public static synchronized void onConnectionClosed() { endSession(); }
    public static synchronized void onDisconnect() { endSession(); }

    private static void endSession() {
        if (recording) saveCurrent();
        recording = false;
        if (buf != null) { buf.close(); buf = null; }
    }

    // ---------------- kullanıcı eylemleri ----------------
    public static synchronized boolean canRecord() { return buf != null && buf.seenPlay; }

    public static synchronized boolean startRecording() {
        if (recording || !canRecord()) return false;
        recording = true;
        recStart = buf.elapsedMs();
        recStartBytes = buf.totalBytes;
        markers.clear();
        return true;
    }

    public static synchronized boolean stopRecording() {
        if (!recording) return false;
        saveCurrent();
        recording = false;
        return true;
    }

    private static void saveCurrent() {
        if (buf == null) return;
        long end = buf.elapsedMs();
        ReplayFile.Meta m = new ReplayFile.Meta();
        long now = System.currentTimeMillis();
        String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date(now));
        String base = stamp + "_" + ReplayFile.sanitize(buf.server);
        Path target = ReplayFile.dir().resolve(base + ReplayFile.EXT);
        m.name = base;
        m.player = buf.player;
        m.server = buf.server;
        m.mcVersion = buf.mcVersion;
        m.date = now;
        m.startMs = m.origStartMs = recStart;
        m.endMs = m.origEndMs = end;
        m.durationMs = end - recStart;
        m.markers = new ArrayList<>(markers);
        buf.save(new SessionBuffer.Save(m, target));
        markers.clear();
    }

    public static synchronized boolean isRecording() { return recording; }
    public static synchronized long elapsedMs() { return recording && buf != null ? buf.elapsedMs() - recStart : 0; }
    public static synchronized long bytes() { return recording && buf != null ? buf.totalBytes - recStartBytes : 0; }
    public static synchronized int markerCount() { return markers.size(); }

    public static synchronized boolean addMarker(String label) {
        if (!recording || buf == null) return false;
        markers.add(new ReplayFile.Marker(buf.elapsedMs(), label));
        return true;
    }

    private RecordingManager() {}
}
