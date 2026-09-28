package com.example.ex30telemetry.render

/**
 * LiveScreen'in cizdirdigi anlik goruntu. Her alan null olabilir ve null olmasi
 * normaldir: bir property araçtan gelmiyorsa ya da metrik henuz guvenilir degilse
 * o satir ekranda "hesaplaniyor…" ya da "—" olarak gorunur, sifir gosterilmez
 * (EX30-YOL-ANALIZI-PROMPT.md §10).
 *
 * Veri katmani (VehicleDataHub + TripRecorder) bu sinifi doldurur; renderer
 * yalnizca okur. Boylece cizim, verinin nereden geldigini hic bilmez.
 */
data class LiveState(
    /**
     * Kayan pencerenin genisligi (km) — kullanici secimi (bkz. WindowSetting).
     *
     * Renderer bunu YALNIZCA etiketlemek icin kullaniyor ("son 20 km"); seriler
     * ve pencere degerleri zaten kirpilmis geliyor. Ekranda yazmasi sart:
     * pencere degisebilir hale gelince, hangi mesafeye baktigini soylemeyen bir
     * sayi yanlis okunuyor.
     */
    val windowKm: Double = 20.0,
    /** Kayan pencere tuketimi (kWh/100 km). */
    val consumptionWindow: Double? = null,
    /** Yolculuk ortalamasi tuketim (kWh/100 km). */
    val consumptionTrip: Double? = null,
    /** Tuketim henuz esik altindaysa gosterilecek aciklama. */
    val consumptionPending: String? = null,

    val socPercent: Double? = null,
    val rangeKm: Double? = null,
    /**
     * Bataryadaki kalan enerji (kWh). Alt gosterge: araç ekrani yalnizca yuzde
     * ve km gosteriyor, kWh hicbir yerde yok.
     */
    val batteryKwh: Double? = null,
    /**
     * Anlik kullanilabilir kapasite (kWh) — `EV_CURRENT_BATTERY_CAPACITY`.
     * Alt gosterge: aracin hicbir ekraninda yok, zamanla dususu izlenebilen
     * tek yer bu uygulama.
     */
    val usableCapacityKwh: Double? = null,
    val outsideTempC: Double? = null,
    val distanceKm: Double? = null,
    val durationSec: Long? = null,
    val speedKmh: Double? = null,
    val altitudeM: Double? = null,

    /** Irtifa sparkline'i: (yolculuk mesafesi m, irtifa m). */
    val altitudeSeries: List<Sample> = emptyList(),
    /** Hiz sparkline'i: (yolculuk mesafesi m, km/h). */
    val speedSeries: List<Sample> = emptyList(),

    // --- A5: rakim–enerji (grafiklerin alt satirinda gosterilir) ---
    val altGainM: Double? = null,
    val altLossM: Double? = null,
    /** Tirmanisin potansiyel enerjisi m·g·Δh (kWh). */
    val climbKwh: Double? = null,
    /** Yolculuk boyunca rejenle geri kazanilan enerji (kWh). */
    val regenKwh: Double? = null,
    /** Yolculukta gorulen azami hiz (km/h) — hiz panelinin alt satiri. */
    val maxSpeedKmh: Double? = null,
    /**
     * Mesafenin ne kadari GPS yerine hiz integralinden geldi (0..1).
     * Irtifa o bolumde hic orneklenmedigi icin tirmanis/inis eksik sayiliyor —
     * oran belirginse ekranda soylenmesi gerekiyor, sessizce eksik vermek
     * uydurma hassasiyet olur.
     */
    val bridgedFraction: Double? = null,

    // --- Ortalama hiz bari (hiz grafiginin altinda) ---
    /** Yolculugun tamami icin ortalama hiz (km/h). */
    val avgSpeedTripKmh: Double? = null,
    /** Son [windowKm] kilometrenin ortalama hizi — tuketimle ayni pencere. */
    val avgSpeedWindowKmh: Double? = null,

    /**
     * GPS bu yolculukta guvenilir mi (tekerlek mesafesiyle karsilastirildi).
     * false ise irtifa turevleri de supheli — yukseklik yalnizca GPS'ten geliyor.
     */
    val gpsHealthy: Boolean? = null,

    /** Yolculuk durum makinesinin adi — ust satirda rozet olarak gosterilir. */
    val tripState: String = "BEKLEME",

    /** A4 bir olcum yakalayinca 5 sn gosterilen serit. */
    val banner: Banner? = null,
) {
    data class Sample(val distanceM: Double, val value: Double)

    data class Banner(val text: String, val untilElapsedMs: Long)
}
