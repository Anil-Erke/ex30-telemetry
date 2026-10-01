package com.example.ex30telemetry.google

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Google refresh token'ini diskte SIFRELI tutar (Android Keystore, AES-GCM).
 *
 * Refresh token o kisinin Drive'ina (bu uygulamanin dosyalarina) suresiz
 * erisim demek. Uygulamaya ozel depolama zaten baska uygulamalara kapali; bu
 * katman, disari cikarilan bir yedekten ya da root'lu bir cihazdan token'in
 * duz metin okunmasini engelliyor. Anahtar Keystore'dan hic cikmiyor.
 *
 * Anahtar kaybolursa (fabrika ayari, Keystore sifirlanmasi) cozme null doner;
 * cagiran taraf bunu "bagli degil" sayip yeniden baglanmayi istiyor.
 */
internal object SecretBox {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "ex30_google_refresh"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val out = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun decrypt(encoded: String): String? = runCatching {
        val all = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES),
        )
        cipher.doFinal(all, IV_BYTES, all.size - IV_BYTES).toString(Charsets.UTF_8)
    }.getOrNull()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }
}
