package com.example.ex30telemetry.trip

import android.util.Log
import com.example.ex30telemetry.Constants
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Yolculuklarin kalici kaydi. `filesDir` icinde iki dosya:
 *
 *  - `trips.json` — kapanmis yolculuklar, en yeni basta, en fazla 300 kayit.
 *  - `live.json`  — AKTIF yolculugun 1 Hz'de yazilan anlik durumu. Kontak
 *    kesilir ya da uygulama coker ise yolculuk buradan kurtarilir.
 *
 * Sema degisirse eski kayitlar okunmaya devam eder ([Trip.schemaVersion]);
 * okunamayan tek bir kayit butun dosyayi dusurmez.
 */
class TripStore(private val filesDir: File) {

    private val tripsFile get() = File(filesDir, "trips.json")
    private val liveFile get() = File(filesDir, "live.json")
    private val recordsFile get() = File(filesDir, "records.json")

    private val cache = ArrayList<Trip>()
    private var loaded = false

    /** Tur basina en iyi olcum. Yolculuk kaydedilmese bile korunur. */
    private val bests = LinkedHashMap<String, PerfRecord>()
    private var recordsLoaded = false

    /** En yeni yolculuk basta. */
    @Synchronized
    fun trips(): List<Trip> {
        ensureLoaded()
        return cache.toList()
    }

    @Synchronized
    fun add(trip: Trip) {
        ensureLoaded()
        cache.add(0, trip)
        while (cache.size > Constants.MAX_TRIPS) cache.removeAt(cache.size - 1)
        persist()
        clearLive()
    }

    @Synchronized
    fun replaceAll(trips: List<Trip>) {
        ensureLoaded()
        cache.clear()
        cache.addAll(trips.take(Constants.MAX_TRIPS))
        persist()
    }

    // --- A4 rekorlari ---

    /**
     * Rekorlar yolculuk kaydindan AYRI tutuluyor. Bir 0-100 olcumu ~75 metrede
     * bitiyor; o yolculuk 500 m esigini gecmeyip atilsa bile rekor kaybolmamali.
     */
    @Synchronized
    fun bestRecords(): Map<String, PerfRecord> {
        ensureRecordsLoaded()
        return LinkedHashMap(bests)
    }

    /**
     * Yeni bir olcum sunar. Dort turde de KUCUK deger daha iyi.
     * @return rekor kirildiysa true
     */
    @Synchronized
    fun offerRecord(r: PerfRecord): Boolean {
        ensureRecordsLoaded()
        val current = bests[r.kind]
        if (current != null && current.value <= r.value) return false
        bests[r.kind] = r
        persistRecords()
        return true
    }

    private fun ensureRecordsLoaded() {
        if (recordsLoaded) return
        recordsLoaded = true
        val f = recordsFile
        if (!f.exists()) return
        runCatching {
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                runCatching {
                    val r = PerfRecord.fromJson(o)
                    if (r.kind.isNotEmpty()) bests[r.kind] = r
                }
            }
        }.onFailure { Log.w(TAG, "records.json okunamadı", it) }
    }

    private fun persistRecords() {
        runCatching {
            val arr = JSONArray()
            bests.values.forEach { arr.put(it.toJson()) }
            recordsFile.writeText(arr.toString())
        }.onFailure { Log.w(TAG, "records.json yazılamadı", it) }
    }

    // --- Canli kurtarma kaydi ---

    /** AKTIF yolculugun anlik durumunu yazar (1 Hz). */
    fun saveLive(json: JSONObject) {
        runCatching { liveFile.writeText(json.toString()) }
            .onFailure { Log.w(TAG, "live.json yazılamadı", it) }
    }

    /** Acilista yarim kalmis yolculuk varsa dondurur. */
    fun loadLive(): JSONObject? = runCatching {
        if (!liveFile.exists()) null else JSONObject(liveFile.readText())
    }.getOrNull()

    fun clearLive() {
        runCatching { if (liveFile.exists()) liveFile.delete() }
    }

    // --- Ic isler ---

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val f = tripsFile
        if (!f.exists()) return
        runCatching {
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                // Tek bir bozuk kayit yuzunden butun gecmisi kaybetme.
                runCatching { cache.add(Trip.fromJson(o)) }
                    .onFailure { Log.w(TAG, "Yolculuk kaydı okunamadı, atlanıyor", it) }
            }
        }.onFailure { Log.w(TAG, "trips.json okunamadı", it) }
    }

    private fun persist() {
        runCatching {
            val arr = JSONArray()
            cache.forEach { arr.put(it.toJson()) }
            tripsFile.writeText(arr.toString())
        }.onFailure { Log.w(TAG, "trips.json yazılamadı", it) }
    }

    companion object {
        private const val TAG = "JourneyStore"
    }
}
