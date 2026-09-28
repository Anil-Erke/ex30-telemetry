package com.example.ex30telemetry.screen

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.ex30telemetry.PermissionActivity
import com.example.ex30telemetry.Permissions
import com.example.ex30telemetry.R

/**
 * Izin akisi. LiveScreen eksik izin varken bu ekrani ustune iter; izinler
 * verilince kendini kapatir ve LiveScreen'e donulur.
 *
 * Uc izin birden isteniyor:
 *  - Konum: irtifa yalnizca GPS'ten geliyor; mesafe de oyle. (Odometrenin
 *    Android 15'te CAR_MILEAGE_3P ile acilip acilmadigi HENUZ OLCULMEDI —
 *    CarPropertyProbe cevaplayacak. Acilsa bile irtifa icin konum sart.)
 *  - CAR_ENERGY / CAR_SPEED: bu ikisi calisma ani izni, istenmezse araç
 *    verisi hic gelmiyor (bkz. [Permissions]).
 *  - Android 15 ile gelen `*_3P` izinleri (odometre, lastik, direksiyon):
 *    OPSIYONEL. Reddedilmeleri uygulamayi durdurmaz; yalnizca ilgili satir
 *    gorunmez. Kapiya dahil EDILMEDILER, yoksa reddedilen tek bir izin
 *    izin ekranini sonsuz donguye sokardi.
 *
 * ## `CarContext.requestPermissions` KULLANILMIYOR
 *
 * 2026-09-10'da araçta olculdu: o cagri Android 15'te uygulamayi cokertiyor
 * (gerekcesi [PermissionActivity]'de). Yerine kendi opak aktivitemiz aciliyor.
 * Ikinci dugme garantili kacis yolu: uygulamanin sistem ayar sayfasi. Kullanici
 * izinleri zaten elle oradan veriyordu; en azindan menude dolastirmayalim.
 *
 * Aktivite kapaninca buraya donuluyor ve onResume izinleri yeniden okuyor --
 * sonucu geri tasiyan bir geri cagirim yok, olmasi da gerekmiyor.
 */
class PermissionScreen(
    carContext: CarContext,
    private val onGranted: () -> Unit,
) : Screen(carContext), DefaultLifecycleObserver {

    init {
        lifecycle.addObserver(this)
    }

    /** Izin aktivitesinden ya da sistem ayarlarindan donunce durumu yeniden oku. */
    override fun onResume(owner: LifecycleOwner) {
        if (Permissions.missing(carContext).isEmpty()) done()
    }

    override fun onGetTemplate(): Template =
        // Host bu mesaji ~2 satirda kirpiyor; uzun metin ekranda kayboluyor.
        // Ayrintili aciklama gizlilik politikasinda.
        MessageTemplate.Builder(carContext.getString(R.string.perm_message))
            .setTitle(carContext.getString(R.string.perm_title))
            .addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.perm_grant))
                    .setOnClickListener { requestPermissions() }
                    .build()
            )
            // MessageTemplate en fazla iki aksiyon aliyor; ikincisi kacis yolu.
            .addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.perm_settings))
                    .setOnClickListener { openAppSettings() }
                    .build()
            )
            .build()

    /**
     * Opsiyoneller de ayni diyalogda sorulsun -- surucuye iki kere izin ekrani
     * gostermenin anlami yok. Sonuclarina BAKILMIYOR: kapi yalnizca zorunlu
     * izinlere bakiyor (bkz. [Permissions]).
     */
    private fun requestPermissions() {
        if (Permissions.requestableMissing(carContext).isEmpty()) {
            // Eksik kalanlarin hepsi platformda tanimsiz demektir; istek
            // diyalogu bos acilamaz, ayarlara yonlendirmek tek anlamli yol.
            if (Permissions.missing(carContext).isEmpty()) done() else openAppSettings()
            return
        }
        launch(Intent(carContext, PermissionActivity::class.java))
    }

    /** Uygulamanin sistem ayar sayfasi -- izinler elle buradan veriliyor. */
    private fun openAppSettings() {
        launch(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", carContext.packageName, null))
        )
    }

    /**
     * Aktiviteyi acar; olmazsa NEDENINI soyler.
     *
     * **2026-09-10'da araçta olculdu:** araç PARK hâlindeyken (vites P, el freni
     * cekili, hiz 0) her iki dugme de basarisiz oldu. Ilk surumde tek bir "sürüş
     * sırasında açılmıyor" mesaji gosteriliyordu ve bu YANILTICIYDI: araç zaten
     * duruyordu, yani sebep surus kisitlamasi degil. Istisnayi yutmak yerine
     * sinifini ve mesajini ekrana basiyoruz — araçta `adb` olmadigi icin
     * (prompt.md §11) yigin izini baska turlu gorulemiyor.
     *
     * Iki yol deneniyor: once `CarContext`, sonra uygulama baglami. Ikisi ayri
     * kod yollari; birinin engellenmesi digerini engellemeyebilir.
     */
    private fun launch(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val first = runCatching { carContext.startActivity(intent) }
        if (first.isSuccess) return

        val second = runCatching { carContext.applicationContext.startActivity(intent) }
        if (second.isSuccess) return

        val e = second.exceptionOrNull() ?: first.exceptionOrNull()
        val detail = e?.let { "${it.javaClass.simpleName}: ${it.message.orEmpty().take(90)}" }
            ?: carContext.getString(R.string.perm_blocked)
        CarToast.makeText(
            carContext,
            carContext.getString(R.string.perm_launch_error, detail),
            CarToast.LENGTH_LONG,
        ).show()
    }

    private fun done() {
        onGranted()
        finish()
    }
}
