package com.example.ex30telemetry.screen

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import com.example.ex30telemetry.Constants
import com.example.ex30telemetry.R
import com.example.ex30telemetry.trip.RangeAuditor
import com.example.ex30telemetry.trip.Trip

/**
 * Tek bir yolculugun butun alanlari: A1 tuketim, A2 menzil sapmasi,
 * A3 seyir defteri alanlari, A4 rekorlari, A5 rakim-enerji.
 *
 * `PaneTemplate` yerine `ListTemplate`: Pane en fazla birkac satir aliyor,
 * burada on satirin uzerinde alan var ve kaydirilabilir olmasi gerekiyor.
 *
 * Olculemeyen her alan "—" ya da aciklamasiyla gorunuyor; satir gizlenmiyor ki
 * kullanici neyin neden yok oldugunu gorebilsin.
 */
class TripDetailScreen(
    carContext: CarContext,
    private val trip: Trip,
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()

        row(list, s(R.string.detail_distance_time),
            "${TripFormat.num(trip.distanceKm, 1, "km")} · ${TripFormat.duration(trip.durationSec)}",
            "${TripFormat.dateTime(trip.startEpoch)} → ${TripFormat.dateTime(trip.endEpoch)}")

        // --- A1 ---
        row(list, s(R.string.detail_consumption),
            trip.consumptionKwh100?.let { TripFormat.num(it, 1, "kWh/100 km") }
                ?: s(R.string.detail_not_measured),
            s(R.string.detail_used_regen,
                TripFormat.num(trip.energyKwh, 2, "kWh"),
                TripFormat.num(trip.regenKwh, 2, "kWh")))

        row(list, s(R.string.detail_speed),
            s(R.string.detail_speed_avg, TripFormat.num(trip.avgSpeedKmh, 0, "km/h")),
            s(R.string.detail_speed_max, TripFormat.num(trip.maxSpeedKmh, 0, "km/h")))

        // --- A2 ---
        val bias = trip.rangeBiasFactor
        row(list, s(R.string.detail_range_audit),
            bias?.let {
                "${TripFormat.num(it, 2)}× — " +
                    TripFormat.rangeVerdict(RangeAuditor.verdict(it))
            } ?: s(R.string.detail_range_needs, RangeAuditor.MIN_DISTANCE_KM.toInt()),
            s(R.string.detail_range_gauge,
                TripFormat.num(trip.rangeStart, 0),
                TripFormat.num(trip.rangeEnd, 0, "km")))

        row(list, s(R.string.detail_charge),
            "${TripFormat.pct(trip.socStart)} → ${TripFormat.pct(trip.socEnd)}",
            socDeltaText())

        row(list, s(R.string.detail_outside_temp),
            s(R.string.detail_temp_start, TripFormat.num(trip.tempStart, 0, "°C")),
            s(R.string.detail_temp_avg, TripFormat.num(trip.tempAvg, 1, "°C")))

        // --- A5 ---
        row(list, s(R.string.detail_altitude),
            "+${TripFormat.num(trip.altGainM, 0)} / −${TripFormat.num(trip.altLossM, 0, "m")}",
            s(R.string.detail_climb_potential, TripFormat.num(trip.potentialKwh, 2, "kWh")))

        val eff = TripFormat.regenEfficiency(trip)
        row(list, s(R.string.detail_regen_eff),
            eff?.let { TripFormat.pct(it * 100) }
                ?: s(R.string.detail_regen_needs, TripFormat.MIN_DESCENT_M.toInt()),
            // %100'u asabilir: rejen yalnizca inisten degil, duz yolda
            // yavaslamadan da geliyor. Bu yuzden "tahmin" diyoruz.
            s(R.string.detail_descent_potential,
                TripFormat.num(TripFormat.potentialKwh(trip.altLossM), 2, "kWh")))

        // --- A4 ---
        trip.records.forEach { r ->
            val kind = r.perfKind
            row(list,
                kind?.let { s(it.labelRes) } ?: r.kind,
                TripFormat.num(r.value, kind?.decimals ?: 2, r.unit),
                TripFormat.dateTime(r.epoch))
        }

        return ListTemplate.Builder()
            .setTitle(TripFormat.dateTime(trip.startEpoch))
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }

    /**
     * SoC farkini kWh'ye cevirip gosterir — guc integraliyle karsilastirmak
     * icin. Cozunurlugu %1 = 0,66 kWh oldugu icin kaba bir dogrulama; kesin
     * olcum guc integralinden geliyor (Faz 0 karar kurali).
     */
    private fun socDeltaText(): String {
        val start = trip.socStart
        val end = trip.socEnd
        if (start == null || end == null) return s(R.string.detail_no_charge)
        val deltaPct = start - end
        val kwh = deltaPct / 100.0 * Constants.FALLBACK_BATTERY_KWH
        // Cozunurluk de bicimlenmeli: Turkcede "0,66", Ingilizcede "0.66".
        val resolution = TripFormat.num(Constants.FALLBACK_BATTERY_KWH / 100.0, 2)
        return s(R.string.detail_soc_delta, TripFormat.num(kwh, 2, "kWh"), resolution)
    }

    private fun s(id: Int, vararg args: Any): String = carContext.getString(id, *args)

    private fun row(list: ItemList.Builder, title: String, line1: String, line2: String) {
        list.addItem(
            Row.Builder()
                .setTitle(title)
                .addText(line1)
                .addText(line2)
                .build()
        )
    }
}
