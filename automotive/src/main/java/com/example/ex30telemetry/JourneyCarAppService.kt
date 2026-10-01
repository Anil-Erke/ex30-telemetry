package com.example.ex30telemetry

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.ex30telemetry.calib.Calibration
import com.example.ex30telemetry.screen.LiveScreen
import com.example.ex30telemetry.screen.LiveTemplateScreen
import com.example.ex30telemetry.screen.TripFormat


/**
 * TASLAK ANAHTARI - Play "Parked Experiences" bulgusu (8 Eylul 2026).
 *
 *  true  : Surface KULLANMAYAN, tamamen sablon tabanli canli ekran (uyumlu aday)
 *  false : mevcut NavigationTemplate + Surface ekrani (reddedilen hali)
 *
 * Tek satirlik geri donus icin boyle birakildi; karar verilince biri silinir.
 */
private const val TEMPLATE_ONLY_DRAFT = false

class JourneyCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        // Debug derlemede emulator/yerel host'lara izin ver; yayin derlemesinde
        // yalnizca imzasi dogrulanan bilinen host'lar baglanabilsin (prompt.md §7.2).
        return if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }
    }

    override fun onCreateSession(): Session = JourneySession()
}

class JourneySession : Session(), DefaultLifecycleObserver {

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreateScreen(intent: Intent): Screen {
        // Bicimleyici aracin dilini buradan aliyor; oturum bitince birakiliyor.
        TripFormat.attach(carContext)

        // Veri katmani ve kalibrasyon sondasi Screen'den de oturumdan da
        // bagimsiz: arac acilisinda JourneyService zaten kurmus olabilir, o
        // durumda mevcut ornekler donuyor. Oturumun katkisi yalnizca CarContext.
        val app = carContext.applicationContext
        Calibration.start(app)
        JourneyData.start(app)
        Calibration.attachCarContext(carContext)
        JourneyData.attachCarContext(carContext)

        // Servis burada da baslatiliyor: arka plan konum izni yoksa acilista
        // baslayamamistir, uygulama su an on planda oldugu icin bu cagri o
        // izin olmadan da gecerli (Android 12+ kurali). Zaten calisiyorsa
        // yalnizca gunluge "uygulama" satiri dusuyor.
        JourneyService.start(carContext, JourneyService.REASON_APP)
        return if (TEMPLATE_ONLY_DRAFT) LiveTemplateScreen(carContext)
        else LiveScreen(carContext)
    }

    /**
     * Gunduz/gece gecisinde ekrani hemen yeniden ciz. Surface 1 Hz'de zaten
     * yenileniyor ama tema degisiminde bir saniye eski palette kalmasin.
     */
    override fun onCarConfigurationChanged(newConfiguration: Configuration) {
        runCatching {
            carContext.getCarService(ScreenManager::class.java).top.invalidate()
        }
    }

    /**
     * Uygulama on plana/arka plana gecince gunluge bir satir yaz.
     *
     * **Neden:** surus sirasinda baska bir uygulamaya gecildiginde hiz ve irtifa
     * grafiklerinde uzun duz cizgiler olusuyor. Araç verisinin kesilmedigi
     * olculdu (58 dk boyunca en buyuk bosluk 222 ms), yani suphe konumda —
     * Android arka plandaki sureclerin konum guncellemelerini kisitliyor. Ama
     * gunlukte uygulamanin on planda mi arka planda mi oldugunu soyleyen hicbir
     * sey yoktu, dolayisiyla iki olay eslestirilemiyordu. Bu satir onu kapatiyor:
     * `# gps` satirlariyla yan yana konunca kisitlama varsa hemen gorunecek.
     */
    override fun onStart(owner: LifecycleOwner) {
        Calibration.current()?.note("app", "ön plan")
    }

    override fun onStop(owner: LifecycleOwner) {
        Calibration.current()?.note("app", "arka plan")
    }

    /**
     * Oturum kapaninca kayit DURMUYOR (2026-09-28): veri katmani ve gunluk
     * JourneyService'le birlikte yasamaya devam ediyor. Yalnizca oturumla olen
     * CarContext sokuluyor.
     */
    override fun onDestroy(owner: LifecycleOwner) {
        Calibration.current()?.note("app", "kapandı")
        JourneyData.detachCarContext()
        Calibration.detachCarContext()
        TripFormat.detach()
    }
}
