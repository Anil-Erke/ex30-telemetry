package com.example.ex30telemetry

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.example.ex30telemetry.calib.Calibration

/**
 * Yolculuk kaydinin sahibi olan konum tipli ON PLAN SERVISI.
 *
 * ## Iki isi var
 *
 * 1. **Uygulama acilmadan kayit (2026-09-28).** Arac acilinca
 *    [BootReceiver] bu servisi baslatiyor; servis veri katmanini
 *    ([JourneyData]) ve olcum gunlugunu ([Calibration]) kuruyor. Yolculuk
 *    durum makinesi ([com.example.ex30telemetry.trip.TripRecorder]) gerisini
 *    kendisi yapiyor: kontak, vites ve hizdan yolculugu acip kapatiyor.
 *
 * 2. **Arka plan konum kisitindan cikis (onceki JourneyLocationService).**
 *    Surucu baska uygulamaya gecince arac verisi akmaya devam ediyor ama
 *    Android konumu seyreltiyor (on planda 1 Hz, arka planda dakikada ~1 fix).
 *    Kisit izne degil surec durumuna bakiyor; konum tipli bir on plan servisi
 *    calisirken surec FGS durumunda kaliyor ve kisit hic uygulanmiyor.
 *
 * ## Head unit iki sekilde aciliyor — ikisi de karsilanmali
 *
 * Kalibrasyon basliklarindaki `elapsedRealtime` ile olculdu (12 acilis,
 * 2026-08-29 … 09-19): gun ici kisa parklarda head unit cogu zaman SIFIRDAN
 * aciliyor, gece uzun parklarda UYKUDAN uyaniyor (8-20 saat "acik" sayac).
 *
 *  - Sifirdan: [BootReceiver] `BOOT_COMPLETED` ile baslatiyor.
 *  - Uykudan: `BOOT_COMPLETED` gelmiyor. Servis zaten ayaktaysa uykudan oldugu
 *    gibi cikiyor; kontak ON olunca durum makinesi yolculugu aciyor. Servis
 *    olduruldugunde START_STICKY sistemin onu yeniden kurmasini istiyor.
 *
 * ## Arka plandan baslatma sarti: ACCESS_BACKGROUND_LOCATION
 *
 * Android 14+, uygulama on planda DEGILKEN baslatilan konum tipli bir servisi
 * bu izin olmadan reddediyor (`startForeground` SecurityException atiyor).
 * Acilista uygulama on planda degil — bu yuzden [BootReceiver] izin yoksa hic
 * denemiyor. Uygulama acildiginda baslatma ise izinsiz de calisiyor (onceki
 * surumlerdeki davranis).
 *
 * POST_NOTIFICATIONS ISTENMIYOR: izin yoksa bildirim gorunmuyor ama servis
 * calismaya devam ediyor (§6.12).
 */
class JourneyService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // intent null = sistem START_STICKY ile yeniden kurdu.
        val reason = intent?.getStringExtra(EXTRA_REASON) ?: REASON_RESTART

        val fgs = runCatching {
            startForeground(NOTIF_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        }
        if (fgs.isFailure) {
            running = false
            Log.w(TAG, "Ön plan servisi başlatılamadı ($reason)", fgs.exceptionOrNull())
            // Gunluk henuz yoksa bu satir kaybolur; o durumda yazacak yer de yok.
            Calibration.current()?.note(
                "svc", "açılamadı", reason, fgs.exceptionOrNull()?.javaClass?.simpleName,
            )
            lastStart = Start(reason, System.currentTimeMillis(), ok = false)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        running = true
        lastStart = Start(reason, System.currentTimeMillis(), ok = true)

        // Ikisi de idempotent: oturum once acildiysa mevcut ornekler donuyor.
        // onStartCommand ana thread'de — konum dinleyicisinin Looper'i o.
        Calibration.start(applicationContext)
        JourneyData.start(applicationContext)

        // Acilis tanisi: head unit "kac saattir acik" ile birlikte. Kucukse
        // sifirdan acilmis, buyukse uykudan uyanmistir (sinifin notuna bak).
        Calibration.current()?.note(
            "svc", "başladı", reason, "%.2f".format(SystemClock.elapsedRealtime() / 3_600_000.0),
        )
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        Calibration.current()?.note("svc", "kapandı")
        // Veri katmani BILEREK durdurulmuyor: oturum aciksa kayit surmeli.
        // Surec olurse zaten her sey onunla gidiyor.
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        // IMPORTANCE_LOW: aracin ekranina asilip surucuyu rahatsiz etmesin,
        // yalnizca bildirim listesinde dursun.
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.fgs_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        runCatching { nm.createNotificationChannel(channel) }
    }

    private fun notification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.fgs_title))
            .setContentText(getString(R.string.fgs_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()

    /** Son baslatma denemesi — Olcum ekrani gosteriyor (araçta logcat yok). */
    data class Start(val reason: String, val atEpoch: Long, val ok: Boolean)

    companion object {
        private const val TAG = "JourneySvc"
        // Kanal kimligi onceki surumle ayni: kullanicinin kanal ayari korunsun.
        private const val CHANNEL_ID = "ex30_telemetry_trip"
        private const val NOTIF_ID = 1
        private const val EXTRA_REASON = "reason"

        const val REASON_APP = "uygulama"
        const val REASON_BOOT = "açılış"
        const val REASON_UPDATE = "güncelleme"
        const val REASON_PERMISSION = "izin"
        const val REASON_RESTART = "yeniden"

        /**
         * Servis su an ayakta mi — Olcum ekrani bunu gosteriyor.
         *
         * Araçta logcat okunamiyor; "arka planda konum geliyor mu" sorusunun
         * cevabi ekranda gorunmezse bir daha gunler suren bir teshis olurdu.
         */
        @Volatile
        var running = false
            private set

        @Volatile
        var lastStart: Start? = null
            private set

        /**
         * Servisi baslatir. Konum izni yoksa HIC denemiyor: konum tipli servis
         * izinsiz `startForeground`'da SecurityException atar.
         *
         * Arka plandan cagrilacaksa (acilis) cagiran taraf
         * [Permissions.canAutoStart]'a da bakmali.
         */
        fun start(context: Context, reason: String) {
            if (!Permissions.hasLocation(context)) return
            val intent = Intent(context.applicationContext, JourneyService::class.java)
                .putExtra(EXTRA_REASON, reason)
            runCatching {
                context.applicationContext.startForegroundService(intent)
            }.onFailure {
                // Android 12+ arka plandan baslatmayi reddedebilir. Cokme sebebi
                // olmamali: uygulama acikken kayit yine calisiyor.
                Log.w(TAG, "startForegroundService reddedildi ($reason)", it)
                Calibration.current()?.note("svc", "reddedildi", reason, it.javaClass.simpleName)
                lastStart = Start(reason, System.currentTimeMillis(), ok = false)
            }
        }
    }
}
