package com.example.ex30telemetry.trip

/**
 * A4 — performans ölçümleri, sürüş sırasında **kendiliğinden** yakalanır.
 *
 * EX30 0-100'deki `SprintTimer` mantığı üzerine kurulu ama bir farkla: orada
 * kullanıcı "Hazır"a basıyordu, burada kimse hiçbir şeye dokunmuyor. Ölçüm
 * fırsatı geçtiğinde yakalanır, geçmediğinde hiçbir şey olmaz.
 *
 * Doğruluk notları:
 * - Zaman ekseni `CarPropertyValue.getTimestamp()` — VHAL'in ölçüm anı, callback
 *   gecikmesi değil (prompt.md §10.6).
 * - Eşiğin geçildiği an iki örnek arasında **doğrusal interpolasyonla** bulunur;
 *   aksi halde sonuç örnekleme aralığına (gerçek EX30'da 100 ms) yuvarlanırdı.
 * - Kalkış anı için v=0'a geriye interpolasyon yapılır, sonra bir sonraki
 *   örnekten hesaplanan ivmeyle rafine edilir.
 *
 * **Taban belirsizlik:** Faz 0'da gerçek araçta ölçülen zaman damgaları tam
 * ±0 ms sapma gösterdi — gerçek bir donanım örneklemesi bu kadar mükemmel
 * olmaz. Damgalar ölçüm anını değil sabit bir zaman ızgarasını yansıtıyor
 * olabilir. İnterpolasyon bunu gideremez; sonuçlar ±50 ms mertebesinde bir
 * taban belirsizlik taşır ve ekranda bu söylenir.
 */
class PerformanceCatcher(private val onCaught: (PerfRecord, PerfKind) -> Unit) {

    private var prevKmh = 0.0
    private var prevNanos = 0L
    private var hasPrev = false

    // --- Hızlanma koşusu (0-60, 0-100) ---
    private var accelActive = false
    private var accelT0 = 0L
    private var accelT0Refined = false
    private var accelLaunchKmh = 0.0
    private var accelLaunchNanos = 0L
    private var accelPrevNanos = 0L
    private var accelPeak = 0.0
    private val accelDone = HashSet<PerfKind>()

    // --- Elastikiyet (80-120) ---
    private var elasticActive = false
    private var elasticT80 = 0L

    // --- Fren (100-0) ---
    private var brakeActive = false
    private var brakeDistanceM = 0.0
    private var brakeMinKmh = 0.0

    /** Bir ölçüm anında hangi tür yakalandıysa dışarıya bildirilir. */
    fun onSpeed(kmh: Double, tNanos: Long) {
        val pKmh = prevKmh
        val pNanos = prevNanos
        val had = hasPrev
        prevKmh = kmh
        prevNanos = tNanos
        hasPrev = true
        if (!had) return
        // Damga geri gitmişse (kaynak değişimi) durumu sıfırla.
        if (tNanos <= pNanos) { abortAll(); return }

        stepAccel(pKmh, pNanos, kmh, tNanos)
        stepElastic(pKmh, pNanos, kmh, tNanos)
        stepBrake(pKmh, pNanos, kmh, tNanos)
    }

    fun reset() {
        hasPrev = false
        abortAll()
    }

    private fun abortAll() {
        accelActive = false
        elasticActive = false
        brakeActive = false
        accelDone.clear()
        brakeDistanceM = 0.0
    }

    // --- 0-60 / 0-100 ---

    private fun stepAccel(pKmh: Double, pNanos: Long, kmh: Double, tNanos: Long) {
        if (!accelActive) {
            // Duruştan kalkış: önceki örnek duruyor, bu örnek eşiği aştı.
            if (pKmh <= STOP_KMH && kmh > LAUNCH_KMH) {
                accelActive = true
                accelDone.clear()
                accelPeak = kmh
                accelT0Refined = false
                accelLaunchKmh = kmh
                accelLaunchNanos = tNanos
                accelPrevNanos = pNanos
                // İlk tahmin: hızı 0'a doğru geriye uzat.
                accelT0 = crossingTime(pKmh, pNanos, kmh, tNanos, 0.0)
            }
            return
        }

        // Kalkış anını bir sonraki örnekten hesaplanan ivmeyle rafine et.
        if (!accelT0Refined && tNanos > accelLaunchNanos) {
            val dtSec = (tNanos - accelLaunchNanos) / NANOS
            val aKmhPerSec = (kmh - accelLaunchKmh) / dtSec
            if (aKmhPerSec > MIN_REFINE_ACCEL) {
                val backNanos = (accelLaunchKmh / aKmhPerSec * NANOS).toLong()
                accelT0 = (accelLaunchNanos - backNanos)
                    .coerceIn(accelPrevNanos, accelLaunchNanos)
            }
            accelT0Refined = true
        }

        if (kmh > accelPeak) accelPeak = kmh

        // Sürekli olmayan hızlanma ölçüm sayılmaz: zirveden belirgin düşüş iptal.
        if (kmh < accelPeak - DROP_TOL_KMH) { accelActive = false; return }
        if (tNanos - accelT0 > MAX_ACCEL_NANOS) { accelActive = false; return }

        catchCross(PerfKind.SPRINT_0_60, 60.0, pKmh, pNanos, kmh, tNanos, accelT0, accelDone)
        catchCross(PerfKind.SPRINT_0_100, 100.0, pKmh, pNanos, kmh, tNanos, accelT0, accelDone)
        if (accelDone.contains(PerfKind.SPRINT_0_100)) accelActive = false
    }

    // --- 80-120 ---

    private fun stepElastic(pKmh: Double, pNanos: Long, kmh: Double, tNanos: Long) {
        if (!elasticActive) {
            if (pKmh < ELASTIC_FROM && kmh >= ELASTIC_FROM) {
                elasticActive = true
                elasticT80 = crossingTime(pKmh, pNanos, kmh, tNanos, ELASTIC_FROM)
            }
            return
        }
        if (kmh < ELASTIC_FROM - DROP_TOL_KMH) { elasticActive = false; return }
        if (tNanos - elasticT80 > MAX_ELASTIC_NANOS) { elasticActive = false; return }

        if (pKmh < ELASTIC_TO && kmh >= ELASTIC_TO) {
            val tCross = crossingTime(pKmh, pNanos, kmh, tNanos, ELASTIC_TO)
            emit(PerfKind.ELASTIC_80_120, (tCross - elasticT80) / NANOS, tCross)
            elasticActive = false
        }
    }

    // --- 100-0 fren mesafesi ---

    private fun stepBrake(pKmh: Double, pNanos: Long, kmh: Double, tNanos: Long) {
        if (!brakeActive) {
            if (pKmh > BRAKE_FROM && kmh <= BRAKE_FROM) {
                brakeActive = true
                brakeDistanceM = 0.0
                brakeMinKmh = kmh
                // 100 km/h'nin geçildiği andan itibaren say: örnek tam 100'de
                // olmadığı için kalan parçayı interpolasyonla ekle.
                val tCross = crossingTime(pKmh, pNanos, kmh, tNanos, BRAKE_FROM)
                brakeDistanceM += trapezoidM(BRAKE_FROM, kmh, tNanos - tCross)
            }
            return
        }

        brakeDistanceM += trapezoidM(pKmh, kmh, tNanos - pNanos)
        if (kmh < brakeMinKmh) brakeMinKmh = kmh

        // Tekrar hızlanma: sürekli bir fren olayı değil.
        if (kmh > brakeMinKmh + RISE_TOL_KMH) { brakeActive = false; return }
        // Ornekler arasi bosluk buyukse mesafe integrali guvenilmez.
        // NOT: prevNanos bu noktada zaten tNanos'a esitlenmis durumda; onceki
        // ornegin damgasi pNanos'ta.
        if (tNanos - pNanos > MAX_BRAKE_NANOS) { brakeActive = false; return }

        if (kmh <= STOP_KMH) {
            emit(PerfKind.BRAKE_100_0, brakeDistanceM, tNanos)
            brakeActive = false
        }
    }

    // --- Yardımcılar ---

    private fun catchCross(
        kind: PerfKind, target: Double,
        pKmh: Double, pNanos: Long, kmh: Double, tNanos: Long,
        t0: Long, done: MutableSet<PerfKind>,
    ) {
        if (done.contains(kind)) return
        if (pKmh >= target || kmh < target) return
        val tCross = crossingTime(pKmh, pNanos, kmh, tNanos, target)
        done += kind
        emit(kind, (tCross - t0) / NANOS, tCross)
    }

    /**
     * Olcumu kayda gonderir — ama once **makul mu** diye bakar.
     *
     * `MAX_ACCEL_NANOS` (60 sn) yakalamanin ust siniri; zirveden dusus iptali
     * ona bagli oldugu icin genis birakildi. Ama 60 saniyede tamamlanan bir
     * "0-100" performans olcumu degil, trafikte siradan bir hizlanmadir.
     * Gercek kayitlarda `0-60: 49,1 s`, `80-120: 49,7 s`, `100-0: 1099,9 m`
     * gibi satirlar birikmisti: en iyi degeri bozmuyorlar ama yolculuk
     * kayitlarini kirletiyor ve Rekorlar ekranini anlamsizlastiriyorlardi.
     *
     * Esikler EX30'un fiziksel siniriyla degil, "bu bir performans denemesi
     * miydi" sorusuyla belirlendi: gercek bir 0-100 ~6 sn, kotu kosulda 10 sn;
     * 15 saniyeyi asan bir gecis artik denemenin degil trafigin olcusudur.
     */
    private fun emit(kind: PerfKind, value: Double, tNanos: Long) {
        if (value <= 0 || value.isNaN()) return
        if (value > kind.plausibleMax) return
        onCaught(PerfRecord.of(kind, value, System.currentTimeMillis()), kind)
    }

    /** İki örnek arasında [target] hızına ulaşılan anı doğrusal olarak bulur. */
    private fun crossingTime(v0: Double, t0: Long, v1: Double, t1: Long, target: Double): Long {
        if (v1 == v0) return t1
        val ratio = ((target - v0) / (v1 - v0)).coerceIn(0.0, 1.0)
        return t0 + ((t1 - t0) * ratio).toLong()
    }

    /** İki hız örneği arasında kat edilen mesafe (yamuk kuralı), metre. */
    private fun trapezoidM(kmh0: Double, kmh1: Double, dtNanos: Long): Double {
        if (dtNanos <= 0) return 0.0
        val avgMps = (kmh0 + kmh1) / 2.0 / 3.6
        return avgMps * (dtNanos / NANOS)
    }

    private companion object {
        const val NANOS = 1_000_000_000.0

        /** Bu hızın altında araç duruyor kabul edilir. */
        const val STOP_KMH = 0.4

        /** Bu hızın üzerine çıkınca "kalktı" sayılır (gürültü payı). */
        const val LAUNCH_KMH = 0.8

        /** Zirveden bu kadar düşüş hızlanma ölçümünü iptal eder. */
        const val DROP_TOL_KMH = 3.0

        /** Frende bu kadar tekrar hızlanma ölçümü iptal eder. */
        const val RISE_TOL_KMH = 2.0

        const val ELASTIC_FROM = 80.0
        const val ELASTIC_TO = 120.0
        const val BRAKE_FROM = 100.0

        /** Kalkış anını rafine etmek için gereken en küçük ivme (km/h/sn). */
        const val MIN_REFINE_ACCEL = 1.0

        const val MAX_ACCEL_NANOS = 60_000_000_000L
        const val MAX_ELASTIC_NANOS = 60_000_000_000L

        /** İki örnek arası bu kadar boşluk varsa fren ölçümü güvenilmez. */
        const val MAX_BRAKE_NANOS = 2_000_000_000L
    }
}
