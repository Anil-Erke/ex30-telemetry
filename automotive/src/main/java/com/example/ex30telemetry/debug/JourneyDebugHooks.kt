package com.example.ex30telemetry.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import com.example.ex30telemetry.screen.CalibrationScreen
import com.example.ex30telemetry.screen.ProbeScreen
import com.example.ex30telemetry.screen.RecordsScreen
import com.example.ex30telemetry.screen.TripsScreen

/**
 * Yalnizca debug derlemesinde kurulan gelistirme kancasi. Emulatorde bu kanca
 * olmadan hicbir sey dogrulanamiyor (prompt.md §6.11):
 *
 *  - VHAL enjeksiyonu user-build'de kapali, emulator hizi sabit 0 (§6.9)
 *  - `adb shell input tap` host dugmelerine isabet etmiyor (§6.10)
 *
 * Kullanim:
 * ```
 * PKG=com.example.ex30telemetry
 * adb shell am broadcast --user 10 -p $PKG -a $PKG.DEBUG --es cmd screen --es to calib
 * ```
 *
 * `cmd` degerleri:
 *
 * | cmd | ornek | ne yapar |
 * |---|---|---|
 * | `screen` | `--es cmd screen --es to calib` | ekran acar (calib/probe/trips/records/live) |
 * | `demo` | `--es cmd demo` | LiveScreen'i sentetik degerlerle doldurur (§7.6b) |
 * | `live` | `--es cmd live` | demo katmanini kaldirir, gercek veriye doner |
 * | `state` | `--es cmd state --es to AKTIF` | yolculuk durum makinesini elle surer |
 *
 * Sonraki fazlarda `trip`, `sprint`, `seed` eklenecek; taninmayan komutlar
 * [onCommand]'a geciriliyor.
 */
class JourneyDebugHooks(
    private val carContext: CarContext,
    private val onCommand: (cmd: String, intent: Intent) -> Boolean = { _, _ -> false },
) {
    companion object {
        private const val TAG = "JourneyDebug"
        const val ACTION = "com.example.ex30telemetry.DEBUG"
    }

    private var receiver: BroadcastReceiver? = null

    fun install() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                intent ?: return
                val cmd = intent.getStringExtra("cmd") ?: return
                Log.i(TAG, "cmd=$cmd extras=${intent.extras?.keySet()?.joinToString()}")
                // "screen" disindaki her sey LiveScreen'e delege ediliyor;
                // durum makinesi ve demo katmani orada yasiyor.
                when (cmd) {
                    "screen" -> pushScreen(intent.getStringExtra("to"))
                    else -> if (!onCommand(cmd, intent)) Log.w(TAG, "bilinmeyen cmd: $cmd")
                }
            }
        }
        // targetSdk 33+ ile disaridan `am broadcast` gelecekse bayrak sart.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            carContext.registerReceiver(r, IntentFilter(ACTION), Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            carContext.registerReceiver(r, IntentFilter(ACTION))
        }
        receiver = r
        Log.i(TAG, "Debug kancalari kurulu — $ACTION")
    }

    fun remove() {
        receiver?.let { runCatching { carContext.unregisterReceiver(it) } }
        receiver = null
    }

    private fun pushScreen(to: String?) {
        val manager = carContext.getCarService(ScreenManager::class.java)
        val screen: Screen = when (to) {
            "calib" -> CalibrationScreen(carContext)
            "probe" -> ProbeScreen(carContext)
            "trips" -> TripsScreen(carContext)
            "records" -> RecordsScreen(carContext)
            "live" -> { manager.popToRoot(); return }
            else -> {
                Log.w(TAG, "bilinmeyen ekran: $to")
                return
            }
        }
        manager.push(screen)
    }
}
