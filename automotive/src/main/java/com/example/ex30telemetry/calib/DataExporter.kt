package com.example.ex30telemetry.calib

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import com.example.ex30telemetry.R
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Kayit dosyalarini araçtan cikarilabilecek bir yere kopyalar.
 *
 * **Neden gerekli:** uygulamanin kendi dosyalari `filesDir` altinda
 * (`/data/user/12/com.example.ex30telemetry/files/`) ve orasi uygulamaya
 * ozel — baska hicbir uygulama okuyamaz. Araçta `adb` de yok. Dosyayi disari
 * cikarmanin tek yolu paylasilan depolamaya kopyalamak.
 *
 * **Neden MediaStore:** Android 11'den beri `Android/data` klasoru de baska
 * uygulamalara kapali. `MediaStore.Downloads` izin istemiyor (API 29+) ve
 * dosyayi sistem dizinine kaydediyor — sistemin İndirilenler ekrani boylece
 * gorebiliyor. Dogrudan `File` API'siyle yazmak da mumkun ama o dosyalar
 * MediaStore'a kaydolmadigi icin sistem ekraninda HIC gorunmuyor
 * (2026-08-25'te olculdu: diskte 18 dosya, MediaStore'da 15).
 *
 * **Onemli sinir:** kapsamli depolama yuzunden buraya yazdigimiz dosyalar
 * BASKA BIR UYGULAMA tarafindan goruLEMEZ — dosyalar bizim uygulamaya ait
 * damgalaniyor. EX30 File Explorer klasoru "boş" gosteriyor; sebebi bu.
 * Araçta dosyalari gormenin yolu [openDownloads] ile sistemin belge arayuzunu
 * acmak.
 *
 * Kopya kullanicinin kendi istegiyle olusuyor ve cihazdan cikmiyor. (Dosyayi
 * araçtan CIKARMAK icin ikinci bir yol olarak [DriveUploader] var; o yol ag
 * kullaniyor ve `INTERNET` iznini bu yuzden manifest'e getiriyor.)
 */
object DataExporter {

    private const val TAG = "JourneyExport"
    private const val SUBDIR = "EX30YolAnalizi"

    /**
     * Cikarilabilir kayit dosyalari: `filesDir` icindeki ad -> MIME.
     *
     * [DriveUploader] ayni listeyi kullaniyor ve `drive-sync/Kod.gs` icindeki
     * `ALLOWED` listesi de bununla BIREBIR ayni olmak zorunda: script yalnizca
     * oradaki adlari kabul ediyor. Buraya bir dosya eklenip script
     * guncellenmezse yukleme "izin verilmeyen dosya adi" ile dusuyor.
     */
    val FILES = listOf(
        "calib.csv" to "text/csv",
        // Gunluk dolunca donduruluyor; bir onceki dosya da cikarilabilmeli,
        // yoksa donmeden onceki surus kayda gecmis ama araçta mahsur kalir.
        "calib-prev.csv" to "text/csv",
        "trips.json" to "application/json",
        "records.json" to "application/json",
    )

    data class Result(
        val exported: List<String>,
        val failed: List<String>,
        /** Aktarilan icerigin EN YENI satirinin yasi — dosya adindaki tarih degil. */
        val newestContentMs: Long? = null,
    ) {
        fun summary(context: Context): String = when {
            exported.isEmpty() -> context.getString(R.string.export_failed)
            failed.isEmpty() ->
                context.getString(R.string.export_ok, SUBDIR, exported.size, ageSuffix(context))
            else ->
                context.getString(R.string.export_partial, exported.size, failed.size)
        }

        /**
         * Dosya adina AKTARIM tarihi yaziliyor; icerik cok daha eski olabilir.
         * 23 ve 29 Agustos'ta alinan iki aktarim birebir ayni dosya cikmisti ve
         * adlarindaki tarih bunu gizliyordu — o yuzden yas artik ozette.
         */
        private fun ageSuffix(context: Context): String {
            val ms = newestContentMs ?: return ""
            val age = System.currentTimeMillis() - ms
            if (age < 10 * 60 * 1000L) return ""
            val hours = age / 3_600_000L
            return " " + context.getString(R.string.export_age, hours)
        }
    }

    /**
     * `calib.csv`, `trips.json` ve `records.json` dosyalarini
     * `İndirilenler/EX30YolAnalizi/` altina zaman damgali adlarla kopyalar.
     */
    fun exportAll(context: Context): Result {
        // Tamponda bekleyen satirlar kaybolmasin.
        Calibration.current()?.flush()

        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date())
        val exported = mutableListOf<String>()
        val failed = mutableListOf<String>()

        var newest: Long? = null

        FILES.forEach { (name, mime) ->
            val src = File(context.filesDir, name)
            if (!src.exists() || src.length() == 0L) return@forEach
            val target = "${name.substringBeforeLast('.')}-$stamp.${name.substringAfterLast('.')}"
            if (copyToDownloads(context, src, target, mime)) {
                exported += target
                val m = src.lastModified()
                if (m > 0 && (newest == null || m > newest!!)) newest = m
            } else {
                failed += name
            }
        }

        Log.i(TAG, "dışa aktarım: ${exported.size} başarılı, ${failed.size} başarısız")
        return Result(exported, failed, newest)
    }

    /**
     * Serbest metni tek bir dosya olarak İndirilenler'e yazar.
     *
     * `exportAll` sabit dosya adlarini `filesDir`'den kopyaliyor; sonda raporu
     * ise diske hic yazilmadan uretiliyor. Ayni MediaStore yolunu kullaniyor
     * ki sistemin İndirilenler ekraninda gorunebilsin — araçta dosyaya
     * ulasmanin tek yolu o (bkz. [openDownloads]).
     *
     * @return olusan dosya adi, ya da yazilamadiysa null.
     */
    fun exportText(context: Context, baseName: String, content: String): String? {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date())
        val target = "$baseName-$stamp.txt"

        val tmp = File(context.cacheDir, target)
        return runCatching {
            tmp.writeText(content)
            if (copyToDownloads(context, tmp, target, "text/plain")) target else null
        }.onFailure {
            Log.w(TAG, "$target yazılamadı", it)
        }.getOrNull().also { tmp.delete() }
    }

    /**
     * Yalnizca teshis: hangi hedef klasorlere gercekten yazabildigimizi olcer.
     *
     * Kapsamli depolamada (Android 11+) bir uygulamanin yazdigi medya-disi
     * dosyalar baska uygulamalara gorunmuyor. `MediaStore.Downloads` bu yuzden
     * yetmedi: dosya yaziliyor ama EX30 File Explorer "Klasör boş" diyor.
     * Hangi yolun acik oldugunu tahmin etmek yerine olcuyoruz.
     */
    fun probeTargets(context: Context): List<String> {
        val results = mutableListOf<String>()
        val external = Environment.getExternalStorageDirectory()

        fun probe(label: String, dir: File?) {
            if (dir == null) { results += "$label → yol yok"; return }
            val outcome = runCatching {
                dir.mkdirs()
                val f = File(dir, "ex30-probe.tmp")
                f.writeText("probe")
                val ok = f.exists() && f.length() > 0
                f.delete()
                if (ok) "YAZILABILIR" else "yazilamadi"
            }.getOrElse { "hata: ${it.javaClass.simpleName}" }
            results += "$label → $outcome  [${dir.absolutePath}]"
        }

        probe("kendi harici klasorumuz", context.getExternalFilesDir(null))
        probe(
            "File Explorer klasoru",
            File(external, "Android/data/com.example.ex30files/files"),
        )
        probe("Download (dogrudan File API)", File(external, "Download"))
        probe("Documents (dogrudan File API)", File(external, "Documents"))

        results.forEach { Log.i(TAG, "yoklama: $it") }
        return results
    }

    /**
     * Sistemin İndirilenler ekranini acar (`com.android.car.documentsui`).
     *
     * Araçta dosyalari gormenin TEK yolu bu. Kapsamli depolama yuzunden
     * disariya yazdigimiz dosyalari baska bir uygulama goremiyor (bkz.
     * [exportAll] notu); sistemin belge arayuzu ise ayricalikli oldugu icin
     * gorebiliyor.
     *
     * Arac hareket halindeyken sistem bu ekrani acmayi engelliyor — normal.
     */
    fun openDownloads(context: Context): Boolean = runCatching {
        context.startActivity(
            Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.onFailure { Log.w(TAG, "İndirilenler ekranı açılamadı", it) }.getOrDefault(false)

    private fun copyToDownloads(
        context: Context,
        src: File,
        displayName: String,
        mime: String,
    ): Boolean = runCatching {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/$SUBDIR")
            // Yazim bitene kadar dosya yoneticilerine yarim gorunmesin.
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return@runCatching false

        resolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
            ?: return@runCatching false

        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)

        // "Istisna atmadi" ile "dosya gercekten yazildi" ayni sey degil:
        // geri okuyup boyutu karsilastir.
        val written = resolver.openInputStream(uri)?.use { input ->
            var total = 0L
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
            }
            total
        } ?: -1L

        if (written != src.length()) {
            Log.w(TAG, "${src.name}: ${src.length()} bayt bekleniyordu, $written yazıldı")
            return@runCatching false
        }
        Log.i(TAG, "$displayName → $uri ($written bayt)")
        true
    }.onFailure { Log.w(TAG, "${src.name} kopyalanamadı", it) }.getOrDefault(false)
}
