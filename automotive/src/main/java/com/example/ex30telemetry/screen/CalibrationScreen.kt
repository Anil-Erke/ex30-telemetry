package com.example.ex30telemetry.screen

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
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.ex30telemetry.BuildConfig
import com.example.ex30telemetry.Constants
import com.example.ex30telemetry.JourneyData
import com.example.ex30telemetry.Permissions
import com.example.ex30telemetry.R
import com.example.ex30telemetry.calib.Calibration
import com.example.ex30telemetry.calib.CalibrationLogger
import com.example.ex30telemetry.calib.DataExporter
import com.example.ex30telemetry.calib.DriveUploader
import com.example.ex30telemetry.car.VehicleDataHub
import com.example.ex30telemetry.loc.JourneyLocationService
import com.example.ex30telemetry.render.ThemeSetting
import com.example.ex30telemetry.render.WindowSetting

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

    override fun onGetTemplate(): Template {
        val logger = Calibration.current()
        val list = ItemList.Builder()

        // Sonda satiri EN USTE: bu ekran zaten teshis ekrani (surus ekrani
        // LiveScreen), dolayisiyla §4d'nin "surerken alti satir" kirpmasi
        // burada bir olcum satirini degil en cok ihtiyac duyulan girisi one
        // aliyor. Sonda arka thread'de calisiyor, ekrani kilitlemiyor.
        list.addItem(
            Row.Builder()
                .setTitle(s(R.string.calib_probe))
                .addText(s(R.string.calib_probe_hint))
                .setOnClickListener { openProbe() }
                .build()
        )

        // "Drive'a aktar" — ActionStrip'te DEGIL, satir olarak.
        //
        // Neden: ListTemplate'in aksiyon cubugu BASLIKLI tek aksiyona izin
        // veriyor, o yuva da "Dışa aktar"in (asagidaki setActionStrip notu).
        // Ikinci basliklı aksiyon eklemek host'ta cokme uretiyor, sessizce
        // dusurmuyor. Tema ayari da ayni sebeple satir.
        //
        // Yukleme ARKA THREAD'de; bu satira dokunmak ekrani kilitlemiyor.
        list.addItem(
            Row.Builder()
                .setTitle(s(R.string.calib_drive))
                .addText(
                    when {
                        uploading -> s(R.string.calib_drive_busy)
                        !DriveUploader.isConfigured() -> s(R.string.calib_drive_off)
                        else -> s(R.string.calib_drive_hint)
                    }
                )
                .setOnClickListener { uploadToDrive() }
                .build()
        )

        // Surum satiri. 9 Eylul 2026'da araçta ESKI surum calisirken yeni AAB
        // yuklenmis saniliyordu ve bunu anlamanin hicbir yolu yoktu; ekran
        // goruntusunden geriye dogru kod okumak gerekti. Bir daha olmasin.
        list.addItem(
            Row.Builder()
                .setTitle(s(R.string.calib_version))
                .addText("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                .build()
        )

        if (logger == null) {
            list.addItem(
                Row.Builder()
                    .setTitle(s(R.string.calib_not_started))
                    .addText(s(R.string.calib_no_car_service))
                    .build()
            )
        } else {
            buildRows(logger.report()).forEach { list.addItem(it) }
        }

        return ListTemplate.Builder()
            .setTitle(s(R.string.calib_title))
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
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
        if (!DriveUploader.isConfigured()) {
            CarToast.makeText(
                carContext, s(R.string.drive_not_configured), CarToast.LENGTH_LONG
            ).show()
            return
        }

        uploading = true
        invalidate()
        CarToast.makeText(carContext, s(R.string.drive_busy), CarToast.LENGTH_LONG).show()

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
     * Ekran icerigi. Sira "surucu neyi merak eder" diye kuruldu: once konum ve
     * hiz, sonra enerji, en sonda gunluk/tanilama.
     *
     * **Kontak ve park freni satirlari kaldirildi:** Faz 0'da bu property'lerin
     * CONTINUOUS aboneligi kabul edip etmedigi olculuyordu; cevap alindi
     * (ikisi de ON_CHANGE davraniyor) ve durum makinesi disinda bir islevleri
     * yok. Surucuye vitesin P'de oldugunu soylemek gostergenin isi.
     */
    private fun buildRows(r: CalibrationLogger.Report): List<Row> {
        val rows = mutableListOf<Row>()
        val byName = r.rows.associateBy { it.name }
        val hub = JourneyData.current()?.hub
        val snap = hub?.snapshot
        val none = TripFormat.NONE

        r.streamError?.let {
            rows += Row.Builder()
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
        rows += if (fix == null) {
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
        // konum kisitini uygulamiyor demektir (bkz. JourneyLocationService).
        rows += Row.Builder()
            .setTitle(s(R.string.calib_bg_location))
            .addText(
                when {
                    !Permissions.hasLocation(carContext) -> s(R.string.calib_bg_denied)
                    JourneyLocationService.running -> s(R.string.calib_bg_on)
                    else -> s(R.string.calib_bg_off)
                }
            )
            .addText(s(R.string.calib_bg_note))
            .build()

        // --- Hiz ---
        rows += Row.Builder()
            .setTitle(s(R.string.calib_speed))
            .addText(
                s(
                    R.string.calib_speed_values,
                    snap?.speedKmh?.let { "${num(it, 0)} km/h" } ?: none,
                    snap?.rawSpeedKmh?.let { "${num(it, 0)} km/h" } ?: none,
                )
            )
            .addText(
                byName["PERF_VEHICLE_SPEED_DISPLAY"]?.let { rateLine(it) }
                    ?: s(R.string.calib_speed_unreadable)
            )
            .build()

        // --- Batarya ---
        rows += Row.Builder()
            .setTitle(s(R.string.calib_battery))
            .addText(
                // Bolen olarak GERCEK kapasite gosteriliyor: 2026-09-09'da
                // olculdu ki EV_BATTERY_LEVEL artik SoC x GERCEK kapasite
                // (nominal degil). Uc olcumde de tam yuzde tutturdu:
                // 40418,6/66260 = %61,00 · 39756,0/66260 = %60,00.
                "${r.socLast?.let { TripFormat.pct(it) } ?: none}  ·  " +
                    "${snap?.batteryKwh?.let { "${num(it, 2)} kWh" } ?: none}  /  " +
                    (snap?.usableCapacityKwh ?: r.nominalCapacityKwh)
                        ?.let { "${num(it, 2)} kWh" }.orEmpty().ifEmpty { none }
            )
            .addText(byName["EV_BATTERY_LEVEL"]?.let { stepLine(it) } ?: s(R.string.calib_no_events))
            .build()

        // --- Menzil ---
        byName["RANGE_REMAINING"]?.let { p ->
            rows += Row.Builder()
                .setTitle(s(R.string.calib_range))
                .addText(snap?.rangeKm?.let { "${num(it, 0)} km" } ?: none)
                .addText(stepLine(p))
                .build()
        }

        // --- Anlik guc ---
        // Isaret Faz 0'da olculdu: pozitif = tuketim, negatif = rejen. Property
        // adi ("charge rate") bunun tersini cagristiriyor.
        byName["EV_BATTERY_INSTANTANEOUS_CHARGE_RATE"]?.let { p ->
            rows += Row.Builder()
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

        // --- Dis sicaklik ---
        // Ic sicaklik yok: AndroidManifest'teki nota bak — iklim izni gerekiyor
        // ve tek bir okunur satir icin surucuden istemeye degmiyor.
        rows += Row.Builder()
            .setTitle(s(R.string.calib_outside_temp))
            .addText(snap?.outsideTempC?.let { "${num(it, 1)} °C" } ?: none)
            .addText(s(R.string.calib_temp_note))
            .build()

        // --- Vites ---
        byName["GEAR_SELECTION"]?.let { p ->
            rows += Row.Builder()
                .setTitle(s(R.string.calib_gear))
                .addText(gearText(snap?.gear))
                .addText(s(R.string.calib_gear_events, p.count, p.changeCount))
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
            rows += Row.Builder()
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
            rows += Row.Builder()
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
        rows += Row.Builder()
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
        rows += Row.Builder()
            .setTitle(s(R.string.calib_window))
            .addText(s(R.string.live_window, WindowSetting.km(carContext).toInt()))
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
        rows += Row.Builder()
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
        rows += Row.Builder()
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
            rows += Row.Builder()
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

    /** Durum makinesi adlari Turkce sabitler; ekranda gosterilen etiket dile bagli. */
    private fun stateLabel(name: String): String = when (name) {
        "AKTİF" -> s(R.string.state_active)
        "HAZIR" -> s(R.string.state_ready)
        "KAPANIYOR" -> s(R.string.state_closing)
        else -> s(R.string.state_idle)
    }

    private fun gearText(gear: Int?): String = when (gear) {
        null -> TripFormat.NONE
        VehicleDataHub.GEAR_PARK -> s(R.string.calib_gear_park)
        GEAR_REVERSE -> s(R.string.calib_gear_reverse)
        GEAR_NEUTRAL -> s(R.string.calib_gear_neutral)
        GEAR_DRIVE -> s(R.string.calib_gear_drive)
        else -> gear.toString()
    }

    private fun stepLine(p: CalibrationLogger.Row): String {
        val step = p.minStepText
            ?: return s(R.string.calib_step_no_change, p.lastValueText, p.count)
        val gap = p.minChangeGapSec?.let { " " + s(R.string.calib_step_min_gap, num(it, 1)) } ?: ""
        val mean = p.meanChangeGapSec?.let { " " + s(R.string.calib_step_mean_gap, num(it, 1)) } ?: ""
        return s(R.string.calib_step_line, p.lastValueText, step, gap, mean, p.changeCount)
    }

    private fun rateLine(p: CalibrationLogger.Row): String {
        val hz = p.measuredHz?.let { num(it, 2) } ?: TripFormat.NONE
        val jit = p.jitterMs?.let { num(it, 0) } ?: TripFormat.NONE
        val invalid =
            if (p.invalidCount > 0) " " + s(R.string.calib_rate_invalid, p.invalidCount) else ""
        return s(R.string.calib_rate_line, p.subscription, hz, jit, p.count, invalid)
    }

    private fun s(id: Int, vararg args: Any): String = carContext.getString(id, *args)

    /** Ondalik ayraci dile gore degisiyor; ortak bicimleyiciden geciyor. */
    private fun num(v: Double, decimals: Int): String = TripFormat.num(v, decimals)

    companion object {
        private const val REFRESH_MS = 2000L

        // VehiclePropertyIds sabitleri; VehicleDataHub yalnizca PARK icin tutuyor.
        private const val GEAR_NEUTRAL = 1
        private const val GEAR_REVERSE = 2
        private const val GEAR_DRIVE = 8
    }
}
