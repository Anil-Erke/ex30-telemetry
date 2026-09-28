package com.example.ex30telemetry.screen

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import com.example.ex30telemetry.JourneyData
import com.example.ex30telemetry.R
import com.example.ex30telemetry.trip.PerfKind

/**
 * A4 rekorlari. Kumulatif istatistikler ayri ekranda ([StatsScreen]).
 *
 * **Neden ayri:** host surerken listeyi alti ogeye kisitliyor. Rekorlar ve
 * istatistikler tek listedeyken istatistikler sessizce kirpiliyordu (gercek
 * araçta goruldu). Bu ekran tam alti oge: dort rekor + istatistik baglantisi +
 * hassasiyet notu.
 *
 * Rekorlar yolculuk kaydindan ayri saklaniyor: bir 0-100 olcumu ~75 metrede
 * bitiyor, o yolculuk 500 m esigini gecmese bile rekor kaybolmamali.
 */
class RecordsScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val bests = JourneyData.current()?.store?.bestRecords() ?: emptyMap()
        val list = ItemList.Builder()

        PerfKind.entries.forEach { kind ->
            val r = bests[kind.id]
            list.addItem(
                Row.Builder()
                    .setTitle(carContext.getString(kind.labelRes))
                    .addText(
                        r?.let { TripFormat.num(it.value, kind.decimals, kind.unit) }
                            ?: carContext.getString(R.string.records_pending)
                    )
                    .addText(
                        r?.let { TripFormat.dateTime(it.epoch) }
                            ?: carContext.getString(R.string.records_auto)
                    )
                    .build()
            )
        }

        list.addItem(
            Row.Builder()
                .setTitle(carContext.getString(R.string.records_stats))
                .addText(carContext.getString(R.string.records_stats_sub))
                .setBrowsable(true)
                .setOnClickListener {
                    carContext.getCarService(ScreenManager::class.java)
                        .push(StatsScreen(carContext))
                }
                .build()
        )

        // Olcum belirsizligini gizlemiyoruz (Faz 0: damgalar sabit izgara gibi).
        list.addItem(
            Row.Builder()
                .setTitle(carContext.getString(R.string.records_accuracy))
                .addText(carContext.getString(R.string.records_accuracy_1))
                .addText(carContext.getString(R.string.records_accuracy_2))
                .build()
        )

        return ListTemplate.Builder()
            .setTitle(carContext.getString(R.string.records_title))
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }
}
