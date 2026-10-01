package com.example.ex30telemetry.screen

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.ex30telemetry.JourneyData
import com.example.ex30telemetry.R
import com.example.ex30telemetry.calib.Calibration
import com.example.ex30telemetry.google.GoogleAuth

/**
 * Google hesabi baglama ekrani (cihaz akisi, bkz. [GoogleAuth]).
 *
 * Ekran bir adres ve bir kod gosteriyor; surucu telefonundan
 * google.com/device'a girip kodu yaziyor. Bu sirada arka thread Google'a
 * [GoogleAuth.poll] ile soruyor; onay gelince ekran kendini kapatiyor ve
 * otomatik yukleme gecmisi gondermeye basliyor.
 *
 * Sablon: PaneTemplate — MessageTemplate metni ~2 satirda kirpiyor
 * (PermissionScreen notu), kod ise gorunur ve buyuk olmali: ayri satirlar.
 */
class GoogleLinkScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {

    private sealed class State {
        object Loading : State()
        data class Showing(val code: GoogleAuth.DeviceCode) : State()
        data class Error(val message: String) : State()
    }

    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var state: State = State.Loading

    /** Arka thread'in dongusunu durdurur; ekran kapaninca Google'a sormaya devam etmesin. */
    @Volatile private var generation = 0

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) = start()

    override fun onDestroy(owner: LifecycleOwner) {
        generation++
    }

    override fun onGetTemplate(): Template = when (val st = state) {
        State.Loading -> PaneTemplate.Builder(Pane.Builder().setLoading(true).build())
            .setTitle(s(R.string.google_link_title))
            .setHeaderAction(Action.BACK)
            .build()

        is State.Showing -> PaneTemplate.Builder(
            Pane.Builder()
                .addRow(
                    Row.Builder()
                        .setTitle(s(R.string.google_link_step1))
                        .addText(st.code.verificationUrl.removePrefix("https://").removePrefix("www."))
                        .build()
                )
                .addRow(
                    Row.Builder()
                        .setTitle(s(R.string.google_link_step2))
                        .addText(st.code.userCode)
                        .build()
                )
                .addRow(
                    Row.Builder()
                        .setTitle(s(R.string.google_link_step3))
                        .addText(s(R.string.google_link_drive_box))
                        .build()
                )
                .build()
        )
            .setTitle(s(R.string.google_link_title))
            .setHeaderAction(Action.BACK)
            .build()

        is State.Error -> MessageTemplate.Builder(st.message)
            .setTitle(s(R.string.google_link_title))
            .setHeaderAction(Action.BACK)
            .addAction(
                Action.Builder()
                    .setTitle(s(R.string.google_link_retry))
                    .setOnClickListener { start() }
                    .build()
            )
            .build()
    }

    private fun start() {
        val gen = ++generation
        state = State.Loading
        invalidate()
        Thread({ run(gen) }, "GoogleLink").start()
    }

    /** Arka thread: kodu al, onay gelene ya da ekran kapanana kadar sor. */
    private fun run(gen: Int) {
        val code = try {
            GoogleAuth.startDevice()
        } catch (e: Exception) {
            show(gen, State.Error(s(R.string.google_link_failed, e.message ?: e.javaClass.simpleName)))
            return
        }
        show(gen, State.Showing(code))

        var interval = code.intervalSec
        while (gen == generation) {
            SystemClock.sleep(interval * 1000L)
            if (gen != generation) return
            val r = try {
                GoogleAuth.poll(carContext, code)
            } catch (e: Exception) {
                // Anlik ag kesintisi akisi bitirmesin; bir sonraki turda yeniden sor.
                GoogleAuth.PollResult.Pending(interval)
            }
            when (r) {
                is GoogleAuth.PollResult.Pending -> interval = r.intervalSec
                is GoogleAuth.PollResult.Failed -> {
                    show(gen, State.Error(s(R.string.google_link_failed, r.message)))
                    return
                }
                is GoogleAuth.PollResult.Linked -> {
                    handler.post {
                        if (gen != generation) return@post
                        Calibration.current()?.note("google", "bağlandı")
                        JourneyData.current()?.sync?.onAccountChanged()
                        CarToast.makeText(
                            carContext, s(R.string.google_linked_toast, r.email), CarToast.LENGTH_LONG
                        ).show()
                        finish()
                    }
                    return
                }
            }
        }
    }

    private fun show(gen: Int, next: State) {
        handler.post {
            if (gen != generation) return@post
            state = next
            invalidate()
        }
    }

    private fun s(id: Int, vararg args: Any): String = carContext.getString(id, *args)
}

/**
 * Ilk kurulumda BIR KEZ sorulan "Drive'a yedeklemek ister misin?" ekrani.
 *
 * **Neden (2026-09-29):** baglanti Olcum ekraninin icinde; uygulamayi yeni
 * kuran biri oraya kendiliginden bakmaz ve yolculuklari hic yedeklenmeden
 * birikir. Izinler verildikten sonra hesap bagli degilse bu ekran aciliyor.
 *
 * Iki cevap da kalici: "Bagla" baglama ekranina gecer, "Simdi degil" bir
 * daha sormaz. Baglanti her zaman Olcum → Google hesabi satirindan yapilabilir.
 * Bayrak ayri bir tercih dosyasinda: GoogleAuth baglanti kesilince kendi
 * dosyasini temizliyor, bu soru o zaman yeniden cikmamali.
 */
class GoogleOfferScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template =
        MessageTemplate.Builder(carContext.getString(R.string.google_offer_message))
            .setTitle(carContext.getString(R.string.google_offer_title))
            .addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.google_offer_link))
                    .setOnClickListener {
                        markAsked(carContext)
                        val sm = screenManager
                        finish()
                        sm.push(GoogleLinkScreen(carContext))
                    }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.google_offer_later))
                    .setOnClickListener {
                        markAsked(carContext)
                        CarToast.makeText(
                            carContext, carContext.getString(R.string.google_offer_later_toast),
                            CarToast.LENGTH_LONG,
                        ).show()
                        finish()
                    }
                    .build()
            )
            .build()

    companion object {
        private const val PREFS = "onboarding"
        private const val KEY_ASKED = "google_offer_asked"

        /** Sorulmali mi: istemci kimligi var, hesap bagli degil, daha once sorulmadi. */
        fun shouldAsk(context: android.content.Context): Boolean =
            GoogleAuth.isConfigured() &&
                !GoogleAuth.isLinked(context) &&
                !context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                    .getBoolean(KEY_ASKED, false)

        private fun markAsked(context: android.content.Context) {
            context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ASKED, true).apply()
        }
    }
}

/** "Baglantiyi kes" onayi. Yerel token siliniyor ve Google'da da iptal ediliyor. */
class GoogleUnlinkScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template =
        MessageTemplate.Builder(
            carContext.getString(R.string.google_unlink_message, GoogleAuth.email(carContext) ?: "?")
        )
            .setTitle(carContext.getString(R.string.google_account))
            .setHeaderAction(Action.BACK)
            .addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.google_unlink))
                    .setOnClickListener { unlink() }
                    .build()
            )
            .build()

    private fun unlink() {
        val app = carContext.applicationContext
        Thread({ GoogleAuth.unlink(app) }, "GoogleUnlink").start()
        Calibration.current()?.note("google", "bağlantı kesildi")
        CarToast.makeText(carContext, carContext.getString(R.string.google_unlinked_toast), CarToast.LENGTH_LONG).show()
        finish()
    }
}
