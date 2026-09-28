package com.example.ex30telemetry.trip

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Kalicilik ve geriye donuk uyumluluk. Sema degisirse eski kayitlar okunmaya
 * devam etmeli, silinmemeli.
 */
class TripJsonTest {

    @Test
    fun `kayit yazilip geri okundugunda ayni kalir`() {
        val t = Trip(
            startEpoch = 1_700_000_000_000,
            endEpoch = 1_700_000_900_000,
            durationSec = 900,
            distanceKm = 21.04,
            energyKwh = 3.47,
            regenKwh = 0.62,
            socStart = 64.0, socEnd = 58.0,
            rangeStart = 246.0, rangeEnd = 208.0,
            avgSpeedKmh = 84.2, maxSpeedKmh = 102.5,
            tempStart = 37.0, tempAvg = 36.2,
            altGainM = 143.0, altLossM = 151.0,
            potentialKwh = 0.713,
            consumptionKwh100 = 16.5,
            rangeBiasFactor = 1.806,
            records = listOf(PerfRecord.of(PerfKind.SPRINT_0_100, 5.42, 1_700_000_500_000)),
        )
        val back = Trip.fromJson(JSONObject(t.toJson().toString()))
        assertEquals(t, back)
    }

    /** Null alanlar sifir olarak degil, null olarak geri gelmeli. */
    @Test
    fun `null alanlar null kalir`() {
        val t = Trip(
            startEpoch = 1, endEpoch = 2, durationSec = 1,
            distanceKm = 0.6,
            energyKwh = null, regenKwh = null,
            socStart = null, socEnd = null, rangeStart = null, rangeEnd = null,
            avgSpeedKmh = null, maxSpeedKmh = null, tempStart = null, tempAvg = null,
            altGainM = 0.0, altLossM = 0.0, potentialKwh = null,
            consumptionKwh100 = null, rangeBiasFactor = null,
        )
        val back = Trip.fromJson(JSONObject(t.toJson().toString()))
        assertNull(back.energyKwh)
        assertNull(back.consumptionKwh100)
        assertNull(back.rangeBiasFactor)
        assertNull(back.maxSpeedKmh)
    }

    /**
     * 0.2.0 oncesi kayitlarda `regenKwh`, `potentialKwh` gibi alanlar yok ve
     * `schemaVersion` bulunmayabiliyor. Kayit atilmamali.
     */
    @Test
    fun `eski sema kaydi okunabilir`() {
        val old = JSONObject(
            """
            {"startEpoch":1700000000000,"endEpoch":1700000900000,"durationSec":900,
             "distanceKm":14.39,"energyKwh":0,"socStart":100,"socEnd":100,
             "altGainM":719.6,"altLossM":701.6,"consumptionKwh100":0}
            """.trimIndent()
        )
        val t = Trip.fromJson(old)
        assertEquals(1, t.schemaVersion)
        // Sema 1'de PerfRecord yalnizca `seconds` tasiyordu; saniye kabul edilir.
        val legacy = PerfRecord.fromJson(
            org.json.JSONObject("""{"kind":"0-100","seconds":5.42,"epoch":1700000500000}""")
        )
        assertEquals(5.42, legacy.value, 1e-9)
        assertEquals("s", legacy.unit)
        assertEquals(14.39, t.distanceKm, 1e-9)
        assertEquals(719.6, t.altGainM, 1e-9)
        assertNull(t.regenKwh)
        assertNull(t.potentialKwh)
        assertEquals(0, t.records.size)
    }
}
