package dev.flashback;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public final class FlashbackConfig {
    public static boolean autoRecord = true;
    public static boolean showHud = true;
    public static boolean smoothPath = true;
    /** Serbest kamera hızı (blok/sn). */
    public static float freecamSpeed = 8f;
    /** Bu süreden kısa kayıtlar otomatik silinir (sn). 0 = kapalı. */
    public static int discardShorterThanSec = 10;
    /** Video dışa aktarma için ffmpeg çalıştırılabilir dosyası (PATH'te ise "ffmpeg" yeterli). */
    public static String ffmpegPath = "ffmpeg";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final class Data {
        boolean autoRecord = true, showHud = true, smoothPath = true;
        float freecamSpeed = 8f;
        int discardShorterThanSec = 10;
        String ffmpegPath = "ffmpeg";
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("flashback.json");
    }

    public static void load() {
        try {
            Path f = file();
            if (!Files.exists(f)) { save(); return; }
            try (Reader r = Files.newBufferedReader(f)) {
                Data d = GSON.fromJson(r, Data.class);
                if (d == null) return;
                autoRecord = d.autoRecord; showHud = d.showHud; smoothPath = d.smoothPath;
                freecamSpeed = d.freecamSpeed; discardShorterThanSec = d.discardShorterThanSec;
                if (d.ffmpegPath != null && !d.ffmpegPath.isBlank()) ffmpegPath = d.ffmpegPath;
            }
        } catch (Exception e) {
            FlashbackMod.LOGGER.warn("Ayarlar okunamadı", e);
        }
    }

    public static void save() {
        try {
            Data d = new Data();
            d.autoRecord = autoRecord; d.showHud = showHud; d.smoothPath = smoothPath;
            d.freecamSpeed = freecamSpeed; d.discardShorterThanSec = discardShorterThanSec; d.ffmpegPath = ffmpegPath;
            try (Writer w = Files.newBufferedWriter(file())) { GSON.toJson(d, w); }
        } catch (Exception e) {
            FlashbackMod.LOGGER.warn("Ayarlar kaydedilemedi", e);
        }
    }

    private FlashbackConfig() {}
}
