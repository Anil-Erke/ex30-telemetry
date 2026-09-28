package com.example.ex30telemetry.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * A4 olcumlerinin dogrulugu. Ornekleme gercek EX30'daki gibi 10 Hz; esik
 * gecisleri interpolasyonla bulunmazsa sonuc 100 ms'e yuvarlanirdi.
 */
class PerformanceCatcherTest {

    private val caught = LinkedHashMap<PerfKind, Double>()
    private val catcher = PerformanceCatcher { record, kind -> caught[kind] = record.value }

    /**
     * §8 deseni: hedefi 5,300 sn olan sentetik ivmelenme egrisi.
     * EX30 0-100'de ayni zincir 5,3004 sn olcmustu.
     */
    @Test
    fun `sentetik 5_3 saniyelik kalkis dogru olculur`() {
        feedLaunchCurve(targetSeconds = 5.3, untilSeconds = 8.0)
        val t100 = caught[PerfKind.SPRINT_0_100]!!
        assertEquals(5.3, t100, 0.02)
        // 0-60 de ayni kosuda yakalanmali ve 0-100'den kucuk olmali.
        assertTrue(caught.containsKey(PerfKind.SPRINT_0_60))
        assertTrue(caught[PerfKind.SPRINT_0_60]!! < t100)
    }

    /**
     * Sabit yavaslamada fren mesafesi v²/(2a). 100 km/h = 27,778 m/s,
     * a = 9,9206 m/s² → 38,89 m.
     */
    @Test
    fun `sabit yavaslamada fren mesafesi dogru`() {
        val a = 9.9206
        var t = 0.0
        var i = 0
        while (t <= 3.2) {
            val mps = max(0.0, 30.0 - a * t)
            catcher.onSpeed(mps * 3.6, nanos(t))
            i++
            t = i / 10.0
        }
        val d = caught[PerfKind.BRAKE_100_0]!!
        assertEquals(27.7778 * 27.7778 / (2 * a), d, 0.2)
    }

    /** 80-120 elastikiyet: sabit 2 m/s² ivmede 11,111/2 = 5,556 sn. */
    @Test
    fun `80-120 elastikiyet dogru olculur`() {
        val a = 2.0
        var i = 0
        var t = 0.0
        while (t <= 12.0) {
            val mps = 70.0 / 3.6 + a * t
            catcher.onSpeed(mps * 3.6, nanos(t))
            i++
            t = i / 10.0
        }
        assertEquals(11.1111 / a, caught[PerfKind.ELASTIC_80_120]!!, 0.02)
    }

    /**
     * Surekli olmayan hizlanma olcum sayilmaz: sehir icinde 0'dan 50'ye cikip
     * yavaslayip sonra 110'a cikan bir surus "0-100" uretmemeli.
     */
    @Test
    fun `kesintili hizlanma olcum uretmez`() {
        val profile = ramp(0.0, 50.0, 4.0) + ramp(50.0, 40.0, 2.0) + ramp(40.0, 110.0, 8.0)
        feedProfile(profile)
        assertNull(caught[PerfKind.SPRINT_0_100])
        assertNull(caught[PerfKind.SPRINT_0_60])
    }

    /** Frende tekrar hizlanma olursa mesafe kaydedilmez. */
    @Test
    fun `frende tekrar hizlanma olcumu iptal eder`() {
        val profile = ramp(110.0, 60.0, 3.0) + ramp(60.0, 90.0, 3.0) + ramp(90.0, 0.0, 5.0)
        feedProfile(profile)
        assertNull(caught[PerfKind.BRAKE_100_0])
    }

    /** Duruştan kalkış olmadan 0-100 uretilmez (yolun ortasinda baslamaz). */
    @Test
    fun `duruştan kalkmadan sifir yuz olculmez`() {
        feedProfile(ramp(40.0, 120.0, 8.0))
        assertNull(caught[PerfKind.SPRINT_0_100])
        assertNull(caught[PerfKind.SPRINT_0_60])
    }

    // --- Yardimcilar ---

    /** v(t) = vmax(1 − e^(−t/τ)); [targetSeconds] saniyede tam 100 km/h. */
    private fun feedLaunchCurve(targetSeconds: Double, untilSeconds: Double) {
        val vmax = 55.0
        val tau = -targetSeconds / ln(1.0 - 27.7778 / vmax)
        var i = 0
        var t = 0.0
        while (t <= untilSeconds) {
            val mps = vmax * (1.0 - exp(-t / tau))
            catcher.onSpeed(mps * 3.6, nanos(t))
            i++
            t = i / 10.0
        }
    }

    /** [fromKmh] → [toKmh] arasi dogrusal, 10 Hz ornekli hiz dizisi. */
    private fun ramp(fromKmh: Double, toKmh: Double, seconds: Double): List<Double> {
        val steps = (seconds * 10).toInt()
        return (0 until steps).map { fromKmh + (toKmh - fromKmh) * it / steps }
    }

    private fun feedProfile(kmhSamples: List<Double>) {
        kmhSamples.forEachIndexed { i, kmh -> catcher.onSpeed(kmh, nanos(i / 10.0)) }
    }

    private fun nanos(seconds: Double): Long = (seconds * 1_000_000_000L).toLong()
}
