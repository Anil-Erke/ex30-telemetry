package com.example.ex30telemetry.debug

import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * Yalnizca debug: sentetik bir surus profili besler (§8 `cmd sprint`).
 *
 * Emulator `PERF_VEHICLE_SPEED`'i sabit 0 yayinliyor ve VHAL enjeksiyonu
 * user-build'de kapali (prompt.md §6.9); A4 olcumleri baska turlu
 * dogrulanamiyor.
 *
 * Profil tek seferde dort olcumu birden uretiyor:
 * kalkis → 130 km/h (0-60, 0-100, 80-120) → sabit yavaslama ile duruş (100-0).
 *
 * **Zaman damgalari sentetik** (adim sayacindan turetiliyor), gercek saatten
 * degil: amac olcum matematigini dogrulamak, Handler'in zamanlama gurultusunu
 * olcmek degil.
 */
class SprintSim(
    private val feed: (kmh: Double, tNanos: Long) -> Unit,
    private val onFinished: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var step = 0
    private var tau = 0.0
    private var baseNanos = 0L
    private var braking = false
    private var brakeStartKmh = 0.0
    private var brakeStartSec = 0.0

    private val runner = object : Runnable {
        override fun run() {
            val t = step * STEP_SEC
            val kmh = if (!braking) {
                val v = VMAX_KMH * (1.0 - exp(-t / tau))
                if (v >= TOP_KMH) {
                    braking = true
                    brakeStartKmh = v
                    brakeStartSec = t
                }
                v
            } else {
                max(0.0, brakeStartKmh - BRAKE_KMH_PER_SEC * (t - brakeStartSec))
            }

            feed(kmh, baseNanos + (t * 1_000_000_000L).toLong())
            step++

            if (braking && kmh <= 0.0) {
                Log.i(TAG, "sentetik sürüş bitti (${"%.1f".format(t)} sn)")
                onFinished()
                return
            }
            if (t > MAX_SEC) { onFinished(); return }
            handler.postDelayed(this, STEP_MS)
        }
    }

    /** [targetSeconds] saniyede tam 100 km/h'ye ulasan bir egri baslatir. */
    fun start(targetSeconds: Double) {
        stop()
        tau = -targetSeconds / ln(1.0 - 100.0 / VMAX_KMH)
        step = 0
        braking = false
        baseNanos = 1_000_000_000L
        Log.i(TAG, "sentetik sürüş: hedef $targetSeconds sn (tau=$tau)")
        handler.post(runner)
    }

    fun stop() {
        handler.removeCallbacks(runner)
    }

    private companion object {
        const val TAG = "JourneyDebug"

        /** Gercek araçtaki ornekleme hizi: 10 Hz. */
        const val STEP_MS = 100L
        const val STEP_SEC = 0.1

        /** Egrinin asimptotu — 130 km/h yakalanabilsin diye yuksek. */
        const val VMAX_KMH = 198.0

        /** Bu hiza varinca frene basilir. */
        const val TOP_KMH = 130.0

        /** Sabit yavaslama (km/h/sn) — ~9,9 m/s². */
        const val BRAKE_KMH_PER_SEC = 35.7

        const val MAX_SEC = 60.0
    }
}
