package com.example.ex30telemetry.screen

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.ex30telemetry.PermissionActivity
import com.example.ex30telemetry.Permissions
import com.example.ex30telemetry.R
import com.example.ex30telemetry.calib.DataExporter
import com.example.ex30telemetry.car.CarPropertyProbe

/**
 * Property sondasi ekrani — "araç bu veriyi gercekten veriyor mu?"
 *
 * Ekranda YALNIZCA ozet var; tam dokum dosyaya yaziliyor. Iki sebep:
 *  1. Araç hareket halindeyken host listeyi SESSIZCE alti satira kirpiyor
 *     (PROMPT-MD-NOTLARI §4d) — 30 satirlik bir dokum ekranda guvenilir degil.
 *  2. Raporun asil degeri karsilastirilabilir olmasi: dosya olarak sakla,
 *     bir dahaki surumde farkini al.
 *
 * Sonda BLOKLAYAN bir is (`Car.createCar` servise baglanana kadar bekler),
 * o yuzden arka thread'de calisiyor ve sonuc ana thread'e postalaniyor (§10.7).
 */
class ProbeScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {

    private val handler = Handler(Looper.getMainLooper())

    private var report: CarPropertyProbe.Report? = null
    private var running = false
    private var exportedName: String? = null
    private var exportFailed = false

    /** Son sondanin gordugu verilmis izin sayisi; degisirse sonda anlamsizlasir. */
    private var grantedAtLastRun = -1

    init {
        lifecycle.addObserver(this)
    }

    /**
     * Ilk acilista ve izin ekranindan DONUSTE calisir.
     *
     * Izin sayisi degismeden tekrar calistirmiyoruz: sonda birkac saniye suruyor
     * ve her ekran donusunde bastan baslatmak dosyaya yeni bir rapor daha yazardi.
     */
    override fun onResume(owner: LifecycleOwner) {
        val granted = Permissions.ALL.count { Permissions.granted(carContext, it) }
        if (report == null || granted != grantedAtLastRun) {
            grantedAtLastRun = granted
            start()
        }
    }

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        val r = report

        when {
            running -> list.addItem(
                Row.Builder()
                    .setTitle(s(R.string.probe_running))
                    .addText(s(R.string.probe_running_hint))
                    .build()
            )

            r == null -> list.addItem(
                Row.Builder()
                    .setTitle(s(R.string.probe_failed))
                    .addText(s(R.string.probe_retry_hint))
                    .setOnClickListener { start() }
                    .build()
            )

            else -> buildRows(r).forEach { list.addItem(it) }
        }

        return ListTemplate.Builder()
            .setTitle(s(R.string.probe_title))
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            // ListTemplate'in aksiyon cubugu BASLIKLI YALNIZCA BIR aksiyon
            // kabul ediyor; ikincisi host'u cokertiyor (CalibrationScreen'deki
            // ayni not). Bu yuzden "tekrar calistir" bir SATIR, dugme degil.
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
     * Ozet satirlari. Sira "once cevabini merak ettigimiz soru": once yeni
     * izinlerle acilmis olabilecekler, sonra dogrulama.
     *
     * Surerken alti satir gorunur (§4d) — ilk alti satir en onemlileri olacak
     * sekilde siralandi.
     */
    private fun buildRows(r: CarPropertyProbe.Report): List<Row> {
        val rows = mutableListOf<Row>()

        if (r.startError != null) {
            rows += Row.Builder()
                .setTitle(s(R.string.probe_car_off))
                .addText(r.startError)
                .build()
            return rows
        }

        // 1) En cok merak edilen tek satir: odometre acildi mi?
        r.findings.firstOrNull { it.name == "PERF_ODOMETER" }?.let {
            rows += Row.Builder()
                .setTitle(s(R.string.probe_odometer))
                .addText(outcomeText(it))
                .addText(s(R.string.probe_odometer_hint))
                .build()
        }

        // 2) Alt gostergeye aday: batarya sicakligi + gercek kapasite
        val temp = r.findings.firstOrNull { it.name == "EV_BATTERY_AVERAGE_TEMPERATURE" }
        val cap = r.findings.firstOrNull { it.name == "EV_CURRENT_BATTERY_CAPACITY" }
        rows += Row.Builder()
            .setTitle(s(R.string.probe_gauge_candidates))
            .addText("${s(R.string.probe_batt_temp)}: ${temp?.let { outcomeText(it) } ?: "—"}")
            .addText("${s(R.string.probe_real_capacity)}: ${cap?.let { outcomeText(it) } ?: "—"}")
            .build()

        // 3) Gruplarin sayisal ozeti
        CarPropertyProbe.GROUPS.forEach { g ->
            val group = r.group(g)
            if (group.isEmpty()) return@forEach
            val ok = group.count { it.ok }
            rows += Row.Builder()
                .setTitle(g)
                .addText(s(R.string.probe_group_count, ok, group.size))
                .addText(
                    group.filter { it.ok }.joinToString(", ") { it.label }
                        .ifEmpty { s(R.string.probe_none_came) }
                )
                .build()
        }

        // 4) Izinler — bir property "bildirilmiyor" diyorsa ilk bakilacak yer.
        //    "verilmedi" ile "platformda tanimsiz" AYRI gosteriliyor: ikincisi
        //    istenerek cozulemez, izin o Android imajinda hic yok.
        val undefined = r.permissions.filterNot { it.defined }.map { it.short }
        val denied = r.permissions.filter { it.defined && !it.granted }.map { it.short }
        rows += Row.Builder()
            .setTitle(s(R.string.probe_permissions))
            .addText(
                s(R.string.probe_perm_count, r.permissions.count { it.granted }, r.permissions.size)
            )
            .addText(
                when {
                    denied.isEmpty() && undefined.isEmpty() -> s(R.string.probe_perm_all)
                    undefined.isEmpty() -> denied.joinToString(", ")
                    denied.isEmpty() ->
                        s(R.string.probe_perm_undefined, undefined.joinToString(", "))
                    else -> denied.joinToString(", ") + " · " +
                        s(R.string.probe_perm_undefined, undefined.joinToString(", "))
                }
            )
            .setOnClickListener { requestMissing() }
            .build()

        // 5) Platform ve dokum buyuklugu
        rows += Row.Builder()
            .setTitle(s(R.string.probe_platform))
            .addText("Android ${r.androidRelease} · SDK ${r.sdkInt}")
            .addText(
                s(
                    R.string.probe_counts,
                    r.reportedCount,
                    r.knownConstantCount,
                    r.vendorConfigs.size,
                )
            )
            .build()

        // 6) Rapor dosyasi
        rows += Row.Builder()
            .setTitle(s(R.string.probe_report))
            .addText(
                when {
                    exportedName != null -> exportedName!!
                    exportFailed -> s(R.string.export_failed)
                    else -> s(R.string.probe_not_exported)
                }
            )
            .addText(s(R.string.probe_report_hint))
            .build()

        // 7) Tekrar calistir — dugme degil satir (tek baslikli aksiyon kurali)
        rows += Row.Builder()
            .setTitle(s(R.string.probe_rerun))
            .addText(s(R.string.probe_rerun_hint))
            .setOnClickListener { start() }
            .build()

        return rows
    }

    private fun outcomeText(f: CarPropertyProbe.Finding): String = when (f.outcome) {
        CarPropertyProbe.Outcome.OK -> f.pretty ?: s(R.string.probe_ok)
        CarPropertyProbe.Outcome.NO_VALUE -> s(R.string.probe_no_value)
        CarPropertyProbe.Outcome.NOT_IN_SDK -> s(R.string.car_sub_undefined)
        CarPropertyProbe.Outcome.NOT_REPORTED -> s(R.string.car_sub_not_reported)
        CarPropertyProbe.Outcome.ERROR -> s(R.string.probe_error)
    }

    /**
     * Sondayi arka thread'de calistirir. `Car.createCar` ana thread'de
     * cagrilirsa acilis takilir (§10.7).
     */
    private fun start() {
        if (running) return
        running = true
        exportedName = null
        exportFailed = false
        invalidate()

        Thread {
            val result = runCatching { CarPropertyProbe(carContext).run() }.getOrNull()
            handler.post {
                report = result
                running = false
                // Raporu hemen dosyaya yaz: kullanici "Dışa aktar"a basmayi
                // unutursa olcum kaybolmasin. Dosya adi ekranda gorunuyor.
                result?.let { autoExport(it) }
                invalidate()
            }
        }.apply { isDaemon = true; name = "ex30-probe" }.start()
    }

    private fun autoExport(r: CarPropertyProbe.Report) {
        val name = DataExporter.exportText(carContext, "sonda", r.toText())
        exportedName = name
        exportFailed = name == null
    }

    /**
     * Rapor yazilir ve sistemin İndirilenler ekrani acilir — araçta dosyaya
     * ulasabilen tek arayuz o (DataExporter notu). Araç hareket halindeyken
     * sistem bu ekrani acmiyor; normal.
     */
    private fun export() {
        val r = report
        if (r == null) {
            CarToast.makeText(carContext, s(R.string.probe_running), CarToast.LENGTH_SHORT).show()
            return
        }
        autoExport(r)
        val name = exportedName
        CarToast.makeText(
            carContext,
            name ?: s(R.string.export_failed),
            CarToast.LENGTH_LONG,
        ).show()
        if (name != null) DataExporter.openDownloads(carContext)
        invalidate()
    }

    /**
     * Eksik izinleri yeniden ister. `CarContext.requestPermissions` Android
     * 15'te cokuyor (bkz. PermissionActivity); kendi aktivitemiz aciliyor.
     * Donuste sonda [onResume] ile kendiliginden yeniden calisiyor.
     */
    private fun requestMissing() {
        if (Permissions.missingIncludingOptional(carContext).isEmpty()) return
        val intent = if (Permissions.requestableMissing(carContext).isEmpty()) {
            // Kalanlarin hepsi platformda tanimsiz -- istek diyalogu bos acilir.
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", carContext.packageName, null))
        } else {
            Intent(carContext, PermissionActivity::class.java)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val first = runCatching { carContext.startActivity(intent) }
        if (first.isSuccess) return
        val second = runCatching { carContext.applicationContext.startActivity(intent) }
        if (second.isSuccess) return

        // Gercek istisnayi goster: araçta adb yok, yigin izi baska turlu alinamiyor.
        val e = second.exceptionOrNull() ?: first.exceptionOrNull()
        val detail = e?.let { "${it.javaClass.simpleName}: ${it.message.orEmpty().take(90)}" }
            ?: s(R.string.perm_blocked)
        CarToast.makeText(
            carContext, s(R.string.perm_launch_error, detail), CarToast.LENGTH_LONG,
        ).show()
    }

    private fun s(id: Int, vararg args: Any): String = carContext.getString(id, *args)
}
