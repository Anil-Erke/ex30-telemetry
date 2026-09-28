# EX30 Telemetry

**English** · [Türkçe](README.tr.md)

A trip computer and vehicle-data dashboard for the **Volvo EX30**, running
directly on the car's **Android Automotive OS** screen. It records every trip
automatically and shows live consumption, altitude, speed and energy data that
the stock UI does not expose.

> **Unofficial project.** Not affiliated with, endorsed by or supported by Volvo
> Cars. "Volvo" and "EX30" are trademarks of their respective owners and are used
> here only to describe compatibility. See [Disclaimer](#disclaimer).

<p>
  <img src="screenshots/en-live.png" width="270" alt="Live screen, light theme">
  <img src="screenshots/en-live-dark.png" width="270" alt="Live screen, dark theme">
</p>
<p>
  <img src="screenshots/en-trips.png" width="270" alt="Trip history">
  <img src="screenshots/en-records.png" width="270" alt="Records">
  <img src="screenshots/en-vehicle-data.png" width="270" alt="Vehicle data / diagnostics">
</p>

<sub>Live screen (light and dark theme), trip history, records and vehicle data.
Taken on the AAOS emulator with the debug build's synthetic demo data.</sub>

## Features

**Live screen** (drawn on the navigation surface, stays visible while driving)

- Consumption (kWh/100 km) over a sliding window of the last **10 / 20 / 50 km**,
  next to the whole-trip average
- Altitude chart for the same window with highest / lowest point, total
  climb / descent, energy spent climbing and energy regenerated
- Speed chart with maximum, and average speed for the window vs. the whole trip
- A banner when a new performance record is set
- Light / dark theme that follows the car's night mode (or can be fixed by hand)

**Automatic trip recording**

- A trip starts when the car starts moving and is saved after it has been
  parked for 60 s. A stop at a red light does not end the trip.
- Per trip: distance, duration, net energy, regen, state of charge start → end,
  range indicator start → end, outside temperature, climb / descent,
  average / max speed, GPS distance vs. wheel distance
- **Range check:** compares the car's range indicator with the distance actually
  driven (optimistic / pessimistic, weighted by distance)
- **Records** captured automatically while driving: 0-60, 0-100, 80-120 km/h and
  100-0 km/h braking, interpolated between samples
- Statistics: totals, average consumption, consumption by outside temperature band

**Vehicle data / diagnostics**

- A diagnostics screen that shows every vehicle signal, its measured sample rate
  and resolution
- **Property probe:** lists which vehicle properties the car actually reports to
  third-party apps (Android 15 / car software 2.1.2 opened several new ones)
- A raw measurement log (`calib.csv`) for your own analysis

**Export**

- **Export:** copies the logs to `Downloads/EX30YolAnalizi/` on the car
- **Upload to Drive (optional):** sends the logs to a folder in **your own**
  Google Drive through a small Apps Script that you deploy yourself
  ([drive-sync/](drive-sync/README.md)). There is no developer server.
- The companion desktop app **EX30 Trip Viewer** reads the exported
  `trips.json` and draws charts on Windows.

UI languages: **English** and **Turkish** (follows the system language).

## Privacy

- All data stays on the car, in the app's private storage.
- Nothing is sent anywhere unless **you** press *Export* or *Upload to Drive*.
  The Drive upload goes only to the endpoint you configure.
- No ads, analytics, crash reporting or third-party SDKs.
- Location is used to calculate distance and altitude. The EX30's odometer and
  built-in GPS sensors are not available to third-party apps.

## Before you build: things you must fill in

Personal values were removed from this repository. Every place that needs your
own value is marked with a **CAPITALISED** comment.

| What | Where | Required? |
|---|---|---|
| Package name (`applicationId`) | `automotive/build.gradle.kts` | **Yes**, for your own release builds. Google Play rejects `com.example.*` |
| Signing key | copy `keystore.properties.example` → `keystore.properties` | Only for signed release builds |
| Drive upload endpoint | copy `drive.properties.example` → `drive.properties` | Optional |
| Drive folder ID + two keys | `drive-sync/Kod.gs` (`PASTE-…` placeholders) | Optional |

`keystore.properties`, `drive.properties`, `*.jks`, `*.aab` and `*.apk` are in
`.gitignore`. **Never commit them.**

## Build

Requirements: Android Studio (JDK 17), Android SDK 35.

```powershell
# Windows
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :automotive:assembleDebug       # debug APK (emulator)
.\gradlew.bat :automotive:testDebugUnitTest   # unit tests
.\gradlew.bat :automotive:bundleRelease       # signed AAB (needs keystore.properties)
```

```bash
# macOS / Linux
./gradlew :automotive:assembleDebug
```

## Getting it onto a car

- **Emulator:** create an *Automotive* AVD in Android Studio and install the debug
  APK with `adb`. On AAOS the driver profile is usually **user 10**:

  ```bash
  PKG=com.example.ex30telemetry   # or your own applicationId
  adb install -r -t automotive/build/outputs/apk/debug/automotive-debug.apk
  adb shell pm grant --user 10 $PKG android.permission.ACCESS_FINE_LOCATION
  adb shell am start --user 10 -n "$PKG/androidx.car.app.activity.CarAppActivity"
  ```

- **Real car:** as far as we know, production cars do not allow ADB sideloading.
  The practical route is your **own Google Play Console** account (one-time
  registration fee) → *Internal testing* track, with your own package name and
  signing key, then install it from Play on the car with the tester account.
  Adding the Android Automotive OS form factor requires Google's review.

### Debug hooks (emulator only)

The emulator does not let you inject vehicle speed, and `adb shell input tap` does
not reach the host's buttons. The **debug build** therefore listens for a broadcast
(not compiled into release builds):

```bash
adb shell am broadcast --user 10 -p $PKG -a $PKG.DEBUG --es cmd demo          # fill the live screen with synthetic values
adb shell am broadcast --user 10 -p $PKG -a $PKG.DEBUG --es cmd live          # back to real data
adb shell am broadcast --user 10 -p $PKG -a $PKG.DEBUG --es cmd screen --es to trips   # calib / probe / trips / records / live
```

## Project layout

```
automotive/src/main/java/.../
  car/      vehicle property streams, probe, wheel odometer
  trip/     trip state machine, accumulator, records, range audit, storage
  render/   live screen drawing, theme and window settings
  screen/   Car App Library screens (trips, detail, stats, records, diagnostics)
  calib/    measurement log, export, Drive upload
  loc/      location + foreground service
  debug/    debug-only hooks and demo data
drive-sync/ optional Google Apps Script endpoint (Kod.gs)
play-assets/ icon and feature graphic
screenshots/ README images (en-*, tr-*)
```

Code comments are mostly in Turkish. Some comments refer to internal
development notes (`prompt.md`) that are not part of this repository.

## Disclaimer

This software is provided **"as is", without warranty of any kind**. It reads
vehicle data through public Android Automotive APIs and does not modify the car.
Do not interact with the app while driving; the driver is solely responsible for
driving safely and legally. Values shown (consumption, range, performance
times) are estimates and may be inaccurate.

## License

Copyright (C) 2026 Anıl Erke

This program is free software: you can redistribute it and/or modify it under the
terms of the **GNU General Public License** as published by the Free Software
Foundation, either version 3 of the License, or (at your option) any later version
(`GPL-3.0-or-later`).

This program is distributed in the hope that it will be useful, but **WITHOUT ANY
WARRANTY**; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
PARTICULAR PURPOSE. See the full text in [LICENSE](LICENSE).

In short: you may use, study, modify and share this code. If you distribute a
modified version (including publishing it on an app store), you must release its
source code under the same license.
