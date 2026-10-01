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
 *     - doGet (listeleme ve INDIRME) yalnizca ayri OKUMA anahtariyla
 *       calisiyor; APK'daki yazma anahtari icerik okutmuyor.
 *     - Yolculuk dosyalari (protokol 2) yalnizca OLUSTURULABILIYOR: sizan
 *       anahtarla gecmis silinemez ya da degistirilemez.
 *   Yine de bu klasoru baska hicbir sey icin kullanma.
 *
 * ONEMLI: Apps Script web uygulamasi HTTP durum kodu DONDUREMIYOR; her sey
 * 200 (ya da sonuca giden 302) olarak gidiyor. Istemci basariyi durum kodundan
 * degil, yanit govdesindeki `ok` alanindan anlamak ZORUNDA.
 *
 * PROTOKOL 2 (2026-09-28) — tam tanim: drive-sync/PROTOKOL.md
 *   Surum 1'in dort sabit dosyasi AYNEN duruyor (EX30 Trip Viewer ve eski
 *   uygulama surumleri bozulmasin). Ustune YOLCULUK DOSYALARI geldi:
 *     yolculuklar/<yyyy>/<MM>/trip-<startEpoch>.json     ozet (Trip.toJson)
 *     yolculuklar/<yyyy>/<MM>/trip-<startEpoch>.csv.gz   GPS izi
 *     yolculuklar/dizin.jsonl                            her dosyaya bir satir
 *   Bunlar YALNIZCA OLUSTURULUR, UZERINE ASLA YAZILMAZ. Ayni ad tekrar gelirse
 *   "mevcut" denip basarili sayilir — istemci guvenle yeniden deneyebilir,
 *   sizan bir yazma anahtari da gecmisi SILEMEZ, en fazla cop ekleyebilir.
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
 * >>> FILL IN: A RANDOM LETTERS+DIGITS STRING (40 chars). KEEP IT OUT OF THE REPOSITORY.
 * >>> DOLDURUN: RASTGELE HARF+RAKAM DIZISI (40 karakter). DEPOYA EKLEMEYIN.
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

/** Yanitlarin hepsinde `protokol` alani olarak gidiyor. */
const PROTOCOL = 2;

/** Yolculuk dosyalarinin kok klasoru (FOLDER_ID'nin altinda). */
const TRIPS_DIR = 'yolculuklar';

/**
 * Yolculuk dosyalarinin dizini: her satir bir JSON nesnesi.
 *
 * NEDEN VAR: listelemek icin yil/ay klasorlerini gezip her dosyaya ayri ayri
 * sormak DriveApp'te dosya basina onlarca ms; birkac yuz yolculukta liste
 * saniyeler, binlercesinde dakikalar surerdi. Dizin tek dosya.
 * Bozulursa `?dizin=yeniden` klasorleri gezip bastan kurar.
 */
const INDEX_NAME = 'dizin.jsonl';

/** trip-<13 haneli epoch ms>.json | .csv.gz */
const TRIP_RE = /^trip-(\d{13})\.(json|csv\.gz)$/;

/** Bundan eski "yolculuk" reddedilir (2024-01-01): saat bozuk bir istemciye karsi. */
const MIN_TRIP_EPOCH = Date.UTC(2024, 0, 1);

/** Kilit icin azami bekleme. Ayni anda iki yukleme dizini ezmesin. */
const LOCK_WAIT_MS = 30000;


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
    const tripMatch = TRIP_RE.exec(name || '');
    if (tripMatch) return postTrip(e, p, name, Number(tripMatch[1]), tripMatch[2]);

    const mime = ALLOWED[name];
    if (!mime) return fail('izin verilmeyen dosya adi: ' + name);

    // YAZMADAN ONCE dogrula. Yarim kalmis bir yukleme, klasordeki saglam
    // dosyayi EZMEMELI — bu yuzden kontrol setContent'ten once yapiliyor.
    const body = decodeBody(e, p, name);
    if (body.error) return fail(body.error);
    const bytes = body.plain;
    const expected = bytes.length;

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
 *   ?k=<OKUMA>                              -> kok klasor listesi (surum 1)
 *   ?k=<OKUMA>&file=<ad>                    -> dosya icerigi (gzip + base64);
 *                                              <ad> trip-<epoch>.json|.csv.gz da olabilir
 *   ?k=<OKUMA>&liste=yolculuklar&sonra=<ms> -> dizin: sunucuya <ms>'den SONRA
 *                                              eklenen yolculuk dosyalari
 *   ?k=<OKUMA>&dizin=yeniden                -> dizini klasorlerden bastan kurar
 *
 * Ilki tarayicidan acilabilir; saglik kontrolu olarak da kullaniliyor.
 */
function doGet(e) {
  try {
    const p = (e && e.parameter) || {};
    if (!configured()) return fail('yapilandirilmadi / not configured');
    if (p.k !== READ_SECRET) return fail('yetkisiz');

    const folder = DriveApp.getFolderById(FOLDER_ID);
    if (p.liste === 'yolculuklar') return tripListing(folder, p.sonra);
    if (p.dizin === 'yeniden') return rebuildIndex(folder);
    if (p.file) {
      const m = TRIP_RE.exec(p.file);
      return m ? downloadTrip(folder, p.file, Number(m[1]), m[2]) : download(folder, p.file);
    }
    return listing(folder);
  } catch (err) {
    return fail('beklenmeyen hata: ' + (err && err.message ? err.message : err));
  }
}

/**
 * Govdeyi cozer ve dogrular: base64 -> (gz=1 ise) gzip ac -> boyut kontrolu.
 *
 * Donus: { wire: base64'ten cikan bayt, plain: acilmis icerik, gz } ya da
 * { error: '...' }. Tek yardimci, cunku surum 1 dosyalari ile yolculuk
 * dosyalari AYNI kurallarla dogrulanmali.
 */
function decodeBody(e, p, name) {
  const expected = Number(p.bytes);
  if (!isFinite(expected) || expected <= 0) return { error: 'bytes parametresi gecersiz' };
  if (expected > MAX_BYTES) return { error: 'dosya cok buyuk: ' + expected + ' bayt' };

  if (!e.postData || !e.postData.contents) return { error: 'govde bos' };

  let wire;
  try {
    wire = Utilities.base64Decode(e.postData.contents);
  } catch (err) {
    return { error: 'base64 cozulemedi: ' + err };
  }

  let plain = wire;
  if (p.gz !== '0') {
    try {
      const gzBlob = Utilities.newBlob(wire, 'application/x-gzip', name + '.gz');
      plain = Utilities.ungzip(gzBlob).getBytes();
    } catch (err) {
      return { error: 'gzip acilamadi: ' + err };
    }
  }

  if (plain.length !== expected) {
    return { error: 'boyut tutmadi: beklenen ' + expected + ', gelen ' + plain.length };
  }
  return { wire: wire, plain: plain, gz: p.gz !== '0' };
}


/**
 * Yolculuk dosyasi yukleme (protokol 2). YALNIZCA OLUSTURUR.
 *
 *   trip-<epoch>.json    ozet; JSON olmali ve startEpoch adla ayni olmali.
 *                        ACILMIS hali saklaniyor (Drive'da okunabilsin).
 *   trip-<epoch>.csv.gz  GPS izi; ilk satiri "# ex30-track;<surum>;<epoch>".
 *                        GZIP'LI saklaniyor (izler tekrarli sayi, ~5x kuculuyor).
 *
 * Ayni ad zaten varsa DOKUNULMUYOR ve `mevcut: true` ile basari donuyor.
 * Neden hata degil: istemcinin yeniden denemesi (yanit yolda kayboldu, head
 * unit uykuya gecti) zararsiz olmali. Neden uzerine yazma degil: yazma
 * anahtari APK'da ve sizabilir; sizarsa gecmis silinememeli.
 */
function postTrip(e, p, name, epoch, ext) {
  if (epoch < MIN_TRIP_EPOCH || epoch > Date.now() + 24 * 3600 * 1000) {
    return fail('gecersiz yolculuk zamani: ' + epoch);
  }
  const kind = ext === 'json' ? 'ozet' : 'iz';

  const body = decodeBody(e, p, name);
  if (body.error) return fail(body.error);

  // Icerik adla tutarli mi? Cop dosyayi ve yanlis eslesmeyi burada kes.
  const text = Utilities.newBlob(body.plain).getDataAsString('UTF-8');
  if (kind === 'ozet') {
    let obj;
    try {
      obj = JSON.parse(text);
    } catch (err) {
      return fail('ozet JSON degil: ' + err);
    }
    if (!obj || Number(obj.startEpoch) !== epoch) {
      return fail('ozetin startEpoch degeri adla tutmuyor');
    }
  } else {
    const head = text.substring(0, 80).split('\n')[0].split(';');
    if (head[0] !== '# ex30-track' || Number(head[2]) !== epoch) {
      return fail('iz basligi gecersiz ya da adla tutmuyor');
    }
  }

  const lock = LockService.getScriptLock();
  if (!lock.tryLock(LOCK_WAIT_MS)) return fail('sunucu mesgul, tekrar dene');
  try {
    const root = DriveApp.getFolderById(FOLDER_ID);
    const folder = monthFolder(root, epoch, true);

    const existing = folder.getFilesByName(name);
    if (existing.hasNext()) {
      const f = existing.next();
      return ok({ name: name, mevcut: true, id: f.getId(), bayt: body.plain.length });
    }

    let stored;
    if (kind === 'ozet') {
      stored = Utilities.newBlob(body.plain, 'application/json', name);
    } else if (body.gz) {
      stored = Utilities.newBlob(body.wire, 'application/gzip', name);
    } else {
      stored = Utilities.gzip(Utilities.newBlob(body.plain, 'text/csv', name), name);
      stored.setContentType('application/gzip');
    }
    const want = stored.getBytes().length;
    const file = folder.createFile(stored);

    // "Istisna atmadi" ile "dosya gercekten yazildi" ayni sey degil: geri oku.
    const got = file.getBlob().getBytes().length;
    if (got !== want) {
      file.setTrashed(true);
      return fail('yazildi ama boyut tutmuyor: diskte ' + got + ', beklenen ' + want);
    }

    // `olusturuldu` Drive'in damgasi DEGIL, kilit altindaki Date.now():
    // listeleme de ayni kilidi aliyor, boylece bir listenin dondurdugu
    // `sunucuZamani`'ndan sonra dizine giren her kayit >= o degeri tasiyor.
    // Drive damgasi kullanilsaydi, listeyle eszamanli bir yukleme imlecin
    // gerisinde kalip bir daha hic listelenmeyebilirdi.
    appendIndex(tripsRoot(root, true), {
      ad: name,
      epoch: epoch,
      tur: kind,
      bayt: body.plain.length,
      id: file.getId(),
      olusturuldu: Date.now(),
    });
    appendLog(root, name, body.plain.length, epoch);

    return ok({ name: name, mevcut: false, id: file.getId(), bayt: body.plain.length });
  } finally {
    lock.releaseLock();
  }
}

/**
 * Dizindeki kayitlar; `sonra` verilirse yalnizca sunucuya ondan SONRA eklenenler.
 *
 * NEDEN yolculuk zamanina gore degil de EKLENME zamanina gore: araç bir
 * yolculugu gunler sonra yukleyebilir (ag yoktu, geriye donuk yukleme). Imlec
 * yolculuk zamani olsaydi o gec gelen yolculuk bir daha hic listelenmezdi.
 * Istemci bir sonraki istekte yanittaki `sunucuZamani`'ni `sonra` olarak
 * gonderir.
 *
 * Suzme `>=`: ayni milisaniyede eklenen kayit kacmasin. Bedeli, sinirdaki
 * kaydin bir sonraki listede TEKRAR gelebilmesi — istemci `ad` ile teklemeli.
 * Kilit, eszamanli bir yuklemenin imlecin gerisinde kalmasini onluyor
 * (bkz. postTrip'teki `olusturuldu` notu).
 */
function tripListing(root, sonra) {
  const since = Number(sonra) || 0;
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(LOCK_WAIT_MS)) return fail('sunucu mesgul, tekrar dene');
  try {
    const now = Date.now();
    const all = readIndex(tripsRoot(root, false));
    const list = all.filter(function (r) { return r.olusturuldu >= since; });
    list.sort(function (a, b) { return a.epoch - b.epoch || (a.tur < b.tur ? -1 : 1); });
    return ok({ sunucuZamani: now, toplam: all.length, kayitlar: list });
  } finally {
    lock.releaseLock();
  }
}


/** Yolculuk dosyasini indirir. Yanit bicimi surum 1'deki download() ile ayni. */
function downloadTrip(root, name, epoch, ext) {
  const folder = monthFolder(root, epoch, false);
  if (!folder) return fail('dosya yok: ' + name);
  const it = folder.getFilesByName(name);
  if (!it.hasNext()) return fail('dosya yok: ' + name);

  const f = it.next();
  const stored = f.getBlob().getBytes();
  let gzBytes;
  let plainLength;
  if (ext === 'json') {
    gzBytes = Utilities.gzip(Utilities.newBlob(stored, 'application/octet-stream', name)).getBytes();
    plainLength = stored.length;
  } else {
    // Iz zaten gzip'li saklaniyor: oldugu gibi gonder, acilmis boyutu hesapla
    // ki istemci yarim inen dosyayi fark edebilsin.
    gzBytes = stored;
    plainLength = Utilities.ungzip(
      Utilities.newBlob(stored, 'application/x-gzip', name)
    ).getBytes().length;
  }
  return ok({
    name: name,
    bytes: plainLength,
    gz: true,
    guncellendi: fmt(f.getLastUpdated()),
    data: Utilities.base64Encode(gzBytes),
  });
}


/**
 * Dizini yil/ay klasorlerini gezerek bastan yazar. Yavas; yalnizca onarim icin.
 *
 * `olusturuldu` NEDEN Drive'in olusturulma tarihi DEGIL: istemciler
 * `sonra=<imlec>` ile yalnizca imleclerinden sonra eklenenleri istiyor. Drive
 * tarihi bir istemcinin imlecinden ESKI olabilir (dosya elle eklendi, istemci
 * arada esitledi, dizin sonra onarildi) ve o kayit artimli listede bir daha
 * hic gorunmez. 2026-09-28'de yasandi: sunucuda 54 dosya, telefonda 0.
 *
 * Bu yuzden:
 *   - Onceki dizinde olan kayit eski satirini (ve `olusturuldu`'sunu) korur:
 *     istemciler onu zaten gordu, tekrar "yeni" gorunmesin.
 *   - Dizine ILK KEZ giren kayit onarim aninin Date.now()'ini alir. Kilit
 *     altinda; boylece her istemcinin son `sunucuZamani`'ndan sonra geliyor
 *     (postTrip'teki `olusturuldu` notuyla ayni gerekce).
 *   - Ayni adda iki dosya (elle iki kez yukleme) tek satir: `toplam` sisip
 *     istemcileri bosuna tam listeye zorlamasin.
 */
function rebuildIndex(root) {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(LOCK_WAIT_MS)) return fail('sunucu mesgul, tekrar dene');
  try {
    const trips = tripsRoot(root, true);
    const previous = {};
    readIndex(trips).forEach(function (r) {
      if (r && r.ad && isFinite(Number(r.olusturuldu))) previous[r.ad] = r;
    });
    const now = Date.now();
    const rows = [];
    const seen = {};
    let added = 0;
    const years = trips.getFolders();
    while (years.hasNext()) {
      const months = years.next().getFolders();
      while (months.hasNext()) {
        const files = months.next().getFiles();
        while (files.hasNext()) {
          const f = files.next();
          const name = f.getName();
          const m = TRIP_RE.exec(name);
          if (!m || seen[name]) continue;
          seen[name] = true;
          const old = previous[name];
          if (!old) added++;
          rows.push({
            ad: name,
            epoch: Number(m[1]),
            tur: m[2] === 'json' ? 'ozet' : 'iz',
            // Eski satirda acilmis boyut var; yeni izde GZIP'LI boyut: her
            // dosyayi acmak pahali, onarimda yeterli.
            bayt: old && isFinite(Number(old.bayt)) ? old.bayt : f.getSize(),
            id: f.getId(),
            olusturuldu: old ? Number(old.olusturuldu) : now,
          });
        }
      }
    }
    rows.sort(function (a, b) { return a.epoch - b.epoch || (a.tur < b.tur ? -1 : 1); });
    writeIndex(trips, rows);
    return ok({ kayit: rows.length, yeni: added });
  } finally {
    lock.releaseLock();
  }
}


function tripsRoot(root, create) {
  return childFolder(root, TRIPS_DIR, create);
}

/** yolculuklar/<yyyy>/<MM> — ay yolculugun BASLANGICINA gore, TZ saatinde. */
function monthFolder(root, epoch, create) {
  const trips = tripsRoot(root, create);
  if (!trips) return null;
  const d = new Date(epoch);
  const year = childFolder(trips, Utilities.formatDate(d, TZ, 'yyyy'), create);
  if (!year) return null;
  return childFolder(year, Utilities.formatDate(d, TZ, 'MM'), create);
}

function childFolder(parent, name, create) {
  const it = parent.getFoldersByName(name);
  if (it.hasNext()) return it.next();
  return create ? parent.createFolder(name) : null;
}

function readIndex(trips) {
  if (!trips) return [];
  const it = trips.getFilesByName(INDEX_NAME);
  if (!it.hasNext()) return [];
  const text = it.next().getBlob().getDataAsString('UTF-8');
  const rows = [];
  text.split('\n').forEach(function (line) {
    if (!line.trim()) return;
    try {
      rows.push(JSON.parse(line));
    } catch (err) {
      // Tek bozuk satir butun dizini dusurmesin.
      console.warn('dizin satiri okunamadi: ' + line);
    }
  });
  return rows;
}

/** Cagiran kilidi tutuyor olmali. */
function appendIndex(trips, row) {
  const line = JSON.stringify(row) + '\n';
  const it = trips.getFilesByName(INDEX_NAME);
  if (it.hasNext()) {
    const f = it.next();
    f.setContent(f.getBlob().getDataAsString('UTF-8') + line);
  } else {
    trips.createFile(INDEX_NAME, line, 'application/x-ndjson');
  }
}

function writeIndex(trips, rows) {
  const text = rows.map(function (r) { return JSON.stringify(r); }).join('\n') +
    (rows.length ? '\n' : '');
  const it = trips.getFilesByName(INDEX_NAME);
  if (it.hasNext()) it.next().setContent(text);
  else trips.createFile(INDEX_NAME, text, 'application/x-ndjson');
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
  payload.protokol = PROTOCOL;
  return json(payload);
}

function fail(message) {
  return json({ ok: false, hata: message, protokol: PROTOCOL });
}

function json(payload) {
  return ContentService
    .createTextOutput(JSON.stringify(payload))
    .setMimeType(ContentService.MimeType.JSON);
}
