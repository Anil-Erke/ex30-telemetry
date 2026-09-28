package com.example.ex30telemetry.trip

import android.location.Location
import com.example.ex30telemetry.Constants
import kotlin.math.abs
import kotlin.math.max

/**
 * AKTIF yolculugun birikimi. Yolculuk kapaninca [toTrip] ile kalici kayda
 * donusur.
 *
 * Mesafe ve irtifa GPS'ten geliyor: odometre (`PERF_ODOMETER`) ucuncu partiye
 * kapali, araç GPS'i ve `CarSensors` EX30'da hic calismiyor (prompt.md §10.3).
 */
class TripAccumulator(
    val startEpoch: Long,
    /** Testlerde zamani sabitleyebilmek icin disaridan veriliyor. */
    private val now: () -> Long = System::currentTimeMillis,
) {

    data class Point(val distanceM: Double, val value: Double)

    val energy = EnergyAccountant()

    var distanceM = 0.0
        private set
    var altGainM = 0.0
        private set
    var altLossM = 0.0
        private set
    var maxSpeedKmh = 0.0
        private set

    var socStart: Double? = null
    var socEnd: Double? = null
    var rangeStartKm: Double? = null
    var rangeEndKm: Double? = null
    var tempStart: Double? = null

    private var tempSum = 0.0
    private var tempCount = 0L

    /** Bu yolculuk sirasinda yakalanan A4 olcumleri. */
    val records = java.util.concurrent.CopyOnWriteArrayList<PerfRecord>()

    /** Sparkline serileri; ekran genisliginden fazlasi tutulmuyor. */
    val altitudeSeries = ArrayList<Point>()
    val speedSeries = ArrayList<Point>()

    /** A1 kayan pencere icin: (mesafe, o ana kadarki net kWh). */
    private val energyTrail = ArrayList<Point>()

    /** (mesafe m, yolculuk basindan gecen sn) — kayan pencere ortalama hizi icin. */
    private val timeTrail = ArrayList<Point>()

    // --- GPS bosluklarini ham hiz integraliyle kopruleme ---
    private var lastRawSpeedNanos = 0L
    private var lastRawSpeedKmh = 0.0
    private var hasRawSpeed = false
    /** Son mesafe adimindan bu yana biriken ham hiz integrali (m). */
    private var pendingIntegralM = 0.0
    private var lastFixElapsedMs = 0L
    /** Ayri bayrak: "0" gecerli bir zaman damgasi, sentinel olarak kullanilamaz. */
    private var hasFix = false
    /** Son adim koprulemeyle atildi: sonraki GPS adimi yok sayilmali. */
    private var bridged = false

    private var lastLocation: Location? = null
    private val altWindow = ArrayDeque<Double>()
    private var lastCountedAltitude: Double? = null

    /**
     * VHAL hizi hic sifirdan buyuk gelmediyse hiz serisi GPS'ten besleniyor
     * (EX30 0-100'deki VHAL → GPS dusme deseni). Gercek araçta VHAL 10 Hz'de
     * calisiyor; bu yol emulator ve VHAL'in susmasi ihtimali icin.
     */
    private var vhalSpeedSeen = false

    /**
     * Hiz serisine yazilmayi bekleyen son deger.
     *
     * Hiz 10 Hz, konum 1 Hz geliyor; grafigin x ekseni ise MESAFE. Her hiz
     * ornegini dogrudan seriye yazmak, ayni mesafeye on nokta yigiyordu:
     * arac dururken dikey cizgi, kalkista da uzun duz bir rampa cikiyordu
     * (gercek araçta 2026-08-01'de goruldu, sentetik surusle dogrulandi).
     * Cozum: seriye yalnizca mesafe ilerledigi zaman nokta ekle.
     */
    private var pendingSpeedKmh: Double? = null

    // --- Besleme ---

    @Synchronized
    fun onLocation(location: Location) {
        val prev = lastLocation
        val stepM = if (prev != null) prev.distanceTo(location).toDouble() else 0.0
        // GPS sicramalarini ve durma anindaki titresimi ele.
        if (prev != null && stepM < MIN_STEP_M) return
        lastLocation = location
        advance(
            stepM = stepM,
            altitudeM = if (location.hasAltitude()) location.altitude else null,
            gpsSpeedKmh = if (location.hasSpeed()) location.speed * 3.6 else null,
        )
    }

    /**
     * Konum sinifindan bagimsiz birikim adimi. `android.location.Location` JVM
     * birim testlerinde calismadigi icin mantik buraya ayrildi; testler dogrudan
     * bu metodu cagiriyor.
     */
    @Synchronized
    fun advance(stepM: Double, altitudeM: Double?, gpsSpeedKmh: Double? = null) {
        // Bosluk ham hiz integraliyle zaten kapatildiysa bu adim o boslugun
        // tamamini iceriyor; eklemek mesafeyi ikiye katlardi. Konumu yalnizca
        // guncelle, mesafeyi atla.
        val step = if (bridged) 0.0 else stepM
        bridged = false
        pendingIntegralM = 0.0
        distanceM += step

        if (altitudeM != null) {
            // GPS irtifasi gurultulu; kucuk bir kayan ortalama (prompt.md §5.3).
            altWindow.addLast(altitudeM)
            if (altWindow.size > ALT_SMOOTHING) altWindow.removeFirst()
            val smoothed = altWindow.average()

            val last = lastCountedAltitude
            if (last == null) {
                lastCountedAltitude = smoothed
            } else {
                val d = smoothed - last
                // Olu bant: gurultunun hem kazanci hem kaybi sismesini onler.
                if (abs(d) >= ALT_DEADBAND_M) {
                    if (d > 0) altGainM += d else altLossM -= d
                    lastCountedAltitude = smoothed
                }
            }
            addPoint(altitudeSeries, Point(distanceM, smoothed))
        }

        if (gpsSpeedKmh != null) {
            maxSpeedKmh = max(maxSpeedKmh, gpsSpeedKmh)
            if (!vhalSpeedSeen) pendingSpeedKmh = gpsSpeedKmh
        }

        // Mesafe ilerlediyse hiz serisine bir nokta yaz; boylece iki grafik de
        // ayni x ekseninde ve ayni cozunurlukte kaliyor.
        if (step > 0) {
            pendingSpeedKmh?.let { addPoint(speedSeries, Point(distanceM, it)) }
        }
    }

    /** VHAL hiz ornegi (10 Hz). Seriye yazilmasi mesafe ilerlemesini bekler. */
    @Synchronized
    fun onSpeed(kmh: Double) {
        if (kmh > 0) vhalSpeedSeen = true
        maxSpeedKmh = max(maxSpeedKmh, kmh)
        if (vhalSpeedSeen) pendingSpeedKmh = kmh
    }

    /**
     * HAM hiz ornegi (10 Hz) — yalnizca mesafe yedegi icin biriktiriliyor.
     *
     * GPS sustugunda ([bridgeIfGpsStale]) bu integral mesafeye ekleniyor.
     * Gosterge hizi degil ham hiz kullaniliyor: gosterge %2,5 + 1,2 km/h yuksek
     * okuyor. Ham hizin GPS ile ortusmesi gercek surusle olculdu
     * (2026-08-31, 15 km): ∫ham 11,836 km · GPS 11,805 km.
     */
    @Synchronized
    fun onRawSpeed(kmh: Double, tNanos: Long) {
        val prevT = lastRawSpeedNanos
        val prevV = lastRawSpeedKmh
        val hadPrev = hasRawSpeed
        lastRawSpeedNanos = tNanos
        lastRawSpeedKmh = kmh
        hasRawSpeed = true
        // "0" gecerli bir zaman damgasi; ilk ornegi bayrakla ayirt ediyoruz.
        if (!hadPrev) return
        val dtSec = (tNanos - prevT) / 1e9
        // Uzun bosluk = ornek kacmis; yamuk kurali orada guvenilmez.
        if (dtSec <= 0 || dtSec > MAX_SPEED_GAP_SEC) return
        pendingIntegralM += (kmh + prevV) / 2.0 / 3.6 * dtSec
    }

    /** Her konum sabitlemesinde cagriliyor: yedek sayaci sifirlanir. */
    @Synchronized
    fun onGpsFix(elapsedMs: Long) {
        lastFixElapsedMs = elapsedMs
        hasFix = true
    }

    /**
     * GPS bayatladiysa mesafeyi ham hiz integralinden yurutur ve hiz serisine
     * nokta yazar.
     *
     * **Neden gerekli:** uygulama arka plandayken Android konum guncellemelerini
     * kisitliyor. Araç verisi 10 Hz akmaya devam ediyor ama GPS seyreliyor;
     * sonuc olarak grafiklerde uzun duz cizgiler olusuyor, mesafe kus ucusu
     * sayiliyor ve en kotusu yolculuk 500 m esigini gecemeyip ATILABILIYOR.
     *
     * Kopruleme yapildiginda bir sonraki GPS adimi ATLANIYOR ([bridged]):
     * o adim boslugun tamamini iceriyor, mesafeye ikinci kez eklenirse
     * yolculuk iki katina cikardi.
     *
     * @return kopruleme yapildiysa true
     */
    @Synchronized
    fun bridgeIfGpsStale(elapsedMs: Long): Boolean {
        if (!hasFix) return false
        if (elapsedMs - lastFixElapsedMs < GPS_STALE_MS) return false
        val step = pendingIntegralM
        pendingIntegralM = 0.0
        lastFixElapsedMs = elapsedMs
        if (step <= 0.0) return false
        distanceM += step
        bridged = true
        bridgedM += step
        // Grafik ekseni de ilerlesin: aksi halde bosluk yine duz cizgi kalirdi.
        pendingSpeedKmh?.let { addPoint(speedSeries, Point(distanceM, it)) }
        return true
    }

    /** Kopruleme ile eklenen toplam mesafe (m) — teshis satirinda raporlaniyor. */
    var bridgedM = 0.0
        private set

    /** Mesafenin koprulemeden gelen orani (0..1) — irtifanin eksikligi bu kadar. */
    val bridgedFraction: Double
        get() = if (distanceM > 0) (bridgedM / distanceM).coerceIn(0.0, 1.0) else 0.0

    @Synchronized
    fun onTick(temp: Double?) {
        if (temp != null) { tempSum += temp; tempCount++ }
        addPoint(energyTrail, Point(distanceM, energy.netKwh))
        // Mesafe-zaman izi: kayan pencere ORTALAMA HIZI icin gerekli. Hiz
        // orneklerinin aritmetik ortalamasi dogru cevabi vermez — duraklamalar
        // orada bir ornek olarak sayilir, oysa ortalama hiz mesafe/sure demek.
        addPoint(timeTrail, Point(distanceM, durationSec.toDouble()))
    }

    /**
     * Seriler cizim sirasinda okunurken arka planda degisiyor; kopyayi kilit
     * altinda al, yoksa `ConcurrentModificationException` cikiyor.
     */
    @Synchronized
    fun altitudeSnapshot(): List<Point> = altitudeSeries.toList()

    @Synchronized
    fun speedSnapshot(): List<Point> = speedSeries.toList()

    /**
     * Grafiklerin cizdigi son [windowKm] kilometre.
     *
     * **Neden pencere:** yolculugun tamamini cizmek uzun yolda grafigi
     * okunaksiz yapiyor — 200 km'yi 700 piksele sigdirinca her piksel ~300
     * metre oluyor ve tepeler duzlesiyor. Tuketim ve ortalama hiz zaten kayan
     * pencereyle hesaplaniyordu; grafikler de ayni pencereye oturunca ekrandaki
     * butun sayilar ayni mesafeden bahsediyor.
     */
    @Synchronized
    fun altitudeWindow(windowKm: Double): List<Point> = windowOf(altitudeSeries, windowKm)

    @Synchronized
    fun speedWindow(windowKm: Double): List<Point> = windowOf(speedSeries, windowKm)

    private fun windowOf(list: List<Point>, windowKm: Double): List<Point> {
        if (list.size < 2) return list.toList()
        val from = list.last().distanceM - windowKm * 1000.0
        val i = list.indexOfFirst { it.distanceM >= from }
        // Pencere henuz dolmadiysa elimizdeki her sey ciziliyor.
        if (i <= 0) return list.toList()
        // Bir onceki nokta da aliniyor: aksi halde cizgi pencerenin sol
        // kenarinda boslukla basliyor.
        return list.subList(i - 1, list.size).toList()
    }

    // --- Turetilen degerler ---

    val durationSec: Long
        get() = (now() - startEpoch) / 1000

    val distanceKm: Double get() = distanceM / 1000.0

    // --- Tekerlek mesafesi: GPS'in DENETCISI ---
    //
    // Odometre ucuncu partiye kapali oldugu icin GPS mesafesinin hatasini
    // simdiye kadar hic olcememistik. WHEEL_TICK bagimsiz bir kaynak: yolculuk
    // basinda ve sonunda sayac okunuyor, fark GPS mesafesiyle karsilastiriliyor.

    /** Yolculuk basindaki tekerlek sayaci (metre). Veri yoksa null. */
    var wheelStartM: Double? = null

    /** En son gorulen tekerlek sayaci (metre). */
    var wheelEndM: Double? = null

    /** Tekerlekten olculen yolculuk mesafesi (km). */
    val wheelDistanceKm: Double?
        get() {
            val a = wheelStartM ?: return null
            val b = wheelEndM ?: return null
            return ((b - a) / 1000.0).takeIf { it >= 0 }
        }

    /**
     * Tekerlek mesafesinin DUZELTILMIS hali (km).
     *
     * Ham deger [wheelDistanceKm]'de kaliyor ve kayda o giriyor; carpan
     * yalnizca burada uygulaniyor (gerekcesi [Constants.WHEEL_TICK_SCALE]).
     */
    val wheelDistanceCalibratedKm: Double?
        get() = wheelDistanceKm?.times(Constants.WHEEL_TICK_SCALE)

    /**
     * GPS guvenilir mi? Null = henuz karsilastirilamiyor.
     *
     * false donduguyse yalnizca mesafe degil **irtifa da supheli**: yukseklik
     * yalnizca GPS'ten geliyor, hiz integrali yukseklik bilmiyor.
     */
    val gpsHealthy: Boolean?
        get() = gpsVsWheel?.let { abs(it - 1.0) <= Constants.GPS_HEALTH_TOLERANCE }

    /**
     * GPS mesafesi ÷ tekerlek mesafesi.
     *
     * **Bolen DUZELTILMIS tekerlek mesafesi** ([Constants.WHEEL_TICK_SCALE]).
     * Ham tick sabiti %2,5 dusuk oldugu icin duzeltmeden once bu oran her
     * yolculukta yanlislikla "GPS %2 fazla okuyor" diyordu — 2026-09-19'da
     * aracin kendi sayaciyla karsilastirilinca GPS'in dogru oldugu goruldu.
     *
     * 1,00 = GPS dogru. 0,97 = GPS %3 EKSIK olcuyor (tunel, agac altı, kopruleme).
     * Anlamli olmasi icin yolculugun en az [MIN_WHEEL_COMPARE_KM] olmasi gerekiyor;
     * kisa mesafede tek bir GPS sicramasi orani ucuruyor.
     */
    val gpsVsWheel: Double?
        get() {
            val w = wheelDistanceCalibratedKm ?: return null
            if (w < MIN_WHEEL_COMPARE_KM) return null
            return distanceKm / w
        }

    val avgSpeedKmh: Double?
        get() {
            val h = durationSec / 3600.0
            return if (h > 0 && distanceM > 0) distanceKm / h else null
        }

    val tempAvg: Double? get() = if (tempCount > 0) tempSum / tempCount else null

    /**
     * A1 yolculuk ortalamasi: kWh/100 km.
     *
     * Hem mesafe hem enerji esigi araniyor. Enerji esigi olmadan, guc kaynagi
     * susmus (ya da emulatorde sabit 0) bir araçta ekrana "0,0 kWh/100 km"
     * yaziliyor — veri yokken sayi gostermek uydurma hassasiyettir.
     */
    val consumptionKwh100: Double?
        get() = if (distanceKm >= MIN_CONSUMPTION_KM && abs(energy.netKwh) >= MIN_ENERGY_KWH)
            energy.netKwh / distanceKm * 100.0 else null

    /** A1 kayan pencere: son [windowKm] kilometrenin tuketimi. */
    @Synchronized
    fun windowConsumptionKwh100(windowKm: Double): Double? {
        if (energyTrail.size < 2) return null
        val endD = energyTrail.last().distanceM
        val endE = energyTrail.last().value
        val startD = endD - windowKm * 1000.0
        // Pencere henuz dolmadiysa elimizdeki en eski noktadan basla.
        val from = energyTrail.firstOrNull { it.distanceM >= startD } ?: energyTrail.first()
        val km = (endD - from.distanceM) / 1000.0
        val kwh = endE - from.value
        if (km < MIN_CONSUMPTION_KM || abs(kwh) < MIN_ENERGY_KWH) return null
        return kwh / km * 100.0
    }

    /**
     * Son [windowKm] kilometrenin ORTALAMA HIZI (km/h) — hiz barindaki ikinci top.
     *
     * Tuketim penceresiyle ayni mantik: mesafe farki / sure farki. Duraklamalar
     * ortalamayi asagi ceker; "hareket halindeki ortalama" degil, gercek
     * ortalama hiz gosteriliyor — yolculuk ortalamasiyla ayni tanim olmasaydi
     * iki top karsilastirilamazdi.
     */
    @Synchronized
    fun windowAvgSpeedKmh(windowKm: Double): Double? {
        if (timeTrail.size < 2) return null
        val end = timeTrail.last()
        val startD = end.distanceM - windowKm * 1000.0
        val from = timeTrail.firstOrNull { it.distanceM >= startD } ?: timeTrail.first()
        val km = (end.distanceM - from.distanceM) / 1000.0
        val hours = (end.value - from.value) / 3600.0
        if (km < MIN_CONSUMPTION_KM || hours <= 0) return null
        return km / hours
    }

    /** A2: gosterge menzili kac km dustu ÷ gercekte kac km gidildi. */
    val rangeBiasFactor: Double?
        get() = RangeAuditor.biasFactor(rangeStartKm, rangeEndKm, distanceKm)

    /** A5: tirmanisin potansiyel enerjisi m·g·Δh → kWh. */
    val potentialKwh: Double
        get() = Constants.VEHICLE_MASS_KG * Constants.GRAVITY * altGainM / 3_600_000.0

    // --- Kayda donusum ---

    @Synchronized
    fun toTrip(endEpoch: Long): Trip = Trip(
        startEpoch = startEpoch,
        endEpoch = endEpoch,
        durationSec = (endEpoch - startEpoch) / 1000,
        distanceKm = distanceKm,
        // Esigin altinda kalan enerji yazilmiyor: "0,0 kWh" ile "guc verisi hic
        // gelmedi" ayirt edilemez hale gelir.
        energyKwh = energy.netKwh.takeIf { abs(it) >= MIN_ENERGY_KWH },
        regenKwh = energy.regenKwh.takeIf { it >= MIN_ENERGY_KWH },
        socStart = socStart,
        socEnd = socEnd,
        rangeStart = rangeStartKm,
        rangeEnd = rangeEndKm,
        avgSpeedKmh = avgSpeedKmh,
        maxSpeedKmh = maxSpeedKmh.takeIf { it > 0 },
        tempStart = tempStart,
        tempAvg = tempAvg,
        altGainM = altGainM,
        altLossM = altLossM,
        potentialKwh = potentialKwh.takeIf { altGainM > 0 },
        consumptionKwh100 = consumptionKwh100,
        rangeBiasFactor = rangeBiasFactor,
        wheelDistanceKm = wheelDistanceKm,
        records = records.toList(),
    )

    /** Yolculuk kaydedilmeye deger mi (§6: park manevrasi gurultusu). */
    val worthKeeping: Boolean
        get() = distanceM >= Constants.MIN_TRIP_DISTANCE_M &&
            durationSec >= Constants.MIN_TRIP_DURATION_SEC

    private fun addPoint(list: ArrayList<Point>, p: Point) {
        list.add(p)

        // En genis pencerenin disinda kalan gecmisi AT.
        //
        // Neden: seri butun yolculugu tasidiginda nokta butcesi (MAX_POINTS)
        // cogunlukla artik cizilmeyen kilometrelere harcaniyor ve seyreltme
        // yuzunden GORUNEN kismin cozunurlugu dusuyor. 200 km'lik bir surusten
        // sonra son 20 km yalnizca ~120 noktayla ciziliyordu; pencere disi
        // atilinca ayni 20 km ~480 nokta aliyor.
        //
        // Toplamlar (tirmanis, inis, mesafe, enerji) bu listelerden HESAPLANMIYOR
        // — ayri ayri birikiyorlar — dolayisiyla budama hicbir sayiyi bozmuyor.
        val cutoff = p.distanceM - SERIES_KEEP_KM * 1000.0
        if (list.first().distanceM < cutoff) {
            var drop = 0
            while (drop < list.size - 1 && list[drop].distanceM < cutoff) drop++
            // Sol kenarin hemen disindaki noktayi birak: cizgi kenara kadar gitsin.
            if (drop > 0) drop--
            if (drop > 0) list.subList(0, drop).clear()
        }

        // Kapasite dolunca bir atlayarak seyrelt: seri tutulan araligi
        // kapsamaya devam eder, cozunurlugu yariya iner.
        if (list.size > MAX_POINTS) {
            var w = 0
            for (i in list.indices step 2) { list[w++] = list[i] }
            while (list.size > w) list.removeAt(list.size - 1)
        }
    }

    companion object {
        /**
         * Grafik serilerinde tutulan azami gecmis (km).
         *
         * WindowSetting.CHOICES icindeki en buyuk secenek kadar olmali: daha
         * kisa tutulursa kullanici 50 km'yi secince gecmis olmadigi icin grafik
         * kirpik baslar, daha uzun tutmanin ise gorunur bir karsiligi yok.
         */
        const val SERIES_KEEP_KM = 50.0

        private const val MIN_STEP_M = 3.0
        private const val ALT_SMOOTHING = 5
        private const val ALT_DEADBAND_M = 1.5
        private const val MAX_POINTS = 1200

        /**
         * Bu kadar suredir konum gelmiyorsa mesafe ham hiz integralinden yurutulur.
         *
         * 10 sn bilerek genis: konum dinleyicisi 3 m'lik bir filtreyle aciliyor,
         * yani araç dururken zaten fix gelmiyor. 10 sn boyunca fix yoksa ya
         * gercekten duruyoruz (integral ~0, zararsiz) ya da GPS kesilmis.
         */
        private const val GPS_STALE_MS = 10_000L

        /** Bu araliktan uzun hiz bosluklarinda yamuk kurali guvenilmez. */
        private const val MAX_SPEED_GAP_SEC = 2.0

        /** Bu mesafenin altinda tuketim gosterme; GPS mesafesi henuz guvenilir degil. */
        /**
         * Tekerlek/GPS karsilastirmasi icin en kisa mesafe (km). Altinda tek bir GPS
         * sicramasi orani anlamsiz kiliyor.
         */
        private const val MIN_WHEEL_COMPARE_KM = 1.0

        private const val MIN_CONSUMPTION_KM = 0.3

        /**
         * Bu kadar enerji harcanmadan tuketim gosterme (kWh). ~15 kW'lik normal
         * bir cekiste 12 saniyede asiliyor; guc verisi hic gelmiyorsa hic asilmiyor.
         */
        private const val MIN_ENERGY_KWH = 0.05
    }
}
