package com.example.ex30telemetry.google

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Google ucnoktalari icin en kucuk HTTP yardimcisi. Kutuphane eklemiyoruz:
 * uygulama zaten `HttpURLConnection` ile konusuyordu (Apps Script donemi) ve
 * ihtiyac birkac form POST'u ile Drive REST cagrisindan ibaret.
 *
 * Butun metotlar AG CAGRISI yapar — ana thread'de cagrilmamali.
 */
internal object Http {

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000

    /** Durum kodu + govde. Hata durumunda da govde okunuyor: Google sebebi orada yaziyor. */
    class Response(val code: Int, val body: ByteArray) {
        val text: String get() = body.toString(Charsets.UTF_8)
        val ok: Boolean get() = code in 200..299
    }

    fun form(map: Map<String, String>): ByteArray =
        map.entries.joinToString("&") { (k, v) ->
            URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
        }.toByteArray(Charsets.US_ASCII)

    /** @throws IOException ag hatasinda (baglanti yok, zaman asimi). */
    fun request(
        method: String,
        url: String,
        body: ByteArray? = null,
        contentType: String? = null,
        bearer: String? = null,
    ): Response {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            // PATCH HttpURLConnection'da yok; Drive override basligini kabul ediyor.
            if (method == "PATCH") {
                requestMethod = "POST"
                setRequestProperty("X-HTTP-Method-Override", "PATCH")
            } else {
                requestMethod = method
            }
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            bearer?.let { setRequestProperty("Authorization", "Bearer $it") }
            contentType?.let { setRequestProperty("Content-Type", it) }
        }
        try {
            if (body != null) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            return Response(code, bytes)
        } finally {
            conn.disconnect()
        }
    }
}
