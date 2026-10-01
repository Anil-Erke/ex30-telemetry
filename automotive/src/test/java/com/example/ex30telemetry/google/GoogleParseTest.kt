package com.example.ex30telemetry.google

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class GoogleParseTest {

    private fun idToken(email: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        return "x." + enc.encodeToString("""{"email":"$email"}""".toByteArray()) + ".y"
    }

    @Test
    fun `cihaz kodu okunur, iki url adi da kabul`() {
        val a = AuthParse.deviceCode(
            200,
            """{"device_code":"d","user_code":"ABC-DEF","verification_url":"https://www.google.com/device","expires_in":1800,"interval":5}""",
            nowMs = 1000,
        )
        assertEquals("ABC-DEF", a.userCode)
        assertEquals("https://www.google.com/device", a.verificationUrl)
        assertEquals(1000 + 1_800_000L, a.expiresAtMs)

        val b = AuthParse.deviceCode(
            200, """{"device_code":"d","user_code":"X","verification_uri":"https://g.co/dev"}""", 0,
        )
        assertEquals("https://g.co/dev", b.verificationUrl)
        assertEquals(5, b.intervalSec)
    }

    @Test(expected = GoogleAuth.AuthException::class)
    fun `cihaz kodu hatasi istisna`() {
        AuthParse.deviceCode(400, """{"error":"invalid_client"}""", 0)
    }

    @Test
    fun `tam izinli token baglanir, e-posta id_token'dan`() {
        val t = AuthParse.token(
            200,
            JSONObject()
                .put("access_token", "a").put("refresh_token", "r").put("expires_in", 3599)
                .put("scope", "openid https://www.googleapis.com/auth/drive.file https://www.googleapis.com/auth/userinfo.email")
                .put("id_token", idToken("ali@example.com")).toString(),
            5,
        )
        assertTrue(t is AuthParse.Token.Granted)
        t as AuthParse.Token.Granted
        assertEquals("r", t.refreshToken)
        assertEquals("ali@example.com", t.email)
    }

    @Test
    fun `Drive kutusu isaretlenmediyse baglanti REDDEDILIR`() {
        // 2026-09-29'da gercekten oldu: giris basarili, token'da drive.file yok.
        val t = AuthParse.token(
            200,
            """{"access_token":"a","refresh_token":"r","scope":"openid https://www.googleapis.com/auth/userinfo.email"}""",
            5,
        )
        assertTrue(t is AuthParse.Token.Failed)
        assertTrue((t as AuthParse.Token.Failed).message.contains("Drive"))
    }

    @Test
    fun `yoklama durumlari`() {
        assertEquals(AuthParse.Token.Pending(5), AuthParse.token(428, """{"error":"authorization_pending"}""", 5))
        assertEquals(AuthParse.Token.Pending(10), AuthParse.token(403, """{"error":"slow_down"}""", 5))
        assertTrue(AuthParse.token(403, """{"error":"access_denied"}""", 5) is AuthParse.Token.Failed)
        assertTrue(AuthParse.token(400, """{"error":"expired_token"}""", 5) is AuthParse.Token.Failed)
        assertTrue(AuthParse.token(502, "<html>", 5) is AuthParse.Token.Failed)
    }

    @Test
    fun `bozuk id_token e-postasiz kalir, cokmez`() {
        assertNull(AuthParse.emailOf("bozuk"))
        assertNull(AuthParse.emailOf(""))
    }

    @Test
    fun `Drive sorgusu tirnaklari kacislar`() {
        assertEquals(
            "appProperties has { key='ex30id' and value='trip-1.json' } and trashed=false",
            DriveApi.qAppId("trip-1.json"),
        )
        assertEquals("'a\\'b'", DriveApi.quote("a'b"))
    }

    @Test
    fun `multipart govdesi meta ve icerigi sirayla tasir`() {
        val meta = DriveApi.metadata("trip-1.json", "P", mapOf("ex30id" to "trip-1.json"))
        val (ctype, body) = DriveApi.multipart(meta, "application/json", "{\"x\":1}".toByteArray())
        val text = String(body)
        val boundary = ctype.substringAfter("boundary=")
        assertTrue(ctype.startsWith("multipart/related"))
        assertTrue(text.startsWith("--$boundary\r\n"))
        assertTrue(text.endsWith("\r\n--$boundary--\r\n"))
        assertTrue(text.indexOf("\"parents\":[\"P\"]") < text.indexOf("{\"x\":1}"))
        assertTrue(text.contains("\"appProperties\":{\"ex30id\":\"trip-1.json\"}"))
    }

    @Test
    fun `Drive hatalari kalici-gecici ayrimi`() {
        fun err(code: Int, reason: String) =
            DriveApi.error(code, """{"error":{"code":$code,"message":"m","errors":[{"reason":"$reason"}]}}""")

        assertTrue(err(400, "invalid").permanent)
        assertFalse(err(400, "rateLimitExceeded").permanent)
        assertFalse(err(403, "insufficientPermissions").permanent)
        assertTrue(err(403, "insufficientPermissions").message!!.contains("yeniden bağla"))
        assertFalse(err(429, "rateLimitExceeded").permanent)
        assertFalse(err(500, "backendError").permanent)
        assertFalse(DriveApi.error(503, "<html>").permanent)
    }
}
