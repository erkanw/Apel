package io.github.erkanw.vault.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The server's bridge token. It is encrypted with an AES key that lives in the
 * Android Keystore (it never leaves the secure hardware or the system's key
 * store), so the preferences file holds only ciphertext. Like on the desktop
 * app, the token is stored together with the one address it was entered for:
 * change the address and the token no longer follows it.
 */
class SecretStore(context: Context, file: String = "secret", private val alias: String = "vault_bridge_token") {
    private val prefs = context.getSharedPreferences(file, Context.MODE_PRIVATE)

    /** The token for [url], or null if none was saved for exactly this address. */
    fun tokenFor(url: String): String? {
        if (prefs.getString(KEY_URL, null) != url) return null
        val blob = prefs.getString(KEY_TOKEN, null) ?: return null
        return try {
            val bytes = Base64.decode(blob, Base64.NO_WRAP)
            val iv = bytes.copyOfRange(0, IV_SIZE)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE), Charsets.UTF_8)
        } catch (e: Exception) {
            // The key was lost (a restore, a new lock screen): ask for the token again.
            clear()
            null
        }
    }

    fun save(url: String, token: String) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        prefs.edit {
            putString(KEY_URL, url)
            putString(KEY_TOKEN, Base64.encodeToString(sealed, Base64.NO_WRAP))
        }
    }

    fun clear() = prefs.edit { clear() }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
        const val KEY_URL = "url"
        const val KEY_TOKEN = "token"
    }
}
