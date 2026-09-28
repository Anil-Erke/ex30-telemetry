package com.example.ex30telemetry.render

import android.content.Context

/**
 * **Kayan pencere genisligi** — uygulamanin "son N kilometre" dedigi her yerde
 * ayni sayi kullaniliyor:
 *
 *  - irtifa ve hiz grafiklerinin x ekseni,
 *  - ust paneldeki buyuk tuketim sayisi (kWh/100 km),
 *  - ortalama hiz barindaki yesil top.
 *
 * **Neden tek ayar:** ucu ayri ayri ayarlanabilseydi ekrandaki uc sayi farkli
 * mesafelere ait olurdu ve birbirleriyle karsilastirilamazlardi — "son 20 km'de
 * 18 kWh/100 km harcadim, ortalama 85 km/h gittim" cumlesi ancak ikisi ayni
 * pencereyse kurulabiliyor.
 *
 * **Neden pencere var:** yolculugun tamamini cizen bir grafik uzun yolda
 * okunaksiz hale geliyor — 200 km'lik bir surusu 700 piksele sigdirinca her
 * piksel ~300 metre oluyor ve tek tek tepeler kayboluyor.
 */
object WindowSetting {

    /** Secilebilir pencereler (km). [TripAccumulator.SERIES_KEEP_KM] en buyugunu karsilamali. */
    val CHOICES = listOf(10.0, 20.0, 50.0)

    /** Varsayilan: 20 km. 10 km sehir ici icin bile kisa kaliyordu. */
    private const val DEFAULT_KM = 20.0

    private const val PREFS = "ex30_ui"
    private const val KEY_KM = "window_km"

    /** Ekran 1 Hz ciziliyor; her karede disk okumamak icin bellekte tutuluyor. */
    @Volatile
    private var cached: Double? = null

    fun km(context: Context): Double {
        cached?.let { return it }
        val stored = prefs(context).getFloat(KEY_KM, DEFAULT_KM.toFloat()).toDouble()
        // Secenek listesi degisirse eski deger gecersiz kalabilir; en yakinina degil
        // varsayilana dusuyoruz — "50" secmis birine sessizce 20 vermek yerine
        // bilinen bir degere donmek daha durust.
        val v = if (stored in CHOICES) stored else DEFAULT_KM
        cached = v
        return v
    }

    /** Satira her dokunusta bir sonraki secenege gecer ve secimi saklar. */
    fun next(context: Context): Double {
        val v = CHOICES[(CHOICES.indexOf(km(context)) + 1) % CHOICES.size]
        cached = v
        prefs(context).edit().putFloat(KEY_KM, v.toFloat()).apply()
        return v
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
