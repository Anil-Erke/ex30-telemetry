package com.example.ex30telemetry.trip

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Elektrik kesilmesine dayanikli dosya yazimi: gecici dosya → fsync → yeniden adlandir.
 *
 * **Neden (2026-09-29'da emulatorde olculdu):** `File.writeText` dosyayi once
 * KESIYOR, sonra yaziyor ve diske islenmesini beklemiyor. Emulator yolculuk
 * kaydindan ~1 dk sonra aniden kapatilinca (`adb emu kill` = fis cekmek)
 * `trips.json` ve iz dosyasi **0 bayt** kaldi: dosya vardi, icerigi yoktu.
 * Bu, ext4/f2fs'nin gecikmeli yazma davranisi — cekirdek veriyi ~30 sn'ye
 * kadar bellekte tutuyor. Araçta head unit cogu zaman uykuya geciyor (RAM
 * korunuyor), ama ani bir guc kesilmesi BUTUN yolculuk gecmisini
 * silebilirdi; `trips.json` her kayitta bastan yaziliyor.
 *
 * Bu desenle ya eski dosya ya yeni dosya kalir; yarim ya da bos dosya kalmaz.
 * Android'in `AtomicFile`'i ayni isi yapiyor ama JVM birim testlerinde
 * calismiyor (android.jar taslagi).
 */
object DurableFile {

    /** @throws IOException yazilamazsa — eski dosya o durumda DOKUNULMADAN kalir. */
    fun write(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            // Bazi dosya sistemlerinde hedef varken rename basarisiz olabiliyor.
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw IOException("yeniden adlandırılamadı: ${tmp.name} → ${target.name}")
            }
        }
    }

    fun writeText(target: File, text: String) = write(target, text.toByteArray(Charsets.UTF_8))
}
