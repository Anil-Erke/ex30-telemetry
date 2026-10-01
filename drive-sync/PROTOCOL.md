# EX30 Drive protocol — version 3

**English** · [Türkçe](PROTOKOL.md)

**EX30 Telemetry** in the car writes trips to **the Drive of the Google account the
driver connected in the car**. **EX30 Trip Viewer** on the PC and the phone/tablet
app read from the same person's same Drive. **There is no server in between.**
Every user signs in with their own account; data never mixes.

**This document is the single source of truth for the protocol.** If a field,
file name or rule changes, update this first, then the clients:

| Client | Role | Code |
|---|---|---|
| Car — EX30 Telemetry | **only writer** | `automotive/.../google/`, `sync/`, `trip/TrackRecorder.kt` |
| PC — EX30 Trip Viewer | reader | [ex30-trip-viewer](https://github.com/Anil-Erke/ex30-trip-viewer) repository, `ex30trips/drive.py` |
| Phone/tablet — EX30 Trip Mobile | reader | [ex30-trip-mobile](https://github.com/Anil-Erke/ex30-trip-mobile) repository |

> **Version history.** Versions 1-2 went through an Apps Script endpoint (`Kod.gs`)
> that each user deployed in their own account; its URL and write key were
> embedded in the APK and everybody's trips went to a single Drive. Version 3
> (2026-09-29) gives every user their own Google sign-in instead. `Kod.gs` only
> remains for the old Viewer's `trips.json` reading; the car no longer writes to
> it. **Version 3 clients cannot see files created by Apps Script** (the
> `drive.file` rule); the two worlds are separate.

---

## 1. Authentication

- **One Google Cloud project** (e.g. "EX30 Telemetry") with one OAuth client per
  client app:

  | Client | OAuth type | Flow |
  |---|---|---|
  | Car | *TVs and Limited Input devices* | device flow: the car shows a code, the user opens `google.com/device` on a phone and approves |
  | Viewer | *Desktop app* | browser + loopback (`http://127.0.0.1:<port>`), PKCE |
  | Phone | *Android* (package name + SHA-1) | Sign in with Google / Authorization API |

- **Scope: only `https://www.googleapis.com/auth/drive.file`** (+ `openid email`,
  only to show which account is connected). The apps see **only the files created
  by this project's clients** in Drive; they have no access to the rest of the
  user's Drive.
- **Clients in the same project see each other's files.** Verified against a real
  Drive on 2026-09-29 (`drive-sync/oauth-dogrulama.py`): the desktop client found a
  file created by the car client with an `appProperties` query and read its
  content. The documentation does not say this explicitly; keep all clients in the
  same project.
- **Consent checkbox trap.** Google's consent screen puts a separate checkbox on
  each permission. If the Drive box is not ticked, sign-in looks successful but the
  token has no `drive.file` and every Drive call returns 403. **Every client must
  check the `scope` field of the token response** and, if Drive is missing, reject
  the connection and tell the user.
- **In "Testing" status, refresh tokens die after 7 days.** The consent screen
  must be "In production" (`drive.file` is not a sensitive scope; no Google review
  is needed).
- Client IDs/secrets never go into the repository (`oauth.properties`). For device
  and desktop apps Google does not treat them as secret; on their own they give no
  access to any data. Access comes from each user's token stored on their own
  device.

---

## 2. Drive layout

```
EX30 Trips/                               root — the only thing the user sees
  calib.csv, calib-prev.csv,              latest state from the car, UPDATED
  trips.json, records.json                by the "Upload to Drive" button (manual)
  yolculuklar/                            ("trips")
    2026/
      09/
        trip-1790602585477.json           summary
        trip-1790602585477.csv.gz         GPS track
```

- Folders are **for human eyes only.** Clients find files by **`appProperties`**,
  not by path or name; matching still works if the user moves or renames a file.
- Year/month follow the **start** of the trip, in the car's time zone.

### 2.1 `appProperties`

| Key | Value | Where |
|---|---|---|
| `ex30` | `trip` (trip file) · `kayit` (logs in the root) | files |
| `ex30id` | file name, e.g. `trip-1790602585477.json` — **unique key** | files |
| `epoch` | trip start, epoch ms (as text) | trip files |
| `tur` | `ozet` (summary) \| `iz` (track) | trip files |
| `ex30path` | full folder path, e.g. `EX30 Trips/yolculuklar/2026/09` | folders |

The Turkish key and value names are part of the protocol; do not translate them.

### 2.2 Write rules (car only)

- **Trip files are only created, never overwritten.** The car first searches by
  `ex30id`; if the file exists it is left alone and counted as success. Retrying
  (response lost on the way, head unit went to sleep) is harmless; no duplicates
  are created.
- **Integrity:** the `md5Checksum` returned by Drive is compared with the local
  content; on mismatch the file is deleted and the trip stays in the queue.
- Folders are searched by `ex30path` and created if missing, so the same folders
  are found again after a reinstall.

---

## 3. File formats

### 3.1 Summary — `trip-<startEpoch>.json`

`mimeType: application/json`, **plain** (uncompressed) JSON. The output of
`Trip.toJson()` in the car; exactly the same format as one element of the old
`trips.json`.

Fields (schema 3; `null`/missing = the car did not provide that value, **never
turned into zero**):

| Field | Unit | Note |
|---|---|---|
| `schemaVersion` | — | assume 1 if missing |
| `startEpoch`, `endEpoch` | epoch ms | |
| `durationSec` | s | |
| `distanceKm` | km | from GPS |
| `wheelDistanceKm` | km | from wheel ticks, **RAW** — multiply by 1.02536 for display |
| `energyKwh` | kWh | **net** consumption (used − regen) |
| `regenKwh` | kWh | positive; gross = net + regen |
| `socStart`, `socEnd` | % | may have decimals |
| `rangeStart`, `rangeEnd` | km | range indicator |
| `avgSpeedKmh`, `maxSpeedKmh` | km/h | |
| `tempStart`, `tempAvg` | °C | outside temperature |
| `altGainM`, `altLossM` | m | |
| `potentialKwh` | kWh | potential energy of the climb |
| `consumptionKwh100` | kWh/100 km | |
| `rangeBiasFactor` | — | range-indicator drop ÷ real distance; > 1 = indicator optimistic |
| `records` | array | `{kind, value, unit, epoch}` — `0-100`, `0-60`, `80-120` (s), `100-0` (m) |

Definitions must match `trip/TripStats.kt` and `trip/RangeAuditor.kt` in the car:
**average consumption is energy-weighted**, **range bias is distance-weighted and
only uses trips ≥ 5 km**.

### 3.2 GPS track — `trip-<startEpoch>.csv.gz`

`mimeType: application/gzip`. The decompressed content is UTF-8 text:

```
# ex30-track;1;1790602585477
t;lat;lon;alt;hacc;vacc;gps_kmh;kmh;kw;soc;dist_m
1790602587240;41.000000;29.000000;100.0;5.0;0.5;;0.0;0.00;61.00;0.0
```

- Line 1: `# ex30-track;<format version>;<startEpoch>`. If the format version is
  unknown, do not read the file; tell the user.
- Line 2: column names. **Read columns by name, not by position.**
- Separator `;`, decimal separator **always a dot**, no thousands separator. Empty
  field = no value; zero is never written instead.
- About 1 Hz; sparser while the car is stopped (3 m threshold). ~3,600 lines per
  hour.
- A trip may have no track (trips before 0.7.2, driving without GPS).

| Column | Unit | Meaning |
|---|---|---|
| `t` | epoch ms, UTC | GPS fix time |
| `lat`, `lon` | degrees | 6 decimals |
| `alt` | m | GPS altitude, **RAW** (the chart in the car is smoothed) |
| `hacc`, `vacc` | m | horizontal / vertical accuracy |
| `gps_kmh` | km/h | speed from GPS |
| `kmh` | km/h | the car's displayed speed (latest sample at that moment) |
| `kw` | kW | instant power; **positive = consumption, negative = regen** |
| `soc` | % | may have decimals |
| `dist_m` | m | trip distance so far (the car's own calculation) |

Producer in the car: `trip/TrackRecorder.kt`.

---

## 4. Reading clients — incremental sync

Drive REST v3 (`https://www.googleapis.com/drive/v3/files`).

1. **List:**
   `q = appProperties has { key='ex30' and value='trip' } and trashed=false and createdTime > '<cursor>'`
   `fields = nextPageToken, files(id, name, createdTime, size, md5Checksum, appProperties)`
   `orderBy = createdTime`, `pageSize = 1000`, `spaces = drive`. Page until
   `nextPageToken` runs out.
2. **Cursor:** the largest `createdTime` seen (RFC 3339). On the next request use
   it **5 minutes earlier**: the Drive listing can show a newly created file with a
   short delay. Because of this overlap the same file can arrive in two listings;
   **de-duplicate by `appProperties.ex30id`.**
3. The cursor is **the time the file was added to Drive**, not the trip time: an
   old trip that was queued while offline, or uploaded retroactively, also shows
   up in the new listing.
4. For each summary (`tur=ozet`) download the content with
   `GET files/<id>?alt=media`. Download tracks (`tur=iz`) **when needed** (when a
   map/chart is opened) and keep them locally — files never change, one download is
   enough. Verify with `md5Checksum`.
5. On error do not advance the cursor: save it only once everything in the listing
   has been processed.
6. No reading client ever **writes** to Drive (even if it could). The car is the
   only writer.
7. **Gap check.** Version 2's `toplam` (total) field does not exist in Drive.
   Instead, at least **once a day** (and when the user asks to "rescan") fetch a
   **full listing** without a cursor and download whatever is missing locally. A
   full listing is metadata only; one request per 1,000 files, a second or two for
   hundreds of trips.
8. The `alt=media` response is the file's **raw bytes**: the summary is plain JSON,
   the track is gzip. Version 2's envelope (`ok`/`hata`/`bytes`/`data` base64) does
   NOT exist; integrity is checked with `md5Checksum`. Success is now judged by the
   HTTP status code (Drive returns real codes):
   401 → refresh the token, retry once · 403 `insufficientPermissions` → no Drive
   permission, sign in again · 403/429 `rateLimitExceeded` → back off.

On the first sync (no cursor) list without the `createdTime` condition.

### 4.1 Migrating version 2 clients

The car **never produced** version 2 files (that version was never released), so
version 2 caches hold no persistent data other than Apps Script's copy of
`trips.json`. On migration the old settings (URL + read key), cursor and cache can
be deleted; trips download again from version 3. When an account is connected the
car sends all its trips (≤ 300) to Drive, so no history is lost.

---

## 5. Car (writer) behaviour

- Account: Settings screen → **Google account** row → device flow
  (`screen/GoogleLinkScreen.kt`). The refresh token is stored encrypted with the
  Android Keystore (`google/SecretBox.kt`). Disconnecting also revokes it at
  Google.
- When a trip closes it enters the outbox: `filesDir/outbox/<startEpoch>`. Summary
  first, then track; once both are in place the trip leaves the outbox
  (`sync/TripOutbox.kt`, `sync/DriveTripSender.kt`).
- Attempts: when a trip closes, when the service starts, when the network comes
  back, when an account is connected, and after a failure with back-off of
  1 → 5 → 15 → 30 → 60 min.
- When an account is connected (or changed), **all** trips in the car enter the
  outbox once — the history moves to that account's Drive.
- A permanent error (Drive 400, corrupt local track) marks that trip as
  `<epoch>.red` (rejected) so it does not block the queue. Network, authorisation
  and rate-limit errors are temporary: the queue is kept.
