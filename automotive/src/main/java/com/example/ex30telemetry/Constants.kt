package com.example.ex30telemetry

/**
 * Tek yerde tutulan sabitler. Fiziksel sabitler ve yolculuk esikleri koda
 * dagilmasin diye burada; A5'in potansiyel enerji hesabi arac kutlesine bagli
 * ve bu deger ileride degisirse tek satir degismeli.
 */
object Constants {

    // --- Arac ---

    /** Volvo EX30 kutlesi (kg). A5 potansiyel enerji hesabinda kullaniliyor. */
    const val VEHICLE_MASS_KG = 1830.0

    /** Yercekimi ivmesi (m/s^2). */
    const val GRAVITY = 9.81

    /**
     * Nominal batarya kapasitesi (kWh). Gercek deger araca INFO_EV_BATTERY_CAPACITY
     * ile soruluyor; property gelmezse bu yedek kullanilir (gercek EX30'da 66,0 olctuk).
     */
    const val FALLBACK_BATTERY_KWH = 66.0

    // --- Yolculuk durum makinesi (EX30-YOL-ANALIZI-PROMPT.md §6) ---

    /** Bu hizin uzerine cikinca yolculuk baslar (km/h). */
    const val TRIP_START_SPEED_KMH = 3.0

    /** KAPANIYOR durumunda tekrar hareket beklenen sure (sn). Kirmizi isik icin. */
    const val CLOSING_GRACE_SEC = 60L

    /** Bu mesafenin altindaki yolculuk kaydedilmez (m) — park manevrasi gurultusu. */
    const val MIN_TRIP_DISTANCE_M = 500.0

    /** Bu surenin altindaki yolculuk kaydedilmez (sn). */
    const val MIN_TRIP_DURATION_SEC = 120L

    /** AKTIF durumda canli durumun diske yazilma araligi (ms). */
    const val LIVE_SNAPSHOT_INTERVAL_MS = 1000L

    // --- Kalicilik ---

    /**
     * `WHEEL_TICK` mesafesini gercege getiren carpan.
     *
     * ## Nereden geldi (2026-09-19, gercek olcum)
     *
     * Aracin `configArray`'i 22.000 µm/tick veriyor, ama AOSP'nin kendi tanimi
     * bunun *"statik ve EN IYI YAKLASIM; birden fazla jant/lastik secenegi varsa
     * tipik beklenen olcuye gore"* ayarlandigini soyluyor. Gercek EX30'da
     * **%2,5 dusuk** cikti.
     *
     * Uc kaynakli olcum, 356 km'lik tek bir yolculukta:
     *
     * | Kaynak | Mesafe | Sapma |
     * |---|---|---|
     * | Volvo uygulamasi (arac sayaci) | 357,0 km | referans |
     * | GPS (bu uygulama) | 356,103 km | **-0,25%** |
     * | Tekerlek tikleri (ham) | 348,170 km | **-2,47%** |
     *
     * Sayilan 15.825.897 tick -> gercek sabit **22,558 mm/tick**.
     *
     * Bagimsiz dogrulama: %98,5'i koprulenmis 90 km'lik bir yolculukta (yani
     * mesafe GPS'ten DEGIL, aracin kendi ham hiz integralinden geliyordu)
     * duzeltilmis tekerlek mesafesi GPS ile **%0,03** farkla ortusuyor.
     * 1001 km'lik on yolculukta toplam fark **%0,41**.
     *
     * ## NEDEN KAYDA GOMULMUYOR
     *
     * `Trip.wheelDistanceKm` HAM deger olarak saklaniyor; carpan yalnizca
     * gosterim ve karsilastirma aninda uygulaniyor. Lastik degisirse ya da daha
     * iyi bir referans olcumu alinirsa tek sabiti degistirip **butun gecmis
     * yeniden turetilebilsin** diye. Carpani kayda gomersek o esneklik gider.
     */
    const val WHEEL_TICK_SCALE = 1.02536

    /**
     * GPS ile (duzeltilmis) tekerlek mesafesi arasinda bu kadar fark varsa
     * o yolculukta GPS guvenilmez sayiliyor.
     *
     * Saglikli yolculuklarda fark %1'in altinda kaliyor (on yolculukta en kotu
     * %1,9). Buna karsilik konumu bozulan 3,7 km'lik bir yolculukta GPS
     * tekerlekten **%13,6 dusuk** okudu ve rakim kazanci TAM SIFIR geldi —
     * yani GPS coktugunde yalnizca mesafe degil, irtifa da cope gidiyor.
     * Esik ikisinin ortasina konuldu.
     */
    const val GPS_HEALTH_TOLERANCE = 0.05

    /** TripStore'un tuttugu azami yolculuk sayisi; en eski dusurulur. */
    const val MAX_TRIPS = 300

    /**
     * Trip kaydinin sema surumu. Sema degisirse artir, eski kayitlari silme.
     *
     * 1 → 2: `PerfRecord.seconds` yerine `value` + `unit`. 100-0 fren olcumu
     * saniye degil metre oldugu icin tek bir "seconds" alani yetmiyordu.
     * Sema 1 kayitlari okunmaya devam ediyor (bkz. PerfRecord.fromJson).
     */
    const val TRIP_SCHEMA_VERSION = 3
}
