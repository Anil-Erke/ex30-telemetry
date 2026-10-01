package com.example.ex30telemetry.calib

import android.content.Context
import androidx.car.app.CarContext

/**
 * [CalibrationLogger]'i Screen'lerden bagimsiz tutan sahip. Olcumun butun
 * yolculuk boyunca surmesi gerekiyor; ekran kapaninca durursa hicbir sey
 * olculmez.
 *
 * 2026-09-28'den beri oturuma da bagli degil: arac acilisinda baslayan
 * JourneyService ya da acilan ilk oturum baslatiyor, oturum kapaninca
 * DURMUYOR. Oturumun katkisi yalnizca CarContext ([attachCarContext]).
 */
object Calibration {

    @Volatile
    private var logger: CalibrationLogger? = null

    fun start(context: Context): CalibrationLogger =
        logger ?: synchronized(this) {
            logger ?: CalibrationLogger(context).also {
                it.start()
                logger = it
            }
        }

    fun current(): CalibrationLogger? = logger

    /**
     * Izinler sonradan verildiginde cagrilir. Abonelikler `start()` aninda bir
     * kez kuruluyor; o an izin yoksa property "okunamıyor" olarak isaretlenip
     * bir daha denenmiyor. Izin gelince bastan kurmak gerekiyor.
     *
     * Acik bir oturum varsa CarContext yeni gunluge de takiliyor.
     */
    fun restart(context: Context, carContext: CarContext? = null): CalibrationLogger {
        stop()
        return start(context).also { l -> carContext?.let { l.attachCarContext(it) } }
    }

    fun attachCarContext(carContext: CarContext) {
        logger?.attachCarContext(carContext)
    }

    fun detachCarContext() {
        logger?.detachCarContext()
    }

    fun stop() {
        synchronized(this) {
            logger?.stop()
            logger = null
        }
    }
}
