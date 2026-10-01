package com.example.ex30telemetry.calib

import android.content.Context
import android.util.Log
import com.example.ex30telemetry.R
import com.example.ex30telemetry.google.DriveClient
import com.example.ex30telemetry.google.GoogleAuth
import com.example.ex30telemetry.sync.DriveTripSender
import java.io.File
import java.io.IOException

/**
 * "Drive'a aktar" dugmesi: kayit dosyalarini ([DataExporter.FILES]) baglanan
 * Google hesabinin Drive'ina, `EX30 Trips/` klasorunun KOKUNE yukler.
 *
 * **Neden [DataExporter] yetmiyor:** o dosyalari araçtaki paylasilan depolamaya
 * kopyaliyor, yani dosya hâlâ araçta. Bu sinif ayni dosyalari tek dokunusla
 * kisinin kendi Drive'ina atiyor.
 *
 * **Yolculuklardan farki:** yolculuk dosyalari otomatik gidiyor ve ASLA
 * degismiyor ([com.example.ex30telemetry.sync.TripSync]). Buradakiler
 * araçtaki dosyanin SON HALI — `calib.csv` buyudukce ayni Drive dosyasi
 * guncelleniyor (Drive eski surumleri kendi gecmisinde tutuyor).
 *
 * **2026-09-29'a kadar** bu is Apps Script ucuna (drive-sync/Kod.gs) gomulu
 * bir adres + yazma anahtariyla yapiliyordu. Artik anahtar yok: her kullanici
 * kendi Google hesabini bagliyor ([GoogleAuth]).
 *
 * Butun metotlar ARKA THREAD'de cagrilmali.
 */
object DriveUploader {

    private const val TAG = "JourneyDrive"

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
         * Drive'in gosterdigi tarih YUKLEME tarihi. Icerigin yasini ekranda
         * soylemezsek iki aktarim birebir ayni veriyi tasir ve tarihler bunu gizler.
         */
        private fun ageSuffix(context: Context): String {
            val ms = newestContentMs ?: return ""
            val age = System.currentTimeMillis() - ms
            if (age < 10 * 60 * 1000L) return ""
            return " " + context.getString(R.string.export_age, age / 3_600_000L)
        }
    }

    /** Bir Google hesabi bagli mi — dugme ancak o zaman calisiyor. */
    fun isReady(context: Context): Boolean = GoogleAuth.isLinked(context)

    fun uploadAll(context: Context): Result {
        // Tamponda bekleyen satirlar kaybolmasin (exportAll ile ayni sebep).
        Calibration.current()?.flush()

        val drive = DriveClient(context)
        val uploaded = mutableListOf<String>()
        val failed = mutableListOf<String>()
        var firstError: String? = null
        var newest: Long? = null

        for ((name, mime) in DataExporter.FILES) {
            val src = File(context.filesDir, name)
            if (!src.exists() || src.length() == 0L) continue

            val error = try {
                val bytes = src.readBytes()
                upsert(drive, name, mime, bytes)
                null
            } catch (e: OutOfMemoryError) {
                "bellek yetmedi"
            } catch (e: IOException) {
                e.message ?: e.javaClass.simpleName
            }

            if (error == null) {
                uploaded += name
                val m = src.lastModified()
                if (m > 0 && (newest == null || m > newest!!)) newest = m
            } else {
                failed += name
                if (firstError == null) firstError = error
                Log.w(TAG, "$name yüklenemedi: $error")
                // Ilk hata cogu zaman hepsinde ayni (ag yok, hesap dustu);
                // kalan dosyalar icin beklemeye gerek yok.
                if (stopsAll(error)) break
            }
        }

        Log.i(TAG, "Drive: ${uploaded.size} başarılı, ${failed.size} başarısız")
        return Result(uploaded, failed, firstError, newest)
    }

    /** Ayni `ex30id`'li dosya varsa icerigini degistirir, yoksa olusturur. */
    private fun upsert(drive: DriveClient, name: String, mime: String, bytes: ByteArray) {
        val existing = drive.findByAppId(name)
        if (existing != null) {
            drive.update(existing.id, mime, bytes)
        } else {
            drive.create(
                parentId = drive.folder(listOf(DriveTripSender.ROOT)),
                name = name,
                mime = mime,
                bytes = bytes,
                props = mapOf("ex30" to "kayit", "ex30id" to name),
            )
        }
    }

    private fun stopsAll(error: String) =
        error.contains("bağlı değil") || error.contains("bağlantısı düştü") ||
            error.contains("Unable to resolve host")
}
