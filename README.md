# Flashback (Replay Mod) – Fabric 1.21.11

Oyun oturumunu ağ paketleri seviyesinde kaydeder; sonra serbest kamera, zaman çizelgesi ve kamera yolu ile yeniden izletir.

## Derleme
1. JDK 21 kur.
2. Gradle wrapper üret: `gradle wrapper --gradle-version 9.2.1` (veya projeyi IntelliJ ile aç).
3. `./gradlew build` → `build/libs/flashback-1.0.0.jar` → `mods/` klasörüne (Fabric API ile birlikte).
Geliştirme: `./gradlew runClient`

## Kullanım
1. Bir sunucuya gir, **ESC** bas. Menünün sağ-orta tarafındaki **kamera düğmesine** bas → kayıt başlar (kırmızı nokta yanıp söner). Tekrar basarsan kayıt durur ve kaydedilir.
2. Sunucudan çık. Ana menüde yine sağ-ortadaki **kamera düğmesi** → kayıtların liste halinde.
3. Kayda gir (**Aç / Düzenle**) → düzenleyici açılır: zaman çizelgesi, hız, serbest kamera, kamera yolu, başlangıç/bitiş kırpma, işaret ekle/sil, yeniden adlandır, **Kaydet**.

| Tuş | İşlev |
|---|---|
| F6 | Kayıt listesi |
| F7 | Düzenleyici (tekrar sırasında) |
| F8 | Kayıt sırasında işaret ekle |
| F9 | Kaydı başlat / durdur |
| F10 | Tekrarı duraklat / devam |
| ] / [ | Kamera anahtar karesi ekle / yolu oynat |

Kayıtlar: `.minecraft/flashback/replays/*.fbr` — Ayarlar: `config/flashback.json`

## Düzenleyici sekmeleri
- **Oynatma:** ±10 sn, 0.1x–8x hız, işaretler arası atlama.
- **Kamera:** serbest/oyuncu kamerası, anahtar kare ekle/sil, kamera yolu (yumuşak/doğrusal), kamera hızı.
- **Düzenle:** başlangıç/bitiş kırpma, işaret ekle/sil, yeniden adlandır, kaydet.
- **Dünya:** hava durumu (açık/yağmur/fırtına), oyun modu (survival/creative/adventure/spectator), HUD aç/kapat, FOV. Bunlar yalnızca izleme sırasında istemcide geçerlidir, kayıt dosyası değişmez.
- **Dışa Aktar:** 720p/1080p/1440p/4K, 24/30/60/120 fps, kalite, HUD gizle, gerçek çözünürlük, render hızı ve **çıktı türü**:
  - **AVI (kurulumsuz):** harici program yok; yalnızca Java + oyunun kendi LWJGL-STB kütüphanesi. Telefonda (Pojav/Zalith vb.) çalışır. Varsayılan.
  - **PNG dizisi:** her kare ayrı PNG.
  - **MP4 (ffmpeg):** ffmpeg kuruluysa (masaüstü). Bulunamazsa otomatik AVI'ye düşer. PATH'te yoksa `config/flashback.json` → `ffmpegPath`.
  Çıktılar `.minecraft/flashback/exports/` altına yazılır. Ses yoktur. **İptal: ESC / duraklat düğmesi** (veya F9).

Dışa aktarma nasıl çalışır: oyun "render hızı" kadar yavaşlatılarak oynatılır, her çizilen kare okunup sabit kare hızına (CFR) göre çoğaltılarak/atlanarak çıktıya yazılır. Telefonda 1080p/30 ve 0.25x ile başla; 4K ve "gerçek çözünürlük" telefon GPU'sunda bellek yetmeyebilir.

## Mimari
- `mixin/ClientConnectionMixin`: `ClientConnection.channelRead0` ile gelen paket nesnelerini yakalar ve o anki ağ durumunun codec'iyle kodlar. Uzak sunucuda ve tek oyunculuda aynı şekilde çalışır (Replay Mod'un yaklaşımına benzer).
- Yerel oyuncu kanalı: sunucu oyuncunun kendi hareketini geri göndermediği için her tick oyuncunun konum/bakış/çömelme durumu ayrıca kaydedilir; oynatmada "Oyuncu kamerası" bunu enterpole ederek kullanır.
- `record/SessionBuffer`: oturumun tüm paketlerini geçici dosyada tutar (bağlantı bitince silinir). Kayıt, bu tampondan başlangıç/bitiş zamanıyla kesilip `.fbr` olarak yazılır; böylece kayıt herhangi bir anda başlatılabilir (anlık görüntü gerekmez).
- `replay/ReplayServer`: 127.0.0.1 üzerinde sahte çevrimdışı sunucu; login'i cevaplar, config + play karelerini zamanlayarak akıtır.
- `replay/ReplayManager`: hız ve duraklatma (client TickManager), ileri/geri sarma, kamera yolu.
- `mixin/CameraMixin`, `mixin/EntityMixin`: serbest kamera.

## Bilinen sınırlar
- Tek oyunculu kayıt, paket nesnelerini yakalayarak yapılır (yeni, denenmedi); uzak sunucularla aynı yolu kullanır.
- Kamera yolu anahtar kareleri artık FOV de saklar. Günün saati anahtarı ve ses henüz yok.
- Tampon, oturum süresince diskte yer kaplar (uzun oturumlarda yüzlerce MB olabilir; çıkınca silinir). Kaydedilen dosya da kayıt anına kadarki akışı içerir.
- Kırpma yalnızca aralığı daraltır (akış silinmez); "Sıfırla" ile özgün aralık geri gelir.
- Geri sarma = bağlantıyı yeniden kurup hedef zamana hızlıca akıtma (anlık görüntü yok).
- Kamera yolları diske kaydedilmiyor.
- Kod derlenip denenmedi; Yarn isimleri 1.21.11'de küçük farklar gösterebilir.
