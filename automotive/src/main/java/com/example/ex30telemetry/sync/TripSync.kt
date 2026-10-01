package com.example.ex30telemetry.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.ex30telemetry.calib.Calibration
import com.example.ex30telemetry.google.DriveClient
import com.example.ex30telemetry.google.GoogleAuth
import com.example.ex30telemetry.trip.TrackRecorder
import com.example.ex30telemetry.trip.TripStore
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Yolculuk bitince, baglanan Google hesabinin Drive'ina OTOMATIK yukleme
 * (protokol 3, drive-sync/PROTOKOL.md §4). Hesap bagli degilse hicbir sey
 * denenmiyor; kuyruk birikiyor ve baglaninca bosaliyor.
 *
 * Kuyruk ve gonderim mantigi [TripOutbox]'ta (Android'siz, testli); bu sinif
 * yalnizca NE ZAMAN denenecegini yonetiyor:
 *
 *  - yolculuk kapaninca ([enqueue])
 *  - servis/katman baslarken ([start]) — head unit uyurken kalmis olanlar
 *  - ag gelince — arac otoparkta cekmiyor olabilir
 *  - basarisizlikta 1 → 5 → 15 → 30 → 60 dk geri cekilmeyle
 *
 * **Tek arka thread:** gonderimler sirayla; ayni yolculugu iki thread ayni
 * anda gondermiyor. Ana thread'de ag cagrisi yok.
 */
class TripSync(
    context: Context,
    private val store: TripStore,
    private val tracks: TrackRecorder,
) {
    private val appContext: Context = context.applicationContext
    val outbox = TripOutbox(File(appContext.filesDir, OUTBOX_DIR))
    private val sender = DriveTripSender(DriveClient(appContext))

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "TripSync").apply { isDaemon = true }
    }
    private val handler = Handler(Looper.getMainLooper())
    private val retry = Runnable { kick(REASON_RETRY) }
    private var backoffStep = 0
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    /** Son bosaltma turunun sonucu — Olcum ekrani gosteriyor (araçta logcat yok). */
    data class Status(val atEpoch: Long, val reason: String, val outcome: TripOutbox.Outcome)

    @Volatile
    var lastStatus: Status? = null
        private set

    @Volatile
    var busy = false
        private set

    fun start() {
        backfillOnce()
        registerNetwork()
        kick(REASON_START)
    }

    fun stop() {
        handler.removeCallbacks(retry)
        netCallback?.let { cb ->
            runCatching {
                appContext.getSystemService(ConnectivityManager::class.java)
                    ?.unregisterNetworkCallback(cb)
            }
        }
        netCallback = null
        executor.shutdown()
    }

    /** Kapanan yolculugu kuyruga koyar ve hemen dener. */
    fun enqueue(startEpoch: Long) {
        outbox.enqueue(startEpoch)
        kick(REASON_TRIP)
    }

    fun pendingCount(): Int = outbox.pending().size

    /** Bosaltmayi arka thread'e siralar. Hesap bagli degilse hic denemiyor. */
    fun kick(reason: String) {
        if (!GoogleAuth.isLinked(appContext)) return
        runCatching { executor.execute { drain(reason) } }
            .onFailure { Log.w(TAG, "kuyruk kapali ($reason)", it) }
    }

    private fun drain(reason: String) {
        if (outbox.pending().isEmpty()) return
        busy = true
        try {
            val trips = store.trips().associateBy { it.startEpoch }
            val outcome = outbox.drain(
                tripOf = { trips[it] },
                trackOf = { tracks.trackFile(it) },
                sender = sender,
            )

            lastStatus = Status(System.currentTimeMillis(), reason, outcome)
            Calibration.current()?.note(
                "sync", reason, outcome.uploaded, outcome.rejected, outcome.dropped,
                outbox.pending().size, outcome.error.orEmpty().take(80),
            )
            scheduleRetry(outcome.error != null)
        } catch (t: Throwable) {
            // Beklenmedik bir hata thread'i oldurmesin; bir sonraki tetik yeniden dener.
            Log.w(TAG, "yükleme turu düştü ($reason)", t)
            scheduleRetry(true)
        } finally {
            busy = false
        }
    }

    private fun scheduleRetry(failed: Boolean) {
        handler.removeCallbacks(retry)
        if (!failed) {
            backoffStep = 0
            return
        }
        val delay = BACKOFF_MS[backoffStep.coerceAtMost(BACKOFF_MS.lastIndex)]
        backoffStep++
        handler.postDelayed(retry, delay)
    }

    /**
     * Bu surumle ilk calismada araçtaki BUTUN yolculuklari kuyruga koyar:
     * gecmis Drive'a tasinsin, telefon uygulamasi bos baslamasin. Gonderen taraf
     * var olan dosyayi yeniden yazmadigi icin tekrar calismasi zararsiz; bayrak
     * yalnizca gereksiz trafigi onluyor.
     *
     * Hesap degisince ([onAccountChanged]) bayrak sifirlaniyor: yeni hesabin
     * Drive'i bos, gecmis ona da gitmeli.
     */
    private fun backfillOnce() {
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_BACKFILL, false)) return
        val all = store.trips()
        all.forEach { outbox.enqueue(it.startEpoch) }
        prefs.edit().putBoolean(KEY_BACKFILL, true).apply()
        Calibration.current()?.note("sync", "geçmiş kuyruğa alındı", all.size)
    }

    /** Google hesabi baglandi ya da degisti: gecmisi yeniden kuyruga al ve dene. */
    fun onAccountChanged() {
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_BACKFILL, false).apply()
        backfillOnce()
        backoffStep = 0
        kick(REASON_ACCOUNT)
    }

    private fun registerNetwork() {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                kick(REASON_NETWORK)
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }
            .onSuccess { netCallback = cb }
            .onFailure { Log.w(TAG, "ağ dinleyicisi kurulamadı", it) }
    }

    companion object {
        private const val TAG = "JourneySync"
        const val OUTBOX_DIR = "outbox"
        private const val PREFS = "trip_sync"
        private const val KEY_BACKFILL = "backfill_v3_done"

        const val REASON_START = "başlangıç"
        const val REASON_TRIP = "yolculuk"
        const val REASON_NETWORK = "ağ"
        const val REASON_RETRY = "yeniden"
        const val REASON_MANUAL = "elle"
        const val REASON_ACCOUNT = "hesap"

        private val BACKOFF_MS = longArrayOf(
            60_000L, 5 * 60_000L, 15 * 60_000L, 30 * 60_000L, 60 * 60_000L,
        )
    }
}
