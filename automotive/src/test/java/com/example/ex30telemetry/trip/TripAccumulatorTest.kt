package com.example.ex30telemetry.trip

import com.example.ex30telemetry.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EX30-YOL-ANALIZI-PROMPT.md §8'deki dogrulama testleri.
 *
 * > 24 km + 6 kWh beslendiginde A1 tam olarak 25,0 kWh/100 km yazmali.
 * > 320 m tirmanista 1830 kg icin potansiyel enerji 1,60 kWh cikmali.
 * > Sapma varsa hesap yanlis, ekrani duzeltmeye calisma.
 */
class TripAccumulatorTest {

    private var clock = 0L
    private fun acc() = TripAccumulator(startEpoch = 0L, now = { clock })

    /** §8: 24 km + 6 kWh → tam 25,0 kWh/100 km. */
    @Test
    fun `24 km ve 6 kWh tam 25 kWh100km verir`() {
        val a = acc()
        clock = 20 * 60 * 1000L   // 20 dakika

        // 24 km, 100 m'lik adimlarla
        repeat(240) { a.advance(stepM = 100.0, altitudeM = null) }

        // 6,0 kWh: 21,6 kW × 1000 sn, 10 Hz ornekleme
        feedPower(a, kw = 21.6, seconds = 1000.0)

        assertEquals(24.0, a.distanceKm, 1e-9)
        assertEquals(6.0, a.energy.netKwh, 1e-6)
        assertEquals(25.0, a.consumptionKwh100!!, 1e-6)
    }

    /** §8: 320 m tirmanis, 1830 kg → 1,60 kWh potansiyel enerji. */
    @Test
    fun `320 m tirmanis 1_60 kWh potansiyel enerji verir`() {
        val a = acc()
        // Kayan ortalama penceresi (5 ornek) yuzunden her irtifa plato halinde
        // beslenir; boylece duzlestirilmis deger platonun tam degerine oturur.
        feedAltitudeRamp(a, fromM = 0.0, toM = 320.0, stepM = 16.0)

        assertEquals(320.0, a.altGainM, 1e-6)
        assertEquals(0.0, a.altLossM, 1e-6)
        // m·g·Δh = 1830 × 9,81 × 320 / 3,6e6
        assertEquals(1.5958, a.potentialKwh, 1e-3)
    }

    /** Inis kaybi ayri sayilir, kazanci sismez. */
    @Test
    fun `inis kayip olarak sayilir`() {
        val a = acc()
        feedAltitudeRamp(a, fromM = 0.0, toM = 160.0, stepM = 16.0)
        feedAltitudeRamp(a, fromM = 160.0, toM = 0.0, stepM = -16.0)
        assertEquals(160.0, a.altGainM, 1e-6)
        assertEquals(160.0, a.altLossM, 1e-6)
    }

    /** §6: 500 m altindaki ya da 2 dakikadan kisa yolculuk kaydedilmez. */
    @Test
    fun `kisa yolculuk kaydedilmez`() {
        val a = acc()
        clock = 5 * 60 * 1000L                 // sure yeterli
        a.advance(stepM = 400.0, altitudeM = null)   // mesafe yetersiz
        assertFalse(a.worthKeeping)

        val b = TripAccumulator(startEpoch = 0L, now = { 60_000L })  // 1 dakika
        b.advance(stepM = 3000.0, altitudeM = null)                  // mesafe yeterli
        assertFalse(b.worthKeeping)

        val c = acc()
        clock = Constants.MIN_TRIP_DURATION_SEC * 1000
        c.advance(stepM = Constants.MIN_TRIP_DISTANCE_M, altitudeM = null)
        assertTrue(c.worthKeeping)
    }

    /**
     * Guc verisi hic gelmezse tuketim gosterilmez. Emulatorde guc sabit 0
     * geldigi icin ekrana "0,0 kWh/100 km" yaziliyordu — veri yokken sayi
     * gostermek uydurma hassasiyettir.
     */
    @Test
    fun `enerji esigin altindayken tuketim null`() {
        val a = acc()
        clock = 10 * 60 * 1000L
        repeat(100) { a.advance(stepM = 100.0, altitudeM = null) }   // 10 km
        feedPower(a, kw = 0.0, seconds = 600.0)                      // 0 kWh
        assertNull(a.consumptionKwh100)
        assertNull(a.toTrip(clock).energyKwh)
    }

    /** Kayan pencere: son 10 km'nin tuketimi yolculuk ortalamasindan farkli. */
    @Test
    fun `kayan pencere yalnizca son kilometreleri sayar`() {
        val a = acc()
        // Ilk 10 km yuksek tuketim (36 kW), sonraki 10 km dusuk (9 kW).
        var tick = 0L   // 0,1 sn'lik adim sayaci
        repeat(2) { phase ->
            val kw = if (phase == 0) 36.0 else 9.0
            repeat(100) {
                a.advance(stepM = 100.0, altitudeM = null)
                // Her 100 m'de 10 sn: 10 km 1000 sn'de
                repeat(100) {
                    a.energy.onPower(kw, tick * 100_000_000L)
                    tick++
                }
                a.onTick(null)
            }
        }
        clock = tick * 100L

        val window = a.windowConsumptionKwh100(10.0)!!
        val trip = a.consumptionKwh100!!
        // Son 10 km 9 kW ile gidildi → pencere yolculuk ortalamasindan dusuk.
        assertTrue("pencere=$window trip=$trip", window < trip)
        // 9 kW × 1000 sn = 2,5 kWh / 10 km = 25 kWh/100 km
        assertEquals(25.0, window, 0.5)
    }

    /**
     * Hiz 10 Hz, konum 1 Hz geliyor ama grafigin x ekseni MESAFE. Her hiz
     * ornegi seriye yazilirsa ayni mesafeye on nokta yigiliyor ve grafik
     * bozuluyordu (gercek araçta goruldu). Seride mesafe adimi basina TEK
     * nokta olmali.
     */
    @Test
    fun `hiz serisi mesafe adimi basina tek nokta tutar`() {
        val a = acc()
        repeat(20) { stepIndex ->
            // Her 100 m'de 10 hiz ornegi
            repeat(10) { a.onSpeed(60.0 + stepIndex) }
            a.advance(stepM = 100.0, altitudeM = null)
        }
        val series = a.speedSnapshot()
        assertEquals(20, series.size)
        // Mesafeler artan ve tekrarsiz olmali
        assertEquals(series.map { it.distanceM }.distinct().size, series.size)
    }

    /** Arac dururken mesafe ilerlemiyor; seriye nokta eklenmemeli. */
    @Test
    fun `durusta hiz serisine nokta eklenmez`() {
        val a = acc()
        a.onSpeed(50.0)
        a.advance(stepM = 100.0, altitudeM = null)
        val before = a.speedSnapshot().size
        repeat(500) { a.onSpeed(0.0) }   // 50 saniyelik duruş
        assertEquals(before, a.speedSnapshot().size)
    }

    /**
     * Grafikler artik butun yolculugu degil secilen son N kilometreyi ciziyor.
     * 40 km surulup 20 km'lik pencere istendiginde seri 20 km'yi kapsamali.
     */
    @Test
    fun `pencere yalnizca son N kilometreyi verir`() {
        val a = acc()
        repeat(400) { a.advance(stepM = 100.0, altitudeM = 100.0 + it) }

        val w = a.altitudeWindow(20.0)
        val span = w.last().distanceM - w.first().distanceM
        // Sol kenara bir nokta tasma payi var (cizgi kenara kadar gitsin diye).
        assertTrue("pencere $span m", span >= 20_000.0 && span <= 20_200.0)
        // Tam seri hala daha uzun: pencere kirpiyor, veriyi silmiyor.
        assertTrue(a.altitudeSnapshot().size > w.size)
    }

    /** Pencere yolculuktan genisse elimizdeki her sey cizilir, bos donmez. */
    @Test
    fun `pencere yolculuktan genisse tum seri doner`() {
        val a = acc()
        repeat(50) { a.advance(stepM = 100.0, altitudeM = 100.0) }   // 5 km
        assertEquals(a.altitudeSnapshot().size, a.altitudeWindow(20.0).size)
    }

    /**
     * En genis pencerenin disinda kalan gecmis atiliyor: nokta butcesi artik
     * cizilmeyen kilometrelere harcanirsa GORUNEN kismin cozunurlugu dusuyor.
     */
    @Test
    fun `seri en genis pencerenin otesini tutmaz`() {
        val a = acc()
        // 120 km — SERIES_KEEP_KM'nin (50) iki katindan fazla.
        repeat(1200) { a.advance(stepM = 100.0, altitudeM = 100.0 + it) }

        val all = a.altitudeSnapshot()
        val span = all.last().distanceM - all.first().distanceM
        assertTrue("tutulan $span m", span <= TripAccumulator.SERIES_KEEP_KM * 1000.0 + 200.0)
        // Mesafe toplami budamadan etkilenmemeli.
        assertEquals(120_000.0, a.distanceM, 1e-6)
    }

    // --- Yardimcilar ---

    /**
     * Zaman tam sayi adimdan turetiliyor: `t += 0.1` ile biriktirmek kayan nokta
     * kaymasi yuzunden son ornegi dusuruyor ve integral %0,1 eksik cikiyordu.
     */
    private fun feedPower(a: TripAccumulator, kw: Double, seconds: Double) {
        val steps = (seconds * 10).toInt()
        for (i in 0..steps) {
            a.energy.onPower(kw, i * 100_000_000L)   // 0,1 sn = 1e8 ns
        }
    }

    /**
     * Her irtifa degerini kayan ortalama penceresi kadar tekrarlar; boylece
     * duzlestirilmis deger o degere tam oturur ve test belirlenimci olur.
     */
    private fun feedAltitudeRamp(a: TripAccumulator, fromM: Double, toM: Double, stepM: Double) {
        var alt = fromM
        while (if (stepM > 0) alt <= toM else alt >= toM) {
            repeat(5) { a.advance(stepM = 10.0, altitudeM = alt) }
            alt += stepM
        }
    }

    // --- Tekerlek mesafesi kalibrasyonu (2026-09-19 olcumu) ---

    /** Kayda HAM deger girmeli; duzeltme yalnizca turetilmis alanda olmali. */
    @Test
    fun `ham tekerlek mesafesi duzeltilmeden saklanir`() {
        val a = acc()
        a.wheelStartM = 0.0
        a.wheelEndM = 10_000.0
        assertEquals(10.0, a.wheelDistanceKm!!, 1e-9)
        assertEquals(10.0 * Constants.WHEEL_TICK_SCALE, a.wheelDistanceCalibratedKm!!, 1e-9)
    }

    /** Gercek arac olcumu: 348,170 km ham -> 357,0 km (Volvo sayaci). */
    @Test
    fun `gercek olcum Volvo sayacina oturuyor`() {
        val a = acc()
        a.wheelStartM = 0.0
        a.wheelEndM = 348_169.734
        assertEquals(357.0, a.wheelDistanceCalibratedKm!!, 0.05)
    }

    /** Saglikli yolculukta GPS ile duzeltilmis tekerlek %1'in altinda ayrisir. */
    @Test
    fun `saglikli yolculukta gps saglikli isaretlenir`() {
        val a = acc()
        clock = 60 * 60 * 1000L
        repeat(3561) { a.advance(stepM = 100.0, altitudeM = null) }
        a.wheelStartM = 0.0
        a.wheelEndM = 348_169.734
        assertTrue(a.gpsHealthy!!)
        assertEquals(1.0, a.gpsVsWheel!!, 0.01)
    }

    /**
     * 2026-09-19'da gorulen gercek ariza: GPS 3,714 km derken tekerlek 4,192 km
     * dedi ve rakim kazanci TAM SIFIR geldi. Bu yolculuk guvenilmez sayilmali.
     */
    @Test
    fun `gps coktugunde guvenilmez isaretlenir`() {
        val a = acc()
        clock = 15 * 60 * 1000L
        repeat(3714) { a.advance(stepM = 1.0, altitudeM = null) }
        a.wheelStartM = 0.0
        a.wheelEndM = 4_191.9515
        assertFalse(a.gpsHealthy!!)
    }

    /** Kisa yolculukta oran anlamsiz: tek bir GPS sicramasi sayiyi ucuruyor. */
    @Test
    fun `kisa yolculukta karsilastirma yapilmaz`() {
        val a = acc()
        clock = 60 * 1000L
        repeat(500) { a.advance(stepM = 1.0, altitudeM = null) }
        a.wheelStartM = 0.0
        a.wheelEndM = 490.0
        assertNull(a.gpsVsWheel)
        assertNull(a.gpsHealthy)
    }
}
