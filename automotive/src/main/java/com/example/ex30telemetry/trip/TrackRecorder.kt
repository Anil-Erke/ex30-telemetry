package com.example.ex30telemetry.trip

import android.util.Log
import com.example.ex30telemetry.Constants
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.GZIPOutputStream

/**
 * Yolculugun GPS izi: her konum fix'i bir satir (~1 Hz).
 *
 * **Neden var:** 0.7.2'ye kadar koordinatlar HICBIR dosyaya yazilmiyordu —
 * `Trip` yalnizca ozet tutuyor, `calib.csv` yalnizca araç verisi. Irtifa
 * grafigi bellekteki seyreltilmis bir seriden ciziliyordu ve yolculuk
 * kapaninca kayboluyordu. Iz; yokus/tuketim iliskisi, rejenin haritada nerede
 * toplandigi, ayni guzergahin farkli gunlerde karsilastirilmasi gibi
 * analizlerin ham maddesi.
 *
 * ## Dosyalar (`filesDir` altinda)
 *
 *  - `track-live.csv` — ACIK yolculugun izi, satir satir ekleniyor. Uygulama
 *    olur ya da head unit kapanirsa [TripRecorder.recoverIfNeeded] bunu
 *    `live.json` ile birlikte kurtariyor.
 *  - `tracks/trip-<startEpoch>.csv.gz` — kapanmis yolculuklarin izi.
 *    `startEpoch` [Trip.startEpoch] ile ayni: ozet ile iz bu anahtarla
 *    eslesiyor, Trip semasina yeni alan gerekmedi.
 *
 * ## Bicim (surum 1)
 *
 * ```
 * # ex30-track;1;<startEpoch>
 * t;lat;lon;alt;hacc;vacc;gps_kmh;kmh;kw;soc;dist_m
 * 1790000000000;41.012345;28.976543;63.4;3.8;5.0;47.2;48.0;12.34;61.25;1532.4
 * ```
 *
 * | Alan | Anlami |
 * |---|---|
 * | `t` | fix zamani, epoch ms (UTC) |
 * | `lat`, `lon` | derece, 6 ondalik (~0,1 m) |
 * | `alt` | GPS irtifasi, m — HAM (grafikteki kayan ortalama degil) |
 * | `hacc`, `vacc` | yatay / dikey dogruluk, m |
 * | `gps_kmh` | GPS'in hizi |
 * | `kmh` | aracin gosterge hizi (o anki son ornek) |
 * | `kw` | anlik guc; pozitif = tuketim, negatif = rejen |
 * | `soc` | batarya %, ondalikli olabilir |
 * | `dist_m` | yolculugun o ana kadarki mesafesi (uygulamanin hesabi) |
 *
 * Bos alan = o deger yoktu. Sifir yazmiyoruz ([Trip] ile ayni ilke).
 * Nokta ondalik ayraci SABIT (Locale.ROOT): dosya makineler icin.
 *
 * Boyut: saatte ~3.600 satir, ~300 KB ham, gzip'le ~60-80 KB.
 */
class TrackRecorder(private val filesDir: File) {

    private val liveFile get() = File(filesDir, LIVE_NAME)
    val tracksDir: File get() = File(filesDir, TRACKS_DIR)

    private var writer: BufferedWriter? = null
    /** Writer'in altindaki akis — flush'ta fsync icin (bkz. [DurableFile]). */
    private var stream: FileOutputStream? = null
    private var sinceFlush = 0

    /** Acik izin yolculugu; null = acik iz yok. */
    var openStartEpoch: Long? = null
        private set

    /** Acik izde yazilan satir sayisi — Olcum ekrani gosteriyor. */
    var rows = 0
        private set

    /** Yeni yolculugun izini acar. Acik iz varsa once atilir. */
    @Synchronized
    fun begin(startEpoch: Long) {
        closeWriter()
        runCatching {
            val s = FileOutputStream(liveFile, false)
            val w = BufferedWriter(OutputStreamWriter(s, Charsets.UTF_8), 16 * 1024)
            w.write(header(startEpoch))
            w.flush()
            s.fd.sync()
            stream = s
            writer = w
            openStartEpoch = startEpoch
            rows = 0
        }.onFailure { Log.w(TAG, "iz dosyasi acilamadi", it) }
    }

    @Synchronized
    fun append(
        tMs: Long,
        lat: Double,
        lon: Double,
        altM: Double?,
        hAccM: Double?,
        vAccM: Double?,
        gpsKmh: Double?,
        kmh: Double?,
        kw: Double?,
        soc: Double?,
        distM: Double,
    ) {
        val w = writer ?: return
        runCatching {
            w.write(row(tMs, lat, lon, altM, hAccM, vAccM, gpsKmh, kmh, kw, soc, distM))
            rows++
            // Her satirda flush etmiyoruz; ama head unit habersiz kapanabiliyor
            // (§7.11: kilitlenince uykuya geciyor). Kayip en fazla FLUSH_EVERY sn.
            // fsync sart: flush yalnizca cekirdege veriyor, ani guc kesilmesinde
            // dosya 0 bayt kalabiliyor (DurableFile notu).
            if (++sinceFlush >= FLUSH_EVERY) {
                w.flush()
                stream?.fd?.sync()
                sinceFlush = 0
            }
        }.onFailure { Log.w(TAG, "iz satiri yazilamadi", it) }
    }

    /**
     * Acik izi kapatir ve `tracks/trip-<startEpoch>.csv.gz` olarak saklar.
     *
     * @param startEpoch yolculugun anahtari; acik izin basligindakiyle TUTMUYORSA
     *   iz baska bir yolculuga aittir ve atilir (kurtarma yolu icin).
     * @return yazilan dosya; iz yoksa ya da yazilamadiysa null
     */
    @Synchronized
    fun finish(startEpoch: Long): File? {
        closeWriter()
        val src = liveFile
        if (!src.exists()) return null
        val owner = readStartEpoch(src)
        if (owner != startEpoch) {
            Log.w(TAG, "iz baska yolculuga ait ($owner != $startEpoch), atiliyor")
            src.delete()
            return null
        }
        val out = trackFile(startEpoch)
        return runCatching {
            out.parentFile?.mkdirs()
            // Once gecici ada yaz, sonra tasi: yarim kalmis bir .gz, yukleyiciye
            // saglam dosya gibi gorunmemeli.
            // gzip'i bellekte uret, DurableFile ile yaz: gecici dosya + fsync +
            // yeniden adlandirma. Saatlik iz ~60-80 KB, bellek sorun degil.
            val gz = ByteArrayOutputStream()
            GZIPOutputStream(gz).use { z -> src.inputStream().use { it.copyTo(z) } }
            DurableFile.write(out, gz.toByteArray())
            src.delete()
            prune()
            out
        }.onFailure { Log.w(TAG, "iz kaydedilemedi", it) }.getOrNull()
    }

    /** Acik izi atar (esigin altinda kalan yolculuk). */
    @Synchronized
    fun discard() {
        closeWriter()
        runCatching { if (liveFile.exists()) liveFile.delete() }
    }

    /** Kapanmis izler, en yeni basta. */
    fun savedTracks(): List<File> =
        tracksDir.listFiles { f -> f.name.startsWith(PREFIX) && f.name.endsWith(SUFFIX) }
            ?.sortedByDescending { it.name }
            .orEmpty()

    fun trackFile(startEpoch: Long): File = File(tracksDir, "$PREFIX$startEpoch$SUFFIX")

    private fun closeWriter() {
        runCatching { writer?.flush(); stream?.fd?.sync(); writer?.close() }
        writer = null
        stream = null
        openStartEpoch = null
        sinceFlush = 0
    }

    /** En eski izleri siler. Ad `trip-<epoch>` oldugu icin ad sirasi = zaman sirasi. */
    private fun prune() {
        val files = savedTracks()
        if (files.size <= Constants.MAX_TRACKS) return
        files.drop(Constants.MAX_TRACKS).forEach { it.delete() }
    }

    companion object {
        private const val TAG = "JourneyTrack"
        const val LIVE_NAME = "track-live.csv"
        const val TRACKS_DIR = "tracks"
        const val PREFIX = "trip-"
        const val SUFFIX = ".csv.gz"
        const val FORMAT_VERSION = 1
        const val COLUMNS = "t;lat;lon;alt;hacc;vacc;gps_kmh;kmh;kw;soc;dist_m"
        private const val FLUSH_EVERY = 5

        fun header(startEpoch: Long): String =
            "# ex30-track;$FORMAT_VERSION;$startEpoch\n$COLUMNS\n"

        fun row(
            tMs: Long,
            lat: Double,
            lon: Double,
            altM: Double?,
            hAccM: Double?,
            vAccM: Double?,
            gpsKmh: Double?,
            kmh: Double?,
            kw: Double?,
            soc: Double?,
            distM: Double,
        ): String = buildString {
            append(tMs).append(';')
            append(fmt(lat, 6)).append(';')
            append(fmt(lon, 6)).append(';')
            append(opt(altM, 1)).append(';')
            append(opt(hAccM, 1)).append(';')
            append(opt(vAccM, 1)).append(';')
            append(opt(gpsKmh, 1)).append(';')
            append(opt(kmh, 1)).append(';')
            append(opt(kw, 2)).append(';')
            append(opt(soc, 2)).append(';')
            append(fmt(distM, 1)).append('\n')
        }

        /** Baslik satirindaki yolculuk anahtari; okunamazsa null. */
        fun readStartEpoch(f: File): Long? = runCatching {
            f.bufferedReader().use { it.readLine() }
                ?.takeIf { it.startsWith("# ex30-track;") }
                ?.split(';')?.getOrNull(2)?.trim()?.toLong()
        }.getOrNull()

        private fun fmt(v: Double, decimals: Int): String =
            String.format(Locale.ROOT, "%.${decimals}f", v)

        private fun opt(v: Double?, decimals: Int): String =
            if (v == null || v.isNaN()) "" else fmt(v, decimals)
    }
}
