package com.example.ex30telemetry.calib

import androidx.car.app.CarContext

/**
 * [CalibrationLogger]'i Screen'lerden bagimsiz tutan sahip. Olcumun butun
 * yolculuk boyunca surmesi gerekiyor; ekran kapaninca durursa hicbir sey
 * olculmez. Session acilirken baslatilir, kapaninca durdurulur.
 */
object Calibration {

    @Volatile
    private var logger: CalibrationLogger? = null

    fun start(carContext: CarContext): CalibrationLogger =
        logger ?: synchronized(this) {
            logger ?: CalibrationLogger(carContext).also {
                it.start()
                logger = it
            }
        }

    fun current(): CalibrationLogger? = logger

    /**
     * Izinler sonradan verildiginde cagrilir. Abonelikler `start()` aninda bir
     * kez kuruluyor; o an izin yoksa property "okunamıyor" olarak isaretlenip
     * bir daha denenmiyor. Izin gelince bastan kurmak gerekiyor.
     */
    fun restart(carContext: CarContext): CalibrationLogger {
        stop()
        return start(carContext)
    }

    fun stop() {
        synchronized(this) {
            logger?.stop()
            logger = null
        }
    }
}
