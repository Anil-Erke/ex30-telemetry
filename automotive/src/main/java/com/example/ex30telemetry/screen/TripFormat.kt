package com.example.ex30telemetry.screen

import android.content.Context
import com.example.ex30telemetry.Constants
import com.example.ex30telemetry.R
import com.example.ex30telemetry.trip.RangeAuditor
import com.example.ex30telemetry.trip.Trip
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ekranlarin ortak bicimleme yardimcilari.
 *
 * Kural: veri yoksa "—". Sifir yazip veri varmis gibi gostermiyoruz.
 *
 * **Dil ve bolge:** eskiden burada `Locale("tr")` sabit kodluydu; ondalik
 * ayraci ve ay adlari sistem dilinden bagimsiz Turkce cikiyordu. Artik
 * bicimleme aracin gecerli yerel ayarindan geliyor — Ingilizce bir arac
 * "12.5 km" gorur, Turkce bir arac "12,5 km".
 *
 * [attach] oturum baslarken bir kez cagriliyor ([com.example.ex30telemetry.JourneySession]).
 * Context saklaniyor cunku bicimleme cagrilari cok sayida ve her birine
 * parametre eklemek otuz cagri yerini degistirirdi; oturum bitince [detach]
 * ile birakiliyor.
 */
object TripFormat {

    private var ctx: Context? = null

    fun attach(context: Context) {
        ctx = context
    }

    fun detach() {
        ctx = null
        cachedFormat = null
        cachedLocale = null
    }

    /**
     * Aracin o anki dili. Surus sirasinda degisebiliyor (sistem ayari), bu
     * yuzden her cagrida okunuyor — onbelleklemek dil degisiminde eski
     * bicimde takili kalmaya yol aciyor.
     */
    private val locale: Locale
        get() = ctx?.resources?.configuration?.locales?.get(0) ?: Locale.getDefault()

    private var cachedLocale: Locale? = null
    private var cachedFormat: SimpleDateFormat? = null

    /** Tarih deseni de kaynaklardan geliyor: Turkcede "d MMM", Ingilizcede "MMM d". */
    private fun dayTime(): SimpleDateFormat {
        val l = locale
        cachedFormat?.let { if (cachedLocale == l) return it }
        val pattern = str(R.string.fmt_date_time, fallback = "d MMM · HH:mm")
        return SimpleDateFormat(pattern, l).also {
            cachedFormat = it
            cachedLocale = l
        }
    }

    /** Context yoksa (birim testleri) makul bir taban dondur; cokmesin. */
    private fun str(id: Int, fallback: String, vararg args: Any): String =
        ctx?.getString(id, *args) ?: fallback

    fun num(v: Double?, decimals: Int, unit: String = ""): String {
        if (v == null) return NONE
        val s = String.format(locale, "%.${decimals}f", v)
        return if (unit.isEmpty()) s else "$s $unit"
    }

    /**
     * Yuzde. Isaretin yeri dile gore degisiyor: Turkcede sayinin ONUNDE
     * ("%57"), Ingilizcede ARKASINDA ("57%") — bu yuzden konum koda degil
     * `fmt_percent` kaynagina yazildi.
     */
    fun pct(v: Double?, decimals: Int = 0): String {
        if (v == null) return NONE
        val n = String.format(locale, "%.${decimals}f", v)
        return str(R.string.fmt_percent, "%$n", n)
    }

    fun dateTime(epoch: Long): String = dayTime().format(Date(epoch))

    /** 1:05:09 ya da 25:19 — rakam dizisi, dile gore degismiyor. */
    fun duration(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.ROOT, "%d:%02d", m, s)
    }

    /** "25 dk" / "1 sa 12 dk" — liste satirlari icin kisa hali. */
    fun durationShort(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        return if (h > 0) str(R.string.fmt_hours_minutes, "$h sa $m dk", h, m)
        else str(R.string.fmt_minutes, "$m dk", m)
    }

    /** Menzil sapmasi karari — metne ceviri burada, hesap [RangeAuditor]'da. */
    fun rangeVerdict(v: RangeAuditor.Verdict): String = when (v) {
        is RangeAuditor.Verdict.Accurate ->
            str(R.string.range_accurate, "gösterge tutarlı")
        is RangeAuditor.Verdict.Optimistic ->
            str(R.string.range_optimistic, "%${num(v.percent, 0)} iyimser", num(v.percent, 0))
        is RangeAuditor.Verdict.Pessimistic ->
            str(R.string.range_pessimistic, "%${num(v.percent, 0)} kötümser", num(v.percent, 0))
    }

    /** Yolculuk listesi satiri: tarih + mesafe. */
    fun title(t: Trip): String = "${dateTime(t.startEpoch)} · ${num(t.distanceKm, 1, "km")}"

    /** Yolculuk listesi alt satiri: tuketim + sure. */
    fun subtitle(t: Trip): String {
        val c = t.consumptionKwh100?.let { num(it, 1, "kWh/100 km") }
            ?: str(R.string.fmt_no_consumption, "tüketim ölçülemedi")
        return "$c · ${durationShort(t.durationSec)}"
    }

    /** Potansiyel enerji: m·g·Δh → kWh. */
    fun potentialKwh(altM: Double): Double =
        Constants.VEHICLE_MASS_KG * Constants.GRAVITY * altM / 3_600_000.0

    /**
     * A5 rejen verimi tahmini: iniste geri kazanilan enerji ÷ inisin potansiyel
     * enerjisi. **Tahmindir**: rejen yalnizca inisten degil, duz yolda
     * yavaslamadan da geliyor. Bu yuzden %100'u asabilir ve ekranda "tahmin"
     * olarak etiketleniyor.
     */
    fun regenEfficiency(t: Trip): Double? {
        val regen = t.regenKwh ?: return null
        if (t.altLossM < MIN_DESCENT_M) return null
        val potential = potentialKwh(t.altLossM)
        if (potential <= 0) return null
        return regen / potential
    }

    /** Bu inis miktarinin altinda rejen verimi tahmini yapilmiyor. */
    const val MIN_DESCENT_M = 50.0

    /** Veri yok isareti. */
    const val NONE = "—"
}
