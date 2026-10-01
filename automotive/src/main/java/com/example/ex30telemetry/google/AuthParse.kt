package com.example.ex30telemetry.google

import org.json.JSONObject
import java.util.Base64

/**
 * Google OAuth yanitlarinin cozumlenmesi — ag ve Android'den bagimsiz,
 * JVM testleri dogrudan kullaniyor (bkz. AuthParseTest).
 */
internal object AuthParse {

    sealed class Token {
        data class Granted(
            val accessToken: String,
            val refreshToken: String,
            val expiresInSec: Long,
            val email: String?,
        ) : Token()

        data class Pending(val intervalSec: Int) : Token()
        data class Failed(val message: String) : Token()
    }

    /** `device/code` yaniti. @throws GoogleAuth.AuthException hata yanitinda. */
    fun deviceCode(code: Int, body: String, nowMs: Long): GoogleAuth.DeviceCode {
        val o = runCatching { JSONObject(body) }.getOrNull()
            ?: throw GoogleAuth.AuthException("Google yanıtı okunamadı (HTTP $code)")
        if (!o.has("device_code")) {
            throw GoogleAuth.AuthException(
                "kod alınamadı: ${o.optString("error", "HTTP $code")} ${o.optString("error_description")}".trim()
            )
        }
        return GoogleAuth.DeviceCode(
            deviceCode = o.getString("device_code"),
            userCode = o.getString("user_code"),
            // Google iki adla da gonderebiliyor; RFC 8628 adi verification_uri.
            verificationUrl = o.optString("verification_url").ifEmpty { o.optString("verification_uri") },
            intervalSec = o.optInt("interval", 5).coerceAtLeast(1),
            expiresAtMs = nowMs + o.optLong("expires_in", 1800) * 1000L,
        )
    }

    /**
     * `token` yaniti (cihaz akisi yoklamasi).
     *
     * Basari sayilmasi icin UC sart: access + refresh token var VE verilen
     * izinler arasinda drive.file var. Sonuncusu olmazsa her Drive cagrisi 403
     * doner — izin ekranindaki Drive kutusu isaretlenmemistir.
     */
    fun token(code: Int, body: String, currentIntervalSec: Int): Token {
        val o = runCatching { JSONObject(body) }.getOrNull()
            ?: return Token.Failed("Google yanıtı okunamadı (HTTP $code)")

        val access = o.optString("access_token")
        if (access.isNotEmpty()) {
            val scopes = o.optString("scope").split(' ').filter { it.isNotBlank() }
            if (GoogleAuth.SCOPE_DRIVE_FILE !in scopes) {
                return Token.Failed("Drive izni verilmedi — onay ekranında Drive kutusunu da işaretle")
            }
            val refresh = o.optString("refresh_token")
            if (refresh.isEmpty()) return Token.Failed("Google kalıcı erişim vermedi (refresh token yok)")
            return Token.Granted(access, refresh, o.optLong("expires_in", 3600), emailOf(o.optString("id_token")))
        }

        return when (val err = o.optString("error")) {
            "authorization_pending" -> Token.Pending(currentIntervalSec)
            // RFC 8628: yavasla — araligi 5 sn artir.
            "slow_down" -> Token.Pending(currentIntervalSec + 5)
            "access_denied" -> Token.Failed("onay verilmedi")
            "expired_token" -> Token.Failed("kodun süresi doldu — yeniden dene")
            else -> Token.Failed("bağlanamadı: ${err.ifEmpty { "HTTP $code" }}")
        }
    }

    /**
     * id_token'in govdesindeki e-posta. Imza DOGRULANMIYOR: token'i Google'in
     * token ucundan TLS ile biz aldik, ucuncu bir taraftan gelmiyor; e-posta
     * yalnizca ekranda "hangi hesap" diye gosteriliyor.
     */
    fun emailOf(idToken: String): String? = runCatching {
        val payload = idToken.split('.')[1]
        val json = String(Base64.getUrlDecoder().decode(payload.padEnd((payload.length + 3) / 4 * 4, '=')))
        JSONObject(json).optString("email").ifEmpty { null }
    }.getOrNull()
}
