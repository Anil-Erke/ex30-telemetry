package com.example.ex30telemetry.screen

import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.ex30telemetry.BuildConfig
import com.example.ex30telemetry.JourneyData
import com.example.ex30telemetry.R
import com.example.ex30telemetry.debug.DemoState
import com.example.ex30telemetry.debug.JourneyDebugHooks
import com.example.ex30telemetry.render.LiveState
import com.example.ex30telemetry.render.WindowSetting

/**
 * TASLAK — Surface KULLANMAYAN canli ekran.
 *
 * **Neden var:** Play incelemesi 8 Eylul 2026'da AAB 17'yi "Parked Experiences"
 * bulgusuyla geri cevirdi. Gerekce, `NavigationTemplate`'in Surface'ine kendi
 * gosterge panelimizi cizmemiz:
 *
 * > "Apps must only display experiences rendered using UX elements from the
 * >  templates provided by the Car App Library & the Media Center."
 *
 * Surface navigasyon uygulamalarinda HARITA icindir. Uyumlu olmanin yolu
 * cizimi tamamen birakip her seyi sablon bilesenleriyle gostermek.
 *
 * **Kaybedilenler:** irtifa profili, hiz profili, ortalama hiz bari ve A4 olcum
 * seridi. Sablon kumesinde grafik cizen bir bilesen yok (§4).
 *
 * **Yerlesim:** `PaneTemplate` dort satir aliyor, her satir baslik + iki metin.
 * 4x3 = 12 deger; eski ekranin SAYISAL iceriginin tamami siginiyor.
 *
 * **Olculen kisitlar (2026-09-09, emulator):**
 *  - ActionStrip **en fazla 2** aksiyon aliyor. Ucuncusu
 *    `IllegalArgumentException: Action list exceeded max number of 2 actions`
 *    firlatiyor ve uygulama COKUYOR. NavigationTemplate dort aliyordu.
 *  - Kalan iki giris noktasi Pane'in kendi aksiyonlarina tasindi; orada
 *    baslikli dugme kabul ediliyor.
 *
 * Ayni [LiveState]'ten besleniyor, boylece `cmd demo` bu ekranda da calisiyor
 * ve iki tasarim birebir ayni veriyle karsilastirilabiliyor.
 */
class LiveTemplateScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {

    @Volatile
    private var demoOverride: LiveState? = null
    private var debugHooks: JourneyDebugHooks? = null

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            invalidate()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        if (BuildConfig.DEBUG) {
            debugHooks = JourneyDebugHooks(carContext) { cmd, _ ->
                when (cmd) {
                    "demo" -> { demoOverride = DemoState.build(carContext); invalidate(); true }
                    "live" -> { demoOverride = null; invalidate(); true }
                    else -> false
                }
            }.also { it.install() }
        }
    }

    override fun onResume(owner: LifecycleOwner) {
        handler.postDelayed(tick, REFRESH_MS)
    }

    override fun onPause(owner: LifecycleOwner) {
        handler.removeCallbacks(tick)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        debugHooks?.remove()
        debugHooks = null
    }

    /** Gercek veri katmanindan anlik goruntu; `cmd demo` acilmissa sentetik olan. */
    private fun buildState(): LiveState {
        demoOverride?.let { return it }
        val layer = JourneyData.current() ?: return LiveState()
        val s = layer.hub.snapshot
        val acc = layer.recorder.live
        val windowKm = WindowSetting.km(carContext)
        val window = acc?.windowConsumptionKwh100(windowKm)
        return LiveState(
            windowKm = windowKm,
            consumptionWindow = window,
            consumptionTrip = acc?.consumptionKwh100,
            consumptionPending = when {
                window != null -> null
                acc == null -> carContext.getString(R.string.live_waiting_trip)
                else -> carContext.getString(R.string.live_calculating)
            },
            socPercent = s.socPercent,
            rangeKm = s.rangeKm,
            batteryKwh = s.batteryKwh,
            usableCapacityKwh = s.usableCapacityKwh,
            outsideTempC = s.outsideTempC,
            distanceKm = acc?.distanceKm,
            durationSec = acc?.durationSec,
            avgSpeedTripKmh = acc?.avgSpeedKmh,
            maxSpeedKmh = acc?.maxSpeedKmh?.takeIf { it > 0 },
            altGainM = acc?.altGainM,
            altLossM = acc?.altLossM,
            climbKwh = acc?.potentialKwh?.takeIf { acc.altGainM > 0 },
            regenKwh = acc?.energy?.regenKwh?.takeIf { it > 0.01 },
            gpsHealthy = acc?.gpsHealthy,
            tripState = layer.recorder.state.name,
        )
    }

    override fun onGetTemplate(): Template {
        val st = buildState()
        val none = TripFormat.NONE
        val pane = Pane.Builder()

        pane.addRow(
            row(
                R.string.detail_consumption,
                st.consumptionWindow?.let {
                    "${TripFormat.num(it, 1, "kWh/100 km")} · ${s(R.string.live_window, st.windowKm.toInt())}"
                } ?: st.consumptionPending ?: none,
                s(
                    R.string.pane_trip_avg,
                    st.consumptionTrip?.let { TripFormat.num(it, 1, "kWh/100 km") } ?: none,
                ),
            )
        )

        pane.addRow(
            row(
                R.string.calib_battery,
                "${TripFormat.pct(st.socPercent)} · " +
                    s(R.string.pane_range, st.rangeKm?.let { TripFormat.num(it, 0, "km") } ?: none),
                s(
                    R.string.pane_outside,
                    st.outsideTempC?.let { TripFormat.num(it, 0, "°C") } ?: none,
                ),
            )
        )

        pane.addRow(
            row(
                R.string.pane_trip,
                "${st.distanceKm?.let { TripFormat.num(it, 1, "km") } ?: none} · " +
                    (st.durationSec?.let { TripFormat.duration(it) } ?: none),
                s(
                    R.string.pane_speed_line,
                    st.avgSpeedTripKmh?.let { TripFormat.num(it, 0, "km/h") } ?: none,
                    st.maxSpeedKmh?.let { TripFormat.num(it, 0, "km/h") } ?: none,
                ),
            )
        )

        pane.addRow(
            row(
                R.string.detail_altitude,
                if (st.altGainM != null && st.altLossM != null) {
                    "+${TripFormat.num(st.altGainM, 0)} / −${TripFormat.num(st.altLossM, 0, "m")}"
                } else {
                    none
                },
                s(
                    R.string.pane_climb_regen,
                    st.climbKwh?.let { TripFormat.num(it, 2, "kWh") } ?: none,
                    st.regenKwh?.let { TripFormat.num(it, 2, "kWh") } ?: none,
                ),
            )
        )

        // ActionStrip'te yalnizca IKI yuva var (yukarideki not); kalan ikisi burada.
        pane.addAction(paneAction(R.string.act_new) { newTrip() })
        pane.addAction(paneAction(R.string.act_measure) { openCalibration() })

        return PaneTemplate.Builder(pane.build())
            .setTitle("${s(R.string.live_title)}  ·  ${stateLabel(st.tripState)}")
            .setHeaderAction(Action.APP_ICON)
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(icon(R.drawable.ic_history) { openTrips() })
                    .addAction(icon(R.drawable.ic_records) { openRecords() })
                    .build()
            )
            .build()
    }

    private fun row(titleRes: Int, line1: String, line2: String): Row =
        Row.Builder().setTitle(s(titleRes)).addText(line1).addText(line2).build()

    private fun stateLabel(name: String): String = when (name) {
        "AKTİF" -> s(R.string.state_active)
        "HAZIR" -> s(R.string.state_ready)
        "KAPANIYOR" -> s(R.string.state_closing)
        else -> s(R.string.state_idle)
    }

    private fun openTrips() =
        carContext.getCarService(ScreenManager::class.java).push(TripsScreen(carContext))

    private fun openRecords() =
        carContext.getCarService(ScreenManager::class.java).push(RecordsScreen(carContext))

    private fun openCalibration() =
        carContext.getCarService(ScreenManager::class.java).push(CalibrationScreen(carContext))

    private fun newTrip() {
        JourneyData.current()?.recorder?.restartTrip() ?: return
        demoOverride = null
        invalidate()
        CarToast.makeText(carContext, s(R.string.toast_new_trip), CarToast.LENGTH_SHORT).show()
    }

    /** Pane'in kendi aksiyonu: burada baslikli dugme kabul ediliyor. */
    private fun paneAction(titleRes: Int, onClick: () -> Unit): Action =
        Action.Builder().setTitle(s(titleRes)).setOnClickListener(onClick).build()

    private fun icon(res: Int, onClick: () -> Unit): Action =
        Action.Builder()
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, res)).build())
            .setOnClickListener(onClick)
            .build()

    private fun s(id: Int, vararg args: Any): String = carContext.getString(id, *args)

    companion object {
        private const val REFRESH_MS = 1000L
    }
}
