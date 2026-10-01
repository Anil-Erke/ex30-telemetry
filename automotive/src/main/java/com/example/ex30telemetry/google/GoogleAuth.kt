package com.example.ex30telemetry.google

import android.content.Context
import android.util.Log
import com.example.ex30telemetry.BuildConfig
import org.json.JSONObject
import java.io.IOException

/**
 * Araçta Google hesabi baglama — "sinirli girisli cihaz" OAuth akisi.
 *
 * ## Neden bu akis
 *
 * Araçta tarayici yok ve Google gomulu WebView ile oturum acmayi reddediyor.
 * Cihaz akisinda araç bir KOD gosteriyor; surucu telefonundan
 * google.com/device adresine girip kodu yaziyor ve kendi hesabiyla onayliyor.
 * Araç bu sirada Google'a soruyor ([poll]) ve onay gelince token aliyor.
 *
 * ## Izin: yalnizca `drive.file`
 *
 * Uygulama Drive'da YALNIZCA KENDI OLUSTURDUGU dosyalari gorebiliyor; kisinin
 * geri kalan Drive'ina erisimi yok. Bu akisin destekledigi az sayidaki izinden
 * biri. `openid email` yalnizca "hangi hesaba bagli" diye ekranda gostermek icin.
 *
 * ## Onay kutusu tuzagi (2026-09-29'da yasandi)
 *
 * Google'in izin ekrani her izne ayri onay kutusu koyuyor. Drive kutusu bos
 * birakilirsa giris BASARILI gorunuyor ama token'da Drive izni yok ve her
 * Drive cagrisi 403 donuyor. [poll] token'in `scope` alanina bakip bunu
 * baglanti aninda reddediyor — sessiz bir "bagli ama calismiyor" durumu olmasin.
 *
 * ## Istemci kimligi APK'da — bu bir parola DEGIL
 *
 * `GOOGLE_CLIENT_ID/SECRET` (koktaki `oauth.properties`, depoya girmez) cihaz
 * uygulamalari icin Google'in "gizli sayilmaz" dedigi degerler; tek baslarina
 * hicbir veriye erisim vermiyor. Erisim, her kullanicinin KENDI aracinda
 * sifreli duran refresh token'iyla ([SecretBox]).
 */
object GoogleAuth {

    private const val TAG = "JourneyGoogle"

    const val SCOPE_DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"
    private const val SCOPE = "$SCOPE_DRIVE_FILE openid email"

    private const val DEVICE_URL = "https://oauth2.googleapis.com/device/code"
    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    private const val REVOKE_URL = "https://oauth2.googleapis.com/revoke"

    private const val PREFS = "google_account"
    private const val KEY_REFRESH = "refresh_enc"
    private const val KEY_EMAIL = "email"

    /** Token suresi dolmadan bu kadar once yenile: istek yoldayken dolmasin. */
    private const val EXPIRY_MARGIN_MS = 60_000L

    /** Hesap baglantisi ya da token yenileme hatasi. [relink] = kullanici yeniden baglanmali. */
    class AuthException(message: String, val relink: Boolean = false) : IOException(message)

    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUrl: String,
        val intervalSec: Int,
        val expiresAtMs: Long,
    )

    sealed class PollResult {
        /** Kullanici henuz onaylamadi; [intervalSec] sonra tekrar sor. */
        data class Pending(val intervalSec: Int) : PollResult()
        data class Linked(val email: String) : PollResult()
        /** Akis bitti (reddedildi, suresi doldu, Drive kutusu isaretlenmedi...). */
        data class Failed(val message: String) : PollResult()
    }

    @Volatile private var accessToken: String? = null
    @Volatile private var accessExpiresAtMs = 0L

    /** Derleme `oauth.properties` ile mi yapildi. Degilse baglanma dugmesi calismaz. */
    fun isConfigured(): Boolean =
        BuildConfig.GOOGLE_CLIENT_ID.isNotBlank() && BuildConfig.GOOGLE_CLIENT_SECRET.isNotBlank()

    fun isLinked(context: Context): Boolean = prefs(context).contains(KEY_REFRESH)

    fun email(context: Context): String? = prefs(context).getString(KEY_EMAIL, null)

    /** Akisi baslatir: gosterilecek kodu alir. AG CAGRISI. */
    fun startDevice(): DeviceCode {
        if (!isConfigured()) throw AuthException("istemci kimliği yok (oauth.properties)")
        val r = Http.request(
            "POST", DEVICE_URL,
            Http.form(mapOf("client_id" to BuildConfig.GOOGLE_CLIENT_ID, "scope" to SCOPE)),
            "application/x-www-form-urlencoded",
        )
        return AuthParse.deviceCode(r.code, r.text, System.currentTimeMillis())
    }

    /** Onay geldi mi diye bir kez sorar. Baglanti basariliysa hesabi kaydeder. AG CAGRISI. */
    fun poll(context: Context, code: DeviceCode): PollResult {
        if (System.currentTimeMillis() > code.expiresAtMs) {
            return PollResult.Failed("kodun süresi doldu — yeniden dene")
        }
        val r = Http.request(
            "POST", TOKEN_URL,
            Http.form(
                mapOf(
                    "client_id" to BuildConfig.GOOGLE_CLIENT_ID,
                    "client_secret" to BuildConfig.GOOGLE_CLIENT_SECRET,
                    "device_code" to code.deviceCode,
                    "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                )
            ),
            "application/x-www-form-urlencoded",
        )
        return when (val t = AuthParse.token(r.code, r.text, code.intervalSec)) {
            is AuthParse.Token.Granted -> {
                prefs(context).edit()
                    .putString(KEY_REFRESH, SecretBox.encrypt(t.refreshToken))
                    .putString(KEY_EMAIL, t.email)
                    .apply()
                accessToken = t.accessToken
                accessExpiresAtMs = System.currentTimeMillis() + t.expiresInSec * 1000L
                DriveClient.forgetFolders()
                PollResult.Linked(t.email ?: "?")
            }
            is AuthParse.Token.Pending -> PollResult.Pending(t.intervalSec)
            is AuthParse.Token.Failed -> PollResult.Failed(t.message)
        }
    }

    /**
     * Gecerli bir erisim token'i; gerekirse refresh token ile yeniler. AG CAGRISI.
     *
     * @throws AuthException hesap bagli degilse ya da baglanti gecersizlestiyse
     *   (kullanici erisimi kaldirdi, token 7 gunluk "Testing" suresini doldurdu).
     * @throws IOException ag hatasinda
     */
    @Synchronized
    fun accessToken(context: Context, forceRefresh: Boolean = false): String {
        val cached = accessToken
        if (!forceRefresh && cached != null &&
            System.currentTimeMillis() < accessExpiresAtMs - EXPIRY_MARGIN_MS
        ) return cached

        val enc = prefs(context).getString(KEY_REFRESH, null)
            ?: throw AuthException("Google hesabı bağlı değil", relink = true)
        val refresh = SecretBox.decrypt(enc)
            ?: throw AuthException("kayıtlı bağlantı okunamadı — yeniden bağlan", relink = true)

        val r = Http.request(
            "POST", TOKEN_URL,
            Http.form(
                mapOf(
                    "client_id" to BuildConfig.GOOGLE_CLIENT_ID,
                    "client_secret" to BuildConfig.GOOGLE_CLIENT_SECRET,
                    "refresh_token" to refresh,
                    "grant_type" to "refresh_token",
                )
            ),
            "application/x-www-form-urlencoded",
        )
        val json = runCatching { JSONObject(r.text) }.getOrNull()
        val token = json?.optString("access_token").orEmpty()
        if (r.ok && token.isNotEmpty()) {
            accessToken = token
            accessExpiresAtMs = System.currentTimeMillis() + json!!.optLong("expires_in", 3600) * 1000L
            return token
        }
        val err = json?.optString("error").orEmpty()
        if (err == "invalid_grant") {
            // Erisim kaldirildi ya da token oldu: saklamanin anlami yok.
            Log.w(TAG, "refresh token geçersiz: ${json?.optString("error_description")}")
            clear(context)
            throw AuthException("Google bağlantısı düştü — yeniden bağlan", relink = true)
        }
        throw IOException("token yenilenemedi (HTTP ${r.code} $err)")
    }

    /** Baglantiyi keser: Google'da iptal eder (ag varsa) ve yerelden siler. AG CAGRISI. */
    fun unlink(context: Context) {
        val refresh = prefs(context).getString(KEY_REFRESH, null)?.let { SecretBox.decrypt(it) }
        // ONCE yerelden sil: ekran hemen "bagli degil" gostersin ve kuyruk
        // durmus olsun; Google'daki iptal yavas agda saniyeler surebilir.
        clear(context)
        if (refresh != null) {
            runCatching {
                Http.request(
                    "POST", REVOKE_URL, Http.form(mapOf("token" to refresh)),
                    "application/x-www-form-urlencoded",
                )
            }.onFailure { Log.w(TAG, "iptal isteği gitmedi; yalnızca yerelden silindi", it) }
        }
    }

    private fun clear(context: Context) {
        prefs(context).edit().clear().apply()
        accessToken = null
        accessExpiresAtMs = 0L
        // Klasor kimlikleri o hesabindi; baska hesap baglaninca gecersiz.
        DriveClient.forgetFolders()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
