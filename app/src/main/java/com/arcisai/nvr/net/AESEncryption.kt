package com.arcisai.nvr.net

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Mirrors the production ArcisAI-Android client's [AESEncryption] object so the
 * Arcis_Main_Backend `decryptPassword(SECRET_KEY,IV)` step recovers the
 * plaintext. AES-128/CBC/PKCS5, Base64 (NO_WRAP). The key+IV match the values
 * baked into the backend env on dev.arcisai.io.
 */
object AESEncryption {
    private const val SECRET_KEY = "1234567890123456"
    private const val IV         = "abcdefghijklmnop"

    fun encrypt(text: String): String = try {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val key = SecretKeySpec(SECRET_KEY.toByteArray(Charsets.UTF_8), "AES")
        val iv  = IvParameterSpec(IV.toByteArray(Charsets.UTF_8))
        cipher.init(Cipher.ENCRYPT_MODE, key, iv)
        val ct = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(ct, Base64.NO_WRAP)
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        text
    }
}
