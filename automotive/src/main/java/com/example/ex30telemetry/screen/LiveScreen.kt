package com.example.ex30telemetry.screen

import android.os.Handler
import android.os.Looper
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.ex30telemetry.BuildConfig
import com.example.ex30telemetry.JourneyData
import com.example.ex30telemetry.JourneyService
import com.example.ex30telemetry.Permissions
import com.example.ex30telemetry.R
import com.example.ex30telemetry.calib.Calibration
import com.example.ex30telemetry.debug.DemoState
import com.example.ex30telemetry.debug.JourneyDebugHooks
import com.example.ex30telemetry.debug.SeedTrips
import com.example.ex30telemetry.debug.SprintSim
import com.example.ex30telemetry.render.LiveRenderer
import com.example.ex30telemetry.render.LiveState
import com.example.ex30telemetry.render.ThemeSetting
import com.example.ex30telemetry.render.WindowSetting
import com.example.ex30telemetry.trip.TripState
import com.example.ex30telemetry.sync.TripSync

/**
 * Surus ekrani: NavigationTemplate + Surface.
 *
 * Sofor surerken hicbir seye dokunmuyor; butun aksiyonlar arac dururken
 * kullanilacak varsayimiyla tasarlandi (§7.1). Surface'e dugme cizilmiyor,
 * cunku host dokunma olayini uygulamaya hic iletmiyor (prompt.md §5.4).
 */
class LiveScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {

    /** Yalnizca debug: `cmd demo` ile doldurulan sentetik durum (§7.6b). */
    @Volatile
    private var demoOverride: LiveState? = null

    private val renderer = LiveRenderer(
        context = carContext,
        stateProvider = { demoOverride ?: buildState() },
        // Araç gunduz/gece temasi degistirdiginde uygulama da degissin: koyu bir
        // yuzey gun isiginda cam yansimalarindan okunmuyor ve aracin geri
        // kalaniyla uyumsuz duruyor.
        //
        // Kaynak sirasi: aracin NIGHT_MODE property'si → host'un temasi → elle
        // secim. Tek basina `carContext.isDarkMode`'a guvenmek yetmiyordu: o
        // deger host'un uygulamaya gonderdigi yapilandirmadan geliyor ve host
        // gece bilgisini hic gondermezse sessizce "gunduz" okunuyor, yani
        // uygulama gece de acik temada kaliyor.
        darkModeProvider = {
            ThemeSetting.isDark(
                carContext,
                carNight = JourneyData.current()?.hub?.snapshot?.nightMode,
                hostDark = carContext.isDarkMode,
            )
        },
    )

    /** Yalnizca debug derlemesinde kurulur; release'te hic yaratilmaz (§6.11). */
    private var debugHooks: JourneyDebugHooks? = null
    private var sprintSim: SprintSim? = null

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            renderer.render()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(renderer)
        if (BuildConfig.DEBUG) {
            debugHooks = JourneyDebugHooks(carContext) { cmd, intent ->
                when (cmd) {
                    "demo" -> {
                        demoOverride = DemoState.build(carContext)
                        renderer.render()
                        true
                    }
                    "live" -> {
                        demoOverride = null
                        renderer.render()
                        true
                    }
                    "probe" -> {
                        com.example.ex30telemetry.calib.DataExporter.probeTargets(carContext)
                        true
                    }
                    "export" -> {
                        val r = com.example.ex30telemetry.calib.DataExporter
                            .exportAll(carContext)
                        CarToast.makeText(carContext, r.summary(carContext), CarToast.LENGTH_LONG).show()
                        true
                    }
                    "sprint" -> {
                        val target = intent.getFloatExtra("target", 5.3f).toDouble()
                        val recorder = JourneyData.current()?.recorder
                        if (recorder != null) {
                            sprintSim?.stop()
                            sprintSim = SprintSim(
                                feed = { kmh, t -> recorder.debugFeedSpeed(kmh, t) },
                                onFinished = { invalidate() },
                            ).apply { start(target) }
                        }
                        recorder != null
                    }
                    // Google baglama ekranini acar. Emulatorde `adb input tap`
                    // host satirlarina isabet etmiyor (prompt.md §6.10).
                    "google" -> {
                        carContext.getCarService(ScreenManager::class.java)
                            .push(GoogleLinkScreen(carContext))
                        true
                    }
                    // Yukleme kuyrugunu hemen dener (geri cekilmeyi beklemeden).
                    "sync" -> {
                        JourneyData.current()?.sync?.kick(TripSync.REASON_MANUAL)
                        true
                    }
                    "seed" -> {
                        val n = intent.getIntExtra("trips", 12)
                        JourneyData.current()?.store?.let { SeedTrips.generate(it, n) }
                        invalidate()
                        true
                    }
                    "state" -> {
                        val to = intent.getStringExtra("to")?.uppercase()
                        val target = TripState.entries.firstOrNull {
                            it.name == to || it.name.replace("İ", "I") == to ||
                                (to == "ACTIVE" && it == TripState.AKTİF) ||
                                (to == "IDLE" && it == TripState.BEKLEME)
                        }
                        if (target != null) {
                            JourneyData.current()?.recorder?.forceState(target)
                            invalidate()
                        }
                        target != null
                    }
                    else -> false
                }
            }.apply { install() }
        }
        if (Permissions.missing(carContext).isNotEmpty()) {
            carContext.getCarService(ScreenManager::class.java)
                .push(PermissionScreen(carContext) {
                    // Abonelikler izin verilmeden once kuruldugu icin bastan kur.
                    val app = carContext.applicationContext
                    Calibration.restart(app, carContext)
                    JourneyData.restart(app, carContext)
                    // Konum izni yeni geldiyse servis oturum acilirken
                    // baslayamamisti; uygulama su an on planda, simdi baslar.
                    JourneyService.start(carContext, JourneyService.REASON_PERMISSION)
                    invalidate()
                    // Izinler tamam; ilk kurulumsa Drive yedegini teklif et.
                    offerGoogleIfNeeded()
                })
        } else {
            offerGoogleIfNeeded()
        }
    }

    /** Bkz. [GoogleOfferScreen]: yeni kullanici baglantiyi Olcum ekraninda bulamaz. */
    private fun offerGoogleIfNeeded() {
        if (GoogleOfferScreen.shouldAsk(carContext)) {
            carContext.getCarService(ScreenManager::class.java).push(GoogleOfferScreen(carContext))
        }
    }

    override fun onResume(owner: LifecycleOwner) {
        handler.post(tick)
    }

    override fun onPause(owner: LifecycleOwner) {
        handler.removeCallbacks(tick)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        handler.removeCallbacks(tick)
        sprintSim?.stop()
        sprintSim = null
        debugHooks?.remove()
        debugHooks = null
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(null)
    }

    override fun onGetTemplate(): Template =
        NavigationTemplate.Builder()
            // Ust cubuk: basliklı dugmeler
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(action(R.string.act_history, R.drawable.ic_history) { openTrips() })
                    .addAction(action(R.string.act_records, R.drawable.ic_records) { openRecords() })
                    .addAction(action(R.string.act_new, R.drawable.ic_reset) { newTrip() })
                    // ActionStrip en fazla 4 aksiyon aliyor (§5.4); 4. yuva Olcum.
                    .addAction(action(R.string.act_measure, R.drawable.ic_calibration) { openCalibration() })
                    .build()
            )
            // Harita kontrol cubugu: ikon zorunlu, baslik kabul etmiyor, maks 4 (§5.4)
            .setMapActionStrip(
                ActionStrip.Builder()
                    .addAction(iconAction(R.drawable.ic_history) { openTrips() })
                    .addAction(iconAction(R.drawable.ic_records) { openRecords() })
                    .build()
            )
            .build()

    // --- Veri -> cizim modeli ---

    /**
     * Renderer'in okudugu anlik goruntuyu kurar. Veri yoksa alan null kaliyor;
     * renderer null'lari "—" ya da "hesaplanıyor…" olarak ciziyor, sifir degil.
     */
    private fun buildState(): LiveState {
        val layer = JourneyData.current() ?: return LiveState()
        val s = layer.hub.snapshot
        val acc = layer.recorder.live
        // Tek pencere: grafikler, buyuk tuketim sayisi ve ortalama hiz topu
        // ayni mesafeden bahsetsin diye ucu de bu degeri kullaniyor.
        val windowKm = WindowSetting.km(carContext)
        val window = acc?.windowConsumptionKwh100(windowKm)
        return LiveState(
            windowKm = windowKm,
            consumptionWindow = window,
            consumptionTrip = acc?.consumptionKwh100,
            // Yolculuk acik ama tuketim henuz guvenilir degilse sebebini yaz.
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
            speedKmh = s.speedKmh,
            avgSpeedTripKmh = acc?.avgSpeedKmh,
            gpsHealthy = acc?.gpsHealthy,
            bridgedFraction = acc?.bridgedFraction,
            avgSpeedWindowKmh = acc?.windowAvgSpeedKmh(windowKm),
            altitudeM = acc?.altitudeSnapshot()?.lastOrNull()?.value,
            altitudeSeries = acc?.altitudeWindow(windowKm)
                ?.map { LiveState.Sample(it.distanceM, it.value) } ?: emptyList(),
            speedSeries = acc?.speedWindow(windowKm)
                ?.map { LiveState.Sample(it.distanceM, it.value) } ?: emptyList(),
            altGainM = acc?.altGainM,
            altLossM = acc?.altLossM,
            climbKwh = acc?.potentialKwh?.takeIf { (acc.altGainM) > 0 },
            regenKwh = acc?.energy?.regenKwh?.takeIf { it > 0.01 },
            maxSpeedKmh = acc?.maxSpeedKmh?.takeIf { it > 0 },
            tripState = layer.recorder.state.name,
            banner = banner(layer.recorder.lastCatch),
        )
    }

    /**
     * A4 bir olcum yakalayinca 5 saniye kosede gorunen serit (§7.1).
     * Elapsed saat kullaniliyor: cihaz saati degisse bile serit takilip kalmaz.
     */
    private fun banner(c: com.example.ex30telemetry.trip.TripRecorder.Catch?): LiveState.Banner? {
        c ?: return null
        val ageMs = System.currentTimeMillis() - c.atMs
        if (ageMs > BANNER_MS) return null
        val value = TripFormat.num(c.record.value, c.kind.decimals, c.kind.unit)
        val suffix = if (c.isRecord) carContext.getString(R.string.banner_record) else ""
        return LiveState.Banner(
            text = "${carContext.getString(c.kind.labelRes)}: $value$suffix",
            untilElapsedMs = android.os.SystemClock.elapsedRealtime() + (BANNER_MS - ageMs),
        )
    }

    // --- Aksiyonlar ---

    private fun openTrips() {
        carContext.getCarService(ScreenManager::class.java).push(TripsScreen(carContext))
    }

    private fun openRecords() {
        carContext.getCarService(ScreenManager::class.java).push(RecordsScreen(carContext))
    }

    private fun openCalibration() {
        carContext.getCarService(ScreenManager::class.java).push(CalibrationScreen(carContext))
    }

    private fun newTrip() {
        val recorder = JourneyData.current()?.recorder ?: return
        recorder.restartTrip()
        demoOverride = null
        invalidate()
        CarToast.makeText(carContext, carContext.getString(R.string.toast_new_trip), CarToast.LENGTH_SHORT).show()
    }

    private fun action(titleRes: Int, iconRes: Int, onClick: () -> Unit): Action =
        Action.Builder()
            .setTitle(carContext.getString(titleRes))
            .setIcon(icon(iconRes))
            .setOnClickListener(onClick)
            .build()

    private fun iconAction(iconRes: Int, onClick: () -> Unit): Action =
        Action.Builder()
            .setIcon(icon(iconRes))
            .setOnClickListener(onClick)
            .build()

    private fun icon(res: Int): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(carContext, res)).build()

    companion object {
        /** 1 Hz yeniden cizim; olculen degerler bundan daha sik degismiyor. */
        private const val REFRESH_MS = 1000L

        /** A4 olcum seridinin ekranda kalma suresi (§7.1). */
        private const val BANNER_MS = 5_000L
    }
}
