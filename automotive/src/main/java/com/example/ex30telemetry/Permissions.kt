package com.example.ex30telemetry

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Uygulamanin calisma aninda istemesi gereken izinler.
 *
 * **Emulatorde olculdu (2026-07-30):** araç izinlerinin hepsi kurulum ani izni
 * DEGIL. Bu imajda `CAR_INFO`, `CAR_POWERTRAIN` ve `CAR_EXTERIOR_ENVIRONMENT`
 * kurulumda otomatik veriliyor ama **`CAR_SPEED` ve `CAR_ENERGY` calisma ani
 * izni** ve istenmezse hic verilmiyor. Istenmediginde belirti kafa karistirici:
 * property'ler `getPropertyList()` ciktisinda hic gorunmuyor, yani "araç bu
 * veriyi sunmuyor" gibi okunuyor. (prompt.md §6.12 bu ayrimi yapmiyor.)
 *
 * ## Zorunlu / opsiyonel ayrimi — NEDEN VAR
 *
 * [OPTIONAL] listesindeki izinler reddedilebilir ya da aracin Android surumunde
 * hic tanimli olmayabilir; ikisinde de sonuc "verilmedi" olur ve bu kalici bir
 * durumdur. Eger ekran gecisi [missing] uzerinden kurulsaydi, reddedilen tek bir
 * opsiyonel izin izin ekranini SONSUZ DONGUYE sokardi (LiveScreen saniyede bir
 * yeniden ciziliyor). Bu yuzden kapi yalnizca [REQUIRED] ile kuruluyor;
 * opsiyoneller istenir, sonucuna bakilmaz.
 */
object Permissions {

    /** Olmazsa olmaz: mesafe ve irtifa yalnizca konumdan hesaplanabiliyor. */
    const val LOCATION = Manifest.permission.ACCESS_FINE_LOCATION

    /** Enerji muhasebesi (A1, A5) ve menzil denetimi (A2) icin. */
    const val CAR_ENERGY = "android.car.permission.CAR_ENERGY"

    /** Yolculuk durum makinesi ve performans olcumleri (A4) icin. */
    const val CAR_SPEED = "android.car.permission.CAR_SPEED"

    // --- Android 15 ile ucuncu partiye acilan izinler ---
    //
    // Bunlarin hepsi "Dangerous", yani CAR_SPEED/CAR_ENERGY gibi calisma aninda
    // istenip verilebiliyor. Android 15 oncesinde ayni veriler yalnizca
    // Signature|Privileged ikizleriyle (CAR_MILEAGE, CAR_TIRES, ...) aliniyordu
    // ve ucuncu partiye tamamen kapaliydi — prompt.md §10.3'teki
    // "odometreye erisilemiyor" tespitinin sebebi buydu.
    //
    // UYARI: iznin var olmasi aracin o property'yi YAYINLADIGI anlamina gelmiyor.
    // Hangisinin gercekten geldigini CarPropertyProbe olcuyor.

    /** `PERF_ODOMETER` ve `INSTANTANEOUS_EV_EFFICIENCY`. */
    const val CAR_MILEAGE_3P = "android.car.permission.CAR_MILEAGE_3P"

    /** `TIRE_PRESSURE` — dort tekerlek ayri areaId. */
    const val CAR_TIRES_3P = "android.car.permission.CAR_TIRES_3P"

    /** `PERF_STEERING_ANGLE`. */
    const val READ_CAR_STEERING_3P = "android.car.permission.READ_CAR_STEERING_3P"

    /** `VEHICLE_DRIVING_AUTOMATION_CURRENT_LEVEL` (Pilot Assist seviyesi). */
    const val CAR_DRIVING_STATE_3P = "android.car.permission.CAR_DRIVING_STATE_3P"

    /**
     * `EV_CHARGE_PORT_CONNECTED` / `EV_CHARGE_PORT_OPEN`.
     * Bu bir "Normal" izin — kurulumda verilir, calisma aninda istemek sonucsuz
     * ama zararsiz kalir. Listede olmasi sondanin durumunu raporlamasi icin.
     */
    const val CAR_ENERGY_PORTS = "android.car.permission.CAR_ENERGY_PORTS"

    /** Verilmezse uygulama calisamaz; ekran gecisi yalnizca buna bakar. */
    val REQUIRED = listOf(LOCATION, CAR_ENERGY, CAR_SPEED)

    /** Verilmezse ilgili satir sessizce duser; uygulama calismaya devam eder. */
    val OPTIONAL = listOf(
        CAR_MILEAGE_3P,
        CAR_TIRES_3P,
        READ_CAR_STEERING_3P,
        CAR_DRIVING_STATE_3P,
        CAR_ENERGY_PORTS,
    )

    /** Izin diyaloguna sokulacak liste — ikisi birden, tek seferde sorulsun. */
    val ALL = REQUIRED + OPTIONAL

    /**
     * Platform bu izni TANIYOR mu?
     *
     * Tanimayan bir izni istemek istisna atmiyor, sessizce "reddedildi"
     * donuyor -- ve sonucu okuyan taraf bunu "kullanici vermedi" sanip yanlis
     * cikarim yapiyor. Ayrimi yapabilmek icin izin bilgisi dogrudan
     * PackageManager'a soruluyor.
     *
     * 2026-09-09 sondasinda dort `*_3P` izni "verilmedi" gorunuyordu ve
     * hangisinin gecerli oldugu belirsizdi: kullanici mi reddetti, yoksa bu
     * Android 15 imajinda izin hic mi yok? Bu fonksiyon o soruyu kapatiyor.
     */
    fun definedOnPlatform(context: Context, permission: String): Boolean =
        runCatching {
            context.packageManager.getPermissionInfo(permission, 0)
            true
        }.getOrDefault(false)

    /** Eksik VE platformun tanidigi izinler -- istek diyaloguna yalnizca bunlar girer. */
    fun requestableMissing(context: Context): List<String> =
        missingIncludingOptional(context).filter { definedOnPlatform(context, it) }

    fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** **Kapi bunun uzerine kurulur.** Yalnizca zorunlu izinler. */
    fun missing(context: Context): List<String> = REQUIRED.filterNot { granted(context, it) }

    /** Izin diyalogunda sorulacaklar: eksik olan her sey, opsiyoneller dahil. */
    fun missingIncludingOptional(context: Context): List<String> =
        ALL.filterNot { granted(context, it) }

    fun hasLocation(context: Context): Boolean = granted(context, LOCATION)
}
