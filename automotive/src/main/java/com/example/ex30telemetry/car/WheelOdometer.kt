package com.example.ex30telemetry.car

import android.util.Log
import kotlin.math.abs

/**
 * `WHEEL_TICK` sayaclarindan kat edilen mesafeyi biriktirir.
 *
 * ## Neden var
 *
 * `PERF_ODOMETER` ucuncu partiye kapali (2026-09-10 sondasi: `SecurityException`,
 * `CAR_MILEAGE_3P` izni bu Android 15 imajinda TANIMSIZ). Buna karsilik
 * `WHEEL_TICK` **yalnizca `CAR_SPEED` istiyor** — yani elimizde zaten var — ve
 * arac onu CONTINUOUS 1..20 Hz yayinliyor.
 *
 * Amac mesafeyi GPS yerine bundan hesaplamak DEGIL. Amac **GPS mesafesinin
 * gercek hatasini olcmek**: ikisi paralel birikiyor, farkin oranı uygulamanin
 * butun tuketim hesabinin bolenini kalibre ediyor.
 *
 * ## Gercek EX30'da olculen davranis (2026-09-10)
 *
 * ```
 * configArray: 15, 22000, 22000, 22000, 22000
 * ```
 * `15` = `0b1111`, dort tekerlek de destekli; hepsi **22 mm/tick**.
 * Dogrulama: on sol 77.203 → 194.151 = 116.948 tick × 22 mm = **2.573 m**,
 * ayni araliktaki sürüşle tutarli.
 *
 * Dort tekerlek birbirinden ~%0,6 sapiyor (viraj + lastik asinmasi), o yuzden
 * **ortalama** aliniyor.
 *
 * ## Iki tuzak
 *
 * 1. **Reset sayaci calismyor.** AOSP `Long[0]`'in sifirlanmada artacagini
 *    soyluyor; gercek EX30'da sayac sifirlandigi halde `Long[0]` **0'da kaldi**.
 *    Sifirlanma bu yuzden degerin DUSMESINDEN anlasiliyor.
 * 2. **Geri viteste tick AZALIYOR.** Yani her dusus sifirlanma degil. Ayrimi
 *    buyuklukten yapiyoruz: fiziksel olarak imkansiz bir sicrama ([RESET_TICKS])
 *    sifirlanmadir, kucuk dususler manevradir.
 *
 * Geri manevralarda **mutlak deger** ekleniyor: GPS tarafi da `distanceTo` ile
 * her adimi pozitif sayiyor, karsilastirmanin adil olmasi icin ayni davranis.
 */
class WheelOdometer(
    /** Tekerlek basina mikrometre/tick — configArray[1..4]. 0 = o tekerlek yok. */
    private val micronsPerTick: IntArray,
) {

    private var last: LongArray? = null
    private var warnedType = false

    /** Baslangictan beri kat edilen mesafe (metre). */
    @Volatile
    var totalM: Double = 0.0
        private set

    /** Kac kez sifirlanma gorduk — tanilama icin. */
    @Volatile
    var resets: Int = 0
        private set

    val usable: Boolean get() = micronsPerTick.any { it > 0 }

    /**
     * Bir `WHEEL_TICK` olayini isler.
     *
     * Tip donusumu BILEREK burada: arac property'yi `Long[]` (KUTULANMIS,
     * `Array<Long>`) olarak veriyor — sonda ciktisindaki `tip=Long[]` buyuk
     * L ile yaziyor. Kotlin'de `as? LongArray` bu diziye UYMAZ ve null doner;
     * yani sayac hic ilerlemez, hicbir hata da gorunmez. Tam olarak avladigimiz
     * sessiz hata sinifi, o yuzden tek noktada ve her iki bicimi kabul ederek
     * cozuluyor.
     *
     * @param value `[reset sayaci, on sol, on sag, arka sag, arka sol]`
     */
    fun onTicks(value: Any?) {
        val raw = toLongs(value)
        if (raw == null) {
            if (!warnedType) {
                warnedType = true
                Log.w(TAG, "WHEEL_TICK beklenmedik tip: ${value?.javaClass?.name}")
            }
            return
        }
        if (raw.size < 5) return
        val cur = longArrayOf(raw[1], raw[2], raw[3], raw[4])
        val prev = last
        last = cur
        if (prev == null) return

        // Sifirlanma YALNIZCA GERIYE dogru buyuk sicramadir.
        //
        // Ilk surumde "buyuk mutlak degisim = sifirlanma" demistim; birim testi
        // bunun yanlis oldugunu gosterdi. Ileri yonde buyuk atlama MESRU:
        // uygulama arka plandayken abonelik duraklayabiliyor ve iki ornek arasi
        // dakikalar gecebiliyor (§ JourneyCarAppService'teki "uzun duz cizgi"
        // notu tam olarak bu). Oyle bir atlamayi sifirlanma sayip atmak gercek
        // mesafeyi sessizce yutardi.
        //
        // Geri viteste sayac azaliyor, ama iki ornek arasinda 1,1 km geri
        // manevra yok; esik oradan geliyor. Kontrol yalnizca DESTEKLENEN
        // tekerlekler uzerinde: desteklenmeyenin degeri anlamsiz olabiliyor.
        val reset = cur.indices.any { i ->
            micronsPerTick.getOrElse(i) { 0 } > 0 && (cur[i] - prev[i]) < -RESET_DROP_TICKS
        }
        if (reset) {
            resets++
            Log.i(TAG, "tekerlek sayacı sıfırlandı (${resets}. kez)")
            return
        }

        var sum = 0.0
        var n = 0
        for (i in cur.indices) {
            val um = micronsPerTick.getOrElse(i) { 0 }
            if (um <= 0) continue
            sum += abs(cur[i] - prev[i]).toDouble() * um / 1_000_000.0
            n++
        }
        if (n > 0) totalM += sum / n
    }

    /** `LongArray`, `IntArray` ya da kutulanmis sayi dizisini kabul eder. */
    private fun toLongs(v: Any?): LongArray? = when (v) {
        is LongArray -> v
        is IntArray -> LongArray(v.size) { v[it].toLong() }
        is Array<*> -> {
            val out = LongArray(v.size)
            var ok = true
            for (i in v.indices) {
                val n = v[i] as? Number
                if (n == null) { ok = false; break }
                out[i] = n.toLong()
            }
            if (ok) out else null
        }
        else -> null
    }

    companion object {
        private const val TAG = "JourneyWheel"

        /**
         * Bu kadar GERIYE giden bir adim sifirlanmadir (1,1 km geri manevra yok).
         * Ileri yondeki buyuk atlamalar mesru sayiliyor — bkz. [onTicks].
         */
        private const val RESET_DROP_TICKS = 50_000L

        /**
         * `configArray`'den olusturur: `[maske, on sol, on sag, arka sag, arka sol]`.
         * Dizi beklenen bicimde degilse null doner — o zaman tekerlek mesafesi
         * hesaplanmaz ve ilgili satir ekranda gorunmez (sifir gostermeyiz).
         */
        fun fromConfigArray(configArray: List<Int>): WheelOdometer? {
            if (configArray.size < 5) return null
            val microns = IntArray(4) { configArray[it + 1] }
            val o = WheelOdometer(microns)
            return o.takeIf { it.usable }
        }
    }
}
