package com.example.ex30telemetry.screen

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import com.example.ex30telemetry.JourneyData
import com.example.ex30telemetry.R

/**
 * A3 — gecmis yolculuklar. Tarih + mesafe ustte, tuketim + sure altta.
 *
 * Oge sayisi host'un `ConstraintManager` sinirina gore kirpiliyor: surerken
 * host listeyi kisitliyor ve sinir asilirsa sablonu tamamen reddediyor.
 */
class TripsScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val store = JourneyData.current()?.store
        val trips = store?.trips() ?: emptyList()

        val limit = runCatching {
            carContext.getCarService(ConstraintManager::class.java)
                .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        }.getOrDefault(DEFAULT_LIMIT).coerceAtLeast(1)

        val list = ItemList.Builder()
        if (trips.isEmpty()) {
            list.setNoItemsMessage(carContext.getString(R.string.trips_empty))
        } else {
            trips.take(limit).forEach { trip ->
                list.addItem(
                    Row.Builder()
                        .setTitle(TripFormat.title(trip))
                        .addText(TripFormat.subtitle(trip))
                        .setBrowsable(true)
                        .setOnClickListener {
                            carContext.getCarService(ScreenManager::class.java)
                                .push(TripDetailScreen(carContext, trip))
                        }
                        .build()
                )
            }
        }

        val title = if (trips.size > limit) {
            carContext.getString(R.string.trips_title_limited, limit, trips.size)
        } else {
            carContext.getString(R.string.trips_title)
        }

        return ListTemplate.Builder()
            .setTitle(title)
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }

    private companion object {
        /** ConstraintManager okunamazsa guvenli taban. */
        const val DEFAULT_LIMIT = 6
    }
}
