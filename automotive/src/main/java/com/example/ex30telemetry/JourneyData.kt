package com.example.ex30telemetry

import android.content.Context
import androidx.car.app.CarContext
import com.example.ex30telemetry.car.VehicleDataHub
import com.example.ex30telemetry.loc.LocationTracker
import com.example.ex30telemetry.sync.TripSync
import com.example.ex30telemetry.trip.TrackRecorder
import com.example.ex30telemetry.trip.TripRecorder
import com.example.ex30telemetry.trip.TripStore

/**
 * Veri katmaninin sahibi. Screen'lerden BAGIMSIZ olmak zorunda: Screen yeniden
 * yaratildiginda ya da kullanici Gecmis ekranina gecince yolculugun kaydi
 * kesilmemeli.
 *
 * ## Oturumdan da bagimsiz (2026-09-28)
 *
 * Onceden Session acilirken baslayip kapaninca duruyordu; uygulamayi acmadigin
 * bir surus hic kaydedilmiyordu. Artik [JourneyService] ya da acilan ilk
 * oturum baslatiyor ve oturum kapaninca DURMUYOR — surec yasadikca yasiyor.
 * Oturumun tek katkisi CarContext: Car App Library'nin SoC dinleyicisi ona
 * bagli, [attachCarContext] ile takilip [detachCarContext] ile sokuluyor.
 */
object JourneyData {

    class Layer(context: Context) {
        private val appContext: Context = context.applicationContext
        val hub = VehicleDataHub(appContext)
        val store = TripStore(appContext.filesDir)
        val recorder = TripRecorder(hub, store, TrackRecorder(appContext.filesDir))
        val location = LocationTracker(appContext) { recorder.onLocation(it) }

        /** Yolculuk bitince Drive'a otomatik yukleme (drive-sync/PROTOKOL.md §4). */
        val sync = TripSync(appContext, store, recorder.track)

        init {
            // recorder.start()'tan ONCE: acilista kurtarilan yolculuk da kuyruga girsin.
            recorder.onTripSaved = { sync.enqueue(it.startEpoch) }
        }

        /** Ana thread'de cagrilmali: konum dinleyicisi cagiranin Looper'ina baglaniyor. */
        fun start() {
            hub.start()
            recorder.start()
            // Izin yoksa konum dinleyicisi acilmaz; izin gelince katman
            // [restart] ile bastan kuruluyor.
            if (Permissions.hasLocation(appContext)) location.start()
            sync.start()
        }

        fun stop() {
            sync.stop()
            location.stop()
            recorder.stop()
            hub.stop()
        }
    }

    @Volatile
    private var instance: Layer? = null

    fun start(context: Context): Layer =
        instance ?: synchronized(this) {
            instance ?: Layer(context).also {
                it.start()
                instance = it
            }
        }

    fun current(): Layer? = instance

    /**
     * Izinler sonradan verildiginde katmani bastan kurar.
     *
     * Araç property abonelikleri `start()` aninda bir kez kuruluyor; o an
     * CAR_SPEED/CAR_ENERGY yoksa "okunamıyor" diye isaretlenip bir daha
     * denenmiyor. Katman eskiden oturumla birlikte oldugu icin bu, uygulama
     * kapatilip acilinca kendiliginden duzeliyordu; artik surec boyunca yasadigi
     * icin acikca yeniden kurulmali.
     *
     * Acik bir yolculuk varsa kaybolmaz: `live.json` ve `track-live.csv` diskte
     * kaliyor, yeni katmanin [TripRecorder] acilisi onlari kurtariyor.
     */
    fun restart(context: Context, carContext: CarContext? = null): Layer {
        synchronized(this) {
            instance?.let {
                it.hub.detachCarContext()
                it.stop()
            }
            instance = null
        }
        return start(context).also { l -> carContext?.let { l.hub.attachCarContext(it) } }
    }

    fun attachCarContext(carContext: CarContext) {
        instance?.hub?.attachCarContext(carContext)
    }

    fun detachCarContext() {
        instance?.hub?.detachCarContext()
    }
}
