package chat.mural.network

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores the OpenAI API key encrypted by a non-exportable Android Keystore key. */
class CredentialStore internal constructor(
    context: Context,
    preferencesName: String,
    private val keyAlias: String,
) {
    constructor(context: Context) : this(context, PREFERENCES, KEY_ALIAS)

    private val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    val hasKey: Boolean
        get() = read() != null

    @Synchronized
    fun save(key: String) {
        val value = key.trim()
        if (!value.startsWith("sk-") || value.length < 20 || value.any(Char::isWhitespace)) {
            throw CredentialException.Invalid
        }

        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
            val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val saved = preferences.edit()
                .putString(CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .putString(IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .commit()
            if (!saved) throw CredentialException.Save
        } catch (error: CredentialException) {
            throw error
        } catch (_: Exception) {
            throw CredentialException.Save
        }
    }

    @Synchronized
    fun read(): String? {
        val encodedCiphertext = preferences.getString(CIPHERTEXT, null) ?: return null
        val encodedIv = preferences.getString(IV, null) ?: return clearUnreadableCredential()
        return try {
            val key = keyStore().getKey(keyAlias, null) as? SecretKey ?: return clearUnreadableCredential()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(GCM_TAG_BITS, Base64.decode(encodedIv, Base64.NO_WRAP)),
            )
            cipher.doFinal(Base64.decode(encodedCiphertext, Base64.NO_WRAP)).toString(Charsets.UTF_8)
                .takeIf { it.startsWith("sk-") && it.length >= 20 && it.none(Char::isWhitespace) }
                ?: clearUnreadableCredential()
        } catch (_: Exception) {
            clearUnreadableCredential()
        }
    }

    @Synchronized
    fun delete() {
        if (!preferences.edit().clear().commit()) throw CredentialException.Remove
        try {
            val store = keyStore()
            if (store.containsAlias(keyAlias)) store.deleteEntry(keyAlias)
        } catch (_: Exception) { }
    }

    private fun encryptionKey(): SecretKey {
        val store = keyStore()
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
            generateKey()
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    private fun clearUnreadableCredential(): Nothing? {
        preferences.edit().clear().commit()
        try {
            val store = keyStore()
            if (store.containsAlias(keyAlias)) store.deleteEntry(keyAlias)
        } catch (_: Exception) {
            // A stale ciphertext is already gone; a later save can retry key replacement.
        }
        return null
    }

    sealed class CredentialException(message: String) : IllegalStateException(message) {
        data object Invalid : CredentialException("Enter a valid OpenAI API key.")
        data object Save : CredentialException("The key couldn't be saved securely on this device.")
        data object Remove : CredentialException("The key couldn't be removed. Unlock this device and try again.")
    }

    companion object {
        // The app excludes all shared preferences from cloud backup and device transfer.
        private const val PREFERENCES = "mural_openai_credentials"
        private const val CIPHERTEXT = "ciphertext"
        private const val IV = "iv"
        private const val KEY_ALIAS = "chat.mural.openai.aes"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }
}
