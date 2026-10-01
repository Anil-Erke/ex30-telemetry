package com.example.ex30telemetry.sync

import com.example.ex30telemetry.trip.Trip
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Drive'a gidecek yolculuklarin kuyrugu: `filesDir/outbox/<startEpoch>` bos
 * isaret dosyalari.
 *
 * **Neden dosya:** head unit habersiz uyuyor ya da kapaniyor (§7.11). Bellekteki
 * bir kuyruk o an kaybolurdu; dosya bir sonraki acilista hala orada.
 *
 * **Neden icerik degil isaret:** ozet zaten `trips.json`'da, iz `tracks/`
 * altinda. Kuyruk yalnizca "bu yolculuk henuz gitmedi" bilgisini tutuyor;
 * gonderim aninda icerik diskten taze okunuyor.
 *
 * Sunucunun kalici olarak reddettigi yolculuk `<epoch>.red` olur (icinde
 * sebep): kuyrugu tikamaz, yeniden denenmez, Olcum ekraninda sayisi gorunur.
 *
 * Android'e bagimli degil — JVM birim testleri dogrudan kullaniyor.
 */
class TripOutbox(private val dir: File) {

    @Synchronized
    fun enqueue(epoch: Long) {
        dir.mkdirs()
        runCatching { File(dir, epoch.toString()).createNewFile() }
    }

    /** Bekleyenler, EN ESKI basta: gecmis sirayla gitsin. */
    fun pending(): List<Long> =
        dir.listFiles()?.mapNotNull { it.name.toLongOrNull() }?.sorted().orEmpty()

    fun rejectedCount(): Int =
        dir.listFiles()?.count { it.name.endsWith(REJECTED_SUFFIX) } ?: 0

    fun done(epoch: Long) {
        File(dir, epoch.toString()).delete()
    }

    fun reject(epoch: Long, reason: String) {
        runCatching { File(dir, "$epoch$REJECTED_SUFFIX").writeText(reason) }
        done(epoch)
    }

    /** Bir bosaltma turunun sonucu. */
    data class Outcome(
        /** Ozeti (ve varsa izi) sunucuya ulasan yolculuk sayisi. */
        val uploaded: Int,
        /** Sunucunun kalici olarak reddettigi. */
        val rejected: Int,
        /** Araçtan silinmis (300 siniri) — gonderilecek bir sey kalmamis. */
        val dropped: Int,
        /** Turu yarida kesen gecici hata; null = kuyruk bosaldi. */
        val error: String?,
    )

    /**
     * Drive'a gidecek tek dosya, SAKLANACAGI bicimde: ozet duz JSON (Drive'da
     * okunabilsin), iz gzip'li (diskte zaten oyle; izler ~5x kuculuyor).
     */
    class TripFile(
        val name: String,
        val startEpoch: Long,
        /** "ozet" | "iz" — okuyan istemciler bu alanla suzuyor (PROTOKOL.md §3). */
        val kind: String,
        val mime: String,
        val bytes: ByteArray,
    )

    /**
     * Tek bir dosyayi gonderen taraf. Donus: hata metni ya da null (basari).
     * Kalici hatalar [PERMANENT_PREFIX] ile baslamali; gerisi gecici sayilir.
     */
    fun interface Sender {
        fun send(file: TripFile): String?
    }

    /**
     * Kuyrugu sirayla bosaltir. Her yolculuk icin once ozet, sonra (varsa) iz;
     * ikisi de gidince kuyruktan duser.
     *
     * Gecici hatada (ag, sunucu mesgul, yetki) tur DURUR: sonraki yolculuklari
     * denemek ayni hatayi tekrarlamaktan baska ise yaramaz. Kalici redde o
     * yolculuk ayrilir ve tur devam eder — tek bir bozuk kayit kuyrugu
     * sonsuza dek tikamamali.
     */
    fun drain(
        tripOf: (Long) -> Trip?,
        trackOf: (Long) -> File?,
        sender: Sender,
    ): Outcome {
        var uploaded = 0
        var rejected = 0
        var dropped = 0
        for (epoch in pending()) {
            val trip = tripOf(epoch)
            if (trip == null) {
                done(epoch)
                dropped++
                continue
            }

            val err = sendSummary(trip, sender) ?: trackOf(epoch)?.let { sendTrack(epoch, it, sender) }
            when {
                err == null -> {
                    done(epoch)
                    uploaded++
                }
                isPermanent(err) -> {
                    reject(epoch, err)
                    rejected++
                }
                else -> return Outcome(uploaded, rejected, dropped, err)
            }
        }
        return Outcome(uploaded, rejected, dropped, null)
    }

    private fun sendSummary(trip: Trip, sender: Sender): String? {
        val json = trip.toJson().toString().toByteArray(Charsets.UTF_8)
        return sender.send(
            TripFile("trip-${trip.startEpoch}.json", trip.startEpoch, KIND_SUMMARY, MIME_JSON, json)
        )
    }

    private fun sendTrack(epoch: Long, file: File, sender: Sender): String? {
        if (!file.exists()) return null
        val gz = runCatching { file.readBytes() }.getOrElse { return "iz okunamadı: ${it.message}" }
        // Bozuk gzip'i gondermek okuyan her istemcide hata demek; bunu yeniden
        // denemek de kuyrugu tikardi.
        runCatching { gunzippedSize(gz) }.onFailure {
            return "$PERMANENT_PREFIX iz bozuk: ${it.message}"
        }
        return sender.send(TripFile("trip-$epoch.csv.gz", epoch, KIND_TRACK, MIME_GZIP, gz))
    }

    companion object {
        const val REJECTED_SUFFIX = ".red"

        /**
         * KALICI hata isareti: ayni icerikle kac kez denense ayni cevap gelir
         * (Drive 400, bozuk yerel dosya). Geri kalan her sey — ag, hiz siniri,
         * hatta yetki — gecici sayiliyor: bagli olmayan ya da izni daraltilmis
         * bir hesap butun kuyrugu RED'e cevirmemeli, yeniden baglaninca gitmeli.
         */
        const val PERMANENT_PREFIX = "[kalıcı]"

        const val KIND_SUMMARY = "ozet"
        const val KIND_TRACK = "iz"
        const val MIME_JSON = "application/json"
        const val MIME_GZIP = "application/gzip"

        fun isPermanent(error: String): Boolean = error.startsWith(PERMANENT_PREFIX)

        fun gunzippedSize(gz: ByteArray): Int {
            var total = 0
            GZIPInputStream(gz.inputStream()).use { input ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                }
            }
            return total
        }
    }
}
