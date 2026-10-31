package com.secureencryptor.app.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the shared password and the chat history encrypted with a key that lives in the
 * Android Keystore (never leaves the secure hardware / system store, only this app can use it).
 */
object SecretStore {
    private const val ALIAS = "secure_encryptor_store_key"
    private const val PREFS = "secure_store"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return kg.generateKey()
    }

    /** iv(12) + ciphertext */
    fun seal(data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return c.iv + c.doFinal(data)
    }

    fun open(blob: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, 12))
        return c.doFinal(blob, 12, blob.size - 12)
    }

    @Synchronized
    fun setPassword(ctx: Context, password: String?) {
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (password.isNullOrEmpty()) {
            p.edit().remove("pw").apply()
        } else {
            val enc = Base64.encodeToString(seal(password.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
            p.edit().putString("pw", enc).apply()
        }
    }

    @Synchronized
    fun getPassword(ctx: Context): String? = try {
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.getString("pw", null)?.let { String(open(Base64.decode(it, Base64.NO_WRAP)), Charsets.UTF_8) }
    } catch (e: Exception) {
        null
    }
}
