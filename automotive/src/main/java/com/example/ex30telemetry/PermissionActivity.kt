package com.example.ex30telemetry

import android.app.Activity
import android.os.Bundle
import android.util.Log

/**
 * Izinleri isteyen kendi aktivitemiz.
 *
 * ## Neden var — `CarContext.requestPermissions` KULLANILMIYOR
 *
 * **Araçta olculdu (2026-09-10, yazilim 2.1.2 / Android 15):** izin ekranindaki
 * "İzin ver" dugmesine basildiginda uygulama COKUYOR. Ayni davranis bu makinede
 * gelistirilen butun AAOS uygulamalarinda goruldu, yani sorun bizim izin
 * listemizde degil, kutuphanenin akisinda.
 *
 * Car App Library 1.4.0'in `CarContext.requestPermissions` cagrisi kendi
 * aktivitesini aciyor ve o aktivite AAR manifestinde soyle tanimli:
 *
 * ```xml
 * <activity android:name="androidx.car.app.CarAppPermissionActivity"
 *           android:exported="false"
 *           android:theme="@android:style/Theme.Translucent.NoTitleBar" />
 * ```
 *
 * **Saydam (translucent) bir aktivite** — Android'in "yalnizca tam ekran opak
 * aktiviteler yonelim isteyebilir" kuralinin cakistigi bilinen bir cokme
 * sinifidir ve Android 15'in aktivite/yonelim kisitlari bu tarafta sertlesti.
 * Araçta `adb` olmadigi icin yigin izini alinamiyor (prompt.md §11), dolayisiyla
 * hangi satirin attigini kanitlayamiyoruz. Bu yuzden teshis etmek yerine
 * **supheli yolu tamamen devre disi birakiyoruz**: izni platformun kendi
 * API'siyle, kendi OPAK aktivitemizden istiyoruz.
 *
 * Sablon kullanmayan normal Activity yolunun bu araçta calistigi zaten
 * dogrulanmisti (prompt.md §4.1 — EX30 Browser ve File Explorer).
 *
 * ## Neden tema opak
 *
 * `CarAppTheme` opak. Saydam yapmak yukaridaki cokme sinifini geri getirir;
 * kisa sureligine gorunen duz bir ekran bunun yaninda bedelsiz.
 *
 * ## `distractionOptimized` BILEREK yok
 *
 * Izin diyalogu surus sirasinda acilmamali; sistem engellesin (§4.2).
 *
 * ## Iki asamali istek — arka plan konumu AYRI (2026-09-28)
 *
 * Otomatik baslatma icin `ACCESS_BACKGROUND_LOCATION` gerekiyor ve Android 11+
 * onu digerleriyle AYNI istekte vermiyor. Sira: once normal izinler, sonuc
 * gelince (on plan konumu verildiyse) arka plan konumu tek basina. Android 11+
 * bu ikinci istekte diyalog yerine uygulamanin konum izni sayfasini aciyor;
 * surucu orada "Her zaman izin ver"i seciyor.
 *
 * Normal izinler zaten tamsa aktivite dogrudan ikinci asamaya geciyor — Olcum
 * ekranindaki "Otomatik başlatma" satiri bu yolu kullaniyor.
 */
class PermissionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ekran donunce istek bastan gonderilmesin; sistem diyalogu zaten ayakta.
        if (savedInstanceState != null) return

        // Platformda TANIMSIZ olan bir izni istemek sessiz redle sonuclanir ve
        // sonucu okuyan tarafta "kullanici reddetti" gibi gorunur. Istemeden
        // once ayikliyoruz — hangisinin tanimsiz oldugu sonda raporunda ayri
        // bir durum olarak gosteriliyor.
        val missing = Permissions.requestableMissing(this)
        if (missing.isEmpty()) {
            requestBackgroundOrFinish()
            return
        }

        Log.i(TAG, "izin isteniyor: ${missing.joinToString()}")
        runCatching { requestPermissions(missing.toTypedArray(), REQUEST_CODE) }
            .onFailure {
                Log.w(TAG, "izin isteği açılamadı", it)
                finish()
            }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Sonuc ne olursa olsun ilerliyoruz: kismi izin de kabul, cagiran ekran
        // durumu kendi yeniden okuyor (PermissionScreen.onResume).
        if (requestCode == REQUEST_CODE) requestBackgroundOrFinish() else finish()
    }

    /**
     * Ikinci asama. On plan konumu yoksa arka plan konumu istenemez (sistem
     * sessizce reddeder), o durumda dogrudan kapaniyoruz.
     */
    private fun requestBackgroundOrFinish() {
        val bg = Permissions.BACKGROUND_LOCATION
        if (!Permissions.hasLocation(this) ||
            Permissions.hasBackgroundLocation(this) ||
            !Permissions.definedOnPlatform(this, bg)
        ) {
            finish()
            return
        }
        Log.i(TAG, "arka plan konumu isteniyor")
        runCatching { requestPermissions(arrayOf(bg), REQUEST_CODE_BACKGROUND) }
            .onFailure {
                Log.w(TAG, "arka plan konum isteği açılamadı", it)
                finish()
            }
    }

    companion object {
        private const val TAG = "JourneyPerm"
        private const val REQUEST_CODE = 1001
        private const val REQUEST_CODE_BACKGROUND = 1002
    }
}
