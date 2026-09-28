package com.example.ex30telemetry.trip

import kotlin.math.abs

/**
 * A2 — menzil tahmini denetleyicisi.
 *
 * Gosterge menzilinin kac km dustugunu gercekte gidilen mesafeyle karsilastirir.
 * Oran 1,0 ise gosterge tuttu; 1,0'in ustu gostergenin **iyimser** oldugunu
 * (vaat ettiginden azini verdigini), altinda kalmasi **kotumser** oldugunu
 * gosterir.
 *
 * Esik neden 5 km: `RANGE_REMAINING` gercek EX30'da **1 km adimlarla**
 * degisiyor (Faz 0'da olculdu). 2 km'lik bir yolculukta tek bir adim orani
 * %50 saptirir; 5 km'de tek adimin etkisi %20'ye, 20 km'de %5'e iner.
 *
 * **Bu sinif metin uretmiyor.** [verdict] bir karar tipi donduruyor, insan
 * diline cevirmeyi [com.example.ex30telemetry.screen.TripFormat] yapiyor.
 * Sebep: uygulama iki dilli (values/ + values-tr/) ve saf hesap sinifinin
 * `Context`e bagimli olmasi hem test edilemez hem yanlis katman olurdu.
 */
object RangeAuditor {

    /** Bu mesafenin altinda oran hesaplanmiyor — 1 km'lik adim orani bozuyor. */
    const val MIN_DISTANCE_KM = 5.0

    /** Bu bandin icinde gosterge "tutarli" sayiliyor. */
    private const val ACCURATE_BAND = 0.05

    /** Gostergenin sapma karari. Yuzde degerleri 0..∞, isaretsiz. */
    sealed interface Verdict {
        /** Sapma [ACCURATE_BAND] icinde. */
        data object Accurate : Verdict

        /** Gosterge vaat ettiginden azini veriyor. */
        data class Optimistic(val percent: Double) : Verdict

        /** Gosterge vaat ettiginden fazlasini veriyor. */
        data class Pessimistic(val percent: Double) : Verdict
    }

    /**
     * @return gosterge menzil dususu ÷ gercek mesafe, ya da veri yetersizse null
     */
    fun biasFactor(rangeStartKm: Double?, rangeEndKm: Double?, distanceKm: Double): Double? {
        val start = rangeStartKm ?: return null
        val end = rangeEndKm ?: return null
        if (distanceKm < MIN_DISTANCE_KM) return null
        val drop = start - end
        // Menzil artmissa (uzun inis, yeniden hesaplama) oran anlamsiz.
        if (drop <= 0) return null
        return drop / distanceKm
    }

    /** Birden cok yolculugun mesafeye gore agirlikli ortalamasi. */
    fun averageFactor(trips: List<Trip>): Double? {
        var weighted = 0.0
        var totalKm = 0.0
        for (t in trips) {
            val f = t.rangeBiasFactor ?: continue
            weighted += f * t.distanceKm
            totalKm += t.distanceKm
        }
        return if (totalKm > 0) weighted / totalKm else null
    }

    /** Orani karara cevirir; metne cevirmek gorunum katmaninin isi. */
    fun verdict(factor: Double): Verdict = when {
        abs(factor - 1.0) <= ACCURATE_BAND -> Verdict.Accurate
        factor > 1.0 -> Verdict.Optimistic((factor - 1.0) * 100)
        else -> Verdict.Pessimistic((1.0 - factor) * 100)
    }
}
