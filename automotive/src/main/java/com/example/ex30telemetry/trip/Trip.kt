package com.example.ex30telemetry.trip

import com.example.ex30telemetry.Constants
import com.example.ex30telemetry.R
import org.json.JSONArray
import org.json.JSONObject

/** A4 olcum turleri. Dordunde de KUCUK deger daha iyi. */
enum class PerfKind(
    val id: String,
    val labelRes: Int,
    val unit: String,
    val decimals: Int,
    /**
     * Bunun ustundeki degerler kayda hic girmiyor: performans olcumu degil,
     * trafikte siradan bir gecis demektir (bkz. PerformanceCatcher.emit).
     */
    val plausibleMax: Double,
) {
    SPRINT_0_100("0-100", R.string.perf_0_100, "s", 2, plausibleMax = 15.0),
    SPRINT_0_60("0-60", R.string.perf_0_60, "s", 2, plausibleMax = 10.0),
    ELASTIC_80_120("80-120", R.string.perf_80_120, "s", 2, plausibleMax = 15.0),
    BRAKE_100_0("100-0", R.string.perf_100_0, "m", 1, plausibleMax = 120.0);

    companion object {
        fun byId(id: String): PerfKind? = entries.firstOrNull { it.id == id }
    }
}

/**
 * A4 performans olcumu.
 *
 * [value] birimi olcum turune gore degisiyor: hizlanmalar saniye, fren
 * mesafesi metre. Sema 1'de yalnizca `seconds` alani vardi; eski kayitlar
 * okunurken saniye kabul ediliyor.
 */
data class PerfRecord(
    val kind: String,
    val value: Double,
    val unit: String,
    val epoch: Long,
) {
    val perfKind: PerfKind? get() = PerfKind.byId(kind)

    fun toJson(): JSONObject = JSONObject().apply {
        put("kind", kind)
        put("value", value)
        put("unit", unit)
        put("epoch", epoch)
    }

    companion object {
        fun fromJson(o: JSONObject): PerfRecord {
            // Sema 1 uyumlulugu: `value` yoksa `seconds` alanindan oku.
            val hasValue = o.has("value") && !o.isNull("value")
            return PerfRecord(
                kind = o.optString("kind"),
                value = if (hasValue) o.optDouble("value") else o.optDouble("seconds"),
                unit = if (o.has("unit")) o.optString("unit") else "s",
                epoch = o.optLong("epoch"),
            )
        }

        fun of(kind: PerfKind, value: Double, epoch: Long) =
            PerfRecord(kind.id, value, kind.unit, epoch)
    }
}

/**
 * Kaydedilmis bir yolculuk. Butun A1–A5 ciktilari bu tek kayda yaziliyor.
 *
 * Null alanlar normaldir: arac o property'yi vermediyse ya da metrik guvenilir
 * hale gelmediyse deger yazilmiyor, ekranda o satir gorunmuyor. Sifir yazip
 * uydurma hassasiyet uretmiyoruz.
 *
 * [schemaVersion] geriye donuk uyumluluk icin: sema degisirse eski kayitlar
 * okunmaya devam eder, silinmez.
 */
data class Trip(
    val startEpoch: Long,
    val endEpoch: Long,
    val durationSec: Long,
    /** GPS'ten hesaplanan mesafe; odometre ucuncu partiye kapali (§10.3). */
    val distanceKm: Double,
    /** Guc integralinden gelen net tuketim (kWh). */
    val energyKwh: Double?,
    /** Rejenle geri kazanilan enerji (kWh, pozitif). */
    val regenKwh: Double?,
    val socStart: Double?,
    val socEnd: Double?,
    val rangeStart: Double?,
    val rangeEnd: Double?,
    val avgSpeedKmh: Double?,
    val maxSpeedKmh: Double?,
    val tempStart: Double?,
    val tempAvg: Double?,
    val altGainM: Double,
    val altLossM: Double,
    /** A5: tirmanisin potansiyel enerjisi (m·g·Δh), kWh. */
    val potentialKwh: Double?,
    /** A1: kWh/100 km. */
    val consumptionKwh100: Double?,
    /** A2: gosterge menzil dususu ÷ gercek mesafe. 1,0 = gosterge dogru. */
    val rangeBiasFactor: Double?,
    /**
     * Tekerlek tiklerinden olculen mesafe (km) — sema 3.
     * [distanceKm] GPS'ten geliyor; ikisinin orani GPS'in hatasini veriyor.
     */
    val wheelDistanceKm: Double? = null,
    val records: List<PerfRecord> = emptyList(),
    val schemaVersion: Int = Constants.TRIP_SCHEMA_VERSION,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("schemaVersion", schemaVersion)
        put("startEpoch", startEpoch)
        put("endEpoch", endEpoch)
        put("durationSec", durationSec)
        put("distanceKm", distanceKm)
        putOpt("energyKwh", energyKwh)
        putOpt("regenKwh", regenKwh)
        putOpt("socStart", socStart)
        putOpt("socEnd", socEnd)
        putOpt("rangeStart", rangeStart)
        putOpt("rangeEnd", rangeEnd)
        putOpt("avgSpeedKmh", avgSpeedKmh)
        putOpt("maxSpeedKmh", maxSpeedKmh)
        putOpt("tempStart", tempStart)
        putOpt("tempAvg", tempAvg)
        put("altGainM", altGainM)
        put("altLossM", altLossM)
        putOpt("potentialKwh", potentialKwh)
        putOpt("consumptionKwh100", consumptionKwh100)
        putOpt("rangeBiasFactor", rangeBiasFactor)
        putOpt("wheelDistanceKm", wheelDistanceKm)
        put("records", JSONArray().also { a -> records.forEach { a.put(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject): Trip {
            val recs = mutableListOf<PerfRecord>()
            o.optJSONArray("records")?.let { a ->
                for (i in 0 until a.length()) {
                    a.optJSONObject(i)?.let { recs += PerfRecord.fromJson(it) }
                }
            }
            return Trip(
                startEpoch = o.optLong("startEpoch"),
                endEpoch = o.optLong("endEpoch"),
                durationSec = o.optLong("durationSec"),
                distanceKm = o.optDouble("distanceKm", 0.0),
                energyKwh = o.optDoubleOrNull("energyKwh"),
                regenKwh = o.optDoubleOrNull("regenKwh"),
                socStart = o.optDoubleOrNull("socStart"),
                socEnd = o.optDoubleOrNull("socEnd"),
                rangeStart = o.optDoubleOrNull("rangeStart"),
                rangeEnd = o.optDoubleOrNull("rangeEnd"),
                avgSpeedKmh = o.optDoubleOrNull("avgSpeedKmh"),
                maxSpeedKmh = o.optDoubleOrNull("maxSpeedKmh"),
                tempStart = o.optDoubleOrNull("tempStart"),
                tempAvg = o.optDoubleOrNull("tempAvg"),
                altGainM = o.optDouble("altGainM", 0.0),
                altLossM = o.optDouble("altLossM", 0.0),
                potentialKwh = o.optDoubleOrNull("potentialKwh"),
                consumptionKwh100 = o.optDoubleOrNull("consumptionKwh100"),
                rangeBiasFactor = o.optDoubleOrNull("rangeBiasFactor"),
                // Sema 3 oncesi kayitlarda yok; null kalmasi dogru.
                wheelDistanceKm = o.optDoubleOrNull("wheelDistanceKm"),
                records = recs,
                // Eski kayitta alan yoksa 1 varsay; kaydi ASLA atma.
                schemaVersion = o.optInt("schemaVersion", 1),
            )
        }
    }
}

/** JSON'da alan yoksa ya da null ise gercekten null dondurur (optDouble 0.0 verir). */
internal fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }
