package com.example.ex30telemetry.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GPS boslugunu ham hiz integraliyle kopruleme
 * ([TripAccumulator.bridgeIfGpsStale]).
 *
 * **Neden test var:** buradaki asil risk cift saymak. Kopruleme boslugu mesafeye
 * ekliyor; bosluk bittiginde gelen ILK GPS adimi ise boslugun tamamini iceriyor.
 * Ikisi de eklenirse yolculuk iki katina cikar — ve bu, ekranda "makul ama
 * yanlis" bir sayi olarak gorunur, yani gozle yakalanmasi en zor hata turu.
 *
 * Yontemin gecerliligi gercek surusle olculdu (2026-08-31, 15 km):
 * ∫ham hiz 11,836 km · GPS 11,805 km — %0,26 fark.
 */
class GpsBridgeTest {

    private fun acc() = TripAccumulator(startEpoch = 0L, now = { 0L })

    /** 10 Hz ham hiz ornegi besler; tNanos nanosaniye cinsinden. */
    private fun feedRawSpeed(a: TripAccumulator, kmh: Double, seconds: Double, fromSec: Double = 0.0) {
        val steps = (seconds * 10).toInt()
        for (i in 0..steps) {
            a.onRawSpeed(kmh, ((fromSec + i / 10.0) * 1e9).toLong())
        }
    }

    @Test
    fun `GPS taze iken kopruleme yapilmaz`() {
        val a = acc()
        a.onGpsFix(1_000L)
        feedRawSpeed(a, kmh = 72.0, seconds = 5.0)

        // Son fix'in uzerinden 5 sn gecti; esik 10 sn.
        assertFalse(a.bridgeIfGpsStale(6_000L))
        assertEquals(0.0, a.distanceM, 1e-9)
    }

    @Test
    fun `GPS susunca mesafe ham hizdan yurutulur`() {
        val a = acc()
        a.onGpsFix(0L)
        // 72 km/h = 20 m/s, 30 saniye → 600 m
        feedRawSpeed(a, kmh = 72.0, seconds = 30.0)

        assertTrue(a.bridgeIfGpsStale(30_000L))
        assertEquals(600.0, a.distanceM, 1.0)
        assertEquals(600.0, a.bridgedM, 1.0)
    }

    /** Asil tuzak: koprulemeden sonraki ilk GPS adimi ATLANMALI. */
    @Test
    fun `koprulemeden sonraki GPS adimi ikinci kez eklenmez`() {
        val a = acc()
        a.onGpsFix(0L)
        feedRawSpeed(a, kmh = 72.0, seconds = 30.0)
        a.bridgeIfGpsStale(30_000L)
        val afterBridge = a.distanceM

        // Bosluk bitti: gelen fix, boslugun tamamini kapsayan 600 m'lik bir adim.
        a.advance(stepM = 600.0, altitudeM = null)

        assertEquals("bosluk iki kez sayilmamali", afterBridge, a.distanceM, 1e-9)

        // Bir sonraki normal adim yeniden sayilmali.
        a.advance(stepM = 50.0, altitudeM = null)
        assertEquals(afterBridge + 50.0, a.distanceM, 1e-9)
    }

    @Test
    fun `durur halde kopruleme mesafe eklemez`() {
        val a = acc()
        a.onGpsFix(0L)
        // Araç duruyor: 3 m'lik filtre yuzunden fix gelmiyor ama mesafe de yok.
        feedRawSpeed(a, kmh = 0.0, seconds = 30.0)

        assertFalse(a.bridgeIfGpsStale(30_000L))
        assertEquals(0.0, a.distanceM, 1e-9)
    }

    @Test
    fun `kopruleme hiz serisine nokta yazar`() {
        val a = acc()
        a.onGpsFix(0L)
        a.onSpeed(72.0)                       // gosterge hizi: seriye yazilan deger
        feedRawSpeed(a, kmh = 72.0, seconds = 30.0)

        val before = a.speedSnapshot().size
        a.bridgeIfGpsStale(30_000L)

        // Bosluk artik duz cizgi degil: eksen ilerledi ve nokta yazildi.
        assertEquals(before + 1, a.speedSnapshot().size)
        assertEquals(a.distanceM, a.speedSnapshot().last().distanceM, 1e-9)
    }

    /** Uzun ornek bosluklarinda yamuk kurali guvenilmez; atlanmali. */
    @Test
    fun `hizda uzun bosluk integrale katilmaz`() {
        val a = acc()
        a.onGpsFix(0L)
        a.onRawSpeed(72.0, 0L)
        // Sonraki ornek 5 saniye sonra: esik 2 sn, bu aralik sayilmamali.
        a.onRawSpeed(72.0, 5_000_000_000L)

        assertFalse(a.bridgeIfGpsStale(30_000L))
        assertEquals(0.0, a.distanceM, 1e-9)
    }
}
