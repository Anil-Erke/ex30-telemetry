package com.example.ex30telemetry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Head unit acilinca (ve uygulama guncellenince) [JourneyService]'i baslatir.
 *
 * Bu iki yayin, Android 12+'nin "arka plandan on plan servisi baslatilamaz"
 * kuralinin resmi istisnalari. Android 15'in `BOOT_COMPLETED` kisiti ise
 * yalnizca dataSync/camera/mediaPlayback/phoneCall/mediaProjection/microphone
 * turlerini kapsiyor; bizim tur `location`.
 *
 * **Uykudan uyanmada bu alici CALISMAZ** — `BOOT_COMPLETED` yalnizca sifirdan
 * acilista geliyor. O durum servisin kendisinin ayakta kalmasiyla karsilaniyor
 * (bkz. [JourneyService] sinif notu).
 *
 * `MY_PACKAGE_REPLACED`: Play guncellemesi sureci olduruyor. Yakalanmazsa yeni
 * surum, arac bir sonraki sifirdan acilisa kadar uygulama acilmadan kayit
 * yapmazdi — ve head unit gece boyunca uyudugu icin bu gunler surebilir.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val reason = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> JourneyService.REASON_BOOT
            Intent.ACTION_MY_PACKAGE_REPLACED -> JourneyService.REASON_UPDATE
            else -> return
        }
        if (!Permissions.canAutoStart(context)) {
            // Gunluk henuz acik degil; bu satir yalnizca logcat'e gidiyor.
            // Olcum ekranindaki "Otomatik başlatma" satiri ayni bilgiyi veriyor.
            Log.i(TAG, "otomatik başlatma atlandı ($reason): izin eksik")
            return
        }
        Log.i(TAG, "servis başlatılıyor ($reason)")
        JourneyService.start(context, reason)
    }

    private companion object {
        const val TAG = "JourneyBoot"
    }
}
