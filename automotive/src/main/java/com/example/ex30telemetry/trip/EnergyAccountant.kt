package com.example.ex30telemetry.trip

/**
 * Enerji muhasebesi — A1 ve A5'in tabani.
 *
 * **Faz 0'in karar kurali burada uygulandi.** Gercek EX30'da olculdu:
 * `EV_BATTERY_INSTANTANEOUS_CHARGE_RATE` surus sirasinda 10 Hz'de anlamli deger
 * veriyor (−20,1 kW … +42,2 kW), birimi mW ve **pozitif deger tuketim**
 * demek. Dolayisiyla enerji, gucun zamana gore integralinden kuruluyor;
 * SoC farki yalnizca dogrulama icin kullaniliyor.
 *
 * SoC'ye dayali olcum secenegi elenmisti: `EV_BATTERY_LEVEL` %1 SoC = 0,66 kWh
 * adimlarla degisiyor, yani kisa mesafede hicbir sey olcemezdi.
 */
class EnergyAccountant {

    private var lastKw = 0.0
    private var lastNanos = 0L

    /**
     * "Onceki ornek var mi" bilgisi ayri tutuluyor. Onceki surumde
     * `lastNanos != 0L` kontrol ediliyordu; damgasi tam 0 olan bir ornekten
     * sonraki aralik sessizce dusuyordu ve integral bir ornek eksik cikiyordu.
     */
    private var hasPrev = false

    /** Net tuketim (kWh): tuketilen − geri kazanilan. */
    var netKwh = 0.0
        private set

    /** Yalnizca tuketim yonundeki enerji (kWh, pozitif). */
    var grossKwh = 0.0
        private set

    /** Rejenle geri kazanilan enerji (kWh, pozitif). */
    var regenKwh = 0.0
        private set

    var sampleCount = 0L
        private set

    /**
     * @param kw anlik guc; pozitif = tuketim, negatif = rejen
     * @param tNanos `CarPropertyValue.getTimestamp()` (elapsedRealtime tabanli)
     */
    @Synchronized
    fun onPower(kw: Double, tNanos: Long) {
        val prev = lastKw
        val prevT = lastNanos
        val had = hasPrev
        lastKw = kw
        lastNanos = tNanos
        hasPrev = true
        sampleCount++
        if (!had) return

        val dtHours = (tNanos - prevT) / 1e9 / 3600.0
        // Veri kaybi olan araliklari kopru yapma; 5 sn'den uzak bosluklari at.
        if (dtHours <= 0 || dtHours > MAX_GAP_HOURS) return

        // Yamuk kurali. 10 Hz'de isaret degisimini ortalamaya gore
        // siniflandirmak yeterli; hata mertebesi olcum gurultusunun altinda.
        val avgKw = (prev + kw) / 2.0
        val kwh = avgKw * dtHours
        netKwh += kwh
        if (avgKw >= 0) grossKwh += kwh else regenKwh -= kwh
    }

    @Synchronized
    fun reset() {
        lastKw = 0.0
        lastNanos = 0L
        hasPrev = false
        netKwh = 0.0
        grossKwh = 0.0
        regenKwh = 0.0
        sampleCount = 0
    }

    private companion object {
        const val MAX_GAP_HOURS = 5.0 / 3600.0
    }
}
