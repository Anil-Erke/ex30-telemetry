package com.example.ex30telemetry.screen

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import com.example.ex30telemetry.JourneyData
import com.example.ex30telemetry.R
import com.example.ex30telemetry.trip.RangeAuditor
import com.example.ex30telemetry.trip.TripStats

/**
 * Kumulatif istatistikler: toplamlar, ortalama tuketim, menzil goStergesinin
 * genel sapmasi ve sicaklik bantlarina gore tuketim.
 *
 * Rekorlardan AYRI bir ekran: host surerken listeyi alti ogeye kisitliyor ve
 * ikisi tek listede oldugunda istatistikler sessizce kirpiliyordu (gercek
 * araçta goruldu, 2026-08-19). Ayrildiginda ikisi de kendi siniri icinde
 * kaliyor.
 */
class StatsScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val trips = JourneyData.current()?.store?.trips() ?: emptyList()
        val stats = TripStats.of(trips)
        val list = ItemList.Builder()

        if (trips.isEmpty()) {
            list.setNoItemsMessage(carContext.getString(R.string.trips_empty))
            return build(list)
        }

        list.addItem(
            Row.Builder()
                .setTitle(carContext.getString(R.string.stats_total))
                .addText(
                    carContext.getString(
                        R.string.stats_total_trips,
                        TripFormat.num(stats.totalKm, 0, "km"),
                        stats.tripCount,
                    )
                )
                .addText(
                    carContext.getString(
                        R.string.stats_used_regen,
                        TripFormat.num(stats.totalEnergyKwh, 1, "kWh"),
                        TripFormat.num(stats.totalRegenKwh, 1, "kWh"),
                    )
                )
                .build()
        )

        list.addItem(
            Row.Builder()
                .setTitle(carContext.getString(R.string.stats_avg_consumption))
                .addText(
                    stats.avgConsumptionKwh100?.let { TripFormat.num(it, 1, "kWh/100 km") }
                        ?: carContext.getString(R.string.stats_pending)
                )
                .addText(
                    carContext.getString(
                        R.string.stats_total_climb,
                        TripFormat.num(stats.totalAltGainM, 0, "m"),
                    )
                )
                .build()
        )

        list.addItem(
            Row.Builder()
                .setTitle(carContext.getString(R.string.stats_range_gauge))
                .addText(
                    stats.avgRangeBias?.let {
                        "${TripFormat.num(it, 2)}× — " +
                            TripFormat.rangeVerdict(RangeAuditor.verdict(it))
                    } ?: carContext.getString(
                        R.string.stats_range_needs,
                        RangeAuditor.MIN_DISTANCE_KM.toInt(),
                    )
                )
                .addText(carContext.getString(R.string.stats_range_weighted))
                .build()
        )

        stats.bands.forEach { b ->
            list.addItem(
                Row.Builder()
                    // Bant etiketleri sayi ve birimden ibaret ("< 5 °C"), dile
                    // gore degismiyor; kaynak dosyasina tasinmadi.
                    .setTitle(b.band.label)
                    .addText(
                        b.consumptionKwh100?.let { TripFormat.num(it, 1, "kWh/100 km") }
                            ?: carContext.getString(R.string.stats_no_data)
                    )
                    .addText(
                        carContext.getString(
                            R.string.stats_band_trips,
                            b.tripCount,
                            TripFormat.num(b.km, 0, "km"),
                        )
                    )
                    .build()
            )
        }

        return build(list)
    }

    private fun build(list: ItemList.Builder): Template =
        ListTemplate.Builder()
            .setTitle(carContext.getString(R.string.stats_title))
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
}
