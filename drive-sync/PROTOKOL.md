# EX30 Drive protokolü — sürüm 3

[English](PROTOCOL.md) · **Türkçe**

Araçtaki **EX30 Telemetry** yolculukları, sürücünün araçta bağladığı **Google
hesabının kendi Drive'ına** yazar. Bilgisayardaki **EX30 Trip Viewer** ve
telefon/tablet uygulaması aynı kişinin aynı Drive'ından okur. **Arada sunucu
yok.** Her kullanıcı kendi hesabıyla bağlanır, veriler birbirine karışmaz.

**Bu dosya protokolün tek kaynağı.** Bir alan, dosya adı ya da kural değişecekse
önce burası güncellenir, sonra istemciler:

| İstemci | Rol | Kod |
|---|---|---|
| Araç — EX30 Telemetry | **tek yazar** | `automotive/.../google/`, `sync/`, `trip/TrackRecorder.kt` |
| Bilgisayar — EX30 Trip Viewer | okur | [ex30-trip-viewer](https://github.com/Anil-Erke/ex30-trip-viewer) deposu, `ex30trips/drive.py` |
| Telefon/tablet — EX30 Trip Mobile | okur | ayrı uygulama (henüz yayınlanmadı) |

> **Sürüm geçmişi.** Sürüm 1-2 kullanıcının kendi hesabında yayınladığı bir
> Apps Script ucundan (`Kod.gs`) geçiyordu; adres ve yazma anahtarı APK'ya
> gömülüydü ve herkesin yolculuğu tek bir Drive'a giderdi. Sürüm 3 (2026-09-29)
> bunun yerine her kullanıcıya kendi Google girişini veriyor. `Kod.gs` yalnızca
> eski Viewer'ın `trips.json` okuması için duruyor; araç artık ona yazmıyor.
> **Apps Script'in oluşturduğu dosyaları sürüm 3 istemcileri göremez**
> (`drive.file` kuralı) — iki dünya ayrı.

---

## 1. Kimlik doğrulama

- **Tek Google Cloud projesi** ("EX30 Telemetry"), içinde istemci başına bir
  OAuth istemcisi:

  | İstemci | OAuth türü | Akış |
  |---|---|---|
  | Araç | *TVs and Limited Input devices* | cihaz akışı: araç kod gösterir, kullanıcı telefondan `google.com/device`'a girip onaylar |
  | Viewer | *Desktop app* | tarayıcı + loopback (`http://127.0.0.1:<port>`), PKCE |
  | Telefon | *Android* (paket adı + SHA-1) | Google ile giriş / Authorization API |

- **İzin: yalnızca `https://www.googleapis.com/auth/drive.file`** (+ `openid email`
  yalnızca "hangi hesap" diye göstermek için). Uygulama Drive'da **yalnızca bu
  projenin istemcilerinin oluşturduğu dosyaları** görür, kişinin geri kalan
  Drive'ına erişimi yoktur.
- **Aynı projedeki istemciler birbirlerinin dosyalarını görür.** 2026-09-29'da
  gerçek Drive'a karşı doğrulandı (`drive-sync/oauth-dogrulama.py`): aracın
  oluşturduğu dosyayı masaüstü istemcisi `appProperties` sorgusuyla buldu ve
  içeriğini okudu. Belgeler bunu açıkça yazmıyor; istemci eklerken aynı
  projede kalın.
- **İzin kutusu tuzağı.** Google'ın onay ekranı her izne ayrı kutu koyuyor. Drive
  kutusu işaretlenmezse giriş başarılı görünür ama token'da `drive.file` yoktur
  ve her Drive çağrısı 403 döner. **Her istemci token yanıtındaki `scope`
  alanını kontrol etmeli** ve Drive yoksa bağlantıyı reddedip kullanıcıya söylemeli.
- **"Testing" durumunda refresh token 7 günde ölür.** İzin ekranı "In production"
  durumunda olmalı (`drive.file` hassas değil; Google incelemesi gerekmiyor).
- İstemci kimliği/sırrı depoya girmez (`oauth.properties`). Cihaz ve masaüstü
  uygulamalarında Google bunları gizli saymıyor; tek başına hiçbir veriye erişim
  vermiyorlar. Erişim her kullanıcının kendi cihazında saklanan token'ıyla.

---

## 2. Drive düzeni

```
EX30 Trips/                               kök — kullanıcının gördüğü tek şey
  calib.csv, calib-prev.csv,              araçtaki son hâl, "Drive'a aktar"
  trips.json, records.json                düğmesiyle GÜNCELLENİR (elle)
  yolculuklar/
    2026/
      09/
        trip-1790602585477.json           özet
        trip-1790602585477.csv.gz         GPS izi
```

- Klasörler **yalnızca insan gözü için.** İstemciler dosyaları yol ya da adla
  değil **`appProperties`** ile bulur; kullanıcı bir dosyayı taşısa ya da adını
  değiştirse de eşleşme bozulmaz.
- Yıl/ay, yolculuğun **başlangıcına** göre, aracın saat diliminde.

### 2.1 `appProperties`

| Anahtar | Değer | Nerede |
|---|---|---|
| `ex30` | `trip` (yolculuk dosyası) · `kayit` (kökteki günlükler) | dosyalar |
| `ex30id` | dosya adı, ör. `trip-1790602585477.json` — **tekil anahtar** | dosyalar |
| `epoch` | yolculuk başlangıcı, epoch ms (metin) | yolculuk dosyaları |
| `tur` | `ozet` \| `iz` | yolculuk dosyaları |
| `ex30path` | klasörün tam yolu, ör. `EX30 Trips/yolculuklar/2026/09` | klasörler |

### 2.2 Yazma kuralları (yalnızca araç)

- **Yolculuk dosyaları yalnızca oluşturulur, asla üzerine yazılmaz.** Önce
  `ex30id` ile aranır; varsa dokunulmaz ve başarı sayılır. Yeniden deneme
  (yanıt yolda kayboldu, head unit uyudu) zararsızdır, çift dosya oluşmaz.
- **Bütünlük:** Drive'ın döndürdüğü `md5Checksum` yerel içerikle karşılaştırılır;
  tutmazsa dosya silinir ve yolculuk kuyrukta kalır.
- Klasörler `ex30path` ile aranır, yoksa oluşturulur; yeniden kurulumdan sonra da
  aynı klasörler bulunur.

---

## 3. Dosya biçimleri

### 3.1 Özet — `trip-<startEpoch>.json`

`mimeType: application/json`, **düz** (sıkıştırılmamış) JSON. Araçtaki
`Trip.toJson()` çıktısı; eski `trips.json`'daki bir elemanla birebir aynı biçim.

Alanlar (şema 3; `null`/eksik = araç o değeri vermedi, **sıfıra çevrilmez**):

| Alan | Birim | Not |
|---|---|---|
| `schemaVersion` | — | yoksa 1 kabul et |
| `startEpoch`, `endEpoch` | epoch ms | |
| `durationSec` | s | |
| `distanceKm` | km | GPS'ten |
| `wheelDistanceKm` | km | tekerlek tiklerinden, **HAM** — gösterimde × 1,02536 |
| `energyKwh` | kWh | **net** tüketim (tüketilen − rejen) |
| `regenKwh` | kWh | pozitif; brüt = net + rejen |
| `socStart`, `socEnd` | % | ondalıklı olabilir |
| `rangeStart`, `rangeEnd` | km | gösterge menzili |
| `avgSpeedKmh`, `maxSpeedKmh` | km/h | |
| `tempStart`, `tempAvg` | °C | dış sıcaklık |
| `altGainM`, `altLossM` | m | |
| `potentialKwh` | kWh | tırmanışın potansiyel enerjisi |
| `consumptionKwh100` | kWh/100 km | |
| `rangeBiasFactor` | — | gösterge menzil düşüşü ÷ gerçek mesafe; >1 = gösterge iyimser |
| `records` | dizi | `{kind, value, unit, epoch}` — `0-100`, `0-60`, `80-120` (s), `100-0` (m) |

Tanımlar araçtaki `trip/TripStats.kt` ve `trip/RangeAuditor.kt` ile aynı
tutulmalı: **ortalama tüketim enerji ağırlıklı**, **menzil sapması mesafe
ağırlıklı ve yalnızca ≥ 5 km yolculuklarda**.

### 3.2 GPS izi — `trip-<startEpoch>.csv.gz`

`mimeType: application/gzip`. Açılmış içerik UTF-8 metin:

```
# ex30-track;1;1790602585477
t;lat;lon;alt;hacc;vacc;gps_kmh;kmh;kw;soc;dist_m
1790602587240;41.000000;29.000000;100.0;5.0;0.5;;0.0;0.00;61.00;0.0
```

- 1. satır: `# ex30-track;<biçim sürümü>;<startEpoch>`. Biçim sürümü bilinmiyorsa
  dosyayı okuma, kullanıcıya söyle.
- 2. satır: sütun adları. **Sütunları adla oku, sırayla değil.**
- Ayraç `;`, ondalık ayracı **her zaman nokta**, binlik ayracı yok. Boş alan =
  değer yoktu; sıfır yazılmaz.
- Yaklaşık 1 Hz; araç dururken seyrekleşir (3 m eşiği). Saatte ~3.600 satır.
- Bir yolculuğun izi olmayabilir (0.7.2 öncesi yolculuklar, GPS'siz sürüş).

| Sütun | Birim | Anlamı |
|---|---|---|
| `t` | epoch ms, UTC | GPS fix zamanı |
| `lat`, `lon` | derece | 6 ondalık |
| `alt` | m | GPS irtifası, **HAM** (araç ekranındaki grafik kayan ortalamalı) |
| `hacc`, `vacc` | m | yatay / dikey doğruluk |
| `gps_kmh` | km/h | GPS'in hızı |
| `kmh` | km/h | aracın gösterge hızı (o anki son örnek) |
| `kw` | kW | anlık güç; **pozitif = tüketim, negatif = rejen** |
| `soc` | % | ondalıklı olabilir |
| `dist_m` | m | yolculuğun o ana kadarki mesafesi (aracın hesabı) |

Araçtaki üretici: `trip/TrackRecorder.kt`.

---

## 4. Okuyan istemciler — artımlı eşitleme

Drive REST v3 (`https://www.googleapis.com/drive/v3/files`).

1. **Liste:**
   `q = appProperties has { key='ex30' and value='trip' } and trashed=false and createdTime > '<imleç>'`
   `fields = nextPageToken, files(id, name, createdTime, size, md5Checksum, appProperties)`
   `orderBy = createdTime`, `pageSize = 1000`, `spaces = drive`. `nextPageToken` bitene
   kadar sayfala.
2. **İmleç:** görülen en büyük `createdTime` (RFC 3339). Bir sonraki istekte
   **5 dakika geri** çekerek kullan: Drive listesi yeni oluşturulan dosyayı kısa
   bir gecikmeyle gösterebiliyor. Bu örtüşme yüzünden aynı dosya iki listede
   gelebilir; **`appProperties.ex30id` ile tekle.**
3. İmleç yolculuk zamanı değil **Drive'a eklenme zamanı**: ağ yokken biriken ya da
   geriye dönük yüklenen eski bir yolculuk da yeni listede gelir.
4. Her özet (`tur=ozet`) için `GET files/<id>?alt=media` ile içeriği indir.
   İzleri (`tur=iz`) **gerektiğinde** (harita/grafik açılınca) indir ve yerelde
   sakla — dosyalar değişmez, bir kez inen yeter. `md5Checksum` ile doğrula.
5. Hata olursa imleci ilerletme: ancak listedeki her şey işlendiyse kaydet.
6. Hiçbir okuyan istemci Drive'a **yazmaz** (izni olsa da). Tek yazar araç.
7. **Boşluk denetimi.** Sürüm 2'deki `toplam` alanı Drive'da yok. Yerine: en az
   **günde bir** (ve kullanıcı "yeniden tara" dediğinde) imleçsiz **tam liste** al
   ve yerelde olmayanları indir. Tam liste yalnızca metadata; 1000 dosya başına tek
   istek, yüzlerce yolculukta bir-iki saniye.
8. `alt=media` yanıtı dosyanın **ham baytı**: özet düz JSON, iz gzip. Sürüm 2'deki
   zarf (`ok`/`hata`/`bytes`/`data` base64) YOK; bütünlük ölçütü `md5Checksum`.
   Başarı ölçütü de artık HTTP durum kodu (Drive gerçek kodlar döndürüyor):
   401 → token'ı yenile, bir kez tekrar dene · 403 `insufficientPermissions` →
   Drive izni yok, yeniden giriş · 403/429 `rateLimitExceeded` → geri çekil.

İlk eşitlemede (imleç yok) `createdTime` koşulu olmadan liste al.

### 4.1 Sürüm 2 istemcilerinin geçişi

Araç sürüm 2 dosyası **hiç üretmedi** (o sürüm yayınlanmadı), yani sürüm 2
önbelleklerinde Apps Script'in `trips.json` kopyasından başka kalıcı veri yok.
Geçişte eski ayar (adres + okuma anahtarı), imleç ve önbellek silinebilir;
yolculuklar sürüm 3'ten yeniden iner. Araç, hesap bağlanınca içindeki bütün
yolculukları (≤300) Drive'a gönderdiği için geçmiş kaybolmaz.

---

## 5. Araç tarafı (yazar) davranışı

- Hesap: Ayarlar ekranı → **Google hesabı** satırı → cihaz akışı
  (`screen/GoogleLinkScreen.kt`). Refresh token Android Keystore ile şifreli
  saklanır (`google/SecretBox.kt`). Bağlantı kesilince Google'da da iptal edilir.
- Yolculuk kapanınca giden kutusuna girer: `filesDir/outbox/<startEpoch>`. Önce
  özet, sonra iz; ikisi de yerleşince kutudan düşer (`sync/TripOutbox.kt`,
  `sync/DriveTripSender.kt`).
- Deneme anları: yolculuk kapanınca, servis başlarken, ağ gelince, hesap
  bağlanınca, başarısızlıkta 1 → 5 → 15 → 30 → 60 dk geri çekilmeyle.
- Hesap bağlanınca (ya da değişince) araçtaki **bütün** yolculuklar bir kerelik
  kutuya girer — geçmiş o hesabın Drive'ına taşınır.
- Kalıcı hata (Drive 400, bozuk yerel iz) o yolculuğu `<epoch>.red` yapar; kuyruğu
  tıkamaz. Ağ, yetki, hız sınırı geçicidir: kuyruk korunur.
