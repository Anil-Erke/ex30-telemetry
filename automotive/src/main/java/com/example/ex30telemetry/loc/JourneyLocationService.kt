package com.example.ex30telemetry.loc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import com.example.ex30telemetry.R
import com.example.ex30telemetry.calib.Calibration

/**
 * Konum tipli ON PLAN SERVISI. Kendisi hicbir sey olcmuyor; tek isi, uygulama
 * acikken onu Android'in **arka plan konum kisitindan** cikarmak.
 *
 * **Neden gerekli (olculdu):** surucu baska bir uygulamaya gectiginde arac
 * verisi (hiz, guc, SoC) 10 Hz akmaya devam ediyor — kesilen tek sey konum.
 * 58 dakikalik bir sursuste arac verisindeki en buyuk bosluk 222 ms olcuLdu,
 * buna karsilik konum on planda 1 Hz, arka planda dakikada ~1 fix'e dusuyor.
 * Sebep izin degil, KISIT: Android, on planda olmayan bir uygulamanin konum
 * guncellemelerini seyreltiyor.
 *
 * Bunun sonucu yalnizca yarim cozulmustu: mesafe ham hiz integralinden
 * yurutulebiliyor ([TripAccumulator.bridgeIfGpsStale]), ama **irtifa
 * yurutulemiyor** — yukseklik yalnizca konumdan geliyor. Arka planda gecen her
 * kilometre irtifa grafiginde duz bir cizgi, tirmanis/inis toplaminda da eksik
 * metre demek.
 *
 * `ACCESS_BACKGROUND_LOCATION` bu iste ISE YARAMAZ: kisit, izne degil
 * uygulamanin surec durumuna bakiyor; ustelik Play'in ayri bir arka plan konum
 * beyani gerekirdi. Tek dogru yol konum tipli bir on plan servisi calistirmak —
 * o an surec durumu FGS oluyor ve kisit hic uygulanmiyor.
 *
 * Servis Session acilirken baslatiliyor (uygulama o anda kesin on plandadir;
 * Android 12+ ARKA PLANDAN on plan servisi baslatmayi reddediyor) ve Session
 * kapaninca durduruluyor.
 */
class JourneyLocationService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        runCatching {
            startForeground(
                NOTIF_ID,
                notification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
            running = true
            Calibration.current()?.note("fgs", "ön plan servisi açık")
        }.onFailure {
            running = false
            Log.w(TAG, "Ön plan servisi başlatılamadı", it)
            Calibration.current()?.note("fgs", "açılamadı: ${it.javaClass.simpleName}")
        }
        // Yeniden baslatmanin anlami yok: servis Session'a bagli, Session yoksa
        // izlenecek bir yolculuk da yok.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        Calibration.current()?.note("fgs", "ön plan servisi kapandı")
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

    companion object {
        private const val TAG = "JourneyFgs"
        private const val CHANNEL_ID = "ex30_telemetry_trip"
        private const val NOTIF_ID = 1

        /**
         * Servis su an ayakta mi — Araç verileri ekrani bunu gosteriyor.
         *
         * Araçta logcat okunamiyor; "arka planda konum geliyor mu" sorusunun
         * cevabi ekranda gorunmezse bir daha gunler suren bir teshis olurdu.
         */
        @Volatile
        var running = false
            private set

        fun start(context: Context) {
            val intent = Intent(context.applicationContext, JourneyLocationService::class.java)
            runCatching {
                context.applicationContext.startForegroundService(intent)
            }.onFailure {
                // Android 12+ arka plandan baslatmayi reddedebilir. Cokme sebebi
                // olmamali: kopruleme hala calisiyor, yalnizca irtifa eksik kalir.
                Log.w(TAG, "startForegroundService reddedildi", it)
                Calibration.current()?.note("fgs", "reddedildi: ${it.javaClass.simpleName}")
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context.applicationContext, JourneyLocationService::class.java)
            runCatching { context.applicationContext.stopService(intent) }
            running = false
        }
    }
}
