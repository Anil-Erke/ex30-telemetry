/**
 * Kod.gs'i Node'da, bellekteki Apps Script taklitleriyle calistiran testler.
 *
 * NEDEN: Apps Script'te birim testi yok ve her deneme "Yeni surum dagit"
 * gerektiriyor; hatali bir surum araçtaki yuklemeleri sessizce dusurur.
 * Bu dosya Drive'a hic dokunmadan protokolu dogruluyor.
 *
 *   node drive-sync/test/kod.test.js
 *
 * Taklitler yalnizca Kod.gs'in kullandigi yuzeyi kapsiyor; yeni bir Apps
 * Script cagrisi eklenirse burada da karsiligi yazilmali.
 */
'use strict';

const fs = require('fs');
const path = require('path');
const vm = require('vm');
const zlib = require('zlib');
const assert = require('assert');

// --- Apps Script taklitleri ----------------------------------------------

const toSigned = (buf) => Array.from(buf, (b) => (b > 127 ? b - 256 : b));
const toBuf = (bytes) => Buffer.from(bytes.map((b) => b & 0xff));

function Blob(bytes, type, name) {
  let data = typeof bytes === 'string' ? toSigned(Buffer.from(bytes, 'utf8')) : bytes.slice();
  return {
    getBytes: () => data.slice(),
    getDataAsString: () => toBuf(data).toString('utf8'),
    getContentType: () => type,
    setContentType(t) { type = t; return this; },
    getName: () => name,
  };
}

let clock = Date.UTC(2026, 8, 28, 12, 0, 0);
let nextId = 1;

function File(blob, nameOverride) {
  let bytes = blob.getBytes();
  const created = new Date(clock++);
  let updated = created;
  const id = 'f' + nextId++;
  return {
    trashed: false,
    getName: () => nameOverride || blob.getName(),
    getBlob: () => Blob(bytes, blob.getContentType(), nameOverride || blob.getName()),
    getSize: () => bytes.length,
    getId: () => id,
    getUrl: () => 'https://drive/' + id,
    getDateCreated: () => created,
    getLastUpdated: () => updated,
    setContent(s) { bytes = toSigned(Buffer.from(s, 'utf8')); updated = new Date(clock++); },
    setTrashed(v) { this.trashed = v; },
  };
}

function iter(list) {
  let i = 0;
  return { hasNext: () => i < list.length, next: () => list[i++] };
}

function Folder(name) {
  const files = [];
  const folders = [];
  const live = () => files.filter((f) => !f.trashed);
  return {
    getName: () => name,
    files: live,
    folders: () => folders,
    getFiles: () => iter(live()),
    getFolders: () => iter(folders),
    getFilesByName: (n) => iter(live().filter((f) => f.getName() === n)),
    getFoldersByName: (n) => iter(folders.filter((f) => f.getName() === n)),
    createFolder(n) { const f = Folder(n); folders.push(f); return f; },
    createFile(a, content, mime) {
      const f = typeof a === 'string' ? File(Blob(content, mime, a), a) : File(a);
      files.push(f);
      return f;
    },
  };
}

function makeContext({ keepPlaceholders = false } = {}) {
  const root = Folder('My EX30 Trips');
  // Kod.gs'in Date.now()'i da taklit saatten okusun: aksi halde dosya
  // damgalari (taklit saat) ile sunucuZamani (gercek saat) karsilastirilamaz.
  const FakeDate = class extends Date {
    static now() { return clock++; }
  };
  const ctx = {
    console,
    Date: FakeDate,
    JSON,
    Number,
    isFinite,
    Utilities: {
      base64Decode: (s) => toSigned(Buffer.from(s, 'base64')),
      base64Encode: (bytes) => toBuf(bytes).toString('base64'),
      newBlob: (b, t, n) => Blob(b, t, n),
      gzip: (blob, n) => Blob(toSigned(zlib.gzipSync(toBuf(blob.getBytes()))), 'application/x-gzip', n || blob.getName()),
      ungzip: (blob) => Blob(toSigned(zlib.gunzipSync(toBuf(blob.getBytes()))), 'application/octet-stream', blob.getName()),
      formatDate: (d, tz, fmt) => {
        const parts = Object.fromEntries(
          new Intl.DateTimeFormat('en-GB', {
            timeZone: tz, year: 'numeric', month: '2-digit', day: '2-digit',
            hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
          }).formatToParts(d).map((p) => [p.type, p.value]),
        );
        return fmt.replace('yyyy', parts.year).replace('MM', parts.month).replace('dd', parts.day)
          .replace('HH', parts.hour).replace('mm', parts.minute).replace('ss', parts.second);
      },
    },
    DriveApp: { getFolderById: () => root },
    LockService: { getScriptLock: () => ({ tryLock: () => true, releaseLock: () => {} }) },
    ContentService: {
      MimeType: { JSON: 'json' },
      createTextOutput: (s) => ({ setMimeType: () => ({ body: JSON.parse(s) }) }),
    },
  };
  vm.createContext(ctx);
  let source = fs.readFileSync(path.join(__dirname, '..', 'Kod.gs'), 'utf8');
  // Depodaki Kod.gs'te anahtarlar yer tutucu ('PASTE-...'). Testler icin
  // yalnizca bellekte gecici degerler konuyor; dosyaya hic yazilmiyor.
  if (!keepPlaceholders) {
    source = source
      .replace("'PASTE-YOUR-DRIVE-FOLDER-ID-HERE'", "'test-folder'")
      .replace("'PASTE-YOUR-WRITE-SECRET-HERE'", "'testWriteKey0123456789abcdefghij'")
      .replace("'PASTE-YOUR-READ-SECRET-HERE'", "'testReadKey0123456789abcdefghijk'");
  }
  vm.runInContext(source, ctx);
  // Anahtarlar Kod.gs'ten okunuyor: gercek anahtarlar yazilmis olsa da testler
  // calissin ve anahtar bu dosyaya hic kopyalanmasin.
  WRITE = vm.runInContext('SECRET', ctx);
  READ = vm.runInContext('READ_SECRET', ctx);
  return { ctx, root };
}

// --- Istemci yardimcilari (araçtaki DriveUploader'in yaptigi) -------------

let WRITE;
let READ;

function post(ctx, name, plain, extra = {}) {
  const raw = Buffer.from(plain);
  const gz = extra.alreadyGz || zlib.gzipSync(raw);
  return ctx.doPost({
    parameter: Object.assign({ k: WRITE, name, bytes: String(raw.length), gz: '1' }, extra.params || {}),
    postData: { contents: gz.toString('base64') },
  }).body;
}

function get(ctx, params) {
  return ctx.doGet({ parameter: Object.assign({ k: READ }, params) }).body;
}

function decode(res) {
  return zlib.gunzipSync(Buffer.from(res.data, 'base64')).toString('utf8');
}

const EPOCH = 1790602585477; // 2026-09-28, Istanbul
const summary = JSON.stringify({ schemaVersion: 3, startEpoch: EPOCH, distanceKm: 1.23 });
const track = `# ex30-track;1;${EPOCH}\nt;lat;lon\n1;41.0;29.0\n`;

// --- Testler ---------------------------------------------------------------

const tests = {
  'yer tutucular duruyorsa her istek reddedilir'() {
    const { ctx, root } = makeContext({ keepPlaceholders: true });
    const w = post(ctx, 'trips.json', '[]');
    assert.strictEqual(w.ok, false);
    assert.match(w.hata, /not configured/);
    assert.strictEqual(get(ctx, {}).ok, false);
    assert.strictEqual(root.files().length, 0);
  },

  'ozet yuklenir, ay klasorune duser, dizine girer'() {
    const { ctx, root } = makeContext();
    const r = post(ctx, `trip-${EPOCH}.json`, summary);
    assert.strictEqual(r.ok, true, JSON.stringify(r));
    assert.strictEqual(r.mevcut, false);
    assert.strictEqual(r.protokol, 2);

    const trips = root.folders().find((f) => f.getName() === 'yolculuklar');
    const month = trips.folders()[0].folders()[0];
    assert.strictEqual(trips.folders()[0].getName(), '2026');
    assert.strictEqual(month.getName(), '09');
    // Ozet ACILMIS saklaniyor: Drive'da dogrudan okunabilsin.
    assert.strictEqual(month.files()[0].getBlob().getDataAsString(), summary);

    const list = get(ctx, { liste: 'yolculuklar' });
    assert.strictEqual(list.kayitlar.length, 1);
    assert.strictEqual(list.kayitlar[0].tur, 'ozet');
    assert.strictEqual(list.kayitlar[0].epoch, EPOCH);
  },

  'iz gzip olarak saklanir ve ayni icerikle geri iner'() {
    const { ctx, root } = makeContext();
    const gz = zlib.gzipSync(Buffer.from(track));
    assert.strictEqual(post(ctx, `trip-${EPOCH}.csv.gz`, track, { alreadyGz: gz }).ok, true);

    const stored = root.folders()[0].folders()[0].folders()[0].files()[0];
    assert.deepStrictEqual(toBuf(stored.getBlob().getBytes()), gz);

    const res = get(ctx, { file: `trip-${EPOCH}.csv.gz` });
    assert.strictEqual(res.ok, true, JSON.stringify(res));
    assert.strictEqual(res.bytes, Buffer.byteLength(track));
    assert.strictEqual(decode(res), track);
  },

  'ayni ad ikinci kez gelirse ezilmez, mevcut der'() {
    const { ctx, root } = makeContext();
    post(ctx, `trip-${EPOCH}.json`, summary);
    const other = JSON.stringify({ startEpoch: EPOCH, distanceKm: 999 });
    const r = post(ctx, `trip-${EPOCH}.json`, other);
    assert.strictEqual(r.ok, true);
    assert.strictEqual(r.mevcut, true);

    const month = root.folders()[0].folders()[0].folders()[0];
    assert.strictEqual(month.files().length, 1);
    assert.strictEqual(month.files()[0].getBlob().getDataAsString(), summary);
    assert.strictEqual(get(ctx, { liste: 'yolculuklar' }).kayitlar.length, 1);
  },

  'ad ile icerik tutmazsa reddedilir'() {
    const { ctx } = makeContext();
    const wrong = JSON.stringify({ startEpoch: EPOCH + 1 });
    assert.strictEqual(post(ctx, `trip-${EPOCH}.json`, wrong).ok, false);
    assert.strictEqual(post(ctx, `trip-${EPOCH}.json`, 'json degil').ok, false);
    assert.strictEqual(post(ctx, `trip-${EPOCH}.csv.gz`, 'baslik yok\n').ok, false);
    assert.strictEqual(post(ctx, `trip-${EPOCH}.csv.gz`, `# ex30-track;1;${EPOCH + 5}\n`).ok, false);
  },

  'saati bozuk yolculuk reddedilir'() {
    const { ctx } = makeContext();
    const old = 1600000000000; // 2020
    const r = post(ctx, `trip-${old}.json`, JSON.stringify({ startEpoch: old }));
    assert.strictEqual(r.ok, false);
    assert.match(r.hata, /gecersiz yolculuk zamani/);
  },

  'yazma anahtari okuyamaz, okuma anahtari yazamaz'() {
    const { ctx } = makeContext();
    assert.strictEqual(ctx.doGet({ parameter: { k: WRITE, liste: 'yolculuklar' } }).body.ok, false);
    const r = ctx.doPost({
      parameter: { k: READ, name: `trip-${EPOCH}.json`, bytes: '2', gz: '0' },
      postData: { contents: Buffer.from('{}').toString('base64') },
    }).body;
    assert.strictEqual(r.hata, 'yetkisiz');
  },

  'sonra imleci yalnizca yeni eklenenleri verir'() {
    const { ctx } = makeContext();
    post(ctx, `trip-${EPOCH}.json`, summary);
    const first = get(ctx, { liste: 'yolculuklar' });
    const e2 = EPOCH - 86400000 * 30; // gec yuklenen ESKI bir yolculuk
    post(ctx, `trip-${e2}.json`, JSON.stringify({ startEpoch: e2 }));

    const next = get(ctx, { liste: 'yolculuklar', sonra: String(first.sunucuZamani) });
    assert.strictEqual(next.kayitlar.length, 1);
    assert.strictEqual(next.kayitlar[0].epoch, e2);
    assert.strictEqual(next.toplam, 2);
  },

  'dizin yeniden kurulabilir'() {
    const { ctx, root } = makeContext();
    post(ctx, `trip-${EPOCH}.json`, summary);
    post(ctx, `trip-${EPOCH}.csv.gz`, track);
    const trips = root.folders()[0];
    trips.getFilesByName('dizin.jsonl').next().setContent('bozuk\n');

    assert.strictEqual(get(ctx, { dizin: 'yeniden' }).kayit, 2);
    assert.strictEqual(get(ctx, { liste: 'yolculuklar' }).kayitlar.length, 2);
  },

  'onarim eski kayitlarin olusturuldu degerini korur'() {
    const { ctx } = makeContext();
    post(ctx, `trip-${EPOCH}.json`, summary);
    post(ctx, `trip-${EPOCH}.csv.gz`, track);
    const before = get(ctx, { liste: 'yolculuklar' }).kayitlar;

    const r = get(ctx, { dizin: 'yeniden' });
    assert.strictEqual(r.kayit, 2);
    assert.strictEqual(r.yeni, 0);
    const after = get(ctx, { liste: 'yolculuklar' }).kayitlar;
    assert.deepStrictEqual(after.map((x) => x.olusturuldu), before.map((x) => x.olusturuldu));
    // Acilmis boyut da korunuyor (onarimla gzip'li boyuta donmuyor).
    assert.deepStrictEqual(after.map((x) => x.bayt), before.map((x) => x.bayt));
  },

  'elle eklenen dosya onarimdan sonra imlecli listede gorunur'() {
    // 2026-09-28'de gercek Drive'da yasanan sira: istemci esitledi, dosyalar
    // elle eklendi, istemci yine esitledi (dizinde yoklar), dizin onarildi.
    const { ctx, root } = makeContext();
    post(ctx, `trip-${EPOCH}.json`, summary);
    const month = root.folders()[0].folders()[0].folders()[0];
    const manual = EPOCH + 60000;
    month.createFile(Blob(JSON.stringify({ startEpoch: manual }), 'application/json', `trip-${manual}.json`));
    const cursor = get(ctx, { liste: 'yolculuklar' }).sunucuZamani; // Drive tarihi bundan ESKI

    const r = get(ctx, { dizin: 'yeniden' });
    assert.strictEqual(r.yeni, 1);
    const next = get(ctx, { liste: 'yolculuklar', sonra: String(cursor) });
    assert.deepStrictEqual(next.kayitlar.map((x) => x.epoch), [manual]);
    assert.strictEqual(next.toplam, 2);
  },

  'ayni adda iki dosya dizine bir kez girer'() {
    const { ctx, root } = makeContext();
    post(ctx, `trip-${EPOCH}.json`, summary);
    const month = root.folders()[0].folders()[0].folders()[0];
    month.createFile(Blob(summary, 'application/json', `trip-${EPOCH}.json`));
    assert.strictEqual(month.files().length, 2);
    assert.strictEqual(get(ctx, { dizin: 'yeniden' }).kayit, 1);
    assert.strictEqual(get(ctx, { liste: 'yolculuklar' }).toplam, 1);
  },

  'surum 1 dosyalari eskisi gibi calisir'() {
    const { ctx, root } = makeContext();
    const trips = '[{"startEpoch":1}]';
    const r = post(ctx, 'trips.json', trips);
    assert.strictEqual(r.ok, true, JSON.stringify(r));
    // Uzerine yazma surum 1'de BILEREK var: dosya araçtaki son hali.
    assert.strictEqual(post(ctx, 'trips.json', '[]').ok, true);
    assert.strictEqual(root.files().filter((f) => f.getName() === 'trips.json').length, 1);
    assert.strictEqual(decode(get(ctx, { file: 'trips.json' })), '[]');
    assert.strictEqual(post(ctx, 'baska.txt', 'x').ok, false);
  },

  'boyut tutmazsa hicbir sey yazilmaz'() {
    const { ctx, root } = makeContext();
    const r = post(ctx, `trip-${EPOCH}.json`, summary, { params: { bytes: '5' } });
    assert.strictEqual(r.ok, false);
    assert.strictEqual(root.folders().length, 0);
  },
};

let failed = 0;
for (const [name, fn] of Object.entries(tests)) {
  try {
    fn();
    console.log('  ok   ' + name);
  } catch (e) {
    failed++;
    console.log('  HATA ' + name + '\n       ' + (e && e.message));
  }
}
console.log(`\n${Object.keys(tests).length - failed}/${Object.keys(tests).length} gecti`);
process.exit(failed ? 1 : 0);
