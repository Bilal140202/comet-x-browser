package com.cometx.browser.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.cometx.browser.util.Logx
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * SecureStore: encrypts provider API keys with an AES-256/GCM key held in the
 * Android Keystore (hardware-backed where available). Ciphertext lives in a
 * plain SharedPreferences file; the plaintext never touches disk.
 *
 * Design notes:
 *  - One master key ("cometx_master"), generated on first use, non-exportable.
 *  - Per-entry random 12-byte IV, prepended to ciphertext, Base64-encoded.
 *  - Nothing about the key material is derivable from the stored blob.
 */
class SecureStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("cometx_secure", Context.MODE_PRIVATE)

    private fun masterKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("cometx_master", null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                "cometx_master",
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return kg.generateKey()
    }

    fun putString(key: String, plain: String) {
        if (plain.isEmpty()) { remove(key); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val blob = Base64.encodeToString(iv + ct, Base64.NO_WRAP)
        prefs.edit().putString(key, blob).apply()
    }

    fun getString(key: String): String? {
        val blob = prefs.getString(key, null) ?: return null
        return try {
            val raw = Base64.decode(blob, Base64.NO_WRAP)
            val iv = raw.copyOfRange(0, 12)
            val ct = raw.copyOfRange(12, raw.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            Logx.e("SecureStore decrypt failed for $key", e)
            null
        }
    }

    fun remove(key: String) = prefs.edit().remove(key).apply()

    /** Generates a random nonce for local test server / session ids. */
    fun randomToken(len: Int = 16): String {
        val alphabet = "abcdef0123456789"
        return buildString { repeat(len) { append(alphabet[SecureRandom().nextInt(alphabet.length)]) } }
    }
}

private object KeyProperties {
    const val KEY_ALGORITHM_AES = "AES"
    const val BLOCK_MODE_GCM = "GCM"
    const val ENCRYPTION_PADDING_NONE = "NoPadding"
    const val PURPOSE_ENCRYPT = 1
    const val PURPOSE_DECRYPT = 2
}
