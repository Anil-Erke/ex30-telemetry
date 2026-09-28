package com.example.ex30telemetry

import androidx.car.app.CarContext
import com.example.ex30telemetry.car.VehicleDataHub
import com.example.ex30telemetry.loc.JourneyLocationService
import com.example.ex30telemetry.loc.LocationTracker
import com.example.ex30telemetry.trip.TripRecorder
import com.example.ex30telemetry.trip.TripStore

/**
 * Veri katmaninin sahibi. Screen'lerden BAGIMSIZ olmak zorunda: Screen yeniden
 * yaratildiginda ya da kullanici Gecmis ekranina gecince yolculugun kaydi
 * kesilmemeli.
 *
 * Session acilirken baslatilir, Session kapaninca durdurulur.
 */
object JourneyData {

    class Layer(private val carContext: CarContext) {
        val hub = VehicleDataHub(carContext)
        val store = TripStore(carContext.filesDir)
        val recorder = TripRecorder(hub, store)
        val location = LocationTracker(carContext) { recorder.onLocation(it) }

        fun start() {
            hub.start()
            recorder.start()
            // Izin yoksa konum dinleyicisi acilmaz; izin gelince
            // onLocationPermissionGranted() ile aciliyor.
            if (Permissions.hasLocation(carContext)) startLocation()
        }

        /**
         * Konum dinleyicisi + konum tipli on plan servisi birlikte aciliyor.
         *
         * Servis burada baslatilmali: Session olusurken uygulama kesin on
         * plandadir ve Android 12+ ARKA PLANDAN on plan servisi baslatmayi
         * reddediyor. Yolculuk AKTIF olunca baslatmayi denemek yanlis olurdu —
         * surucu uygulamayi acip haritaya gecmis olabilir, yolculuk o sirada
         * basliyor ve cagri tam da reddedilecegi ana denk geliyor.
         */
        fun startLocation() {
            location.start()
            JourneyLocationService.start(carContext)
        }

        fun stop() {
            JourneyLocationService.stop(carContext)
            location.stop()
            recorder.stop()
            hub.stop()
        }
    }

    @Volatile
    private var instance: Layer? = null

    fun start(carContext: CarContext): Layer =
        instance ?: synchronized(this) {
            instance ?: Layer(carContext).also {
                it.start()
                instance = it
            }
        }

    fun current(): Layer? = instance

    /** Konum izni sonradan verildiginde cagrilir. */
    fun onLocationPermissionGranted() {
        instance?.startLocation()
    }

    fun stop() {
        synchronized(this) {
            instance?.stop()
            instance = null
        }
    }
}
