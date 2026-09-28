package com.example.ex30telemetry.calib

import android.os.SystemClock
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.OnCarDataAvailableListener
import androidx.car.app.hardware.info.EnergyLevel
import androidx.core.content.ContextCompat
import com.example.ex30telemetry.R
import com.example.ex30telemetry.BuildConfig
import com.example.ex30telemetry.car.CarPropertyStream
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Faz 0 kalibrasyon sondasi. Amaci uygulama degil, **olculmus sayilar**:
 * EX30-YOL-ANALIZI-PROMPT.md §3'teki bes sorunun cevabini gercek araçtan
 * toplar.
 *
 * Dagitim yolu Play Dahili Test oldugu icin bu sinif **release derlemesinde de**
 * calisir: debug APK gercek araca hic ulasmiyor. Ayni sebeple `calib.csv`'yi
 * araçtan `adb pull` ile almak da mumkun degil — olcumun cikis yolu
 * [CalibrationScreen], dosya yalnizca ikincil kayit.
 */
class CalibrationLogger(private val carContext: CarContext) {

    companion object {
        private const val TAG = "JourneyCalib"
        private const val CSV_NAME = "calib.csv"
        private const val CSV_PREV_NAME = "calib-prev.csv"
        private const val CSV_MAX_BYTES = 8L * 1024 * 1024
        private const val CSV_HEADER = "# timestamp;propertyId;value;status\n"
        private const val FLUSH_EVERY = 200

        /**
         * Degeri degismeyen bir property CSV'ye en fazla bu aralikla yazilir.
         * Property basina ayarlaniyor ([Spec.minGapMs]); bu yalnizca varsayilan.
         *
         * **Neden property basina:** 31 Agustos olcumunde gunluk saatte 3,4 MB
         * yaziyordu, yani 8 MiB tavani ~2,4 saatlik surusle doluyordu. Payin
         * %17'si `EV_BATTERY_LEVEL`'di — degeri **3,5 km'de bir** degisen bir
         * sayiyi 5 Hz'de yazmak saf israf. Menzil (1 km adim) ve dis sicaklik
         * icin de 2 Hz fazlaydi. Guc ve hiz tam cozunurlukte kaliyor: olcumun
         * kendisi onlar.
         */
        private const val DEFAULT_MIN_GAP_MS = 200L

        /**
         * Olculecek property'ler. [divisor] ve [unit] yalnizca ekranda okunabilir
         * hale getirmek icin; hesaplar HAM deger uzerinden yapiliyor — arac birim
         * ayarlarina gore donusum yapmak yanlis sonuc uretiyor (prompt.md §10.4/2).
         */
        private val WATCHED = listOf(
            Spec("EV_BATTERY_LEVEL", "Batarya enerjisi", 1000.0, "kWh", 2, minGapMs = 1_000L),
            Spec("RANGE_REMAINING", "Kalan menzil", 1000.0, "km", 1, minGapMs = 2_000L),
            Spec("PERF_VEHICLE_SPEED", "Ham hız", 1 / 3.6, "km/h", 1),
            // Uygulama artik gosterge hizini kullaniyor; ikisi de kayda giriyor ki
            // aralarindaki fark ve ornekleme hizi gercek araçta olculebilsin.
            Spec("PERF_VEHICLE_SPEED_DISPLAY", "Gösterge hızı", 1 / 3.6, "km/h", 1),
            // Ham birim mW (gercek araçta -20,1 kW … +42,2 kW olculdu); kW gosteriyoruz.
            Spec("EV_BATTERY_INSTANTANEOUS_CHARGE_RATE", "Anlık güç", 1_000_000.0, "kW", 2),
            Spec("ENV_OUTSIDE_TEMPERATURE", "Dış sıcaklık", 1.0, "°C", 1, minGapMs = 2_000L),
            Spec("GEAR_SELECTION", "Vites", 1.0, "", 0),
            Spec("IGNITION_STATE", "Kontak", 1.0, "", 0),
            Spec("PARKING_BRAKE_ON", "Park freni", 1.0, "", 0),
        )

        data class Spec(
            val name: String,
            val label: String,
            val divisor: Double,
            val unit: String,
            val decimals: Int,
            /** Degismeyen deger icin en kisa CSV yazim araligi. */
            val minGapMs: Long = DEFAULT_MIN_GAP_MS,
        ) {
            val minGapNanos: Long get() = minGapMs * 1_000_000L
        }
    }

    private val stream = CarPropertyStream(carContext)
    private val stats = LinkedHashMap<String, PropStats>()
    private var csv: File? = null
    private var csvWriter: BufferedWriter? = null
    private var csvBytes = 0L
    private var sinceFlush = 0

    /** Dosya dolu ve dondurulemedi — ekranda gorunur olmali, sessiz kalmamali. */
    private var csvFull = false
    private var csvRotations = 0
    private var headerKind = "session"

    /** INFO_EV_BATTERY_CAPACITY — tek seferlik, ham Wh. */
    var nominalCapacityWh: Double? = null
        private set

    // --- Car App Library tarafi (CAR_ENERGY): SoC ondalik mi geliyor? ---
    private var energyListener: OnCarDataAvailableListener<EnergyLevel>? = null
    private var socSeen = 0L
    private var socHasFraction = false
    private var socMinStep = Double.MAX_VALUE
    private var socLast: Double? = null
    private var carEnergyRangeKm: Double? = null

    // --- Enerji capraz kontrolu (§3 karar kurali) ---
    private var powerIntegralRawHours = 0.0     // ham birim x saat
    private var lastPowerRaw: Double? = null
    private var lastPowerNanos = 0L
    private var batteryLevelFirstWh: Double? = null
    private var batteryLevelLastWh: Double? = null

    var running = false
        private set

    fun start() {
        if (running) return
        running = true

        csv = File(carContext.filesDir, CSV_NAME).also { openCsv(it) }

        if (!stream.start()) {
            Log.w(TAG, "Car property akışı açılamadı: ${stream.lastError}")
        } else {
            nominalCapacityWh = (stream.read("INFO_EV_BATTERY_CAPACITY")?.value as? Number)?.toDouble()
            for (spec in WATCHED) {
                val st = PropStats(spec)
                stats[spec.name] = st
                st.subscription = stream.listen(spec.name) { ev -> record(st, ev) }
                Log.i(TAG, "${spec.name}: ${st.subscription.debugLabel}")
            }
        }

        startCarInfoEnergy()
    }

    /** Tamponda bekleyen satirlari diske yazar (disa aktarim oncesi). */
    @Synchronized
    fun flush() {
        runCatching { csvWriter?.flush() }
        sinceFlush = 0
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        stream.stop()
        runCatching { csvWriter?.flush(); csvWriter?.close() }
        csvWriter = null
        energyListener?.let { l ->
            runCatching {
                carContext.getCarService(CarHardwareManager::class.java)
                    .carInfo.removeEnergyLevelListener(l)
            }
        }
        energyListener = null
    }

    // --- Olay isleme ---

    @Synchronized
    private fun record(st: PropStats, ev: CarPropertyStream.Event) {
        // CSV'ye her olayi yazmiyoruz: gercek araçta EV_BATTERY_LEVEL 100 Hz'de
        // yayin yapip degeri hic degistirmiyor (4 dakikada 25 240 ozdes satir).
        // Degisen her deger yaziliyor, degismeyenler en fazla 5 Hz'de bir.
        // Olay sikligi istatistikleri zaten butun olaylardan hesaplaniyor.
        val incoming = numeric(ev.value)
        val valueChanged = incoming == null || st.lastDistinct == null || incoming != st.lastDistinct
        if (valueChanged || ev.tNanos - st.lastCsvNanos >= st.spec.minGapNanos) {
            appendCsv(ev)
            st.lastCsvNanos = ev.tNanos
        }

        // Gecersiz durumdaki degerler cop olabiliyor (prompt.md §10.4).
        if (ev.status != 0) {
            st.invalidCount++
            return
        }

        st.count++
        if (st.firstNanos == 0L) st.firstNanos = ev.tNanos
        else {
            val dt = (ev.tNanos - st.lastNanos) / 1e6   // ms
            if (dt > 0 && dt < 60_000) {
                st.intervalCount++
                st.intervalSum += dt
                st.intervalSumSq += dt * dt
            }
        }
        st.lastNanos = ev.tNanos

        val num = numeric(ev.value) ?: run { st.lastValue = ev.value; return }
        st.lastValue = ev.value
        if (num < st.minValue) st.minValue = num
        if (num > st.maxValue) st.maxValue = num

        // Adim buyuklugu: ardisik FARKLI degerler arasindaki en kucuk sicrama.
        val prev = st.lastDistinct
        if (prev == null) {
            st.lastDistinct = num
            st.lastChangeNanos = ev.tNanos
        } else if (num != prev) {
            val step = abs(num - prev)
            if (step < st.minStep) st.minStep = step
            st.stepSum += step
            st.changeCount++
            val gap = (ev.tNanos - st.lastChangeNanos) / 1e9
            if (gap > 0) {
                if (gap < st.minChangeGapSec) st.minChangeGapSec = gap
                st.changeGapSum += gap
                st.changeGapCount++
            }
            st.lastDistinct = num
            st.lastChangeNanos = ev.tNanos
        }

        when (st.spec.name) {
            "EV_BATTERY_LEVEL" -> {
                if (batteryLevelFirstWh == null) batteryLevelFirstWh = num
                batteryLevelLastWh = num
            }
            "EV_BATTERY_INSTANTANEOUS_CHARGE_RATE" -> integratePower(num, ev.tNanos)
        }
    }

    /**
     * Anlik gucu zamana gore integre eder (yamuk kurali). Ham birim x saat
     * biriktirilir; mW mi W mi oldugu [Report] icinde iki yorum halinde sunulur.
     */
    private fun integratePower(raw: Double, tNanos: Long) {
        val prev = lastPowerRaw
        // `lastPowerNanos != 0L` kontrol etmiyoruz: damgasi 0 olan bir ornekten
        // sonraki aralik dusurulmemeli (bkz. EnergyAccountant.hasPrev).
        if (prev != null) {
            val dtHours = (tNanos - lastPowerNanos) / 1e9 / 3600.0
            // 30 sn'den uzak iki ornek arasini kopru yapma; veri kaybi olmus demektir.
            if (dtHours > 0 && dtHours < 30.0 / 3600.0) {
                powerIntegralRawHours += (prev + raw) / 2.0 * dtHours
            }
        }
        lastPowerRaw = raw
        lastPowerNanos = tNanos
    }

    private fun startCarInfoEnergy() {
        runCatching {
            val info = carContext.getCarService(CarHardwareManager::class.java).carInfo
            val l = OnCarDataAvailableListener<EnergyLevel> { e ->
                val pct = e.batteryPercent
                if (pct.status == androidx.car.app.hardware.common.CarValue.STATUS_SUCCESS) {
                    val v = pct.value?.toDouble()
                    if (v != null) {
                        socSeen++
                        if (abs(v - Math.round(v)) > 1e-4) socHasFraction = true
                        socLast?.let { p ->
                            val d = abs(v - p)
                            if (d > 1e-6 && d < socMinStep) socMinStep = d
                        }
                        socLast = v
                    }
                }
                val r = e.rangeRemainingMeters
                if (r.status == androidx.car.app.hardware.common.CarValue.STATUS_SUCCESS) {
                    carEnergyRangeKm = r.value?.toDouble()?.div(1000.0)
                }
            }
            energyListener = l
            carContext.getCarService(CarHardwareManager::class.java)
                .carInfo.addEnergyLevelListener(ContextCompat.getMainExecutor(carContext), l)
        }.onFailure { Log.w(TAG, "CarInfo enerji dinleyicisi kurulamadı", it) }
    }

    /**
     * Gunluk dosyasini acar.
     *
     * **Tamponlu yazim sart:** `EV_BATTERY_LEVEL` 100 Hz olay uretiyor, her
     * olayda dosyayi acip kapatmak surus boyunca ciddi yuk.
     *
     * **Neden dondurme var:** onceki surumde dosya ekleme kipinde aciliyor,
     * boyut diskten devraliniyor ve tavan asilinca [appendCsv] sessizce
     * vazgeciyordu. Sonuc: `calib.csv` 8 MiB'ye ulastigi anda KALICI olarak
     * donuyordu — 23 ve 29 Agustos'ta alinan iki disa aktarim birebir ayni
     * dosya cikti, aradaki butun surusler kayipti. Hicbir yerde hata yoktu.
     * Artik dolu dosya `calib-prev.csv`'ye tasiniyor ve yenisi aciliyor.
     */
    private fun openCsv(f: File, afterRotation: Boolean = false) {
        if (f.exists() && f.length() >= CSV_MAX_BYTES) rotate(f)
        val isNew = !f.exists()
        csvBytes = if (isNew) 0L else f.length()
        runCatching {
            csvWriter = BufferedWriter(FileWriter(f, true), 16 * 1024)
            if (isNew) csvWriter?.write(CSV_HEADER)
            headerKind = if (afterRotation) "rotate" else "session"
            // Her acilis kendi baslik satirini yazar: zaman damgasi
            // elapsedRealtime tabanli ve her acilista sifirlaniyor, dosya da
            // ekleme kipinde. Bu satir olmadan uc uce eklenmis oturumlar
            // birbirinden ayirt edilemiyor ve duvar saati hic kurtarilamiyor.
            sessionHeader().let { csvWriter?.write(it); csvBytes += it.length }
            csvWriter?.flush()
        }.onFailure { Log.w(TAG, "calib.csv açılamadı", it) }
    }

    /**
     * Dosya basi satiri.
     *
     * Dondurmeden sonra acilan dosyaya `# rotate` yaziliyor, `# session` degil:
     * ikisi ayni etiketi tasidiginda cozumleme dosyanin basini yeni bir acilis
     * sanip oturumu ikiye boluyordu (31 Agustos verisinde oldu — 19:58'deki
     * "session" aslinda 19:54'te baslayan oturumun dondurulmesiydi).
     */
    private fun sessionHeader(): String {
        val now = System.currentTimeMillis()
        val wall = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(now))
        return "# $headerKind;$now;$wall;${BuildConfig.VERSION_NAME};${SystemClock.elapsedRealtime()}\n"
    }

    /** Dolu dosyayi `calib-prev.csv` yapar; bir onceki yedek dusuruluyor. */
    private fun rotate(f: File): Boolean = runCatching {
        val prev = File(f.parentFile, CSV_PREV_NAME)
        if (prev.exists()) prev.delete()
        val moved = f.renameTo(prev)
        if (moved) csvRotations++
        moved
    }.getOrElse {
        Log.w(TAG, "calib.csv döndürülemedi", it)
        false
    }

    /** Yazim sirasinda tavana carpildi: dosyayi kapat, dondur, yenisini ac. */
    private fun rollOver() {
        val f = csv ?: return
        runCatching { csvWriter?.flush(); csvWriter?.close() }
        csvWriter = null
        if (!rotate(f)) {
            // Dondurulemiyorsa yazmayi birakiyoruz ama artik SESSIZ degil:
            // ekranda "günlük dolu" satiri cikiyor.
            csvFull = true
            return
        }
        openCsv(f, afterRotation = true)
    }

    /**
     * Teshis satiri — uygulamanin KENDI durumunu gunluge yazar.
     *
     * **Neden gerekli:** 31 Agustos'ta 45 km'lik bir surus `trips.json`'a hic
     * girmedi. CSV'de surusun her saniyesi vardi (46 dk hareket, 131 km/h azami)
     * ama gunluk yalnizca araç property'lerini yazdigi icin *nicin* atildigi
     * anlasilamadi: GPS mesafesi mi esigin altinda kaldi, yolculuk hic AKTIF
     * mi olmadi, yoksa kayit mi yazilamadi — ucu de mumkundu. Arada logcat
     * okunamiyor; teshis dosyanin kendisinde olmali.
     *
     * Satirlar `#` ile basliyor, yani mevcut cozumleyiciler bunlari yorum
     * sayip atliyor. Hacim ihmal edilebilir (durum degisimi seyrek, GPS 60 sn'de
     * bir); her satir aninda diske yaziliyor — kontak kesilince kaybolmasinlar.
     */
    @Synchronized
    fun note(kind: String, vararg fields: Any?) {
        val w = csvWriter ?: return
        if (csvFull) return
        val line = buildString {
            append("# ").append(kind).append(';').append(SystemClock.elapsedRealtime())
            for (f in fields) append(';').append(f?.toString() ?: "")
            append('\n')
        }
        runCatching {
            w.write(line)
            csvBytes += line.length
            w.flush()
        }
    }

    private fun appendCsv(ev: CarPropertyStream.Event) {
        if (csvBytes >= CSV_MAX_BYTES && !csvFull) rollOver()
        val w = csvWriter ?: return
        if (csvFull) return
        val line = "${ev.tNanos / 1_000_000};0x${Integer.toHexString(ev.propId)};" +
            "${format(ev.value)};${ev.status}\n"
        runCatching {
            w.write(line)
            csvBytes += line.length
            // Kontak kesilmesi/cokme durumunda son saniyelerden fazlasi kaybolmasin.
            if (++sinceFlush >= FLUSH_EVERY) {
                w.flush()
                sinceFlush = 0
            }
        }
    }

    private fun numeric(v: Any?): Double? = when (v) {
        is Number -> v.toDouble()
        is Boolean -> if (v) 1.0 else 0.0
        else -> null
    }

    private fun format(v: Any?): String = when (v) {
        null -> ""
        is Float -> String.format(Locale.ROOT, "%.4f", v)
        is Double -> String.format(Locale.ROOT, "%.4f", v)
        is FloatArray -> v.joinToString(",")
        is IntArray -> v.joinToString(",")
        else -> v.toString()
    }

    // --- Rapor ---

    @Synchronized
    fun report(): Report {
        val rows = stats.values.map { it.toRow(carContext) }

        val deltaWh = run {
            val a = batteryLevelFirstWh
            val b = batteryLevelLastWh
            if (a != null && b != null) a - b else null
        }
        // Ham deger mW ise: mW*h / 1e6 = kWh. Ham deger W ise: W*h / 1e3 = kWh.
        //
        // ISARET — gercek araçta olculdu (2026-07-31, EX30): deger POZITIFKEN
        // arac enerji TUKETIYOR. Dururken klima calisirken +2,6 kW, tam gazda
        // +42,2 kW, rejen sirasinda -20,1 kW okundu. Yani property adi
        // ("charge rate") yaniltici; isaret sarj degil desarj yonunde.
        // Onceki kod tersini varsayip integrali negatifliyordu.
        val asMilliWatt = powerIntegralRawHours / 1e6
        val asWatt = powerIntegralRawHours / 1e3

        val verdict = when {
            deltaWh == null || abs(deltaWh) < 1e-6 -> null
            else -> {
                val target = deltaWh / 1000.0
                val eMw = abs(asMilliWatt - target)
                val eW = abs(asWatt - target)
                val tol = abs(target) * 0.25 + 0.05
                when {
                    eMw <= tol && eMw < eW -> R.string.calib_verdict_mw
                    eW <= tol && eW < eMw -> R.string.calib_verdict_w
                    else -> R.string.calib_verdict_none
                }
            }
        }

        return Report(
            rows = rows,
            nominalCapacityKwh = nominalCapacityWh?.div(1000.0),
            socSampleCount = socSeen,
            socHasFraction = socHasFraction,
            socMinStep = socMinStep.takeIf { it != Double.MAX_VALUE },
            socLast = socLast,
            carEnergyRangeKm = carEnergyRangeKm,
            batteryDeltaKwh = deltaWh?.div(1000.0),
            powerIntegralAsMilliWattKwh = asMilliWatt.takeIf { lastPowerRaw != null },
            powerIntegralAsWattKwh = asWatt.takeIf { lastPowerRaw != null },
            unitVerdict = verdict,
            streamError = stream.lastError,
            csvPath = csv?.absolutePath,
            csvBytes = csvBytes,
            csvMaxBytes = CSV_MAX_BYTES,
            csvFull = csvFull,
            csvRotations = csvRotations,
        )
    }

    data class Report(
        val rows: List<Row>,
        val nominalCapacityKwh: Double?,
        val socSampleCount: Long,
        val socHasFraction: Boolean,
        val socMinStep: Double?,
        val socLast: Double?,
        val carEnergyRangeKm: Double?,
        val batteryDeltaKwh: Double?,
        val powerIntegralAsMilliWattKwh: Double?,
        val powerIntegralAsWattKwh: Double?,
        /** Kaynak kimligi; metne cevirmeyi ekran yapiyor. */
        val unitVerdict: Int?,
        val streamError: String?,
        val csvPath: String?,
        val csvBytes: Long,
        val csvMaxBytes: Long,
        /** Dosya doldu ve dondurulemedi: yeni satir yazilmiyor. */
        val csvFull: Boolean,
        val csvRotations: Int,
    )

    data class Row(
        val name: String,
        val label: String,
        val subscription: String,
        val count: Long,
        val invalidCount: Long,
        /** Olculen olay sikligi (Hz) — §3/3'un cevabi. */
        val measuredHz: Double?,
        /** Ornekleme araligi sapmasi (ms). */
        val jitterMs: Double?,
        val lastValueText: String,
        /** En kucuk deger sicramasi, gosterim biriminde — §3/1 ve §3/2'nin cevabi. */
        val minStepText: String?,
        val meanStepText: String?,
        /** Degisimler arasi en kisa ve ortalama sure (sn). */
        val minChangeGapSec: Double?,
        val meanChangeGapSec: Double?,
        val changeCount: Long,
        val rangeText: String?,
    )

    private class PropStats(val spec: Spec) {
        var subscription: CarPropertyStream.Subscription =
            CarPropertyStream.Subscription.Unavailable(R.string.car_sub_not_started)
        var count = 0L
        var invalidCount = 0L
        var firstNanos = 0L
        var lastNanos = 0L
        var intervalCount = 0L
        var intervalSum = 0.0
        var intervalSumSq = 0.0
        var lastValue: Any? = null
        var lastDistinct: Double? = null
        var lastChangeNanos = 0L
        var lastCsvNanos = 0L
        var minStep = Double.MAX_VALUE
        var stepSum = 0.0
        var changeCount = 0L
        var minChangeGapSec = Double.MAX_VALUE
        var changeGapSum = 0.0
        var changeGapCount = 0L
        var minValue = Double.MAX_VALUE
        var maxValue = -Double.MAX_VALUE

        fun toRow(context: android.content.Context): Row {
            val meanMs = if (intervalCount > 0) intervalSum / intervalCount else null
            val sd = if (intervalCount > 1) {
                val m = intervalSum / intervalCount
                sqrt((intervalSumSq / intervalCount - m * m).coerceAtLeast(0.0))
            } else null

            return Row(
                name = spec.name,
                label = spec.label,
                subscription = subscription.label(context),
                count = count,
                invalidCount = invalidCount,
                measuredHz = meanMs?.let { if (it > 0) 1000.0 / it else null },
                jitterMs = sd,
                lastValueText = display(lastValue),
                minStepText = minStep.takeIf { it != Double.MAX_VALUE }?.let { scaled(it) },
                meanStepText = if (changeCount > 0) scaled(stepSum / changeCount) else null,
                minChangeGapSec = minChangeGapSec.takeIf { it != Double.MAX_VALUE },
                meanChangeGapSec = if (changeGapCount > 0) changeGapSum / changeGapCount else null,
                changeCount = changeCount,
                rangeText = if (minValue != Double.MAX_VALUE && maxValue != -Double.MAX_VALUE)
                    "${scaled(minValue)} … ${scaled(maxValue)}" else null,
            )
        }

        /** Ham degeri gosterim birimine cevirir; hesaplar hep ham uzerinden yapildi. */
        private fun scaled(raw: Double): String {
            // Once yuvarla, sonra bicimle: aksi halde cok kucuk negatif degerler
            // ekrana "-0,0 km/h" olarak dusuyor (gercek araçta goruldu).
            val factor = Math.pow(10.0, spec.decimals.toDouble())
            var v = Math.round(raw / spec.divisor * factor) / factor
            if (v == 0.0) v = 0.0   // -0.0 -> 0.0
            val s = String.format(Locale.forLanguageTag("tr"), "%.${spec.decimals}f", v)
            return if (spec.unit.isEmpty()) s else "$s ${spec.unit}"
        }

        private fun display(v: Any?): String = when (v) {
            null -> "—"
            is Boolean -> if (v) "true" else "false"
            is Number -> scaled(v.toDouble())
            else -> v.toString()
        }
    }
}
