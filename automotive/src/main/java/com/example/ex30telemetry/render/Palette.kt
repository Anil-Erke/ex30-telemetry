package com.example.ex30telemetry.render

import android.graphics.Color

/**
 * Surus ekraninin renk paleti. Araç gece moduna gecince koyu, gunduz acik
 * palete geciliyor.
 *
 * **Neden gunduz icin ayri palet:** koyu bir yuzey parlak gun isiginda cam
 * yansimalarini gosterir ve okunmaz hale gelir; aracin kendi arayuzu de gunduz
 * acik temaya geciyor. Uygulama tek basina koyu kalirsa hem okunmuyor hem de
 * aracin geri kalaniyla uyumsuz duruyor.
 *
 * Vurgu renkleri gunduz icin koyulastirildi: `#4FC3F7` gibi acik bir mavi beyaz
 * zeminde yeterli kontrast vermiyor.
 */
data class Palette(
    val bg: Int,
    val panel: Int,
    val text: Int,
    val muted: Int,
    val grid: Int,
    val gridText: Int,
    val accent: Int,
    /**
     * Irtifa grafiginin rengi — toprak tonu.
     *
     * **Neden accent degil:** irtifa da "yolculuk ortalamasi" da mavi olunca,
     * ortalama hiz barindaki mavi top irtifa ortalamasi gibi okunuyordu. Mavi
     * artik yalnizca yolculuk ortalamasina ait: tuketim panelindeki sayi ve
     * bardaki top. Irtifa kendi rengiyle duruyor.
     */
    val earth: Int,
    val good: Int,
    val warn: Int,
    val low: Int,
    /** Zirve/dip etiketinin arka plani. */
    val markerBg: Int,
    /** Zirve/dip etiketinin yazi rengi. */
    val markerText: Int,
    /** Grafik dolgusunun alfa kanali (0-255). */
    val fillAlpha: Int,
) {
    companion object {
        val DARK = Palette(
            bg = Color.parseColor("#0F1115"),
            panel = Color.parseColor("#161A21"),
            text = Color.WHITE,
            muted = Color.parseColor("#8A93A5"),
            grid = Color.parseColor("#2A2F3A"),
            gridText = Color.parseColor("#616B7D"),
            accent = Color.parseColor("#4FC3F7"),
            // Kahverengiye caliyor, amber uyari rengine (#FFB300) karismasin.
            earth = Color.parseColor("#C68B59"),
            good = Color.parseColor("#3FB950"),
            warn = Color.parseColor("#FFB300"),
            low = Color.parseColor("#EF5350"),
            markerBg = Color.parseColor("#D00F1115"),
            markerText = Color.WHITE,
            fillAlpha = 0x22,
        )

        val LIGHT = Palette(
            bg = Color.parseColor("#EEF1F5"),
            panel = Color.parseColor("#FFFFFF"),
            text = Color.parseColor("#10141B"),
            muted = Color.parseColor("#5B6472"),
            grid = Color.parseColor("#D2D8E1"),
            gridText = Color.parseColor("#8B94A3"),
            accent = Color.parseColor("#0277BD"),
            // Beyaz zeminde acik tan okunmuyor; koyu siena.
            earth = Color.parseColor("#8A5524"),
            good = Color.parseColor("#2E7D32"),
            warn = Color.parseColor("#E65100"),
            low = Color.parseColor("#C62828"),
            markerBg = Color.parseColor("#E6FFFFFF"),
            markerText = Color.parseColor("#10141B"),
            fillAlpha = 0x28,
        )

        fun of(darkMode: Boolean): Palette = if (darkMode) DARK else LIGHT
    }
}
