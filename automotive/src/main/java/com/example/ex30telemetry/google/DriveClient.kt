package com.example.ex30telemetry.google

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Google Drive REST v3 — bu uygulamanin ihtiyaci kadari (drive-sync/PROTOKOL.md §3).
 *
 * `drive.file` izniyle calisiyor: gorulen tek dosyalar bu Cloud projesinin
 * istemcilerinin olusturduklari. Dosyalari AD ile degil `appProperties`
 * ile ariyoruz (`ex30id` = dosya adi): kullanici Drive'da bir dosyanin adini
 * degistirse ya da tasisa bile eslesme bozulmuyor.
 *
 * Hatalar [DriveException]: [DriveException.permanent] ayni istegi tekrar
 * etmenin anlamsiz oldugunu soyluyor (ornegin 400). Yetki (401/403), hiz
 * siniri (429) ve sunucu (5xx) hatalari GECICI.
 *
 * Butun metotlar AG CAGRISI yapar.
 */
class DriveClient(context: Context) {

    private val appContext = context.applicationContext

    data class DriveFile(val id: String, val md5: String?, val size: Long?)

    class DriveException(message: String, val permanent: Boolean = false) : IOException(message)

    /** `appProperties.ex30id` degeri [appId] olan dosya; yoksa null. */
    fun findByAppId(appId: String): DriveFile? {
        val q = DriveApi.qAppId(appId)
        val r = call("GET", "$FILES?q=${enc(q)}&spaces=drive&fields=files(id,md5Checksum,size)&pageSize=5")
        val files = JSONObject(r.text).optJSONArray("files") ?: JSONArray()
        return (0 until files.length()).map { DriveApi.file(files.getJSONObject(it)) }.firstOrNull()
    }

    /**
     * Klasor yolunu (ornegin `EX30 Trips/yolculuklar/2026/09`) bulur ya da
     * eksik parcalarini olusturur; son klasorun kimligi. Her klasor
     * `appProperties.ex30path` = tam yol ile isaretli: yeniden kurulumdan sonra
     * da ayni klasorler bulunuyor, ikincisi acilmiyor.
     */
    fun folder(path: List<String>): String = synchronized(folderCache) {
        val key = path.joinToString("/")
        folderCache[key]?.let { return it }
        var parent: String? = null
        for (i in path.indices) {
            val sub = path.subList(0, i + 1).joinToString("/")
            val cached = folderCache[sub]
            val id = cached ?: findFolder(sub) ?: createFolder(path[i], parent, sub)
            folderCache[sub] = id
            parent = id
        }
        parent!!
    }

    fun create(parentId: String, name: String, mime: String, bytes: ByteArray, props: Map<String, String>): DriveFile {
        val meta = DriveApi.metadata(name, parentId, props)
        val (ctype, body) = DriveApi.multipart(meta, mime, bytes)
        val r = call("POST", "$UPLOAD?uploadType=multipart&fields=id,md5Checksum,size", body, ctype)
        return DriveApi.file(JSONObject(r.text))
    }

    /** Var olan dosyanin ICERIGINI degistirir (yalnizca surum 1 benzeri kayit dosyalari icin). */
    fun update(fileId: String, mime: String, bytes: ByteArray): DriveFile {
        val (ctype, body) = DriveApi.multipart(JSONObject(), mime, bytes)
        val r = call("PATCH", "$UPLOAD/$fileId?uploadType=multipart&fields=id,md5Checksum,size", body, ctype)
        return DriveApi.file(JSONObject(r.text))
    }

    fun delete(fileId: String) {
        runCatching { call("DELETE", "$FILES/$fileId") }
    }

    // --- Ic isler ---

    private fun findFolder(path: String): String? {
        val q = DriveApi.qFolder(path)
        val r = call("GET", "$FILES?q=${enc(q)}&spaces=drive&fields=files(id)&pageSize=5")
        val files = JSONObject(r.text).optJSONArray("files") ?: return null
        return if (files.length() > 0) files.getJSONObject(0).getString("id") else null
    }

    private fun createFolder(name: String, parent: String?, path: String): String {
        val meta = DriveApi.metadata(name, parent, mapOf("ex30path" to path))
            .put("mimeType", FOLDER_MIME)
        val r = call("POST", "$FILES?fields=id", meta.toString().toByteArray(), "application/json; charset=UTF-8")
        return JSONObject(r.text).getString("id")
    }

    /** Istegi yapar; 401'de token'i bir kez yenileyip tekrar dener. */
    private fun call(method: String, url: String, body: ByteArray? = null, ctype: String? = null): Http.Response {
        var r = Http.request(method, url, body, ctype, GoogleAuth.accessToken(appContext))
        if (r.code == 401) {
            r = Http.request(method, url, body, ctype, GoogleAuth.accessToken(appContext, forceRefresh = true))
        }
        if (r.ok) return r
        throw DriveApi.error(r.code, r.text)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        private const val FILES = "https://www.googleapis.com/drive/v3/files"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
        const val FOLDER_MIME = "application/vnd.google-apps.folder"

        /** Surec boyunca klasor kimlikleri: her yuklemede klasor aramasin. */
        private val folderCache = HashMap<String, String>()

        /**
         * Klasor onbellegini bosaltir. Hesap degisince (baska kisi baglandi)
         * ve bir yukleme hata aldiginda cagriliyor: kullanici klasoru Drive'da
         * silmis olabilir, eski kimlik 404 verir.
         */
        fun forgetFolders() = synchronized(folderCache) { folderCache.clear() }

        fun md5Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/** DriveClient'in saf parcalari — ag yok, JVM testleri kullaniyor. */
internal object DriveApi {

    private const val BOUNDARY = "ex30-sinir-7c1f"

    /** Tek tirnak Drive sorgu dilinde kacislanmali. */
    fun quote(s: String) = "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"

    fun qAppId(appId: String) =
        "appProperties has { key='ex30id' and value=${quote(appId)} } and trashed=false"

    fun qFolder(path: String) =
        "appProperties has { key='ex30path' and value=${quote(path)} } and " +
            "mimeType='${DriveClient.FOLDER_MIME}' and trashed=false"

    fun metadata(name: String, parent: String?, props: Map<String, String>): JSONObject =
        JSONObject().apply {
            put("name", name)
            parent?.let { put("parents", JSONArray().put(it)) }
            if (props.isNotEmpty()) put("appProperties", JSONObject(props as Map<*, *>))
        }

    /** `multipart/related` govdesi: once JSON meta, sonra icerik. */
    fun multipart(meta: JSONObject, mime: String, bytes: ByteArray): Pair<String, ByteArray> {
        val head = "--$BOUNDARY\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" +
            meta.toString() + "\r\n--$BOUNDARY\r\nContent-Type: $mime\r\n\r\n"
        val tail = "\r\n--$BOUNDARY--\r\n"
        return "multipart/related; boundary=$BOUNDARY" to
            (head.toByteArray(Charsets.UTF_8) + bytes + tail.toByteArray(Charsets.UTF_8))
    }

    fun file(o: JSONObject) = DriveClient.DriveFile(
        id = o.getString("id"),
        md5 = o.optString("md5Checksum").ifEmpty { null },
        size = if (o.has("size")) o.optString("size").toLongOrNull() else null,
    )

    /**
     * Drive hata yanitini [DriveClient.DriveException]'a cevirir.
     *
     * 403 iki anlam tasiyor: hiz siniri (`rateLimitExceeded`, gecici) ya da
     * izin yok (`insufficientPermissions` — Drive kutusu isaretlenmemis;
     * GoogleAuth bunu baglantida yakaliyor ama token sonradan daraltilabilir).
     * Ikisi de gecici: kuyruk korunuyor, kullanici yeniden baglaninca gidiyor.
     */
    fun error(code: Int, body: String): DriveClient.DriveException {
        val err = runCatching { JSONObject(body).getJSONObject("error") }.getOrNull()
        val reason = err?.optJSONArray("errors")?.optJSONObject(0)?.optString("reason").orEmpty()
        val msg = err?.optString("message").orEmpty().ifEmpty { "HTTP $code" }
        val text = when (reason) {
            "insufficientPermissions" -> "Drive izni yok — Google hesabını yeniden bağla"
            "storageQuotaExceeded" -> "Drive dolu"
            else -> "Drive: $msg"
        }
        val permanent = code == 400 && reason != "rateLimitExceeded"
        return DriveClient.DriveException(text, permanent)
    }
}
