package com.example.ex30telemetry.debug

import com.example.ex30telemetry.Constants
import com.example.ex30telemetry.trip.PerfKind
import com.example.ex30telemetry.trip.PerfRecord
import com.example.ex30telemetry.trip.RangeAuditor
import com.example.ex30telemetry.trip.Trip
import com.example.ex30telemetry.trip.TripStore
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * Yalnizca debug: gecmis ekranini dolduracak sentetik yolculuklar (§8 `cmd seed`).
 *
 * Iki isi var: liste/detay ekranlarini gercek veri beklemeden dogrulamak ve
 * Play icin dolu ekran goruntusu uretmek (§7.6b — `—` dolu ekran magazada kotu
 * duruyor).
 *
 * Uretilen degerler gercek EX30 olcumleriyle tutarli: tuketim sicakliga gore
 * degisiyor, menzil sapmasi 1 km adimli gostergeye gore hesaplaniyor, enerji
 * mesafeyle tutarli.
 */
object SeedTrips {

    fun generate(store: TripStore, count: Int, seed: Long = 30L) {
        val rnd = Random(seed)
        val now = System.currentTimeMillis()
        val trips = ArrayList<Trip>(count)

        for (i in 0 until count) {
            // Her yolculuk bir oncekinden ~1 gun once
            val start = now - (i + 1) * DAY_MS - rnd.nextLong(0, 6 * HOUR_MS)
            val distanceKm = rnd.nextDouble(4.0, 120.0)
            val tempAvg = rnd.nextDouble(-4.0, 34.0)

            // Soguk havada tuketim artiyor; 20 °C civari en verimli bant.
            val base = 15.5 + abs(tempAvg - 20.0) * 0.28
            val consumption = base + rnd.nextDouble(-1.6, 2.4)

            val avgSpeed = rnd.nextDouble(28.0, 96.0)
            val durationSec = (distanceKm / avgSpeed * 3600).roundToLong()
            val energyKwh = consumption * distanceKm / 100.0

            val altGain = rnd.nextDouble(10.0, 620.0)
            val altLoss = altGain * rnd.nextDouble(0.75, 1.3)
            // Rejen, inisin potansiyel enerjisinin bir kismi + duz yol yavaslamasi
            val regenKwh = potentialKwh(altLoss) * rnd.nextDouble(0.45, 0.85) +
                energyKwh * rnd.nextDouble(0.02, 0.07)

            val socStart = rnd.nextDouble(45.0, 96.0).roundToLong().toDouble()
            val socEnd = (socStart - energyKwh / NOMINAL_KWH * 100.0).roundToLong().toDouble()

            // Gosterge menzili 1 km adimli; sapma katsayisi 0,9–1,5 arasi
            val rangeStart = (socStart / 100.0 * NOMINAL_KWH / consumption * 100.0).roundToLong().toDouble()
            val bias = rnd.nextDouble(0.92, 1.45)
            val rangeEnd = (rangeStart - distanceKm * bias).roundToLong().toDouble()

            val records = if (rnd.nextInt(5) == 0) {
                listOf(
                    PerfRecord.of(
                        PerfKind.SPRINT_0_100,
                        rnd.nextDouble(5.2, 6.4),
                        start + durationSec * 400,
                    )
                )
            } else {
                emptyList()
            }

            trips += Trip(
                startEpoch = start,
                endEpoch = start + durationSec * 1000,
                durationSec = durationSec,
                distanceKm = distanceKm,
                energyKwh = energyKwh,
                regenKwh = regenKwh,
                socStart = socStart,
                socEnd = socEnd,
                rangeStart = rangeStart,
                rangeEnd = rangeEnd,
                avgSpeedKmh = avgSpeed,
                maxSpeedKmh = avgSpeed + rnd.nextDouble(12.0, 45.0),
                tempStart = tempAvg + rnd.nextDouble(-2.0, 2.0),
                tempAvg = tempAvg,
                altGainM = altGain,
                altLossM = altLoss,
                potentialKwh = potentialKwh(altGain),
                consumptionKwh100 = consumption,
                rangeBiasFactor = RangeAuditor.biasFactor(rangeStart, rangeEnd, distanceKm),
                records = records,
            )
        }

        // En yeni basta olacak sekilde yaz
        store.replaceAll(trips.sortedByDescending { it.startEpoch })
    }

    /** m·g·Δh → kWh. */
    private fun potentialKwh(altM: Double): Double =
        Constants.VEHICLE_MASS_KG * Constants.GRAVITY * altM / 3_600_000.0

    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val HOUR_MS = 60L * 60 * 1000
    private const val NOMINAL_KWH = 66.0
}
