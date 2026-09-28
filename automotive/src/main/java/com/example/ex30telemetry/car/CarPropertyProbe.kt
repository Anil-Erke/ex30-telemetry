package com.example.ex30telemetry.car

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.ex30telemetry.BuildConfig
import com.example.ex30telemetry.Permissions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Araç hangi property'yi gercekten veriyor — tek oturumda cevap veren sonda.
 *
 * ## Neden var
 *
 * prompt.md §10.5/3'un kurali: bir veri listede yoksa "arac sunmuyor" DEME;
 * once izni calisma aninda istedin mi diye bak. Bu sonda o kurali otomatiklestiriyor:
 * ayni raporda hem izin durumunu hem property sonucunu yan yana koyuyor, boylece
 * "izin verilmedigi icin gorunmuyor" ile "arac gercekten vermiyor" ayirt edilebiliyor.
 *
 * ## Neden simdi
 *
 * Araç yazilimi 2.1.2 ile Android 15'e gecti. Iki sey degismis olabilir:
 *  1. `VehiclePropertyIds` sinifi buyudu — §10.3'te "SDK'da tanimsiz" dedigimiz
 *     `EV_CHARGE_STATE` gibi sabitler artik var.
 *  2. `*_3P` izin ailesi geldi — odometre, lastik basinci ve direksiyon acisi
 *     ucuncu partiye acilmis olabilir (§10.3'te "kapali" yazan satirlar).
 *
 * Ikisi de HENUZ OLCULMEDI. Bu sinif tahmin uretmez; ne geldigini yazar.
 *
 * ## Kullanim
 *
 * [run] BLOKLAR (`Car.createCar` servise baglanana kadar bekler) — arka
 * thread'den cagir (§10.7).
 */
class CarPropertyProbe(private val context: Context) {

    enum class Outcome {
        /** Deger geldi. */
        OK,

        /** Property tanimli ve okunuyor ama deger yok (status != 0 ya da null). */
        NO_VALUE,

        /** `VehiclePropertyIds` sinifinda boyle bir alan yok — Android surumu eski. */
        NOT_IN_SDK,

        /** Sabit var ama arac `getPropertyList()`'te bildirmiyor / izin yok. */
        NOT_REPORTED,

        /** Okuma istisna atti. */
        ERROR,
    }

    /**
     * Bir iznin durumu. UC hal var ve ikisini karistirmak yanlis cikarim uretir:
     *  - [granted] true                    : verildi
     *  - [defined] true, [granted] false   : platform taniyor ama verilmedi
     *  - [defined] false                   : bu Android imajinda izin HIC YOK;
     *    istemek sessiz redle sonuclanir, "kullanici vermedi" gibi gorunur
     */
    data class PermState(val name: String, val granted: Boolean, val defined: Boolean) {
        val short: String get() = name
            .removePrefix("android.car.permission.")
            .removePrefix("android.permission.")
    }

    /** Tek bir hedef property'nin sonucu. */
    data class Finding(
        val group: String,
        val name: String,
        val label: String,
        val outcome: Outcome,
        val pretty: String?,
        val rawText: String?,
        val detail: String?,
    ) {
        val ok: Boolean get() = outcome == Outcome.OK

        fun line(): String {
            val status = when (outcome) {
                Outcome.OK -> pretty ?: rawText ?: "(deger yok)"
                Outcome.NO_VALUE -> "ANLIK DEGER YOK"
                Outcome.NOT_IN_SDK -> "SDK'DA TANIMSIZ"
                Outcome.NOT_REPORTED -> "ARAC BILDIRMIYOR / IZIN YOK"
                Outcome.ERROR -> "HATA"
            }
            val extra = buildString {
                if (outcome == Outcome.OK && rawText != null && rawText != pretty) {
                    append("   [ham: ").append(rawText).append(']')
                }
                if (detail != null) append("   (").append(detail).append(')')
            }
            return "  ${name.padEnd(42)} $status$extra"
        }
    }

    data class Report(
        val takenAt: Long,
        val androidRelease: String,
        val sdkInt: Int,
        val buildDisplay: String,
        val buildIncremental: String,
        val permissions: List<PermState>,
        val capabilities: Map<String, Boolean>,
        val knownConstantCount: Int,
        val reportedCount: Int,
        val configs: List<CarPropertyStream.Config>,
        val idToName: Map<Int, String>,
        val findings: List<Finding>,
        /** Ilgilendigimiz property'lerin configArray'leri (ozellikle WHEEL_TICK). */
        val configArrays: Map<String, List<Int>>,
        val startError: String?,
    ) {
        fun group(name: String): List<Finding> = findings.filter { it.group == name }

        fun okCountIn(group: String): Int = group(group).count { it.ok }

        /** Aracin bildirdigi ama `VehiclePropertyIds`'te karsiligi olmayanlar. */
        val vendorConfigs: List<CarPropertyStream.Config>
            get() = configs.filter { it.propId !in idToName }

        fun toText(): String = buildString {
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(takenAt))
            appendLine("EX30 Telemetry — araç property sondası")
            appendLine("=".repeat(78))
            appendLine("Alındığı an     : $stamp")
            appendLine("Uygulama        : ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android         : $androidRelease (SDK $sdkInt)")
            appendLine("Yazılım (build) : $buildDisplay")
            appendLine("                  $buildIncremental")
            appendLine()
            if (startError != null) {
                appendLine("!! Car servisine bağlanılamadı: $startError")
                appendLine("!! Aşağıdaki bölümler boş kalacak.")
                appendLine()
            }

            appendLine("1) İZİN DURUMU")
            appendLine("-".repeat(78))
            appendLine("   Bir property 'ARAC BILDIRMIYOR' diyorsa ÖNCE buraya bak: izin")
            appendLine("   verilmemişse property getPropertyList() çıktısında hiç görünmez")
            appendLine("   ve 'araç sunmuyor' gibi okunur (prompt.md §10.5/1).")
            appendLine()
            permissions.forEach { ps ->
                val state = when {
                    ps.granted -> "VERİLDİ"
                    !ps.defined -> "PLATFORMDA TANIMSIZ  ← izin bu Android imajında yok"
                    else -> "verilmedi  ← istenebilir, ama verilmemiş"
                }
                appendLine("  ${ps.short.padEnd(30)} $state")
            }
            appendLine()

            appendLine("2) PLATFORM API'Sİ")
            appendLine("-".repeat(78))
            appendLine("   Android 15'in yeni abonelik API'si var mı? Varsa §10.8'deki")
            appendLine("   'aynı değer saniyede 100 kez' israfı VUR ile kapanabilir.")
            appendLine()
            capabilities.forEach { (k, v) -> appendLine("  ${k.padEnd(30)} ${if (v) "VAR" else "yok"}") }
            appendLine("  ${"VehiclePropertyIds alan sayısı".padEnd(30)} $knownConstantCount")
            appendLine("  ${"aracın bildirdiği property".padEnd(30)} $reportedCount")
            appendLine()

            GROUPS.forEach { g ->
                val rows = group(g)
                if (rows.isEmpty()) return@forEach
                appendLine("3.${GROUPS.indexOf(g) + 1}) ${g.uppercase(Locale.ROOT)}   " +
                    "[${rows.count { it.ok }}/${rows.size} geldi]")
                appendLine("-".repeat(78))
                rows.forEach { appendLine(it.line()) }
                appendLine()
            }

            appendLine("4) ARACIN BİLDİRDİĞİ TÜM PROPERTY'LER (${configs.size})")
            appendLine("-".repeat(78))
            appendLine("   ad / id · değişim modu · örnekleme aralığı · erişim · tip · areaId'ler")
            appendLine("   changeMode: 0=STATIC 1=ON_CHANGE 2=CONTINUOUS")
            appendLine("   access: 1=READ 2=WRITE 3=READ_WRITE")
            appendLine()
            configs.sortedBy { idToName[it.propId] ?: "zzz_${it.propId}" }.forEach { c ->
                val name = idToName[c.propId] ?: "0x%08x (adsız)".format(c.propId)
                val rate = when {
                    c.maxSampleRate == null || c.maxSampleRate <= 0f -> "-"
                    else -> "%.0f..%.0f Hz".format(c.minSampleRate ?: 0f, c.maxSampleRate)
                }
                appendLine(
                    "  ${name.padEnd(46)} mode=${c.changeMode ?: '-'} " +
                        "rate=${rate.padEnd(12)} access=${c.access ?: '-'} " +
                        "tip=${(c.valueType ?: "-").padEnd(10)} area=${c.areaIds.joinToString(",")}"
                )
            }
            appendLine()

            configArrays["WHEEL_TICK"]?.takeIf { it.isNotEmpty() }?.let { ca ->
                appendLine("4b) WHEEL_TICK SABİTLERİ — mesafenin odometre olmadan kaynağı")
                appendLine("-".repeat(78))
                appendLine("   configArray[0] = desteklenen tekerlek maskesi")
                appendLine("   configArray[1..4] = mikrometre / tick  (ön sol, ön sağ, arka sağ, arka sol)")
                appendLine("   mesafe = Δtick × µm-per-tick ÷ 1.000.000  (metre)")
                appendLine()
                appendLine("  ham configArray: ${ca.joinToString(", ")}")
                val wheels = listOf("ön sol", "ön sağ", "arka sağ", "arka sol")
                wheels.forEachIndexed { i, w ->
                    val um = ca.getOrNull(i + 1) ?: return@forEachIndexed
                    if (um <= 0) {
                        appendLine("  ${w.padEnd(10)} desteklenmiyor")
                    } else {
                        // Binlik ayraci KULLANILMIYOR: "1 tick = 22.000 mm" satiri
                        // 22 mm'yi 22 bin mm gibi okutuyordu (2026-09-10 raporu).
                        appendLine(
                            "  ${w.padEnd(10)} $um µm/tick  ·  1 tick = %.1f mm  ·  1 km = %d tick"
                                .format(Locale.ROOT, um / 1000.0, 1_000_000_000L / um)
                        )
                    }
                }
                appendLine()
            }

            val vendors = vendorConfigs
            appendLine("5) ADSIZ (VOLVO'YA ÖZEL) PROPERTY'LER (${vendors.size})")
            appendLine("-".repeat(78))
            if (vendors.isEmpty()) {
                appendLine("  yok")
            } else {
                appendLine("   VehiclePropertyIds'te karşılığı olmayanlar — anlamları belgelenmemiş.")
                appendLine("   §10.4/4: keşfetmeye değer. V2L adaptörü takılıyken sondayı tekrar")
                appendLine("   çalıştırıp bu bölümün farkını almak, V2L property'sini bulmanın yolu.")
                appendLine()
                vendors.forEach { c ->
                    appendLine(
                        "  0x%08x  mode=%s access=%s tip=%s area=%s".format(
                            c.propId, c.changeMode ?: '-', c.access ?: '-',
                            c.valueType ?: "-", c.areaIds.joinToString(","),
                        )
                    )
                }
            }
            appendLine()
            appendLine("=".repeat(78))
            appendLine("Not: bu dosya tek bir ANLIK GÖRÜNTÜ. Park hâlinde ve sürüşte farklı")
            appendLine("sonuç verebilir (§10.8: çözünürlük sürüşte ve şarjda farklı).")
        }
    }

    fun run(): Report {
        val stream = CarPropertyStream(context)
        val started = stream.start()
        val startError = if (started) null else (stream.lastError ?: "bilinmeyen")

        val known = if (started) stream.knownProperties() else emptyMap()
        val idToName = known.entries.associate { (k, v) -> v to k }
        val configs = if (started) stream.configs() else emptyList()
        val reported = configs.map { it.propId }.toSet()

        val findings = TARGETS.map { t -> probeOne(stream, t, known, reported, configs) }

        // WHEEL_TICK'in mikrometre/tick sabitleri olmadan mesafe hesaplanamiyor.
        val configArrays = if (started) {
            listOf("WHEEL_TICK").mapNotNull { name ->
                known[name]?.let { id -> name to stream.configArrayOf(id) }
            }.toMap()
        } else emptyMap()

        val report = Report(
            takenAt = System.currentTimeMillis(),
            androidRelease = Build.VERSION.RELEASE ?: "?",
            sdkInt = Build.VERSION.SDK_INT,
            buildDisplay = Build.DISPLAY ?: "?",
            buildIncremental = "${Build.VERSION.INCREMENTAL} · ${Build.FINGERPRINT}",
            permissions = Permissions.ALL.map {
                PermState(
                    name = it,
                    granted = Permissions.granted(context, it),
                    defined = Permissions.definedOnPlatform(context, it),
                )
            },
            capabilities = if (started) stream.platformCapabilities() else emptyMap(),
            knownConstantCount = known.size,
            reportedCount = configs.size,
            configs = configs,
            idToName = idToName,
            findings = findings,
            configArrays = configArrays,
            startError = startError,
        )

        stream.stop()
        Log.i(TAG, "sonda bitti: ${configs.size} property bildirildi, " +
            "${findings.count { it.ok }}/${findings.size} hedef okundu")
        return report
    }

    private fun probeOne(
        stream: CarPropertyStream,
        t: Target,
        known: Map<String, Int>,
        reported: Set<Int>,
        configs: List<CarPropertyStream.Config>,
    ): Finding {
        fun finding(outcome: Outcome, pretty: String? = null, raw: String? = null, detail: String? = null) =
            Finding(t.group, t.name, t.label, outcome, pretty, raw, detail ?: t.note)

        val id = known[t.name] ?: return finding(Outcome.NOT_IN_SDK)

        // Bolgeli property'ler (lastik basinci, koltuk) areaId 0'dan OKUNAMAZ:
        // `area ID: 0x0 not supported` ile duserler. Gercek areaId'leri uc
        // kaynaktan sirayla ariyoruz:
        //   1. getPropertyList() ciktisi — ama bu IZINE GORE FILTRELI (§10.5/1)
        //   2. getCarPropertyConfig(id) — dogrudan sorgu, listede olmayani da
        //      dondurebiliyor; "araçta var ama izin yok" ancak boyle ayirt edilir
        //   3. property'nin bilinen bolge kumesi (tekerlek/koltuk) — ilk ikisi de
        //      bos donerse en azindan gecerli bir areaId ile deneyip gercek hatayi
        //      (SecurityException mi, baska mi) gorelim
        val cfg = configs.firstOrNull { it.propId == id } ?: stream.configOf(id)
        val areaIds = cfg?.areaIds ?: t.areaFallback ?: listOf(0)

        val parts = mutableListOf<String>()
        val raws = mutableListOf<String>()
        var anyOk = false
        var lastError: String? = null

        for (area in areaIds) {
            val res = stream.readDetailed(t.name, area)
            res.fold(
                onSuccess = { ev ->
                    val v = ev?.value
                    if (ev == null || v == null) {
                        parts += areaLabel(area, areaIds) + "—"
                    } else {
                        anyOk = true
                        raws += areaLabel(area, areaIds) + rawText(v)
                        parts += areaLabel(area, areaIds) + t.pretty(v)
                        if (ev.status != 0) lastError = "status=${ev.status}"
                    }
                },
                onFailure = { lastError = it.javaClass.simpleName + (it.message?.let { m -> ": $m" } ?: "") },
            )
        }

        return when {
            anyOk -> finding(Outcome.OK, parts.joinToString("  "), raws.joinToString("  "), lastError?.let { "uyarı: $it" })
            lastError != null -> finding(Outcome.ERROR, detail = lastError)
            id !in reported -> finding(Outcome.NOT_REPORTED)
            else -> finding(Outcome.NO_VALUE)
        }
    }

    private fun areaLabel(area: Int, all: List<Int>): String =
        if (all.size <= 1) "" else "[0x%x] ".format(area)

    private fun rawText(v: Any): String = when (v) {
        is FloatArray -> v.joinToString(", ")
        is IntArray -> v.joinToString(", ")
        is LongArray -> v.joinToString(", ")
        is Array<*> -> v.joinToString(", ")
        else -> v.toString()
    }

    /**
     * Sondanin hedefi. [divisor] ham degeri okunabilir birime cevirir —
     * `CalibrationLogger.Spec` ile ayni kural: `gosterilen = ham / divisor`.
     */
    private data class Target(
        val group: String,
        val name: String,
        val label: String,
        val divisor: Double? = null,
        val unit: String = "",
        val decimals: Int = 1,
        val enumOf: ((Int) -> String)? = null,
        /** Yapilandirma alinamazsa denenecek bolge kimlikleri. */
        val areaFallback: List<Int>? = null,
        val note: String? = null,
    ) {
        fun pretty(v: Any): String {
            enumOf?.let { f -> (v as? Number)?.let { return f(it.toInt()) } }
            if (v is Boolean) return if (v) "true" else "false"
            val n = v as? Number
            if (n != null && divisor != null) {
                return "%.${decimals}f %s".format(Locale.ROOT, n.toDouble() / divisor, unit).trim()
            }
            if (n != null) return "${trimNum(n)} $unit".trim()
            return when (v) {
                is FloatArray -> v.joinToString(", ")
                is IntArray -> v.joinToString(", ")
                is LongArray -> v.joinToString(", ")
                is Array<*> -> v.joinToString(", ")
                else -> v.toString()
            }
        }

        private fun trimNum(n: Number): String {
            val d = n.toDouble()
            return if (d == d.toLong().toDouble()) d.toLong().toString() else "%.3f".format(Locale.ROOT, d)
        }
    }

    companion object {
        private const val TAG = "JourneyProbe"

        private const val G_NEW3P = "Android 15 · 3P izinleriyle açılanlar"
        private const val G_NEWENERGY = "Mevcut izinlerle yeni okunabilecekler"
        private const val G_KNOWN = "Bilinenler (regresyon kontrolü)"
        private const val G_CLOSED = "Kapalı olduğu doğrulanacaklar"

        val GROUPS = listOf(G_NEW3P, G_NEWENERGY, G_KNOWN, G_CLOSED)

        /** VehicleAreaWheel: sol on, sag on, sol arka, sag arka. */
        private val WHEEL_AREAS = listOf(0x1, 0x2, 0x4, 0x8)

        /** VehicleAreaSeat, 1. sira: sol, orta, sag. */
        private val SEAT_AREAS = listOf(0x1, 0x2, 0x4)

        private fun chargeState(v: Int) = when (v) {
            0 -> "UNKNOWN"; 1 -> "CHARGING"; 2 -> "FULLY_CHARGED"
            3 -> "NOT_CHARGING"; 4 -> "ERROR"; else -> "? ($v)"
        }

        private fun regenState(v: Int) = when (v) {
            0 -> "UNKNOWN"; 1 -> "DISABLED"; 2 -> "PARTIALLY_ENABLED"
            3 -> "FULLY_ENABLED"; else -> "? ($v)"
        }

        private fun stoppingMode(v: Int) = when (v) {
            0 -> "OTHER"; 1 -> "CREEP"; 2 -> "ROLL"; 3 -> "STANDSTILL"; else -> "? ($v)"
        }

        private fun gear(v: Int) = when (v) {
            1 -> "NEUTRAL"; 2 -> "REVERSE"; 4 -> "PARK"; 8 -> "DRIVE"; else -> "? ($v)"
        }

        private fun ignition(v: Int) = when (v) {
            0 -> "UNDEFINED"; 1 -> "LOCK"; 2 -> "OFF"; 3 -> "ACC"; 4 -> "ON"; 5 -> "START"
            else -> "? ($v)"
        }

        /**
         * Sondanin bakacagi property'ler.
         *
         * Liste bilerek KISA tutuldu: aracin bildirdigi her sey zaten raporun
         * 4. bolumunde ham hâlde dokuluyor. Burasi "bu sorunun cevabini
         * ariyoruz" listesi.
         */
        private val TARGETS = listOf(
            // --- Android 15 ile ucuncu partiye acilmis olabilecekler ---
            Target(G_NEW3P, "PERF_ODOMETER", "Kilometre sayacı",
                unit = "km", note = "§10.3'te 'erişilemiyor' yazıyordu — CAR_MILEAGE_3P ile açıldı mı?"),
            Target(G_NEW3P, "INSTANTANEOUS_EV_EFFICIENCY", "Anlık verim",
                note = "birim DOĞRULANMALI — ham değere bak, kendi hesabımızla karşılaştır"),
            Target(G_NEW3P, "TIRE_PRESSURE", "Lastik basıncı",
                unit = "kPa", areaFallback = WHEEL_AREAS,
                note = "dört tekerlek ayrı areaId"),
            Target(G_NEW3P, "PERF_STEERING_ANGLE", "Direksiyon açısı",
                unit = "°", note = "sol negatif"),
            Target(G_NEW3P, "VEHICLE_DRIVING_AUTOMATION_CURRENT_LEVEL", "Otomasyon seviyesi"),

            // --- Izinlerimiz zaten var; sabit Android 15'te tanimli ---
            Target(G_NEWENERGY, "EV_BATTERY_AVERAGE_TEMPERATURE", "Batarya sıcaklığı",
                unit = "°C", note = "alt göstergeye aday"),
            Target(G_NEWENERGY, "EV_CURRENT_BATTERY_CAPACITY", "Gerçek kapasite",
                divisor = 1000.0, unit = "kWh", decimals = 2,
                note = "÷ INFO_EV_BATTERY_CAPACITY = batarya sağlığı (SoH)"),
            Target(G_NEWENERGY, "EV_CHARGE_STATE", "Şarj durumu", enumOf = ::chargeState,
                note = "§10.3'te 'SDK'da tanımsız' yazıyordu"),
            Target(G_NEWENERGY, "EV_CHARGE_TIME_REMAINING", "Kalan şarj süresi",
                unit = "dk", decimals = 0,
                note = "AOSP 'saniye' diyor ama DAKİKA: 2026-09-10'da property 54 " +
                    "derken araç ekranı '55 min' yazıyordu · şarj yokken -1"),
            Target(G_NEWENERGY, "EV_CHARGE_PERCENT_LIMIT", "Şarj limiti", unit = "%"),
            Target(G_NEWENERGY, "EV_REGENERATIVE_BRAKING_STATE", "Rejen durumu", enumOf = ::regenState),
            Target(G_NEWENERGY, "EV_STOPPING_MODE", "Duruş modu", enumOf = ::stoppingMode,
                note = "tek pedal sürüş — 2.1.2'de profile bağlandı"),
            Target(G_NEWENERGY, "EV_BRAKE_REGENERATION_LEVEL", "Rejen seviyesi"),
            Target(G_NEWENERGY, "EV_CHARGE_PORT_CONNECTED", "Şarj kablosu takılı"),
            Target(G_NEWENERGY, "EV_CHARGE_PORT_OPEN", "Şarj kapağı açık"),

            // --- Zaten kullandiklarimiz: 2.1.2 bunlari bozdu mu? ---
            Target(G_KNOWN, "INFO_EV_BATTERY_CAPACITY", "Nominal kapasite",
                divisor = 1000.0, unit = "kWh", decimals = 2, note = "beklenen 66,00"),
            Target(G_KNOWN, "EV_BATTERY_LEVEL", "Batarya enerjisi",
                divisor = 1000.0, unit = "kWh", decimals = 2),
            Target(G_KNOWN, "RANGE_REMAINING", "Kalan menzil",
                divisor = 1000.0, unit = "km"),
            Target(G_KNOWN, "EV_BATTERY_INSTANTANEOUS_CHARGE_RATE", "Anlık güç",
                divisor = 1_000_000.0, unit = "kW", decimals = 2,
                note = "ham birim mW · pozitif = tüketim (§10.4/1)"),
            Target(G_KNOWN, "PERF_VEHICLE_SPEED", "Ham hız",
                divisor = 1.0 / 3.6, unit = "km/h", note = "işaretli — geri viteste negatif"),
            Target(G_KNOWN, "PERF_VEHICLE_SPEED_DISPLAY", "Gösterge hızı", divisor = 1.0 / 3.6, unit = "km/h"),
            Target(G_KNOWN, "ENV_OUTSIDE_TEMPERATURE", "Dış sıcaklık", unit = "°C"),
            Target(G_KNOWN, "GEAR_SELECTION", "Vites", enumOf = ::gear),
            Target(G_KNOWN, "IGNITION_STATE", "Kontak", enumOf = ::ignition),
            Target(G_KNOWN, "PARKING_BRAKE_ON", "Park freni"),
            Target(G_KNOWN, "NIGHT_MODE", "Gece modu"),
            Target(G_KNOWN, "INFO_EXTERIOR_DIMENSIONS", "Dış ölçüler", unit = "mm"),
            Target(G_KNOWN, "WHEEL_TICK", "Tekerlek tikleri",
                note = "[reset, ön sol, ön sağ, arka sağ, arka sol] · odometre yerine " +
                    "mesafe kaynağı · 22 mm/tick · DİKKAT: reset sayacı 0'da kalıyor, " +
                    "sıfırlanmayı sayaç düşüşünden anla"),

            // --- Kapali oldugu BEKLENEN; rapora kanit olsun diye burada ---
            Target(G_CLOSED, "HVAC_TEMPERATURE_CURRENT", "Kabin sıcaklığı",
                unit = "°C", areaFallback = SEAT_AREAS,
                note = "BEKLENEN: okunamaz. Signature|Privileged izin ister; " +
                    "Play'den kurulan uygulama hiçbir koşulda alamaz"),
        )
    }
}
