package com.example.ex30telemetry.trip

/**
 * Kaydedilmis yolculuklardan turetilen kumulatif istatistikler (Rekorlar
 * ekraninin alt bolumu).
 *
 * Ortalama tuketim **enerji agirlikli**: yolculuk basina kWh/100 km degerlerinin
 * duz ortalamasi 2 km'lik bir yolculugu 200 km'lik bir yolculukla ayni agirlikta
 * sayardi ve yaniltirdi.
 */
object TripStats {

    /** Sicaklik bantlari — soguk havada tuketim belirgin artiyor. */
    enum class Band(val label: String, val minC: Double, val maxC: Double) {
        COLD("< 5 °C", Double.NEGATIVE_INFINITY, 5.0),
        COOL("5 – 15 °C", 5.0, 15.0),
        MILD("15 – 25 °C", 15.0, 25.0),
        WARM("> 25 °C", 25.0, Double.POSITIVE_INFINITY);

        fun contains(tempC: Double) = tempC >= minC && tempC < maxC
    }

    data class BandStat(
        val band: Band,
        val tripCount: Int,
        val km: Double,
        val consumptionKwh100: Double?,
    )

    data class Summary(
        val tripCount: Int,
        val totalKm: Double,
        val totalEnergyKwh: Double,
        /** Enerji agirlikli ortalama; olculebilen yolculuklar uzerinden. */
        val avgConsumptionKwh100: Double?,
        val avgRangeBias: Double?,
        val totalRegenKwh: Double,
        val totalAltGainM: Double,
        val bands: List<BandStat>,
    )

    fun of(trips: List<Trip>): Summary {
        var km = 0.0
        var energy = 0.0
        var measuredKm = 0.0
        var regen = 0.0
        var altGain = 0.0

        for (t in trips) {
            km += t.distanceKm
            altGain += t.altGainM
            t.regenKwh?.let { regen += it }
            val e = t.energyKwh
            if (e != null && t.distanceKm > 0) {
                energy += e
                measuredKm += t.distanceKm
            }
        }

        val bands = Band.entries.map { band ->
            var bKm = 0.0
            var bEnergy = 0.0
            var bMeasuredKm = 0.0
            var count = 0
            for (t in trips) {
                val temp = t.tempAvg ?: t.tempStart ?: continue
                if (!band.contains(temp)) continue
                count++
                bKm += t.distanceKm
                val e = t.energyKwh
                if (e != null && t.distanceKm > 0) {
                    bEnergy += e
                    bMeasuredKm += t.distanceKm
                }
            }
            BandStat(
                band = band,
                tripCount = count,
                km = bKm,
                consumptionKwh100 = if (bMeasuredKm > 0) bEnergy / bMeasuredKm * 100.0 else null,
            )
        }

        return Summary(
            tripCount = trips.size,
            totalKm = km,
            totalEnergyKwh = energy,
            avgConsumptionKwh100 = if (measuredKm > 0) energy / measuredKm * 100.0 else null,
            avgRangeBias = RangeAuditor.averageFactor(trips),
            totalRegenKwh = regen,
            totalAltGainM = altGain,
            bands = bands,
        )
    }
}
