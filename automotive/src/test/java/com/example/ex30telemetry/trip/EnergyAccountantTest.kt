package com.example.ex30telemetry.trip

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Enerji integralinin dogrulugu. Guc ornekleri gercek araçtaki gibi 10 Hz
 * beslenir; zaman damgasi nanosaniye (CarPropertyValue.getTimestamp tabani).
 */
class EnergyAccountantTest {

    /** Sabit 36 kW × 100 sn = 1,0 kWh. */
    @Test
    fun `sabit gucte integral tam cikar`() {
        val a = EnergyAccountant()
        feed(a, kw = 36.0, seconds = 100.0)
        assertEquals(1.0, a.netKwh, 1e-9)
        assertEquals(1.0, a.grossKwh, 1e-9)
        assertEquals(0.0, a.regenKwh, 1e-9)
    }

    /** Negatif guc rejen; net = tuketim − rejen. */
    @Test
    fun `rejen ayri sayilir ve netten dusulur`() {
        val a = EnergyAccountant()
        feed(a, kw = 36.0, seconds = 100.0)      // +1,0 kWh
        feed(a, kw = -18.0, seconds = 100.0, startSec = 100.0)  // −0,5 kWh
        assertEquals(0.5, a.netKwh, 1e-6)
        assertEquals(1.0, a.grossKwh, 1e-6)
        assertEquals(0.5, a.regenKwh, 1e-6)
    }

    /**
     * Veri kaybi olan araliklar kopru yapilmamali: iki ornek arasi 5 sn'yi
     * asiyorsa o bosluk integrale katilmaz. Aksi halde kontak kapaliyken gecen
     * saatler tek bir yamuk olarak eklenirdi.
     */
    @Test
    fun `uzun bosluk integrale katilmaz`() {
        val a = EnergyAccountant()
        a.onPower(36.0, nanos(0.0))
        a.onPower(36.0, nanos(3600.0))   // 1 saat sonra
        assertEquals(0.0, a.netKwh, 1e-9)
    }

    /** Yamuk kurali: dogrusal artan gucte ortalama guc × sure. */
    @Test
    fun `dogrusal artan gucte yamuk kurali dogru`() {
        val a = EnergyAccountant()
        // 0 kW'dan 20 kW'a 100 sn'de dogrusal: ortalama 10 kW → 10 × 100/3600
        val steps = 1000
        for (i in 0..steps) {
            val t = i / 10.0
            a.onPower(20.0 * (t / 100.0), nanos(t))
        }
        assertEquals(10.0 * 100.0 / 3600.0, a.netKwh, 1e-6)
    }

    @Test
    fun `reset her seyi sifirlar`() {
        val a = EnergyAccountant()
        feed(a, kw = 36.0, seconds = 100.0)
        a.reset()
        assertEquals(0.0, a.netKwh, 1e-9)
        assertEquals(0L, a.sampleCount)
    }

    // --- Yardimcilar ---

    /**
     * 10 Hz'de [seconds] saniye boyunca sabit [kw] besler.
     *
     * Zaman tam sayi adimdan turetiliyor: `t += 0.1` ile biriktirmek kayan nokta
     * kaymasi yuzunden son ornegi dusuruyor ve integral %0,1 eksik cikiyordu.
     */
    private fun feed(a: EnergyAccountant, kw: Double, seconds: Double, startSec: Double = 0.0) {
        val steps = (seconds * 10).toInt()
        for (i in 0..steps) {
            a.onPower(kw, nanos(startSec + i / 10.0))
        }
    }

    private fun nanos(seconds: Double): Long = (seconds * 1_000_000_000L).toLong()
}
