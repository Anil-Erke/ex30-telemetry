/**
 * EX30 Telemetry — Drive'a aktarim ucu (Google Apps Script web uygulamasi).
 *
 * NE ISE YARIYOR
 *   Araçtaki uygulama "Drive'a aktar" dugmesine basildiginda dort kayit
 *   dosyasini (calib.csv, calib-prev.csv, trips.json, records.json) buraya
 *   POST ediyor; script onlari asagidaki FOLDER_ID klasorune yaziyor.
 *
 * NEDEN OAUTH YOK
 *   Araçta tarayici yok ve Google gomulu WebView ile oturum acmayi reddediyor.
 *   Bu script "calistiran: ben" olarak yayinlandigi icin Drive erisimini
 *   SCRIPT yapiyor; uygulamanin bildigi tek sey bir URL ve bir anahtar.
 *
 * GUVENLIK MODELI — bunu anlamadan yayinlama
 *   URL + SECRET ikilisi, bu klasore YAZMA yetkisinin ta kendisi. Ikisi de
 *   AAB'nin icinde gomulu gidiyor. Bu yuzden:
 *     - FOLDER_ID uygulamada DEGIL, burada duruyor. URL sizsa bile saldirgan
 *       klasorun nerede oldugunu bilmiyor.
 *     - Yalnizca ALLOWED listesindeki dort ad yazilabiliyor; rastgele dosya
 *       birakilamiyor.
 *     - doGet yalnizca dosya ADLARINI donduruyor, ICERIK okutmuyor.
 *   Yine de bu klasoru baska hicbir sey icin kullanma.
 *
 * ONEMLI: Apps Script web uygulamasi HTTP durum kodu DONDUREMIYOR; her sey
 * 200 (ya da sonuca giden 302) olarak gidiyor. Istemci basariyi durum kodundan
 * degil, yanit govdesindeki `ok` alanindan anlamak ZORUNDA.
 */

/**
 * Drive klasoru — ornegin "My EX30 Trips" (URL'deki /folders/<bu kisim>).
 *
 * >>> FILL IN: CREATE AN EMPTY FOLDER IN YOUR OWN GOOGLE DRIVE AND PASTE ITS ID HERE.
 * >>> DOLDURUN: KENDI GOOGLE DRIVE'INIZDA BOS BIR KLASOR ACIP KIMLIGINI BURAYA YAPISTIRIN.
 */
const FOLDER_ID = 'PASTE-YOUR-DRIVE-FOLDER-ID-HERE';

/**
 * YAZMA anahtari — araçtaki uygulamanin kullandigi. AAB'nin icinde gomulu
 * gidiyor ve APK geri derlenebiliyor, yani bu anahtarin sizmasi mumkun.
 * Sizarsa saldirgan yalnizca bu klasordeki dort dosyayi EZEBILIR; okuyamaz.
 *
 * >>> FILL IN: A RANDOM LETTERS+DIGITS STRING. MUST MATCH driveSecret IN drive.properties.
 * >>> DOLDURUN: RASTGELE HARF+RAKAM DIZISI. drive.properties ICINDEKI driveSecret ILE AYNI OLMALI.
 */
const SECRET = 'PASTE-YOUR-WRITE-SECRET-HERE';

/**
 * OKUMA anahtari — EX30 Trip Viewer'in (bilgisayar uygulamasi) kullandigi.
 *
 * NEDEN AYRI: okuma yazmadan daha degerli (icerik disari cikiyor), ama bu
 * anahtar yalnizca kendi bilgisayarindaki ayar dosyasinda duruyor; hicbir
 * pakete gomulmuyor. Ikisini AYNI yapma — o an araçtaki APK'yi acan biri
 * butun yolculuk gecmisini de indirebilir hale gelir.
 *
 * >>> FILL IN: A SECOND, DIFFERENT RANDOM STRING. ENTER IT IN EX30 TRIP VIEWER'S DRIVE SETTINGS.
 * >>> DOLDURUN: IKINCI, FARKLI BIR RASTGELE DIZI. EX30 TRIP VIEWER'IN DRIVE AYARLARINA GIRILECEK.
 */
const READ_SECRET = 'PASTE-YOUR-READ-SECRET-HERE';

/**
 * Saat damgalarinin yazilacagi saat dilimi.
 * >>> CHANGE TO YOUR OWN TIME ZONE (IANA name, e.g. 'Europe/Berlin').
 * >>> KENDI SAAT DILIMINIZLE DEGISTIRIN (ornek: 'Europe/Istanbul').
 */
const TZ = 'UTC';

/**
 * Yazilmasina izin verilen dosyalar. Ad -> MIME.
 * Uygulamadaki DataExporter.exportAll listesiyle BIREBIR ayni olmali.
 */
const ALLOWED = {
  'calib.csv':      'text/csv',
  'calib-prev.csv': 'text/csv',
  'trips.json':     'application/json',
  'records.json':   'application/json',
};

/**
 * Yukleme gunlugu.
 *
 * NEDEN VAR: dosyalar sabit adla ve uzerine yazilarak tutuluyor, dolayisiyla
 * Drive'in gosterdigi tarih YUKLEME tarihi — icerigin yasini gizliyor.
 * (Ayni tuzak 23 ve 29 Agustos'ta yerel disa aktarimda yasandi; uygulamadaki
 * DataExporter.Result.ageSuffix o yuzden yazilmisti.) Bu dosya her yuklemede
 * icerigin EN YENI satirinin zamanini da kaydediyor.
 */
const LOG_NAME = 'yukleme-gunlugu.csv';
const LOG_HEADER = 'yuklemeZamani;dosya;bayt;icerikEnYeni\n';

/** Acilmis (gzip cozulmus) icerik icin ust sinir. calib.csv tavani 8 MiB. */
const MAX_BYTES = 32 * 1024 * 1024;


/**
 * Dosya yukleme.
 *
 * Sorgu parametreleri:
 *   k      paylasilan anahtar (zorunlu)
 *   name   ALLOWED icindeki dosya adi (zorunlu)
 *   bytes  ACILMIS icerigin bayt sayisi (zorunlu) — dogrulama icin
 *   gz     '1' ise govde gzip'li (varsayilan '1')
 *   newest icerigin en yeni satirinin zamani, epoch ms (istege bagli)
 *
 * Govde: base64. Ham ikili GONDERILEMEZ — e.postData.contents bir String ve
 * ikili veri charset donusumunde bozuluyor.
 */
/**
 * Yer tutucular degistirilmeden yayinlanirsa anahtar herkesin bildigi bir
 * metin olur. O durumda hicbir istegi kabul etme; ayrica iki anahtar ayni
 * olmamali (bkz. READ_SECRET aciklamasi).
 */
function configured() {
  const placeholder = /^PASTE-/;
  if (placeholder.test(FOLDER_ID) || placeholder.test(SECRET) || placeholder.test(READ_SECRET)) return false;
  return SECRET.length >= 20 && READ_SECRET.length >= 20 && SECRET !== READ_SECRET;
}

function doPost(e) {
  try {
    const p = (e && e.parameter) || {};
    if (!configured()) return fail('yapilandirilmadi / not configured');
    if (p.k !== SECRET) return fail('yetkisiz');

    const name = p.name;
    const mime = ALLOWED[name];
    if (!mime) return fail('izin verilmeyen dosya adi: ' + name);

    const expected = Number(p.bytes);
    if (!isFinite(expected) || expected <= 0) return fail('bytes parametresi gecersiz');
    if (expected > MAX_BYTES) return fail('dosya cok buyuk: ' + expected + ' bayt');

    if (!e.postData || !e.postData.contents) return fail('govde bos');

    let bytes;
    try {
      bytes = Utilities.base64Decode(e.postData.contents);
    } catch (err) {
      return fail('base64 cozulemedi: ' + err);
    }

    if (p.gz !== '0') {
      try {
        const gzBlob = Utilities.newBlob(bytes, 'application/x-gzip', name + '.gz');
        bytes = Utilities.ungzip(gzBlob).getBytes();
      } catch (err) {
        return fail('gzip acilamadi: ' + err);
      }
    }

    // YAZMADAN ONCE dogrula. Yarim kalmis bir yukleme, klasordeki saglam
    // dosyayi EZMEMELI — bu yuzden kontrol setContent'ten once yapiliyor.
    if (bytes.length !== expected) {
      return fail('boyut tutmadi: beklenen ' + expected + ', gelen ' + bytes.length);
    }

    const folder = DriveApp.getFolderById(FOLDER_ID);
    const file = upsert(folder, name, bytes, mime);

    // "Istisna atmadi" ile "dosya gercekten yazildi" ayni sey degil: geri oku.
    const stored = file.getBlob().getBytes().length;
    if (stored !== expected) {
      return fail('yazildi ama boyut tutmuyor: diskte ' + stored + ', beklenen ' + expected);
    }

    appendLog(folder, name, stored, p.newest);

    return ok({ name: name, bytes: stored, id: file.getId(), url: file.getUrl() });
  } catch (err) {
    return fail('beklenmeyen hata: ' + (err && err.message ? err.message : err));
  }
}


/**
 * Listeleme ve indirme. OKUMA anahtari ister — araçtaki yazma anahtari burada
 * ISE YARAMAZ.
 *
 *   ?k=<OKUMA>              -> klasor listesi (ad, boyut, tarih)
 *   ?k=<OKUMA>&file=<ad>    -> dosya icerigi (gzip + base64)
 *
 * Ilki tarayicidan acilabilir; saglik kontrolu olarak da kullaniliyor.
 */
function doGet(e) {
  try {
    const p = (e && e.parameter) || {};
    if (!configured()) return fail('yapilandirilmadi / not configured');
    if (p.k !== READ_SECRET) return fail('yetkisiz');

    const folder = DriveApp.getFolderById(FOLDER_ID);
    return p.file ? download(folder, p.file) : listing(folder);
  } catch (err) {
    return fail('beklenmeyen hata: ' + (err && err.message ? err.message : err));
  }
}


function listing(folder) {
  const list = [];
  const it = folder.getFiles();
  while (it.hasNext()) {
    const f = it.next();
    list.push({
      ad: f.getName(),
      bayt: f.getSize(),
      guncellendi: fmt(f.getLastUpdated()),
    });
  }
  list.sort(function (a, b) { return a.ad < b.ad ? -1 : 1; });
  return ok({ klasor: folder.getName(), dosyalar: list });
}


/**
 * Dosya icerigini dondurur.
 *
 * Govde JSON oldugu icin icerik gzip'lenip base64'e cevriliyor: hem ham metni
 * JSON'in icine kacis karakterleriyle gommekten kurtuluyoruz hem de 8 MiB'lik
 * calib.csv makul boyutta iniyor. Istemci `bytes` alanini ACILMIS boyutla
 * karsilastirip yarim inen dosyayi fark edebiliyor.
 *
 * Yalnizca ALLOWED listesindeki adlar ve yukleme gunlugu verilir.
 */
function download(folder, name) {
  if (!ALLOWED[name] && name !== LOG_NAME) {
    return fail('izin verilmeyen dosya adi: ' + name);
  }
  const it = folder.getFilesByName(name);
  if (!it.hasNext()) return fail('dosya yok: ' + name);

  const f = it.next();
  const bytes = f.getBlob().getBytes();
  const gz = Utilities.gzip(Utilities.newBlob(bytes, 'application/octet-stream', name));

  return ok({
    name: name,
    bytes: bytes.length,
    gz: true,
    guncellendi: fmt(f.getLastUpdated()),
    data: Utilities.base64Encode(gz.getBytes()),
  });
}


/**
 * Ayni adda dosya varsa ICERIGINI degistirir, yoksa olusturur.
 *
 * NEDEN uzerine yaziyoruz: her yuklemede tarihli yeni dosya birakmak klasoru
 * birkac haftada onlarca ayni icerikli dosyayla dolduruyor. Uzerine yazinca
 * klasorde hep dort dosya kaliyor ve Drive eski surumleri kendi surum
 * gecmisinde tutuyor.
 *
 * SINIR: Drive, Google formatinda olmayan dosyalarin eski surumlerini suresiz
 * saklamayabiliyor. Belirli bir anin kaydi senin icin kritikse o surumu Drive
 * arayuzunden "surumu sakla" ile sabitle ya da dosyayi elle kopyala.
 */
function upsert(folder, name, bytes, mime) {
  const it = folder.getFilesByName(name);
  if (it.hasNext()) {
    const f = it.next();
    // setContent metin aliyor; dort dosyanin dordu de UTF-8 metin.
    f.setContent(Utilities.newBlob(bytes).getDataAsString('UTF-8'));
    return f;
  }
  return folder.createFile(Utilities.newBlob(bytes, mime, name));
}


/**
 * Gunluge bir satir ekler. Gunluk yazilamazsa yukleme YINE DE basarili sayilir
 * — asil is dosyanin kendisi.
 */
function appendLog(folder, name, bytes, newestMs) {
  try {
    const newest = newestMs && isFinite(Number(newestMs))
      ? fmt(new Date(Number(newestMs)))
      : '';
    const line = [fmt(new Date()), name, bytes, newest].join(';') + '\n';

    const it = folder.getFilesByName(LOG_NAME);
    if (it.hasNext()) {
      const f = it.next();
      f.setContent(f.getBlob().getDataAsString('UTF-8') + line);
    } else {
      folder.createFile(LOG_NAME, LOG_HEADER + line, 'text/csv');
    }
  } catch (err) {
    console.warn('gunluk yazilamadi: ' + err);
  }
}


function fmt(date) {
  return Utilities.formatDate(date, TZ, 'yyyy-MM-dd HH:mm:ss');
}

function ok(payload) {
  payload.ok = true;
  return json(payload);
}

function fail(message) {
  return json({ ok: false, hata: message });
}

function json(payload) {
  return ContentService
    .createTextOutput(JSON.stringify(payload))
    .setMimeType(ContentService.MimeType.JSON);
}
