package com.example.ex30telemetry.render

import android.content.Context
import com.example.ex30telemetry.R

/** Surus ekraninin gorunumu. Kullanici Araç verileri ekranindan degistiriyor. */
enum class ThemeMode(val labelRes: Int) {
    /** Araç ne diyorsa o: once `NIGHT_MODE` property'si, sonra host'un temasi. */
    OTOMATIK(R.string.calib_theme_auto),
    KOYU(R.string.calib_theme_dark),
    ACIK(R.string.calib_theme_light),
}

/**
 * Paletin hangi kaynaktan secildigi — Araç verileri ekraninda gosteriliyor.
 *
 * Teshis degeri var: "gece oldu ama ekran acik kaldi" dendiginde, aracin gece
 * modunu hic bildirmedigi mi yoksa gunduz oldugunu mu soyledigi ancak boyle
 * anlasiliyor.
 */
enum class ThemeSource(val labelRes: Int) {
    /** `NIGHT_MODE` property'si araçtan okundu. */
    ARAC(R.string.calib_theme_src_car),
    /** Property gelmedi; `CarContext.isDarkMode` (host'un temasi) kullanildi. */
    HOST(R.string.calib_theme_src_host),
    /** Kullanici elle sabitledi. */
    ELLE(R.string.calib_theme_src_manual),
}

/**
 * Tema secimi ve cozumu.
 *
 * **Neden elle secim de var:** otomatik yol iki kaynaga bakiyor ama ikisi de
 * araçtan geliyor. Gercek EX30'da `NIGHT_MODE`'un dogru geldigi dogrulanana
 * kadar, surucunun temayi kendi sabitleyebilmesi tek garanti. Kaynak satiri da
 * bu yuzden ekranda yaziyor: otomatik calismiyorsa NEDEN calismadigi gorunsun.
 */
object ThemeSetting {

    private const val PREFS = "ex30_ui"
    private const val KEY_MODE = "theme_mode"

    /** Ekran 1 Hz ciziliyor; her karede disk okumamak icin bellekte tutuluyor. */
    @Volatile
    private var cached: ThemeMode? = null

    fun mode(context: Context): ThemeMode {
        cached?.let { return it }
        val name = prefs(context).getString(KEY_MODE, null)
        val m = ThemeMode.entries.firstOrNull { it.name == name } ?: ThemeMode.OTOMATIK
        cached = m
        return m
    }

    /** Satira her dokunusta bir sonraki moda gecer ve secimi saklar. */
    fun next(context: Context): ThemeMode {
        val values = ThemeMode.entries
        val m = values[(values.indexOf(mode(context)) + 1) % values.size]
        cached = m
        prefs(context).edit().putString(KEY_MODE, m.name).apply()
        return m
    }

    /**
     * @param carNight `NIGHT_MODE` property'si (null = arac bildirmiyor)
     * @param hostDark `CarContext.isDarkMode`
     */
    fun isDark(context: Context, carNight: Boolean?, hostDark: Boolean): Boolean =
        when (mode(context)) {
            ThemeMode.KOYU -> true
            ThemeMode.ACIK -> false
            ThemeMode.OTOMATIK -> carNight ?: hostDark
        }

    fun source(context: Context, carNight: Boolean?): ThemeSource = when {
        mode(context) != ThemeMode.OTOMATIK -> ThemeSource.ELLE
        carNight != null -> ThemeSource.ARAC
        else -> ThemeSource.HOST
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
