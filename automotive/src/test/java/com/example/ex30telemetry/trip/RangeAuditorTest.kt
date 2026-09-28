package com.example.ex30telemetry.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A2 — menzil tahmini denetleyicisi. */
class RangeAuditorTest {

    @Test
    fun `gosterge 15 km dusup 10 km gidildiyse oran 1_5`() {
        assertEquals(1.5, RangeAuditor.biasFactor(250.0, 235.0, 10.0)!!, 1e-9)
    }

    @Test
    fun `gosterge tam tuttuysa oran 1_0`() {
        assertEquals(1.0, RangeAuditor.biasFactor(250.0, 230.0, 20.0)!!, 1e-9)
    }

    /**
     * RANGE_REMAINING gercek araçta 1 km adimlarla degisiyor; kisa yolculukta
     * tek bir adim orani saptirdigi icin hesap yapilmiyor.
     */
    @Test
    fun `kisa mesafede oran hesaplanmaz`() {
        assertNull(RangeAuditor.biasFactor(250.0, 246.0, 4.0))
    }

    @Test
    fun `menzil artmissa oran hesaplanmaz`() {
        // Uzun inis ya da aracin yeniden hesaplamasi menzili artirabiliyor.
        assertNull(RangeAuditor.biasFactor(230.0, 240.0, 20.0))
    }

    @Test
    fun `veri eksikse null doner`() {
        assertNull(RangeAuditor.biasFactor(null, 230.0, 20.0))
        assertNull(RangeAuditor.biasFactor(250.0, null, 20.0))
    }

    @Test
    fun `aciklama insan diline cevirir`() {
        assertEquals(RangeAuditor.Verdict.Accurate, RangeAuditor.verdict(1.02))

        val optimistic = RangeAuditor.verdict(1.5)
        assertTrue(optimistic is RangeAuditor.Verdict.Optimistic)
        assertEquals(50.0, (optimistic as RangeAuditor.Verdict.Optimistic).percent, 1e-9)

        val pessimistic = RangeAuditor.verdict(0.8)
        assertTrue(pessimistic is RangeAuditor.Verdict.Pessimistic)
        assertEquals(20.0, (pessimistic as RangeAuditor.Verdict.Pessimistic).percent, 1e-9)
    }

    @Test
    fun `ortalama mesafeye gore agirliklanir`() {
        val trips = listOf(
            trip(distanceKm = 10.0, factor = 2.0),
            trip(distanceKm = 90.0, factor = 1.0),
        )
        // (2,0×10 + 1,0×90) / 100 = 1,1
        assertEquals(1.1, RangeAuditor.averageFactor(trips)!!, 1e-9)
    }

    @Test
    fun `oran yoksa ortalamaya katilmaz`() {
        val trips = listOf(
            trip(distanceKm = 10.0, factor = null),
            trip(distanceKm = 20.0, factor = 1.5),
        )
        assertEquals(1.5, RangeAuditor.averageFactor(trips)!!, 1e-9)
    }

    private fun trip(distanceKm: Double, factor: Double?) = Trip(
        startEpoch = 0, endEpoch = 0, durationSec = 0,
        distanceKm = distanceKm,
        energyKwh = null, regenKwh = null,
        socStart = null, socEnd = null, rangeStart = null, rangeEnd = null,
        avgSpeedKmh = null, maxSpeedKmh = null, tempStart = null, tempAvg = null,
        altGainM = 0.0, altLossM = 0.0, potentialKwh = null,
        consumptionKwh100 = null, rangeBiasFactor = factor,
    )
}
