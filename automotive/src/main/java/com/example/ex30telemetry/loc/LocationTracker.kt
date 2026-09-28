package com.example.ex30telemetry.loc

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log

/**
 * `LocationManager` sarmalayicisi (EX30 Telemetry'den uyarlandi).
 *
 * Car App Library'nin `CarSensors` API'si EX30'da hic calismiyor ve odometre
 * ucuncu partiye kapali; mesafe ve irtifa yalnizca buradan geliyor
 * (prompt.md §10.3). Izin kontrolu cagiran tarafta yapilir.
 */
class LocationTracker(
    context: Context,
    private val onSample: (Location) -> Unit,
) {
    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    /**
     * Ekranda gosterilen son konum. Olcum ekrani enlem/boylam ve fix yasini
     * buradan okuyor; arka planda GPS seyreldiginde "kac saniyedir fix yok"
     * bilgisi tek basina teshis degeri tasiyor.
     */
    
    var lastFix: Location? = null
        private set

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastFix = location
            onSample(location)
        }

        @Deprecated("Deprecated in API 29")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    private var started = false

    @SuppressLint("MissingPermission")
    fun start() {
        if (started) return
        started = true
        runCatching {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                UPDATE_INTERVAL_MS,
                MIN_DISTANCE_M,
                listener,
            )
        }.onFailure { Log.w(TAG, "Konum dinleyicisi açılamadı", it) }
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { locationManager.removeUpdates(listener) }
    }

    companion object {
        private const val TAG = "JourneyLoc"
        private const val UPDATE_INTERVAL_MS = 1_000L
        private const val MIN_DISTANCE_M = 3f
    }
}
