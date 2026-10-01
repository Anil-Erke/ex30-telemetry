package com.example.ex30telemetry.car

import android.content.Context
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.common.OnCarDataAvailableListener
import androidx.car.app.hardware.info.EnergyLevel
import androidx.core.content.ContextCompat
import com.example.ex30telemetry.Constants
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/**
 * Araç verisinin tek gercek kaynagi. Screen'lerden BAGIMSIZ: Screen yeniden
 * yaratildiginda veri kaybolmamali.
 *
 * Ornekleme hizlari Faz 0'da gercek EX30'da olculdu (bkz. prompt.md §10.8):
 *
 * | Property | Araçtan gelen | Istedigimiz | Neden |
 * |---|---|---|---|
 * | `PERF_VEHICLE_SPEED_DISPLAY` | 10 Hz | 10 Hz | **kullanilan hiz** — aracin gostergesiyle ayni sayi |
 * | `PERF_VEHICLE_SPEED` | 10 Hz · ±0 ms | 10 Hz | yalnizca gosterge hizi yoksa yedek |
 * | `EV_BATTERY_INSTANTANEOUS_CHARGE_RATE` | 10 Hz · ±5 ms | 10 Hz | enerji integralinin tabani |
 * | `EV_BATTERY_LEVEL` | 100 Hz kabul ediyor | **1 Hz** | deger %1 SoC = 0,66 kWh adimlarla degisiyor; 100 Hz'de saniyede yuz kez ayni sayi geliyor |
 * | `RANGE_REMAINING` | ~1,6 Hz | 2 Hz | 1 km adimlarla degisiyor |
 * | `ENV_OUTSIDE_TEMPERATURE` | 2 Hz | 1 Hz | yavas degisen buyukluk |
 * | vites / kontak / park freni | ON_CHANGE | 5 Hz | zaten yalnizca degisimde olay uretiyorlar |
 *
 * Gelmeyen bir property sessizce dusuyor: ilgili alan null kaliyor, ekranda o
 * satir gorunmuyor, uygulama calismaya devam ediyor.
 *
 * ## Oturumdan bagimsiz (2026-09-28)
 *
 * Hub artik duz bir [Context] ile calisiyor: uygulama hic acilmadan, arac
 * acilisinda baslayan [com.example.ex30telemetry.JourneyService]
 * icinde yasiyor. `CarContext` yalnizca bir oturum ACIKKEN var; ona bagli tek
 * kaynak Car App Library'nin SoC dinleyicisi, o da [attachCarContext] ile
 * sonradan takiliyor. Oturum yokken SoC ham property'lerden hesaplaniyor
 * (bkz. [computedSoc]).
 */
class VehicleDataHub(context: Context) {

    private val appContext: Context = context.applicationContext

    /** Ekranlarin okudugu anlik goruntu. Null = veri yok, sifir degil. */
    data class Snapshot(
        val speedKmh: Double? = null,
        /** Ham tekerlek hizi — mesafe yedegi icin; ekranda gosterge hizi kullaniliyor. */
        val rawSpeedKmh: Double? = null,
        /** Pozitif = tuketim, negatif = rejen (Faz 0'da olculdu). */
        val powerKw: Double? = null,
        val batteryKwh: Double? = null,
        /**
         * `EV_CURRENT_BATTERY_CAPACITY` — yaslanmayi ve sicakligi hesaba katan
         * ANLIK kullanilabilir kapasite (kWh). Nominal 66,0 ile karistirilmamali:
         * gercek EX30'da 66,24-66,26 arasinda ve canli degisiyor (2026-09-09).
         */
        val usableCapacityKwh: Double? = null,
        val socPercent: Double? = null,
        val rangeKm: Double? = null,
        val outsideTempC: Double? = null,
        /**
         * Tekerlek tiklerinden biriken mesafe (metre). Odometre ucuncu partiye
         * kapali oldugu icin bu, GPS mesafesini KARSILASTIRABILECEGIMIZ tek
         * bagimsiz kaynak (bkz. WheelOdometer).
         */
        val wheelDistanceM: Double? = null,
        val gear: Int? = null,
        val ignitionOn: Boolean? = null,
        val parkingBrakeOn: Boolean? = null,
        /**
         * Aracin gece modu (`NIGHT_MODE`). Null = arac bu property'yi bildirmiyor.
         *
         * Paletin BIRINCI kaynagi bu. `CarContext.isDarkMode` host'un uygulamaya
         * gonderdigi yapilandirmaya bakiyor ve host gece bilgisini hic
         * gondermezse sessizce "gunduz" okunuyor — uygulama gece de acik temada
         * kaliyor. Property dogrudan araçtan geliyor ve izni
         * (CAR_EXTERIOR_ENVIRONMENT) dis sicaklik icin zaten aliniyor, yeni izin
         * gerekmiyor.
         */
        val nightMode: Boolean? = null,
    )

    /** Yuksek frekansli ornek: A4 ve enerji integrali bunlari dogrudan tuketiyor. */
    fun interface SampleListener {
        fun onSample(value: Double, tNanos: Long)
    }

    private val stream = CarPropertyStream(appContext)

    /** `WHEEL_TICK` sabitleri araçtan gelmezse null kalir ve satir gorunmez. */
    private var wheels: WheelOdometer? = null
    private val speedListeners = CopyOnWriteArrayList<SampleListener>()
    private val powerListeners = CopyOnWriteArrayList<SampleListener>()
    private val rawSpeedListeners = CopyOnWriteArrayList<SampleListener>()

    @Volatile
    var snapshot = Snapshot()
        private set

    /** Nominal batarya kapasitesi (kWh). Gercek EX30: 66,0. */
    @Volatile
    var nominalCapacityKwh: Double = Constants.FALLBACK_BATTERY_KWH
        private set

    private var energyListener: OnCarDataAvailableListener<EnergyLevel>? = null
    private var carContext: CarContext? = null
    private var started = false

    /**
     * Car App Library'nin son SoC degeri. Oturum kapaninca null'a donuyor ve
     * [Snapshot.socPercent] hesaplanan degere geri dusuyor.
     */
    @Volatile
    private var carInfoSoc: Double? = null

    /** Fiilen kullanilan hiz property'si — gosterge hizi yoksa hama dusuluyor. */
    @Volatile
    var speedSource: String = "PERF_VEHICLE_SPEED_DISPLAY"
        private set

    fun addSpeedListener(l: SampleListener) { speedListeners += l }
    fun addPowerListener(l: SampleListener) { powerListeners += l }

    /** Ham hiz: yalnizca mesafe yedegi kullaniyor (bkz. startSpeed). */
    fun addRawSpeedListener(l: SampleListener) { rawSpeedListeners += l }

    @Synchronized
    fun start() {
        if (started) return
        started = true

        if (!stream.start()) {
            Log.w(TAG, "Araç verisi açılamadı: ${stream.lastError}")
        } else {
            (stream.read("INFO_EV_BATTERY_CAPACITY")?.value as? Number)
                ?.toDouble()?.let { if (it > 0) nominalCapacityKwh = it / 1000.0 }

            startSpeed()
            listen("EV_BATTERY_INSTANTANEOUS_CHARGE_RATE", 10f) { v, t ->
                // Ham birim mW, pozitif = tuketim (Faz 0'da olculdu).
                val kw = v / 1_000_000.0
                update { it.copy(powerKw = kw) }
                powerListeners.forEach { l -> l.onSample(kw, t) }
            }
            listen("EV_BATTERY_LEVEL", 1f) { v, _ ->
                update { withSoc(it.copy(batteryKwh = v / 1000.0)) }
            }
            // ON_CHANGE property; sicaklikla birkac on Wh oynuyor. 1 Hz fazlasiyla yeter.
            listen("EV_CURRENT_BATTERY_CAPACITY", 1f) { v, _ ->
                if (v > 0) update { withSoc(it.copy(usableCapacityKwh = v / 1000.0)) }
            }
            listen("RANGE_REMAINING", 2f) { v, _ ->
                update { it.copy(rangeKm = v / 1000.0) }
            }
            listen("ENV_OUTSIDE_TEMPERATURE", 1f) { v, _ ->
                update { it.copy(outsideTempC = v) }
            }
            listen("GEAR_SELECTION", 5f) { v, _ ->
                update { it.copy(gear = v.toInt()) }
            }
            listen("IGNITION_STATE", 5f) { v, _ ->
                update { it.copy(ignitionOn = v.toInt() >= IGNITION_ON) }
            }
            listen("PARKING_BRAKE_ON", 5f) { v, _ ->
                update { it.copy(parkingBrakeOn = v != 0.0) }
            }
            // Gece modu ON_CHANGE davraniyor; 1 Hz fazlasiyla yeterli.
            listen("NIGHT_MODE", 1f) { v, _ ->
                update { it.copy(nightMode = v != 0.0) }
            }
            startWheelTicks()
        }
    }

    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        stream.stop()
        detachCarContext()
    }

    /**
     * Oturum acildi: Car App Library'nin SoC dinleyicisini tak. Oturum
     * kapanmadan [detachCarContext] cagrilmali — CarContext oturumla oluyor.
     */
    @Synchronized
    fun attachCarContext(ctx: CarContext) {
        if (carContext === ctx) return
        detachCarContext()
        carContext = ctx
        startCarInfoEnergy(ctx)
    }

    @Synchronized
    fun detachCarContext() {
        val ctx = carContext ?: return
        energyListener?.let { l ->
            runCatching {
                ctx.getCarService(CarHardwareManager::class.java)
                    .carInfo.removeEnergyLevelListener(l)
            }
        }
        energyListener = null
        carContext = null
        carInfoSoc = null
        update { withSoc(it) }
    }

    // --- Ic isler ---

    /**
     * Hiz kaynagi: **gosterge hizi** (`PERF_VEHICLE_SPEED_DISPLAY`), yani aracin
     * kendi hiz gostergesinde yazan deger. Uygulamanin soyledigi hizin surucunun
     * gordugu hizla ayni olmasi, ham tekerlek hizindan birkac km/h sapmasindan
     * daha degerli — A4 rekorlari da bu sayede gosterge ile karsilastirilabilir.
     *
     * Bu property yoksa ham hiza dusuluyor: opsiyonel olan kaynak, olcum degil
     * (bir property gelmiyorsa uygulama cokmez, sessizce ikinciye gecer).
     */
    private fun startSpeed() {
        val display = listen("PERF_VEHICLE_SPEED_DISPLAY", 10f, ::onSpeed)
        speedSource = if (display is CarPropertyStream.Subscription.Unavailable) {
            Log.i(TAG, "gösterge hızı yok (${display.debugLabel}); ham hıza düşülüyor")
            // Tek kaynak kaldi: hem ekrani hem mesafeyi o besliyor.
            listen("PERF_VEHICLE_SPEED", 10f) { v, t -> onSpeed(v, t); onRawSpeed(v, t) }
            "PERF_VEHICLE_SPEED"
        } else {
            // Ham hiz ekranda kullanilmiyor ama MESAFE icin dinlenmeye devam
            // ediyor: GPS sustugunda mesafe bundan yurutuluyor. Gosterge hizi bu
            // is icin uygun degil — %2,5 + 1,2 km/h yuksek okuyor, mesafeyi de
            // o kadar sisirirdi. Olculdu (2026-08-31, 15 km'lik gercek surus):
            // ∫ham 11,836 km · GPS 11,805 km (%0,26 fark) · ∫gosterge 12,404 km.
            listen("PERF_VEHICLE_SPEED", 10f, ::onRawSpeed)
            "PERF_VEHICLE_SPEED_DISPLAY"
        }
    }

    private fun onRawSpeed(v: Double, t: Long) {
        val kmh = abs(v * 3.6)
        update { it.copy(rawSpeedKmh = kmh) }
        rawSpeedListeners.forEach { l -> l.onSample(kmh, t) }
    }

    private fun onSpeed(v: Double, t: Long) {
        // Ham deger m/s; birim ayarlarina gore donusum yapilmiyor (§10.4/2).
        //
        // ISARETLI geliyor: gercek EX30'da −3,0 km/h'ye kadar negatif deger
        // okundu (geri vites / geri kayma). Uygulamanin hicbir yerinde yone
        // ihtiyac yok — geri gidildigi zaten GEAR_SELECTION'dan belli — ama
        // isaret birakilirsa "hiz > 3" esigi geri viteste hic tetiklenmiyor ve
        // hiz grafigi sifirin altina sarkiyor.
        val kmh = abs(v * 3.6)
        update { it.copy(speedKmh = kmh) }
        speedListeners.forEach { l -> l.onSample(kmh, t) }
    }

    /**
     * Property'yi dinler VE once bir kez dogrudan okur.
     *
     * **Ilk okuma neden sart (2026-09-10'da araçta olculdu):** ON_CHANGE
     * property'ler yalnizca DEGER DEGISINCE olay uretiyor. `EV_CURRENT_BATTERY_CAPACITY`
     * sicaklikla dakikada bir kipirdadigi icin alt gostergeler acilistan sonra
     * uzun sure "—" kaliyordu; kullanici "once calismadi, sonra kendiliginden
     * calisti" diye bildirdi. Dinleyici dogruydu, eksik olan BASLANGIC degeriydi.
     *
     * CONTINUOUS property'lerde de zararsiz: ilk olay zaten milisaniyeler icinde
     * gelecek, bir kez fazladan okumus oluyoruz.
     */
    /**
     * `WHEEL_TICK` aboneligi.
     *
     * Ayri bir fonksiyon cunku deger `Long[]`: genel [listen] yardimcisi
     * Number/Boolean'a cevirdigi icin dizi tasiyamiyor, akisa dogrudan
     * baglaniyoruz.
     *
     * 10 Hz isteniyor — hiz kanaliyla ayni. Mesafe icin 1 Hz de yeterdi ama
     * ayni tempoda ornekleme sifirlanma esigini (WheelOdometer.RESET_TICKS)
     * guvenli tutuyor.
     */
    private fun startWheelTicks() {
        val odo = WheelOdometer.fromConfigArray(stream.configArrayOf("WHEEL_TICK"))
        if (odo == null) {
            Log.i(TAG, "WHEEL_TICK sabitleri yok — tekerlek mesafesi kapalı")
            return
        }
        wheels = odo
        val sub = stream.listen("WHEEL_TICK", 10f) { ev ->
            if (ev.status != 0) return@listen
            odo.onTicks(ev.value)
            update { it.copy(wheelDistanceM = odo.totalM) }
        }
        Log.i(TAG, "WHEEL_TICK: ${sub.debugLabel}")
    }

    private fun listen(
        name: String,
        rateHz: Float,
        onValue: (Double, Long) -> Unit,
    ): CarPropertyStream.Subscription {
        // Adsiz fonksiyon (lambda degil): govdede duz `return` kullanilabilsin diye.
        val feed = fun(ev: CarPropertyStream.Event) {
            // Gecersiz durumdaki degerler cop olabiliyor (prompt.md §10.4).
            if (ev.status != 0) return
            val d = when (val v = ev.value) {
                is Number -> v.toDouble()
                is Boolean -> if (v) 1.0 else 0.0
                else -> return
            }
            onValue(d, ev.tNanos)
        }

        // Baslangic degeri: dinleyiciden ONCE, ayni suzgecten gecirilerek.
        runCatching { stream.read(name)?.let(feed) }

        val sub = stream.listen(name, rateHz, feed)
        Log.i(TAG, "$name: ${sub.debugLabel}")
        return sub
    }

    private inline fun update(f: (Snapshot) -> Snapshot) {
        synchronized(this) { snapshot = f(snapshot) }
    }

    /** SoC'yi mevcut kaynaklardan yeniden cozer: once Car App Library, yoksa hesap. */
    private fun withSoc(s: Snapshot): Snapshot =
        s.copy(socPercent = carInfoSoc ?: computedSoc(s))

    /**
     * Oturum yokken SoC: `EV_BATTERY_LEVEL / EV_CURRENT_BATTERY_CAPACITY`.
     *
     * 2026-09-09'da gercek EX30'da olculdu: EV_BATTERY_LEVEL, SoC x GERCEK
     * (kullanilabilir) kapasite — nominal degil. Uc olcumde de Car App
     * Library'nin yuzdesini tam tutturdu (40418,6/66260 = %61,00). Aradaki
     * anlarda bu hesap ONDALIKLI veriyor, kutuphane tam sayi; en fazla 1 puan
     * fark. Kapasite henuz gelmediyse nominale dusuluyor.
     */
    private fun computedSoc(s: Snapshot): Double? {
        val level = s.batteryKwh ?: return null
        val cap = s.usableCapacityKwh ?: nominalCapacityKwh
        if (cap <= 0) return null
        return (level / cap * 100.0).coerceIn(0.0, 100.0)
    }

    /**
     * Oturum acikken SoC'nin birincil kaynagi Car App Library'nin EnergyLevel
     * dinleyicisi — aracin gosterdigi sayiyla ayni olsun diye.
     * Bu cagri iceride `READ_CAR_DISPLAY_UNITS` istiyor (bkz. AndroidManifest).
     * Gercek EX30'da SoC TAM SAYI geliyor — %1 = 0,66 kWh.
     */
    private fun startCarInfoEnergy(ctx: CarContext) {
        runCatching {
            val info = ctx.getCarService(CarHardwareManager::class.java).carInfo
            val l = OnCarDataAvailableListener<EnergyLevel> { e ->
                if (e.batteryPercent.status == CarValue.STATUS_SUCCESS) {
                    e.batteryPercent.value?.toDouble()?.let { p ->
                        carInfoSoc = p
                        update { withSoc(it) }
                    }
                }
            }
            energyListener = l
            info.addEnergyLevelListener(ContextCompat.getMainExecutor(ctx), l)
        }.onFailure { Log.w(TAG, "CarInfo enerji dinleyicisi kurulamadı", it) }
    }

    companion object {
        private const val TAG = "JourneyHub"

        /** `VehicleIgnitionState`: ON = 4, START = 5. Gercek EX30'da 4 okundu. */
        private const val IGNITION_ON = 4

        /** `VehicleGear`: NEUTRAL=1, REVERSE=2, PARK=4, DRIVE=8. */
        const val GEAR_PARK = 4
    }
}
