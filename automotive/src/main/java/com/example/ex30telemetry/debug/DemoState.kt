package com.example.ex30telemetry.debug

import android.content.Context
import android.os.SystemClock
import com.example.ex30telemetry.R
import com.example.ex30telemetry.render.LiveState
import com.example.ex30telemetry.render.WindowSetting
import com.example.ex30telemetry.screen.TripFormat
import com.example.ex30telemetry.trip.PerfKind
import kotlin.math.max
import kotlin.math.sin

/**
 * Yalnizca debug derlemesinde kullanilan sentetik canli durum.
 *
 * Iki isi var: cizim mantigini gercek veri olmadan dogrulamak ve Play icin dolu
 * ekran goruntusu uretmek (prompt.md §7.6b — `--` dolu ekran magazada kotu
 * duruyor). Rakamlar gercek bir EX30 yolculugunun makul degerleri; uygulamanin
 * kendi olcumleriyle hicbir ilgisi yok ve release derlemesine hic girmiyor.
 */
object DemoState {

    fun build(context: Context): LiveState {
        val distanceM = 42_600.0
        return LiveState(
            // Demo da secili pencereyi gostersin; aksi halde ayari degistirip
            // `cmd demo` ile bakan biri eski etiketi gorur ve ayar bozuk sanir.
            windowKm = WindowSetting.km(context),
            consumptionWindow = 17.4,
            consumptionTrip = 18.9,
            socPercent = 63.0,
            rangeKm = 214.0,
            batteryKwh = 41.7,
            usableCapacityKwh = 66.2,
            outsideTempC = 14.0,
            distanceKm = distanceM / 1000.0,
            durationSec = 2947,
            speedKmh = 78.0,
            // 42,6 km / 2947 sn = 52 km/h; pencere ortalamasi bilerek farkli ki
            // iki top ust uste binmesin ve tanitim goruntusunde ikisi de gorunsun.
            avgSpeedTripKmh = 52.0,
            avgSpeedWindowKmh = 87.0,
            altitudeM = 412.0,
            // Irtifa hep pozitif kalmali: sentetik egri eksiye dusunce ekranda
            // "-103 m" gibi anlamsiz bir deger cikiyordu.
            altitudeSeries = series(distanceM) { t ->
                320.0 + 130.0 * sin(t * Math.PI * 1.3) + 55.0 * sin(t * Math.PI * 3.7)
            },
            speedSeries = series(distanceM) { t ->
                max(0.0, 74.0 + 30.0 * sin(t * Math.PI * 2.6) + 12.0 * sin(t * Math.PI * 6.1))
            },
            altGainM = 412.0,
            altLossM = 388.0,
            climbKwh = 2.05,
            regenKwh = 1.34,
            maxSpeedKmh = 128.0,
            tripState = "AKTİF",
            // Ekran goruntusu alirken kaybolmasin diye uzun tutuldu; gercekte 5 sn.
            banner = LiveState.Banner(
                demoBanner(context),
                SystemClock.elapsedRealtime() + 10 * 60_000L,
            ),
        )
    }

    /**
     * Serit metni gercek [com.example.ex30telemetry.screen.LiveScreen] ile
     * ayni yoldan kuruluyor. Onceden sabit Turkce bir dizgeydi ve Ingilizce
     * magaza ekran goruntusunde Turkce cikiyordu (2026-09-06).
     */
    private fun demoBanner(context: Context): String {
        val kind = PerfKind.SPRINT_0_100
        val value = TripFormat.num(5.42, kind.decimals, kind.unit)
        return "${context.getString(kind.labelRes)}: $value" +
            context.getString(R.string.banner_record)
    }

    private fun series(totalM: Double, f: (Double) -> Double): List<LiveState.Sample> =
        (0..POINTS).map { i ->
            val t = i.toDouble() / POINTS
            LiveState.Sample(distanceM = t * totalM, value = f(t))
        }

    private const val POINTS = 240
}
