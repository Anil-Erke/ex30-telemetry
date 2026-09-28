package com.example.ex30telemetry.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `WHEEL_TICK` mesafe mantiginin testleri.
 *
 * Buradaki kurallarin hepsi gercek EX30'da 2026-09-10'da olculmus davranistan
 * geliyor; hicbiri varsayim degil:
 *  - deger KUTULANMIS `Long[]` olarak geliyor (`tip=Long[]`)
 *  - dort tekerlek de 22.000 µm/tick
 *  - sayac sifirlaniyor ama `Long[0]` reset sayaci 0'da kaliyor
 */
class WheelOdometerTest {

    /** Gercek araçtan gelen configArray. */
    private fun odo() = WheelOdometer.fromConfigArray(listOf(15, 22000, 22000, 22000, 22000))

    /** Araç degeri kutulanmis dizi olarak veriyor — testler de oyle vermeli. */
    private fun boxed(vararg v: Long): Array<Long> = v.toTypedArray()

    @Test
    fun `gercek configArray kabul ediliyor`() {
        assertNotNull(odo())
    }

    @Test
    fun `eksik configArray reddediliyor`() {
        assertNull(WheelOdometer.fromConfigArray(listOf(15, 22000)))
        assertNull(WheelOdometer.fromConfigArray(emptyList()))
    }

    @Test
    fun `desteklenen tekerlek yoksa kullanilamaz`() {
        assertNull(WheelOdometer.fromConfigArray(listOf(0, 0, 0, 0, 0)))
    }

    @Test
    fun `ilk ornek yalnizca taban belirler`() {
        val o = odo()!!
        o.onTicks(boxed(0, 1000, 1000, 1000, 1000))
        assertEquals(0.0, o.totalM, 1e-9)
    }

    @Test
    fun `kutulanmis dizi kabul ediliyor ve mesafe 22 mm x tick`() {
        val o = odo()!!
        o.onTicks(boxed(0, 0, 0, 0, 0))
        // Dort tekerlek de 1000 tick ilerledi -> 1000 x 22 mm = 22 m
        o.onTicks(boxed(0, 1000, 1000, 1000, 1000))
        assertEquals(22.0, o.totalM, 1e-6)
    }

    @Test
    fun `ilkel LongArray de kabul ediliyor`() {
        val o = odo()!!
        o.onTicks(longArrayOf(0, 0, 0, 0, 0))
        o.onTicks(longArrayOf(0, 500, 500, 500, 500))
        assertEquals(11.0, o.totalM, 1e-6)
    }

    @Test
    fun `dort tekerlegin ortalamasi aliniyor`() {
        val o = odo()!!
        o.onTicks(boxed(0, 0, 0, 0, 0))
        // 100, 200, 300, 400 -> ortalama 250 tick = 5,5 m
        o.onTicks(boxed(0, 100, 200, 300, 400))
        assertEquals(5.5, o.totalM, 1e-6)
    }

    @Test
    fun `geri viteste mesafe EKLENIYOR, cikarilmiyor`() {
        // GPS tarafi distanceTo() ile her adimi pozitif sayiyor; karsilastirmanin
        // adil olmasi icin tekerlek tarafi da mutlak deger ekliyor.
        val o = odo()!!
        o.onTicks(boxed(0, 10_000, 10_000, 10_000, 10_000))
        o.onTicks(boxed(0, 9_900, 9_900, 9_900, 9_900))
        assertEquals(2.2, o.totalM, 1e-6)
    }

    @Test
    fun `sifirlanma atlaniyor ve sayiliyor`() {
        val o = odo()!!
        o.onTicks(boxed(0, 194_151, 193_821, 193_412, 193_731))
        // Araçta gorulen gercek sifirlanma: deger dustu, Long[0] yine 0.
        o.onTicks(boxed(0, 76_472, 76_128, 76_015, 76_390))
        assertEquals(1, o.resets)
        assertEquals(0.0, o.totalM, 1e-9)
    }

    @Test
    fun `sifirlanmadan sonra saymaya devam ediyor`() {
        val o = odo()!!
        o.onTicks(boxed(0, 194_151, 193_821, 193_412, 193_731))
        o.onTicks(boxed(0, 76_472, 76_128, 76_015, 76_390))
        o.onTicks(boxed(0, 77_472, 77_128, 77_015, 77_390))
        assertEquals(22.0, o.totalM, 1e-6)
    }

    @Test
    fun `desteklenmeyen tekerlek ortalamaya girmiyor`() {
        // Yalnizca on iki tekerlek destekli olsaydi
        val o = WheelOdometer.fromConfigArray(listOf(3, 22000, 22000, 0, 0))!!
        o.onTicks(boxed(0, 0, 0, 0, 0))
        o.onTicks(boxed(0, 1000, 1000, 999_999, 999_999))
        assertEquals(22.0, o.totalM, 1e-6)
    }

    @Test
    fun `bozuk tip mesafeyi bozmuyor`() {
        val o = odo()!!
        o.onTicks(boxed(0, 0, 0, 0, 0))
        o.onTicks("saçmalık")
        o.onTicks(null)
        o.onTicks(boxed(0, 1000, 1000, 1000, 1000))
        assertEquals(22.0, o.totalM, 1e-6)
    }

    @Test
    fun `kisa dizi yok sayiliyor`() {
        val o = odo()!!
        o.onTicks(boxed(0, 1, 2))
        assertEquals(0.0, o.totalM, 1e-9)
    }

    @Test
    fun `gercek araç olcumu dogrulaniyor`() {
        // 2026-09-10, 14:42 -> 14:53 arasi sürüş.
        // On sol tek basina 116.948 tick = 2.573 m; dort tekerlegin ortalamasi
        // 116.722 tick = 2.567,9 m. Uygulama ortalamayi kullaniyor.
        //
        // Bu test ayni zamanda "uzun aralikli iki ornek" durumunu koruyor:
        // 11 dakikalik bir bosluk sifirlanma sanilmamali.
        val o = odo()!!
        o.onTicks(boxed(0, 77_203, 77_079, 76_899, 77_045))
        o.onTicks(boxed(0, 194_151, 193_821, 193_412, 193_731))
        assertEquals(2567.9, o.totalM, 1.0)
        assertEquals(0, o.resets)
    }
}
