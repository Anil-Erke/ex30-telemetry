package com.example.ex30telemetry.sync

import android.util.Log
import com.example.ex30telemetry.google.DriveClient
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Yolculuk dosyasini dogrudan kullanicinin Drive'ina yazar (protokol 3,
 * drive-sync/PROTOKOL.md §3).
 *
 * **Yalnizca olusturur, asla uzerine yazmaz** — Apps Script donemindeki kural
 * artik istemcide: once `ex30id` ile aranir, varsa dokunulmadan basari
 * sayilir. Boylece yeniden deneme (yanit yolda kayboldu, head unit uyudu)
 * zararsiz ve cift dosya olusmuyor.
 *
 * **Butunluk:** Drive'in hesapladigi MD5 yerel icerikle karsilastiriliyor.
 * Tutmazsa yazilan dosya silinip hata donuyor; yolculuk kuyrukta kalip yeniden
 * deneniyor. ("Istisna atmadi" ile "dosya gercekten yazildi" ayni sey degil.)
 */
class DriveTripSender(private val drive: DriveClient) : TripOutbox.Sender {

    override fun send(file: TripOutbox.TripFile): String? = try {
        if (drive.findByAppId(file.name) != null) {
            null
        } else {
            val parent = drive.folder(folderPath(file.startEpoch))
            val created = drive.create(
                parentId = parent,
                name = file.name,
                mime = file.mime,
                bytes = file.bytes,
                props = mapOf(
                    "ex30" to "trip",
                    "ex30id" to file.name,
                    "epoch" to file.startEpoch.toString(),
                    "tur" to file.kind,
                ),
            )
            val local = DriveClient.md5Hex(file.bytes)
            if (created.md5 != null && created.md5 != local) {
                drive.delete(created.id)
                "yazıldı ama içerik tutmuyor (md5), silindi — yeniden denenecek"
            } else {
                null
            }
        }
    } catch (e: DriveClient.DriveException) {
        // Klasor Drive'da silinmis olabilir: bir sonraki denemede yeniden bulunsun.
        DriveClient.forgetFolders()
        Log.w(TAG, "${file.name}: ${e.message}")
        if (e.permanent) "${TripOutbox.PERMANENT_PREFIX} ${e.message}" else e.message ?: "Drive hatası"
    } catch (e: IOException) {
        // Ag yok, zaman asimi, hesap bagli degil (GoogleAuth.AuthException) — gecici.
        e.message ?: e.javaClass.simpleName
    }

    companion object {
        private const val TAG = "JourneyDrive"

        /** Drive'daki kok klasor adi; kullanicinin gordugu tek sey. */
        const val ROOT = "EX30 Trips"
        const val TRIPS = "yolculuklar"

        /**
         * `EX30 Trips/yolculuklar/<yyyy>/<MM>` — ay yolculugun BASLANGICINA
         * gore, cihazin saat diliminde. Klasorler yalnizca insan gozu icin:
         * okuyan istemciler dosyalari appProperties ile buluyor.
         */
        fun folderPath(startEpoch: Long): List<String> {
            val d = Date(startEpoch)
            return listOf(
                ROOT, TRIPS,
                SimpleDateFormat("yyyy", Locale.ROOT).format(d),
                SimpleDateFormat("MM", Locale.ROOT).format(d),
            )
        }
    }
}
