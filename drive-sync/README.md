# Drive upload endpoint — setup

**English** · [Türkçe](README.tr.md)

`Kod.gs` is a Google Apps Script web app that writes the log files sent by the
car app into a folder in **your own** Google Drive (for example **My EX30
Trips**). This page explains how to deploy it. The setup is **optional**:
without it the app works normally and the *Upload to Drive* button simply says
"not configured".

The folder ID lives in the `FOLDER_ID` constant in `Kod.gs`.
**CREATE AN EMPTY FOLDER IN YOUR OWN DRIVE**, open it, and paste the
`/folders/<ID>` part of the address bar there.

> Code comments and the script's response messages are in Turkish. The response
> fields are part of the protocol and are explained in
> [Protocol summary](#protocol-summary).

---

## 1. Generate two keys

You need **two** keys, and they must be different:

| Key | Used by | Stored in |
|---|---|---|
| `SECRET` (write) | EX30 Telemetry in the car | inside the AAB — **can leak** |
| `READ_SECRET` (read) | EX30 Trip Viewer on your PC | only in `%LOCALAPPDATA%` |

Why two: an APK can be decompiled. If the write key leaks, an attacker can
overwrite the files in the folder but **cannot read** them. If both keys were the
same, anyone who opened the APK could download your whole trip history.

The keys travel as URL query parameters, so use **letters and digits only**;
`&`, `=`, `+` and `/` would break the URL. Run this twice in PowerShell:

```powershell
-join ((48..57)+(65..90)+(97..122) | Get-Random -Count 40 | ForEach-Object {[char]$_})
```

Keep the two values safe. Do not share them and never commit them.

## 2. Create the script

1. [script.google.com](https://script.google.com) → **New project**
2. Name the project, for example `EX30 Telemetry Drive Endpoint`
3. Delete the contents of the default `Code.gs` and paste this folder's `Kod.gs`
   as is
4. Replace the `PASTE-…` placeholders on the **`FOLDER_ID`, `SECRET` and
   `READ_SECRET`** lines with your folder ID and the two keys from step 1.
   Optionally set `TZ` to your time zone (IANA name, e.g. `Europe/Berlin`).
   While any placeholder is left, the script rejects every request.
5. Save (Ctrl+S)
6. In the project root, copy `drive.properties.example` to `drive.properties`:
   `driveUrl` = the address from step 3, `driveSecret` = the **write** key.

## 3. Deploy as a web app

**Deploy** → **New deployment** → gear icon → **Web app**, then:

| Field | Value |
|---|---|
| Description | `v1` |
| Execute as | **Me** (your own Google account) |
| Who has access | **Anyone** |

"Execute as: Me" is required: the script itself writes to Drive, because the car
app has no Google identity. "Who has access: Anyone" is also required; the car
app POSTs without signing in.

On the first deployment Google asks for authorisation and shows **"Google hasn't
verified this app"**. That is expected for your own script in your own account:
**Advanced** → **Go to** *(project name)* → **Allow**.

Keep the `https://script.google.com/macros/s/<LONG-ID>/exec` address you get at
the end.

> **Every time you change the code you must publish a new VERSION.**
> Saving with `Ctrl+S` does not change what the `/exec` address serves; it keeps
> serving the last *deployed* version. The right way:
> **Deploy → Manage deployments → pencil icon → Version: New version → Deploy.**
> Updating the existing deployment like this **keeps the same URL**. Choosing
> "New deployment" creates a NEW URL and the app keeps talking to the old code,
> without any error.

## 4. Verify

**From a browser (read):** open the address with `?k=<READ-KEY>`. With an empty
folder you should see:

```json
{"klasor":"My EX30 Trips","dosyalar":[],"ok":true}
```

If you see `{"ok":false,"hata":"yetkisiz"}` ("unauthorised"), the key does not
match. **The write key does not work here**; that is intentional.

**From PowerShell (write):**

```powershell
$url     = 'https://script.google.com/macros/s/<LONG-ID>/exec'
$secret  = '<WRITE-KEY>'
$content = '[]'
$bytes   = [Text.Encoding]::UTF8.GetByteCount($content)
$body    = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($content))
Invoke-RestMethod -Method Post -ContentType 'text/plain; charset=utf-8' -Body $body -Uri "$($url)?k=$secret&name=records.json&bytes=$bytes&gz=0"
```

Expected: `ok = True`, and the folder now contains `records.json` (2 bytes) and
`yukleme-gunlugu.csv` (the upload log). **This writes a real file.** Delete both
after testing, otherwise they mix with the first real upload from the car.

`gz=0` is for testing only; the app always gzips the body and sends `gz=1`.

## 5. Debugging

In the Apps Script left menu, **Executions** shows when each request arrived, how
long it took and any `console.warn` output.

---

## Protocol summary

**Write: the car app.** `POST <URL>?k=&name=&bytes=&gz=1&newest=`

| Parameter | Meaning |
|---|---|
| `k` | the write key |
| `name` | `calib.csv`, `calib-prev.csv`, `trips.json`, `records.json`; anything else is rejected |
| `bytes` | byte count of the **uncompressed** content; the script checks it |
| `gz` | `1` = body is gzipped (default), `0` = plain |
| `newest` | time of the newest line in the content (epoch ms), written to the log |

Body: **base64**. Raw binary cannot be sent: Apps Script hands over
`e.postData.contents` as a String, and binary data gets corrupted by the charset
conversion. Order: `file → gzip → base64 → POST`.

**Response:** always JSON.

```json
{"ok":true,"name":"trips.json","bytes":48213,"id":"...","url":"..."}
{"ok":false,"hata":"boyut tutmadi: beklenen 48213, gelen 12044"}
```

**Read: EX30 Trip Viewer.** `GET <URL>?k=<READ-KEY>&file=trips.json`

```json
{"ok":true,"name":"trips.json","bytes":48213,"gz":true,"guncellendi":"...","data":"H4sIA..."}
```

`data` = base64(gzip(content)); `bytes` is the **uncompressed** size. The client
decompresses, compares the size and rejects a partial download. Without `file`,
the folder listing is returned. Readable names: the four files in `ALLOWED` plus
`yukleme-gunlugu.csv`.

Turkish field names in the responses:

| Field | Meaning |
|---|---|
| `hata` | error message (only when `ok` is `false`) |
| `klasor` | folder name |
| `dosyalar` | list of files |
| `guncellendi` | last modified time |

Common error messages: `yetkisiz` = unauthorised (wrong key),
`yapilandirilmadi / not configured` = placeholders not replaced,
`izin verilmeyen dosya adi` = file name not allowed,
`boyut tutmadi` = size mismatch, `dosya yok` = file not found.

Two rules for clients:

1. **Do not judge success by the HTTP status code.** Apps Script cannot return
   status codes; errors also arrive as 200. Check the `ok` field in the body.
2. **Follow the 302.** The `/exec` request runs `doPost`, but the result is
   delivered through a 302 redirect to `script.googleusercontent.com`. If the
   redirect is not followed the body is empty, which is easily mistaken for
   success. `HttpURLConnection` usually follows it, but never treat an empty body
   as success: if there is no `ok` field, report an error.

## Security boundary

The URL and the **write** key are embedded in the AAB; anyone who opens the APK
can write to this folder. Three things in `Kod.gs` limit the risk:

- The folder ID is in the script, not in the app
- Only four fixed file names can be written
- Reading requires a separate key; the write key cannot download content

The read key is never embedded in any package; it is stored only in the Viewer's
`%LOCALAPPDATA%\EX30TripViewer\drive.json`.

Do not use this folder for anything else.
