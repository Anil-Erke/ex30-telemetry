package com.example.ex30telemetry.calib

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.example.ex30telemetry.BuildConfig
import com.example.ex30telemetry.R
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPOutputStream

/**
 * Kayit dosyalarini kullanicinin kendi Google Drive klasorune yukler.
 *
 * **Neden [DataExporter] yetmiyor:** o dosyalari araçtaki paylasilan depolamaya
 * kopyaliyor, yani dosya hâlâ araçta. Bilgisayara almak icin her seferinde
 * telefon/tarayici ile ugrasmak gerekiyor. Bu sinif ayni dort dosyayi tek
 * dokunusla Drive'a atiyor.
 *
 * **Neden OAuth yok:** araçta tarayici yok ve Google gomulu WebView ile oturum
 * acmayi reddediyor; Drive API'sine dogrudan konusmak icin gereken izin akisi
 * araçta yurumuyor. Onun yerine kullanicinin kendi hesabinda yayinladigi bir
 * Apps Script web uygulamasina POST ediliyor; Drive'a yazan o script.
 * Kurulum ve protokol: `drive-sync/README.md`.
 *
 * **Adres ve anahtar** depoya girmeyen `drive.properties` dosyasindan
 * [BuildConfig]'e geliyor. Ikisi de bosken [isConfigured] false doner ve ekran
 * dugmeyi "yapilandirilmadi" diye gosterir — derleme kirilmaz.
 *
 * **Gizlilik:** veri kullanicinin KENDI Drive hesabina gidiyor, gelistiricinin
 * sunucusu yok ve aktarimi kullanici basliyor. Yine de `INTERNET` izni artik
 * manifest'te; `privacy/index.html` bunu yansitmali.
 *
 * Butun metotlar ARKA THREAD'de cagrilmali — ana thread'de ag cagrisi
 * `NetworkOnMainThreadException` atar.
 */
object DriveUploader {

    private const val TAG = "JourneyDrive"

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000

    /** Apps Script sonucu 302 ile veriyor; zinciri sinirli tut. */
    private const val MAX_REDIRECTS = 5

    data class Result(
        val uploaded: List<String>,
        val failed: List<String>,
        /** Ilk hatanin metni; ozet satirinda gosteriliyor. */
        val firstError: String? = null,
        /** Aktarilan icerigin EN YENI dosyasinin yasi — [DataExporter] ile ayni mantik. */
        val newestContentMs: Long? = null,
    ) {
        fun summary(context: Context): String = when {
            uploaded.isEmpty() && failed.isEmpty() ->
                context.getString(R.string.drive_nothing)
            uploaded.isEmpty() ->
                context.getString(R.string.drive_failed, firstError ?: "")
            failed.isEmpty() ->
                context.getString(R.string.drive_ok, uploaded.size, ageSuffix(context))
            else ->
                context.getString(
                    R.string.drive_partial, uploaded.size, failed.size, firstError ?: ""
                )
        }

        /**
         * Sunucudaki dosya adi sabit ve uzerine yaziliyor, dolayisiyla Drive'in
         * gosterdigi tarih YUKLEME tarihi. Icerigin yasini ekranda soylemezsek
         * [DataExporter]'daki tuzagin aynisi burada tekrarlanir: iki aktarim
         * birebir ayni veriyi tasir ve tarihler bunu gizler.
         */
        private fun ageSuffix(context: Context): String {
            val ms = newestContentMs ?: return ""
            val age = System.currentTimeMillis() - ms
            if (age < 10 * 60 * 1000L) return ""
            return " " + context.getString(R.string.export_age, age / 3_600_000L)
        }
    }

    /** `drive.properties` doldurulmus mu. */
    fun isConfigured(): Boolean =
        BuildConfig.DRIVE_URL.isNotBlank() && BuildConfig.DRIVE_SECRET.isNotBlank()

    /**
     * [DataExporter.FILES] listesindeki her dosyayi tek tek yukler. Bir dosyanin
     * dusmesi digerlerini durdurmuyor: kismi basari da basaridir, ozet satiri
     * kac tanesinin gittigini soyluyor.
     */
    fun uploadAll(context: Context): Result {
        // Tamponda bekleyen satirlar kaybolmasin (exportAll ile ayni sebep).
        Calibration.current()?.flush()

        val uploaded = mutableListOf<String>()
        val failed = mutableListOf<String>()
        var firstError: String? = null
        var newest: Long? = null

        for ((name, _) in DataExporter.FILES) {
            val src = File(context.filesDir, name)
            if (!src.exists() || src.length() == 0L) continue

            val raw = runCatching { src.readBytes() }.getOrNull()
            if (raw == null) {
                failed += name
                if (firstError == null) firstError = context.getString(R.string.drive_unreadable)
                continue
            }

            val modified = src.lastModified()
            val error = upload(name, raw, modified)
            if (error == null) {
                uploaded += name
                if (modified > 0 && (newest == null || modified > newest!!)) newest = modified
            } else {
                failed += name
                if (firstError == null) firstError = error
                Log.w(TAG, "$name yüklenemedi: $error")
            }
        }

        Log.i(TAG, "Drive: ${uploaded.size} başarılı, ${failed.size} başarısız")
        return Result(uploaded, failed, firstError, newest)
    }

    /** @return hata metni, basarili ise null. */
    private fun upload(name: String, raw: ByteArray, modifiedMs: Long): String? {
        // SIRA ONEMLI: dosya -> gzip -> base64.
        //
        // gzip: calib.csv tavani 8 MiB ve tekrarli sayisal metin oldugu icin
        // onlarca kat kuculuyor; araç hattindan yukleme boylece saniyeler
        // suruyor. base64: Apps Script HAM IKILI govde alamiyor — sunucuda
        // `e.postData.contents` bir String ve ikili veri charset donusumunde
        // bozuluyor. %33 sisme, gzip kazancinin yaninda onemsiz.
        //
        // Bellek: en kotu durumda ~8 MiB ham + ~1 MiB gzip + ~1,5 MiB base64.
        // Dosyalar tek tek isleniyor, hepsi ayni anda bellekte tutulmuyor.
        val payload = try {
            Base64.encodeToString(gzip(raw), Base64.NO_WRAP).toByteArray(Charsets.US_ASCII)
        } catch (e: OutOfMemoryError) {
            return "bellek yetmedi"
        } catch (e: IOException) {
            return e.message ?: "sıkıştırılamadı"
        }

        val sep = if (BuildConfig.DRIVE_URL.contains('?')) "&" else "?"
        val url = BuildConfig.DRIVE_URL + sep +
            "k=" + Uri.encode(BuildConfig.DRIVE_SECRET) +
            "&name=" + Uri.encode(name) +
            // ACILMIS boyut: sunucu gzip'i actiktan sonra bununla dogruluyor ve
            // tutmazsa klasordeki saglam dosyaya HIC dokunmuyor.
            "&bytes=" + raw.size +
            "&gz=1" +
            "&newest=" + modifiedMs

        return send(url, payload, MAX_REDIRECTS)
    }

    /**
     * POST eder, gerekirse yonlendirmeyi elle takip eder.
     *
     * **Yonlendirme tuzagi:** `/exec` istegi sunucuda `doPost`'u CALISTIRIYOR,
     * sonucu ise 302 ile `script.googleusercontent.com` uzerinden veriyor.
     * Yonlendirme takip edilmezse govde bos gelir; bos govdeyi basari saymak
     * "yuklendi" deyip hicbir sey yuklememek demek. Bu yuzden takibi kendimiz
     * yapiyoruz ve [parse] bos yaniti acikca HATA sayiyor.
     */
    private fun send(url: String, body: ByteArray, redirectsLeft: Int): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                setFixedLengthStreamingMode(body.size)
            }
            conn.outputStream.use { it.write(body) }

            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                    ?: return "yönlendirme adresi yok (HTTP $code)"
                if (redirectsLeft <= 0) return "çok fazla yönlendirme"
                return followGet(location, redirectsLeft - 1)
            }
            parse(code, readBody(conn))
        } catch (e: IOException) {
            // Ag yokken buraya dusuluyor; mesaj kullaniciya aynen gidiyor.
            e.message ?: "bağlantı kurulamadı"
        } finally {
            conn?.disconnect()
        }
    }

    /** 302 hedefi GET ile okunur; sonuc govdesi orada. */
    private fun followGet(url: String, redirectsLeft: Int): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                    ?: return "yönlendirme adresi yok (HTTP $code)"
                if (redirectsLeft <= 0) return "çok fazla yönlendirme"
                return followGet(location, redirectsLeft - 1)
            }
            parse(code, readBody(conn))
        } catch (e: IOException) {
            e.message ?: "bağlantı kurulamadı"
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * **Basari olcutu HTTP durum kodu DEGIL.** Apps Script web uygulamasi durum
     * kodu donduremiyor; yetki hatasi da, boyut hatasi da 200 ile geliyor. Tek
     * gecerli olcut govdedeki `ok` alani.
     */
    private fun parse(code: Int, text: String?): String? {
        if (text.isNullOrBlank()) return "boş yanıt (HTTP $code)"
        val json = runCatching { JSONObject(text) }.getOrNull()
            ?: return "yanıt JSON değil (HTTP $code)"
        if (json.optBoolean("ok", false)) return null
        return json.optString("hata").ifBlank { "bilinmeyen sunucu hatası" }
    }

    private fun readBody(conn: HttpURLConnection): String? = runCatching {
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
    }.getOrNull()

    private fun gzip(raw: ByteArray): ByteArray {
        // Tahmini sikistirma orani ~10x; tampon bos yere buyumesin.
        val out = ByteArrayOutputStream(raw.size / 8 + 1024)
        GZIPOutputStream(out).use { it.write(raw) }
        return out.toByteArray()
    }
}
