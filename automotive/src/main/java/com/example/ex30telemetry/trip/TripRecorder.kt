package com.example.ex30telemetry.trip

import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.example.ex30telemetry.Constants
import com.example.ex30telemetry.calib.Calibration
import com.example.ex30telemetry.car.VehicleDataHub
import org.json.JSONObject

enum class TripState { BEKLEME, HAZIR, AKTİF, KAPANIYOR }

/**
 * Yolculuk durum makinesi (EX30-YOL-ANALIZI-PROMPT.md §6):
 *
 * ```
 * BEKLEME   --(kontak AÇIK)-------------------------->  HAZIR
 * HAZIR     --(vites P dışı VEYA hız > 3 km/h)------->  AKTİF      [yolculuk başlar]
 * AKTİF     --(vites P VE park freni VEYA kontak KAPALI)-> KAPANIYOR
 * KAPANIYOR --(10 sn içinde tekrar hareket yok)------>  BEKLEME    [yolculuk yazılır]
 * KAPANIYOR --(tekrar hareket)----------------------->  AKTİF
 * ```
 *
 * Kirmizi isikta durus yolculugu bitirmemeli — `KAPANIYOR` gecikmesi bunun icin.
 *
 * Kontak verisi hic gelmiyorsa durum makinesi kilitlenmez: hiz tek basina
 * yolculuk baslatabiliyor (opsiyonel ozellikler sessizce duser).
 */
class TripRecorder(
    private val hub: VehicleDataHub,
    private val store: TripStore,
    /** Yolculugun GPS izi; ozetle ayni anahtarla (startEpoch) saklaniyor. */
    val track: TrackRecorder,
) {
    /**
     * Bir yolculuk kalici kayda girdiginde (ozeti VE izi diskte) cagrilir —
     * otomatik yukleme kuyruga buradan aliyor. Kurtarilan yolculuklar da dahil.
     */
    @Volatile
    var onTripSaved: ((Trip) -> Unit)? = null

    @Volatile
    var state: TripState = TripState.BEKLEME
        private set

    @Volatile
    var live: TripAccumulator? = null
        private set

    /** Son kapanan yolculuk — ekranlar "az önce ne kaydedildi" diye sorabilsin. */
    @Volatile
    var lastSaved: Trip? = null
        private set

    /**
     * Esigin altinda kaldigi icin ATILAN son yolculuk. Atilma daha once yalnizca
     * logcat'e yaziliyordu; araçta logcat okunamadigi icin 45 km'lik bir surusun
     * neden kaydedilmedigi gunler sonra bile anlasilamadi. Artik Olcum ekraninda
     * gorunuyor.
     */
    @Volatile
    var skipped: Skipped? = null
        private set

    data class Skipped(val distanceM: Double, val durationSec: Long, val atEpoch: Long)

    private var lastGpsNoteMs = 0L

    /** A4: en son yakalanan olcum ve rekor olup olmadigi (5 sn'lik serit icin). */
    @Volatile
    var lastCatch: Catch? = null
        private set

    data class Catch(val record: PerfRecord, val kind: PerfKind, val isRecord: Boolean, val atMs: Long)

    /**
     * A4 olcumleri yolculuk acik olmasa da yakalanir: bir 0-100 ~75 metrede
     * bitiyor, o yolculuk 500 m esigini gecmeyebilir ama rekor gecerlidir.
     */
    private val catcher = PerformanceCatcher { record, kind ->
        val isRecord = store.offerRecord(record)
        live?.records?.add(record)
        lastCatch = Catch(record, kind, isRecord, System.currentTimeMillis())
        Log.i(TAG, "ölçüm yakalandı: ${kind.id} = ${record.value} ${record.unit} (rekor=$isRecord)")
    }

    /**
     * Debug kancasi durum makinesini eline aldiginda true olur ve gercek araç
     * verisi artik gecis uretmez (prompt.md §6.11'deki `if (simulating) return`
     * deseni). Emulatorde bu sart: orada vites P ve park freni cekili
     * geliyor, hiz sabit 0 — yani her yolculuk acilir acilmaz kapanirdi.
     *
     * Release derlemesinde kanca hic kurulmadigi icin daima false.
     */
    @Volatile
    var simulating = false
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var closingSinceMs = 0L
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            evaluate()
            handler.postDelayed(this, Constants.LIVE_SNAPSHOT_INTERVAL_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        hub.addSpeedListener { kmh, t ->
            // Sentetik surus sirasinda gercek ornekleri yok say; emulatorun
            // sabit 0 km/h'si her olcumu iptal ederdi (prompt.md §6.11).
            if (simulating) return@addSpeedListener
            live?.onSpeed(kmh)
            catcher.onSpeed(kmh, t)
        }
        hub.addPowerListener { kw, t -> live?.energy?.onPower(kw, t) }
        // Ham hiz yalnizca mesafe yedegini besliyor; ekranda gosterge hizi var.
        hub.addRawSpeedListener { kmh, t -> live?.onRawSpeed(kmh, t) }
        recoverIfNeeded()
        handler.post(tick)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
    }

    fun onLocation(location: Location) {
        val acc = live ?: return
        acc.onGpsFix(SystemClock.elapsedRealtime())
        acc.onLocation(location)
        // Iz, birikimden SONRA yaziliyor: dist_m bu fix'i de icersin.
        //
        // Her fix yaziliyor, birikimin eledigi titresim adimlari da: iz ham
        // veri, suzme analizin isi. Durma anlarini zaten LocationTracker'in
        // 3 m esigi seyreltiyor.
        val s = hub.snapshot
        track.append(
            tMs = location.time,
            lat = location.latitude,
            lon = location.longitude,
            altM = if (location.hasAltitude()) location.altitude else null,
            hAccM = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
            vAccM = if (location.hasVerticalAccuracy()) location.verticalAccuracyMeters.toDouble() else null,
            gpsKmh = if (location.hasSpeed()) location.speed * 3.6 else null,
            kmh = s.speedKmh,
            kw = s.powerKw,
            soc = s.socPercent,
            distM = acc.distanceM,
        )
    }

    /**
     * "Yeni yolculuk" dugmesi: acik yolculugu kapatip sifirdan baslatir.
     *
     * Durum AKTIF olmali. Onceki surumde HAZIR birakiliyordu; sonuc olarak
     * yolculuk birikiyor ama ekranda "HAZIR" yaziyordu ve HAZIR dalinda vites
     * P oldugu icin bir daha AKTIF'e gecemiyordu. Gercek araçta goruldu
     * (2026-08-01): mesafe/sure sayarken rozet HAZIR kalmisti.
     */
    @Synchronized
    fun restartTrip() {
        val before = state
        if (state == TripState.AKTİF || state == TripState.KAPANIYOR) closeTrip()
        beginTrip()
        state = TripState.AKTİF
        // Bu yol evaluate() disindan geciyor; not burada yazilmazsa gunluge hic
        // girmiyor. 31 Agustos kaydinda "HAZIR->AKTİF" satiri tam bu yuzden
        // eksikti ve yolculugun neden basladigi anlasilamadi.
        Calibration.current()?.note("state", "$before->$state (Yeni)")
    }

    /**
     * Debug kancasi icin: sentetik hiz ornegi besler. Emulatorde VHAL hizi
     * sabit 0 yayinladigi icin A4 olcumleri baska turlu dogrulanamiyor (§6.9).
     */
    fun debugFeedSpeed(kmh: Double, tNanos: Long) {
        simulating = true
        live?.onSpeed(kmh)
        catcher.onSpeed(kmh, tNanos)
    }

    /** Debug kancasi icin: durumu elle sur (§8). Otomatik gecisleri kapatir. */
    @Synchronized
    fun forceState(target: TripState) {
        simulating = true
        Log.i(TAG, "durum elle ayarlandı: $state -> $target (simulating)")
        when (target) {
            TripState.AKTİF -> if (live == null) beginTrip()
            TripState.BEKLEME -> if (live != null) closeTrip()
            TripState.KAPANIYOR -> closingSinceMs = System.currentTimeMillis()
            TripState.HAZIR -> Unit
        }
        state = target
    }

    // --- Durum makinesi ---

    @Synchronized
    private fun evaluate() {
        val before = state
        evaluateState()
        // Durum degisimleri kalibrasyon gunlugune de yaziliyor: bir yolculuk
        // kaydedilmediginde "hic AKTIF olmadi mi, yoksa kaydedilirken mi
        // dusuruldu" sorusunun araçtaki tek cevabi bu satirlar (bkz. note()).
        if (state != before) Calibration.current()?.note("state", "$before->$state")
    }

    private fun evaluateState() {
        val s = hub.snapshot

        // Sentetik surus: durumu debug kancasi belirliyor, birikim devam ediyor.
        if (simulating) {
            if (state == TripState.AKTİF || state == TripState.KAPANIYOR) accumulate(s)
            return
        }

        val speed = s.speedKmh ?: 0.0
        val moving = speed > Constants.TRIP_START_SPEED_KMH
        // Veri gelmiyorsa "engelleme" yonunde varsay: kontak bilinmiyorsa acik
        // kabul et, park freni bilinmiyorsa cekilmemis kabul et.
        val ignitionOn = s.ignitionOn ?: true
        val inPark = s.gear?.let { it == VehicleDataHub.GEAR_PARK } ?: false
        val brakeOn = s.parkingBrakeOn ?: false

        when (state) {
            TripState.BEKLEME ->
                if (ignitionOn) state = TripState.HAZIR

            TripState.HAZIR ->
                if (!inPark || moving) {
                    beginTrip()
                    state = TripState.AKTİF
                }

            TripState.AKTİF -> {
                accumulate(s)
                if ((inPark && brakeOn) || !ignitionOn) {
                    closingSinceMs = System.currentTimeMillis()
                    state = TripState.KAPANIYOR
                }
            }

            TripState.KAPANIYOR -> {
                accumulate(s)
                if (moving && ignitionOn) {
                    // Kirmizi isik / kisa duraklama: yolculuk devam ediyor.
                    state = TripState.AKTİF
                } else if (System.currentTimeMillis() - closingSinceMs >=
                    Constants.CLOSING_GRACE_SEC * 1000
                ) {
                    closeTrip()
                    state = TripState.BEKLEME
                }
            }
        }
    }

    private fun accumulate(s: VehicleDataHub.Snapshot) {
        val acc = live ?: return
        // GPS bayatladiysa mesafeyi ham hizdan yurut (bkz. bridgeIfGpsStale).
        acc.bridgeIfGpsStale(SystemClock.elapsedRealtime())
        acc.onTick(s.outsideTempC)

        // GPS mesafesi 60 sn'de bir gunluge. Yolculuk esigin altinda kaldigi icin
        // atildiginda, mesafenin hic ilerleyip ilerlemedigi bu satirlardan
        // gorulebiliyor — konum fix'i mi yoktu, yoksa gercekten kisa mi surdu.
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastGpsNoteMs >= GPS_NOTE_INTERVAL_MS) {
            lastGpsNoteMs = nowMs
            Calibration.current()?.note(
                "gps", "%.0f".format(acc.distanceM), acc.durationSec, acc.speedSnapshot().size,
                // Bunun buyumesi = GPS susmus, mesafe ham hizdan yurutulmus.
                "köprü=${"%.0f".format(acc.bridgedM)}",
            )
        }
        // Tekerlek sayaci: baslangic bir kez, bitis her turda.
        s.wheelDistanceM?.let { m ->
            if (acc.wheelStartM == null) acc.wheelStartM = m
            acc.wheelEndM = m
        }
        s.socPercent?.let { acc.socEnd = it; if (acc.socStart == null) acc.socStart = it }
        s.rangeKm?.let { acc.rangeEndKm = it; if (acc.rangeStartKm == null) acc.rangeStartKm = it }
        s.outsideTempC?.let { if (acc.tempStart == null) acc.tempStart = it }
        // Kontak kesilmesi/cokme durumunda yolculuk kaybolmasin.
        store.saveLive(snapshotJson(acc))
    }

    private fun beginTrip() {
        val acc = TripAccumulator(System.currentTimeMillis())
        val s = hub.snapshot
        acc.socStart = s.socPercent
        acc.rangeStartKm = s.rangeKm
        acc.tempStart = s.outsideTempC
        acc.wheelStartM = s.wheelDistanceM
        acc.wheelEndM = s.wheelDistanceM
        live = acc
        track.begin(acc.startEpoch)
        Log.i(TAG, "yolculuk başladı")
    }

    private fun closeTrip() {
        val acc = live ?: return
        live = null
        if (!acc.worthKeeping) {
            Log.i(
                TAG,
                "yolculuk atlandı: ${"%.0f".format(acc.distanceM)} m / ${acc.durationSec} sn " +
                    "(eşik ${Constants.MIN_TRIP_DISTANCE_M.toInt()} m / ${Constants.MIN_TRIP_DURATION_SEC} sn)",
            )
            Calibration.current()?.note(
                "trip", "atlandı", "%.0f".format(acc.distanceM), acc.durationSec, acc.maxSpeedKmh.toInt(),
            )
            skipped = Skipped(acc.distanceM, acc.durationSec, System.currentTimeMillis())
            store.clearLive()
            track.discard()
            return
        }
        val trip = acc.toTrip(System.currentTimeMillis())
        store.add(trip)
        val trackFile = track.finish(trip.startEpoch)
        Calibration.current()?.note("track", trackFile?.name ?: "yok", track.rows)
        onTripSaved?.invoke(trip)
        lastSaved = trip
        skipped = null
        Log.i(TAG, "yolculuk kaydedildi: ${"%.2f".format(trip.distanceKm)} km")
        Calibration.current()?.note(
            "trip", "kaydedildi", "%.0f".format(acc.distanceM), acc.durationSec, acc.maxSpeedKmh.toInt(),
        )
    }

    /**
     * Acilista yarim kalmis yolculuk varsa kaydeder. Kontak kesildiginde
     * uygulama olduruluyor; 1 Hz'lik `live.json` bu durumda tek kanit.
     */
    private fun recoverIfNeeded() {
        val o = store.loadLive()
        if (o == null) {
            // live.json yoksa yarim iz sahipsiz kalmistir.
            track.discard()
            return
        }
        store.clearLive()
        val recovered = runCatching {
            val distanceM = o.optDouble("distanceM", 0.0)
            val startEpoch = o.optLong("startEpoch")
            val endEpoch = o.optLong("lastEpoch", startEpoch)
            val durationSec = (endEpoch - startEpoch) / 1000
            if (distanceM < Constants.MIN_TRIP_DISTANCE_M ||
                durationSec < Constants.MIN_TRIP_DURATION_SEC
            ) return@runCatching null
            val trip = Trip(
                    startEpoch = startEpoch,
                    endEpoch = endEpoch,
                    durationSec = durationSec,
                    distanceKm = distanceM / 1000.0,
                    energyKwh = o.optDoubleOrNull("netKwh"),
                    regenKwh = o.optDoubleOrNull("regenKwh"),
                    socStart = o.optDoubleOrNull("socStart"),
                    socEnd = o.optDoubleOrNull("socEnd"),
                    rangeStart = o.optDoubleOrNull("rangeStart"),
                    rangeEnd = o.optDoubleOrNull("rangeEnd"),
                    avgSpeedKmh = if (durationSec > 0)
                        distanceM / 1000.0 / (durationSec / 3600.0) else null,
                    maxSpeedKmh = o.optDoubleOrNull("maxSpeedKmh"),
                    tempStart = o.optDoubleOrNull("tempStart"),
                    tempAvg = o.optDoubleOrNull("tempAvg"),
                    altGainM = o.optDouble("altGainM", 0.0),
                    altLossM = o.optDouble("altLossM", 0.0),
                    potentialKwh = o.optDoubleOrNull("potentialKwh"),
                    consumptionKwh100 = o.optDoubleOrNull("consumptionKwh100"),
                    rangeBiasFactor = o.optDoubleOrNull("rangeBiasFactor"),
                )
            store.add(trip)
            Log.i(TAG, "yarım kalmış yolculuk kurtarıldı: ${distanceM.toInt()} m")
            trip
        }.onFailure { Log.w(TAG, "live.json kurtarılamadı", it) }.getOrNull()

        // Iz de kurtariliyor: basligindaki anahtar tutmazsa finish() kendisi atar.
        if (recovered == null) {
            track.discard()
            return
        }
        track.finish(recovered.startEpoch)
        onTripSaved?.invoke(recovered)
    }

    private fun snapshotJson(acc: TripAccumulator): JSONObject = JSONObject().apply {
        put("startEpoch", acc.startEpoch)
        put("lastEpoch", System.currentTimeMillis())
        put("distanceM", acc.distanceM)
        put("netKwh", acc.energy.netKwh)
        put("regenKwh", acc.energy.regenKwh)
        putOpt("socStart", acc.socStart)
        putOpt("socEnd", acc.socEnd)
        putOpt("rangeStart", acc.rangeStartKm)
        putOpt("rangeEnd", acc.rangeEndKm)
        putOpt("maxSpeedKmh", acc.maxSpeedKmh)
        putOpt("tempStart", acc.tempStart)
        putOpt("tempAvg", acc.tempAvg)
        put("altGainM", acc.altGainM)
        put("altLossM", acc.altLossM)
        putOpt("wheelStartM", acc.wheelStartM)
        putOpt("wheelEndM", acc.wheelEndM)
        putOpt("potentialKwh", acc.potentialKwh)
        putOpt("consumptionKwh100", acc.consumptionKwh100)
        putOpt("rangeBiasFactor", acc.rangeBiasFactor)
    }

    private companion object {
        const val TAG = "JourneyTrip"

        /** Teshis satiri araligi; gunlukte yer kaplamayacak kadar seyrek. */
        const val GPS_NOTE_INTERVAL_MS = 60_000L
    }
}
