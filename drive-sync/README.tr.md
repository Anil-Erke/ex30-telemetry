# Drive'a aktarım ucu — kurulum

[English](README.md) · **Türkçe**

> **Eski düzen.** 0.8 sürümünden beri araçtaki uygulama bu Apps Script ucuna
> yazmıyor. Her kullanıcı araçta kendi Google hesabını bağlıyor ve yolculuklar
> doğrudan kendi Drive'ına gidiyor (protokol 3, [`PROTOKOL.md`](PROTOKOL.md);
> kurulum ana [README](../README.tr.md#google-drive-eşitleme-isteğe-bağlı)
> dosyasında). Bu uç yalnızca `trips.json`'u hâlâ buradan okuyan EX30 Trip Viewer
> sürümleri için gerekli. **Yeni kurulumda bunu yapmayın.**
>
> `Kod.gs` ayrıca yayınlanmamış bir ara protokolün (sürüm 2, `yolculuklar/`
> altındaki yolculuk dosyaları) kodunu içeriyor; yayınlanan hiçbir uygulama
> sürümü onu kullanmıyor.

`Kod.gs`, araçtaki uygulamadan gelen kayıt dosyalarını **kendi** Google Drive
klasörünüze (örneğin **My EX30 Trips**) yazan Apps Script web uygulaması. Bu
dosya onu yayına almanın adımları. Bu kurulum **isteğe bağlıdır**; yapılmazsa
uygulama çalışır, yalnızca *Drive'a aktar* düğmesi "yapılandırılmadı" der.

Klasör kimliği `Kod.gs` içindeki `FOLDER_ID` sabitinde durur.
**KENDİ DRIVE'INIZDA BOŞ BİR KLASÖR AÇIN** ve adres çubuğundaki
`/folders/<KİMLİK>` kısmını oraya yapıştırın.

---

## 1. İki anahtar üret

**İki tane** gerekiyor, farklı olmaları önemli:

| Anahtar | Kim kullanıyor | Nerede duruyor |
|---|---|---|
| `SECRET` (yazma) | araçtaki EX30 Telemetry | AAB'nin içinde — **sızabilir** |
| `READ_SECRET` (okuma) | bilgisayardaki EX30 Trip Viewer | yalnızca `%LOCALAPPDATA%` |

Ayrı tutmanın sebebi: APK geri derlenebiliyor. Yazma anahtarı sızarsa saldırgan
klasördeki dosyaları ezebilir ama **okuyamaz**. İkisini aynı yaparsan APK'yı açan
biri bütün yolculuk geçmişini indirebilir hâle gelir.

URL'de sorgu parametresi olarak gidecekleri için **yalnızca harf ve rakam**
kullan; `&`, `=`, `+`, `/` karakterleri URL'i bozar. PowerShell'de iki kez
çalıştır:

```powershell
-join ((48..57)+(65..90)+(97..122) | Get-Random -Count 40 | ForEach-Object {[char]$_})
```

Çıkan değerleri sakla. Hiçbir yere paylaşma, depoya ekleme.

## 2. Script'i oluştur

1. [script.google.com](https://script.google.com) → **Yeni proje**
2. Projeye ad ver: `EX30 Telemetry Drive Ucu`
3. Soldaki `Kod.gs` dosyasının içeriğini sil, bu klasördeki `Kod.gs`'i olduğu gibi yapıştır
4. **`FOLDER_ID`, `SECRET` ve `READ_SECRET`** satırlarındaki `PASTE-…` yer tutucularını
   kendi klasör kimliğin ve 1. adımdaki iki anahtarla değiştir. İstersen `TZ`'yi de kendi
   saat dilimine çevir. Yer tutucular duruyorsa script her isteği reddeder.
5. Kaydet (Ctrl+S)
6. Proje kökündeki `drive.properties.example` dosyasını `drive.properties` olarak kopyala;
   `driveUrl` = 3. adımda çıkan adres, `driveSecret` = **yazma** anahtarı.

## 3. Web uygulaması olarak yayınla

**Dağıt** → **Yeni dağıtım** → dişli → **Web uygulaması**, sonra:

| Alan | Değer |
|---|---|
| Açıklama | `v1` |
| Çalıştıran | **Ben** (kendi Google hesabınız) |
| Erişimi olan | **Herkes** |

"Çalıştıran: Ben" şart — Drive'a yazan script'in kendisi olacak, araçtaki
uygulamanın Google kimliği yok. "Erişimi olan: Herkes" de şart; araçtaki uygulama
oturum açmadan POST edecek.

İlk dağıtımda Google yetki isteyecek ve **"Bu uygulama doğrulanmadı"** uyarısı
çıkacak. Kendi hesabında kendi yazdığın script olduğu için beklenen davranış:
**Gelişmiş** → *(proje adı)* **sayfasına git** → **İzin ver**.

Sonunda verilen `https://script.google.com/macros/s/<UZUN-ID>/exec` adresini sakla.

> **Kodu her değiştirdiğinde yeni bir SÜRÜM yayınlamak zorundasın.**
> `Ctrl+S` ile kaydetmek `/exec` adresinin sunduğu kodu değiştirmiyor —
> orası son *dağıtılmış* sürümü servis etmeye devam ediyor. Doğrusu:
> **Dağıt → Dağıtımları yönet → kalem simgesi → Sürüm: Yeni sürüm → Dağıt.**
> Mevcut dağıtımı bu şekilde güncellersen **URL aynı kalır**. "Yeni dağıtım"
> dersen YENİ bir URL üretilir ve uygulama eski koda bakmaya devam eder —
> hiçbir hata vermeden.

## 4. Doğrula

**Tarayıcıdan (okuma):** adresi `?k=<OKUMA-ANAHTARI>` ile aç. Klasör boşken beklenen:

```json
{"klasor":"My EX30 Trips","dosyalar":[],"ok":true}
```

`{"ok":false,"hata":"yetkisiz"}` görüyorsan anahtar tutmuyor. **Yazma anahtarı
burada çalışmaz** — bu bilinçli.

**PowerShell'den (yazma):**

```powershell
$url    = 'https://script.google.com/macros/s/<UZUN-ID>/exec'
$secret = '<YAZMA-ANAHTARI>'
$icerik = '[]'
$bayt   = [Text.Encoding]::UTF8.GetByteCount($icerik)
$govde  = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($icerik))
Invoke-RestMethod -Method Post -ContentType 'text/plain; charset=utf-8' -Body $govde -Uri "$($url)?k=$secret&name=records.json&bytes=$bayt&gz=0"
```

Beklenen yanıt `ok = True` ve klasörde `records.json` (2 bayt) + `yukleme-gunlugu.csv`.
**Bu gerçek bir dosya yazar** — test bittiğinde ikisini de sil, yoksa araçtan gelen
ilk gerçek veriyle karışır.

`gz=0` yalnızca test içindir; uygulama gövdeyi her zaman gzip'leyip `gz=1` gönderecek.

## 5. Hata ayıklama

Apps Script sol menüsünde **Yürütmeler** — her isteğin ne zaman geldiği, ne kadar
sürdüğü ve `console.warn` çıktıları orada.

---

## Protokol özeti

**Yazma — araçtaki uygulama.** `POST <URL>?k=&name=&bytes=&gz=1&newest=`

| Parametre | Anlamı |
|---|---|
| `k` | paylaşılan anahtar |
| `name` | `calib.csv`, `calib-prev.csv`, `trips.json`, `records.json` — başkası reddedilir |
| `bytes` | **açılmış** içeriğin bayt sayısı; script bununla doğrular |
| `gz` | `1` = gövde gzip'li (varsayılan), `0` = düz |
| `newest` | içeriğin en yeni satırının zamanı (epoch ms), günlüğe yazılır |

Gövde: **base64**. Ham ikili gönderilemez — Apps Script `e.postData.contents`'i
String olarak veriyor ve ikili veri charset dönüşümünde bozuluyor. Sıralama:
`dosya → gzip → base64 → POST`.

**Yanıt:** her zaman JSON.

```json
{"ok":true,"name":"trips.json","bytes":48213,"id":"...","url":"..."}
{"ok":false,"hata":"boyut tutmadi: beklenen 48213, gelen 12044"}
```

**Okuma — EX30 Trip Viewer.** `GET <URL>?k=<OKUMA>&file=trips.json`

```json
{"ok":true,"name":"trips.json","bytes":48213,"gz":true,"guncellendi":"...","data":"H4sIA..."}
```

`data` = base64(gzip(içerik)); `bytes` **açılmış** boyut. İstemci açtıktan sonra
boyutu karşılaştırıyor, tutmazsa yarım inmiş sayıp reddediyor. `file` olmadan
çağrılırsa klasör listesi döner. Okunabilen adlar: `ALLOWED` listesindeki dört
dosya + `yukleme-gunlugu.csv`.

İstemci için iki kural:

1. **Başarıyı HTTP durum kodundan anlama.** Apps Script durum kodu döndüremiyor;
   hata da 200 ile geliyor. Ölçüt gövdedeki `ok` alanı.
2. **302'yi takip et.** `/exec` isteği `doPost`'u çalıştırıyor, sonucu ise
   `script.googleusercontent.com` üzerinden 302 ile veriyor. Yönlendirme takip
   edilmezse gövde boş gelir ve bu sessizce "başarılı" sanılır. `HttpURLConnection`
   çoğu durumda kendisi takip ediyor, ama boş gövdeyi başarı sayma — `ok` alanını
   bulamadıysan hata ver.

## Güvenlik sınırı

URL + **yazma** anahtarı AAB'nin içinde gömülü gidiyor; APK'yı açan biri bu
klasöre yazabilir. Riski sınırlayan üç şey `Kod.gs`'te:

- Klasör kimliği uygulamada değil script'te
- Yalnızca dört sabit dosya adı yazılabiliyor
- Okuma ayrı bir anahtar istiyor; yazma anahtarıyla içerik indirilemiyor

Okuma anahtarı hiçbir pakete gömülmüyor, yalnızca Viewer'ın
`%LOCALAPPDATA%\EX30TripViewer\drive.json` dosyasında duruyor.

Bu klasörü başka hiçbir şey için kullanma.
