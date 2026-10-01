package com.example.ex30telemetry.screen

import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SectionedItemList
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.ex30telemetry.BuildConfig
import com.example.ex30telemetry.Constants
import com.example.ex30telemetry.JourneyData
import com.example.ex30telemetry.JourneyService
import com.example.ex30telemetry.PermissionActivity
import com.example.ex30telemetry.Permissions
import com.example.ex30telemetry.R
import com.example.ex30telemetry.calib.Calibration
import com.example.ex30telemetry.calib.CalibrationLogger
import com.example.ex30telemetry.calib.DataExporter
import com.example.ex30telemetry.calib.DriveUploader
import com.example.ex30telemetry.google.GoogleAuth
import com.example.ex30telemetry.render.ThemeSetting
import com.example.ex30telemetry.render.WindowSetting
import com.example.ex30telemetry.sync.TripSync
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Araç verileri ekrani: canli property degerleri + olcum gunlugunun durumu.
 *
 * Faz 0.da bu ekran salt kalibrasyon ciktisiydi ("§3.teki bes sorunun cevabi").
 * Sorular cevaplandi; ekran artik surucunun okuyabilecegi bir veri sayfasi.
 * Olculmus ayrintilar (ornekleme hizi, adim buyuklugu) her satirin ALT metninde
 * duruyor: kalibrasyon degeri kayboldu degil, one cikmiyor.
 *
 * Ayrica olcumun araçtan cikis yolu: `calib.csv` Play Dahili Test ile dagitilan
 * bir uygulamadan `adb` ile cekilemiyor, "Disa aktar" bu yuzden burada.
 */
class CalibrationScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {

    private val handler = Handler(Looper.getMainLooper())

    /** Drive yuklemesi surerken true; yalnizca ana thread'den okunup yaziliyor. */
    private var uploading = false

    private val tick = object : Runnable {
        override fun run() {
            invalidate()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    init {
        lifecycle.addObserver(this)
    }

    override fun onResume(owner: LifecycleOwner) {
        handler.postDelayed(tick, REFRESH_MS)
    }

    override fun onPause(owner: LifecycleOwner) {
        handler.removeCallbacks(tick)
    }

    /**
     * Satirlar bes bolume ayriliyor (2026-09-29). Sira "ekrana neden
     * gelinir" sorusuna gore:
     *
     *  1. **Kayit ve yukleme** — kurulum (Google hesabi, otomatik baslatma) ve
     *     "yolculuklarim gidiyor mu" sorusu. Kullanicinin bu ekranda en cok
     *     DOKUNDUGU satirlar; canli degerler zaten surus ekraninda (LiveScreen).
     *  2. **Yolculuk** — acik yolculugun durumu ve dogrulugu.
     *  3. **Araç verileri** — ham degerler ve olculmus ornekleme hizlari.
     *  4. **Ayarlar**
     *  5. **Tanilama** — sonda, gunluk dosyasi, surum.
     *
     * Surus sirasinda host listeyi birkac satira kirpiyor (§4d); ilk bolum
     * hesap/yukleme durumunu gosterdigi icin kirpilmis hali de anlamli kaliyor.
     */
    override fun onGetTemplate(): Template {
        val logger = Calibration.current()
        val rows = LinkedHashMap<RowKey, Row>()
        rows[RowKey.GOOGLE] = googleRow()
        rows[RowKey.DRIVE] = driveRow()
        rows[RowKey.PROBE] = probeRow()
        rows[RowKey.VERSION] = versionRow()
        if (logger == null) {
            rows[RowKey.NO_DATA] = Row.Builder()
                .setTitle(s(R.string.calib_not_started))
                .addText(s(R.string.calib_no_car_service))
                .build()
        } else {
            rows.putAll(buildRows(logger.report()))
        }

        val template = ListTemplate.Builder()
            .setTitle(s(R.string.calib_title))
            .setHeaderAction(Action.BACK)
        for ((header, keys) in SECTIONS) {
            val items = keys.mapNotNull { rows[it] }
            if (items.isEmpty()) continue
            val list = ItemList.Builder().apply { items.forEach { addItem(it) } }.build()
            template.addSectionedList(SectionedItemList.create(list, s(header)))
        }

        return template
            // DIKKAT: ListTemplate'in aksiyon cubugu BASLIKLI YALNIZCA BIR
            // aksiyona izin veriyor. Ikincisini eklemek host'ta
            // "Action list exceeded max number of 1 actions with custom titles"
            // hatasi firlatiyor ve uygulama COKUYOR — sessizce dusurmuyor.
            // (NavigationTemplate daha genis; LiveScreen'de dort basliklı
            // aksiyon sorunsuz calisiyor.)
            //
            // "Yenile" kaldirildi: bu ekran zaten REFRESH_MS'de bir kendini
            // yeniliyor, dugme gereksizdi.
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle(s(R.string.calib_export))
                            .setOnClickListener { export() }
                            .build()
                    )
                    .build()
            )
            .build()
    }

    /**
     * Google hesabi — yolculuklarin gidecegi Drive'in sahibi. Bagli degilse
     * otomatik yukleme de "Drive'a aktar" da calismiyor. Baglama yalnizca park
     * halinde anlamli (kod telefondan giriliyor); surus sirasinda host
     * dokunmayi zaten kisitliyor.
     */
    private fun googleRow(): Row = Row.Builder()
        .setTitle(s(R.string.google_account))
        .addText(
            when {
                !GoogleAuth.isConfigured() -> s(R.string.google_not_configured)
                GoogleAuth.isLinked(carContext) ->
                    s(R.string.google_linked, GoogleAuth.email(carContext) ?: "?")
                else -> s(R.string.google_not_linked)
            }
        )
        .setOnClickListener { openGoogleAccount() }
        .build()

    /**
     * "Drive'a aktar" — ActionStrip'te DEGIL, satir olarak.
     *
     * Neden: ListTemplate'in aksiyon cubugu BASLIKLI tek aksiyona izin
     * veriyor, o yuva da "Dışa aktar"in (onGetTemplate'teki setActionStrip
     * notu). Ikinci basliklı aksiyon eklemek host'ta cokme uretiyor, sessizce
     * dusurmuyor. Tema ayari da ayni sebeple satir.
     *
     * Yukleme ARKA THREAD'de; bu satira dokunmak ekrani kilitlemiyor.
     */
    private fun driveRow(): Row = Row.Builder()
        .setTitle(s(R.string.calib_drive))
        .addText(
            when {
                uploading -> s(R.string.calib_drive_busy)
                !DriveUploader.isReady(carContext) -> s(R.string.calib_drive_off)
                else -> s(R.string.calib_drive_hint)
            }
        )
        .setOnClickListener { uploadToDrive() }
        .build()

    /** Sonda arka thread'de calisiyor, ekrani kilitlemiyor. */
    private fun probeRow(): Row = Row.Builder()
        .setTitle(s(R.string.calib_probe))
        .addText(s(R.string.calib_probe_hint))
        .setOnClickListener { openProbe() }
        .build()

    /**
     * Surum satiri. 9 Eylul 2026'da araçta ESKI surum calisirken yeni AAB
     * yuklenmis saniliyordu ve bunu anlamanin hicbir yolu yoktu; ekran
     * goruntusunden geriye dogru kod okumak gerekti. Bir daha olmasin.
     */
    private fun versionRow(): Row = Row.Builder()
        .setTitle(s(R.string.calib_version))
        .addText("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        .build()

    /**
     * "Her zaman" konum izni istegi. Normal izinler zaten tam oldugu icin
     * [PermissionActivity] dogrudan arka plan asamasina geciyor.
     * Acilamazsa nedeni ekrana basiliyor (araçta logcat yok, bkz. PermissionScreen).
     */
    private fun requestAutoStart() {
        val intent = Intent(carContext, PermissionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val r = runCatching { carContext.startActivity(intent) }
            .recoverCatching { carContext.applicationContext.startActivity(intent) }
        r.exceptionOrNull()?.let { e ->
            CarToast.makeText(
                carContext,
                s(R.string.perm_launch_error, "${e.javaClass.simpleName}: ${e.message.orEmpty().take(90)}"),
                CarToast.LENGTH_LONG,
            ).show()
        }
    }

    /** Bagli degilse baglama ekrani, bagliysa "baglantiyi kes" onayi. */
    private fun openGoogleAccount() {
        if (!GoogleAuth.isConfigured()) {
            CarToast.makeText(carContext, s(R.string.google_not_configured), CarToast.LENGTH_LONG).show()
            return
        }
        val next = if (GoogleAuth.isLinked(carContext)) GoogleUnlinkScreen(carContext)
        else GoogleLinkScreen(carContext)
        carContext.getCarService(ScreenManager::class.java).push(next)
    }

    private fun openProbe() {
        carContext.getCarService(ScreenManager::class.java).push(ProbeScreen(carContext))
    }

    /**
     * Kayit dosyalarini paylasilan indirilenler klasorune kopyalar; boylece bir
     * dosya yoneticisiyle araçtan cikarilabiliyorlar. `filesDir` uygulamaya
     * ozel ve araçta `adb` yok.
     */
    private fun export() {
        val result = DataExporter.exportAll(carContext)
        CarToast.makeText(carContext, result.summary(carContext), CarToast.LENGTH_LONG).show()
        // Aktarimdan sonra sistemin İndirilenler ekranini ac: kapsamli depolama
        // yuzunden dosyalari baska bir dosya yoneticisi goremiyor, araçta
        // gorebilecek tek arayuz bu. Surerken sistem acmayi engelliyor —
        // ActionStrip'e zaten arac dururken dokunuluyor (§7.1).
        if (result.exported.isNotEmpty()) DataExporter.openDownloads(carContext)
        invalidate()
    }

    /**
     * Ayni dosyalari kullanicinin Google Drive klasorune yukler (§11 disa
     * aktarma yollarinin ucuncusu; ayrintili gerekce [DriveUploader]'da).
     *
     * **Ag cagrisi ANA THREAD'DE OLAMAZ** — `NetworkOnMainThreadException` ve
     * ANR demek. [export] senkron kalabiliyor cunku o yalnizca dosya kopyaliyor.
     *
     * Sablonda ilerleme cubugu yok ve ekran zaten REFRESH_MS'de bir kendini
     * yeniliyor: baslarken bir toast + satirda "yukleniyor…", bitince sonuc
     * toast'i.
     */
    private fun uploadToDrive() {
        // Cift dokunus iki yukleme baslatmasin; ikincisi ayni dosyayi ayni
        // anda ezmeye calisirdi.
        if (uploading) return
        if (!DriveUploader.isReady(carContext)) {
            CarToast.makeText(
                carContext, s(R.string.drive_not_configured), CarToast.LENGTH_LONG
            ).show()
            return
        }

        uploading = true
        invalidate()
        CarToast.makeText(carContext, s(R.string.drive_busy), CarToast.LENGTH_LONG).show()

        // Elle aktarim otomatik kuyrugu da dürtsün: ag yeni geldiyse ya da
        // geri cekilme suresi uzunsa bekleyen yolculuklar hemen gitsin.
        JourneyData.current()?.sync?.kick(TripSync.REASON_MANUAL)

        Thread({
            val result = DriveUploader.uploadAll(carContext)
            handler.post {
                uploading = false
                CarToast.makeText(
                    carContext, result.summary(carContext), CarToast.LENGTH_LONG
                ).show()
                invalidate()
            }
        }, "DriveUpload").start()
    }

    /**
     * Ekran icerigi. Sira "surucu neyi merak eder" diye kuruldu: once konum,
     * sonra enerji, en sonda gunluk/tanilama.
     *
     * **Kontak ve park freni satirlari kaldirildi:** Faz 0'da bu property'lerin
     * CONTINUOUS aboneligi kabul edip etmedigi olculuyordu; cevap alindi
     * (ikisi de ON_CHANGE davraniyor) ve durum makinesi disinda bir islevleri
     * yok. Surucuye vitesin P'de oldugunu soylemek gostergenin isi.
     *
     * **Hiz, vites, dis sicaklik, kalan menzil ve batarya satirlari da
     * kaldirildi (2026-09-30):** hepsini arac kendi gostergesinde zaten
     * gosteriyor; ekran olcum ekrani olmaktan cikip ayarlar ekranina dondu.
     */
    private fun buildRows(r: CalibrationLogger.Report): Map<RowKey, Row> {
        val rows = LinkedHashMap<RowKey, Row>()
        val byName = r.rows.associateBy { it.name }
        val hub = JourneyData.current()?.hub
        val snap = hub?.snapshot
        val none = TripFormat.NONE

        r.streamError?.let {
            rows[RowKey.NO_DATA] = Row.Builder()
                .setTitle(s(R.string.calib_no_data))
                .addText(it)
                .build()
        }

        // --- Konum ---
        // Enlem/boylam kadar degerli olan "fix kac saniyeliktir": uygulama arka
        // plandayken Android konum guncellemelerini seyreltiyor (olculdu: on
        // planda 1 Hz, arka planda dakikada ~1 fix). Yas buyuyorsa mesafe ham
        // hiz integralinden yurutuluyor demektir.
        val fix = JourneyData.current()?.location?.lastFix
        rows[RowKey.LOCATION] = if (fix == null) {
            Row.Builder()
                .setTitle(s(R.string.calib_location))
                .addText(s(R.string.calib_no_fix))
                .addText(s(R.string.calib_location_sub))
                .build()
        } else {
            val ageSec = (System.currentTimeMillis() - fix.time) / 1000.0
            Row.Builder()
                .setTitle(s(R.string.calib_location))
                .addText("${num(fix.latitude, 5)}  ·  ${num(fix.longitude, 5)}")
                .addText(
                    buildString {
                        append(
                            if (fix.hasAltitude()) "${num(fix.altitude, 0)} m"
                            else s(R.string.calib_no_altitude)
                        )
                        if (fix.hasAccuracy()) {
                            append(" " + s(R.string.calib_accuracy, num(fix.accuracy.toDouble(), 0)))
                        }
                        append(" " + s(R.string.calib_fix_age, num(ageSec, 0)))
                    }
                )
                .build()
        }

        // --- Arka plan konumu ---
        // Bu satir olmadan duzeltmenin araçta calisip calismadigi anlasilamaz:
        // araçta logcat yok, tek gorunur kanit burasi. Servis ayaktaysa Android
        // konum kisitini uygulamiyor demektir (bkz. JourneyService).
        rows[RowKey.BG_LOCATION] = Row.Builder()
            .setTitle(s(R.string.calib_bg_location))
            .addText(
                when {
                    !Permissions.hasLocation(carContext) -> s(R.string.calib_bg_denied)
                    JourneyService.running -> s(R.string.calib_bg_on)
                    else -> s(R.string.calib_bg_off)
                }
            )
            .addText(s(R.string.calib_bg_note))
            .build()

        // --- Otomatik baslatma ---
        // Arac acilinca, uygulama acilmadan kayit. Tek gorunur kanit burasi:
        // son baslatmanin NEDENI "açılış" ise alici calismis, "uygulama" ise
        // servis ancak uygulama acilinca kalkmis demektir. Izin eksikse satira
        // dokunmak "Her zaman" konum izni istegini aciyor.
        val autoOk = Permissions.canAutoStart(carContext)
        rows[RowKey.AUTO_START] = Row.Builder()
            .setTitle(s(R.string.calib_auto))
            .addText(if (autoOk) s(R.string.calib_auto_on) else s(R.string.calib_auto_off))
            .addText(
                JourneyService.lastStart?.let { st ->
                    s(
                        if (st.ok) R.string.calib_auto_last else R.string.calib_auto_last_failed,
                        reasonLabel(st.reason),
                        SimpleDateFormat("dd.MM HH:mm", Locale.ROOT).format(Date(st.atEpoch)),
                    )
                } ?: s(R.string.calib_auto_never)
            )
            .apply { if (!autoOk) setOnClickListener { requestAutoStart() } }
            .build()

        // --- GPS izi ---
        JourneyData.current()?.recorder?.track?.let { tr ->
            rows[RowKey.TRACK] = Row.Builder()
                .setTitle(s(R.string.calib_track))
                .addText(
                    if (tr.openStartEpoch != null) s(R.string.calib_track_open, tr.rows)
                    else s(R.string.calib_track_idle)
                )
                .addText(s(R.string.calib_track_saved, tr.savedTracks().size))
                .build()
        }

        // --- Otomatik yukleme ---
        // Kuyruk ve son turun sonucu. Araçta logcat yok: "yolculuk Drive'a
        // gitti mi, gitmediyse neden" sorusunun tek cevabi bu satir.
        JourneyData.current()?.sync?.let { sync ->
            val pending = sync.pendingCount()
            val red = sync.outbox.rejectedCount()
            rows[RowKey.SYNC] = Row.Builder()
                .setTitle(s(R.string.calib_sync))
                .addText(
                    buildString {
                        append(
                            when {
                                !DriveUploader.isReady(carContext) -> s(R.string.calib_drive_off)
                                sync.busy -> s(R.string.calib_sync_busy, pending)
                                pending > 0 -> s(R.string.calib_sync_pending, pending)
                                else -> s(R.string.calib_sync_empty)
                            }
                        )
                        if (red > 0) append(" · " + s(R.string.calib_sync_rejected, red))
                    }
                )
                .addText(
                    sync.lastStatus?.let { st ->
                        val at = SimpleDateFormat("dd.MM HH:mm", Locale.ROOT).format(Date(st.atEpoch))
                        val err = st.outcome.error
                        if (err == null) s(R.string.calib_sync_last_ok, at, st.outcome.uploaded)
                        else s(R.string.calib_sync_last_error, at, err.take(70))
                    } ?: s(R.string.calib_sync_never)
                )
                .build()
        }

        // --- Anlik guc ---
        // Isaret Faz 0'da olculdu: pozitif = tuketim, negatif = rejen. Property
        // adi ("charge rate") bunun tersini cagristiriyor.
        byName["EV_BATTERY_INSTANTANEOUS_CHARGE_RATE"]?.let { p ->
            rows[RowKey.POWER] = Row.Builder()
                .setTitle(s(R.string.calib_power))
                .addText(
                    snap?.powerKw?.let {
                        s(
                            R.string.calib_power_value,
                            num(it, 1),
                            if (it >= 0) s(R.string.calib_power_draw) else s(R.string.calib_power_regen),
                        )
                    } ?: none
                )
                .addText(s(R.string.calib_range_text, p.rangeText ?: none))
                .build()
        }

        // --- Mesafe: GPS'e karsi tekerlek ---
        // Bu satirin varlik sebebi: odometre ucuncu partiye kapali oldugu icin
        // GPS mesafesinin hatasini simdiye kadar hic olcememistik. WHEEL_TICK
        // bagimsiz bir kaynak (22 mm/tick, yalnizca CAR_SPEED istiyor) ve
        // uygulamanin butun tuketim hesabi mesafeye bolundugu icin bu oran
        // uretilen her metrigin dogrulugunu belirliyor.
        JourneyData.current()?.recorder?.live?.let { acc ->
            // Gosterilen deger DUZELTILMIS (Constants.WHEEL_TICK_SCALE); kayda
            // ham deger giriyor ki sabit degisirse gecmis yeniden turetilebilsin.
            val wheelKm = acc.wheelDistanceCalibratedKm
            val bias = acc.gpsVsWheel
            rows[RowKey.WHEEL] = Row.Builder()
                .setTitle(s(R.string.calib_wheel))
                .addText(
                    if (wheelKm == null) s(R.string.calib_wheel_waiting)
                    else s(R.string.calib_wheel_values, num(acc.distanceKm, 2), num(wheelKm, 2))
                )
                .addText(
                    when {
                        bias == null -> s(R.string.calib_wheel_short)
                        acc.gpsHealthy == false ->
                            s(R.string.calib_wheel_unreliable, num((bias - 1.0) * 100, 1))
                        else -> s(R.string.calib_wheel_bias, num((bias - 1.0) * 100, 1))
                    }
                )
                .build()
        }

        // --- Yolculuk ---
        JourneyData.current()?.recorder?.let { rec ->
            val acc = rec.live
            rows[RowKey.TRIP] = Row.Builder()
                .setTitle(s(R.string.calib_trip, stateLabel(rec.state.name)))
                .addText(
                    acc?.let {
                        s(R.string.calib_trip_values, num(it.distanceKm, 2), it.durationSec / 60)
                    } ?: s(R.string.calib_no_open_trip)
                )
                .addText(
                    acc?.let {
                        val br = it.bridgedFraction
                        if (br > 0.01) s(R.string.calib_trip_bridged, num(br * 100, 0))
                        else s(R.string.calib_trip_from_location)
                    } ?: s(R.string.calib_trip_starts)
                )
                .build()
        }

        // --- Enerji capraz kontrolu: guc integrali ↔ batarya farki ---
        rows[RowKey.ENERGY_CHECK] = Row.Builder()
            .setTitle(s(R.string.calib_energy_check))
            .addText(
                s(
                    R.string.calib_energy_values,
                    r.powerIntegralAsMilliWattKwh?.let { "${num(it, 3)} kWh" } ?: none,
                    r.batteryDeltaKwh?.let { "${num(it, 3)} kWh" } ?: none,
                )
            )
            .addText(r.unitVerdict?.let { s(it) } ?: s(R.string.calib_energy_waiting))
            .build()

        // --- Kayan pencere ---
        // Grafiklerin x ekseni, buyuk tuketim sayisi ve ortalama hiz topu icin
        // TEK bir pencere: ucu ayri ayri ayarlanabilseydi ekrandaki uc sayi
        // farkli mesafelere ait olur ve birbirleriyle karsilastirilamazdi.
        rows[RowKey.WINDOW] = Row.Builder()
            .setTitle(s(R.string.calib_window))
            .addText(s(R.string.calib_window_value, WindowSetting.km(carContext).toInt()))
            .addText(s(R.string.calib_window_sub))
            .setOnClickListener {
                WindowSetting.next(carContext)
                invalidate()
            }
            .build()

        // --- Gorunum ---
        // Uygulamanin tek ayari; ActionStrip'te bos yuva yok (ListTemplate
        // BASLIKLI tek aksiyona izin veriyor, o da "Dışa aktar"), bu yuzden
        // dokunulabilir bir satir olarak duruyor.
        rows[RowKey.THEME] = Row.Builder()
            .setTitle(s(R.string.calib_theme))
            .addText(s(ThemeSetting.mode(carContext).labelRes))
            .addText(
                s(
                    R.string.calib_theme_sub,
                    s(ThemeSetting.source(carContext, snap?.nightMode).labelRes),
                )
            )
            .setOnClickListener {
                ThemeSetting.next(carContext)
                invalidate()
            }
            .build()

        // --- Gunluk dosyasi ---
        // Doluluk EKRANDA gorunmeli: onceki surumde dosya 8 MiB'ye carpip sessizce
        // yazmayi birakmisti; alti gun fark edilmedi ve arka arkaya iki disa
        // aktarim birebir ayni dosyayi verdi.
        rows[RowKey.LOG] = Row.Builder()
            .setTitle(if (r.csvFull) s(R.string.calib_log_full) else s(R.string.calib_log))
            .addText(
                s(
                    R.string.calib_log_size,
                    num(r.csvBytes / 1048576.0, 2),
                    r.csvMaxBytes / 1048576,
                    if (r.csvRotations > 0) " " + s(R.string.calib_log_rotations, r.csvRotations) else "",
                )
            )
            .addText(
                if (r.csvFull) s(R.string.calib_log_stuck) else s(R.string.calib_log_rotates)
            )
            .build()

        // Esigin altinda kaldigi icin ATILAN son yolculuk. 31 Agustos'ta 45 km'lik
        // bir surus sessizce atildi ve yalnizca logcat'e yazildi; araçta logcat
        // okunamadigi icin gunler sonra fark edildi. Artik burada duruyor.
        JourneyData.current()?.recorder?.skipped?.let { sk ->
            rows[RowKey.SKIPPED] = Row.Builder()
                .setTitle(s(R.string.calib_trip_skipped))
                .addText(
                    s(
                        R.string.calib_skip_values,
                        num(sk.distanceM, 0),
                        sk.durationSec,
                        Constants.MIN_TRIP_DISTANCE_M.toInt(),
                        Constants.MIN_TRIP_DURATION_SEC,
                    )
                )
                .addText(s(R.string.calib_skip_note))
                .build()
        }

        return rows
    }

    /** Servis baslatma nedenleri gunluge Turkce sabit olarak yaziliyor; ekranda dile bagli. */
    private fun reasonLabel(reason: String): String = when (reason) {
        JourneyService.REASON_BOOT -> s(R.string.svc_reason_boot)
        JourneyService.REASON_UPDATE -> s(R.string.svc_reason_update)
        JourneyService.REASON_APP -> s(R.string.svc_reason_app)
        JourneyService.REASON_PERMISSION -> s(R.string.svc_reason_permission)
        JourneyService.REASON_RESTART -> s(R.string.svc_reason_restart)
        else -> reason
    }

    /** Durum makinesi adlari Turkce sabitler; ekranda gosterilen etiket dile bagli. */
    private fun stateLabel(name: String): String = when (name) {
        "AKTİF" -> s(R.string.state_active)
        "HAZIR" -> s(R.string.state_ready)
        "KAPANIYOR" -> s(R.string.state_closing)
        else -> s(R.string.state_idle)
    }

    private fun s(id: Int, vararg args: Any): String = carContext.getString(id, *args)

    /** Ondalik ayraci dile gore degisiyor; ortak bicimleyiciden geciyor. */
    private fun num(v: Double, decimals: Int): String = TripFormat.num(v, decimals)

    /** Ekrandaki her satirin kimligi — bolumler bu anahtarlarla kuruluyor. */
    private enum class RowKey {
        GOOGLE, SYNC, AUTO_START, BG_LOCATION, TRACK, DRIVE,
        TRIP, WHEEL, SKIPPED,
        NO_DATA, LOCATION, POWER, ENERGY_CHECK,
        WINDOW, THEME,
        PROBE, LOG, VERSION,
    }

    companion object {
        private const val REFRESH_MS = 2000L

        /** Bolum basligi → icindeki satirlar, ekrandaki sirayla (onGetTemplate notu). */
        private val SECTIONS = listOf(
            R.string.calib_sec_recording to listOf(
                RowKey.GOOGLE, RowKey.SYNC, RowKey.AUTO_START,
                RowKey.BG_LOCATION, RowKey.TRACK, RowKey.DRIVE,
            ),
            R.string.calib_sec_trip to listOf(RowKey.TRIP, RowKey.WHEEL, RowKey.SKIPPED),
            R.string.calib_sec_vehicle to listOf(
                RowKey.NO_DATA, RowKey.LOCATION, RowKey.POWER, RowKey.ENERGY_CHECK,
            ),
            R.string.calib_sec_settings to listOf(RowKey.WINDOW, RowKey.THEME),
            R.string.calib_sec_diagnostics to listOf(RowKey.PROBE, RowKey.LOG, RowKey.VERSION),
        )
    }
}
