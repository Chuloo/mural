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

/** Stores provider API keys encrypted by non-exportable Android Keystore keys. */
class CredentialStore internal constructor(
    context: Context,
    preferencesName: String,
    private val keyAlias: String,
) {
    constructor(context: Context) : this(context, PREFERENCES, KEY_ALIAS)

    private val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    val hasKey: Boolean
        get() = read() != null

    fun hasKey(provider: AIProvider): Boolean = read(provider) != null

    @Synchronized
    fun save(key: String) {
        save(key, AIProvider.OPENAI)
    }

    @Synchronized
    fun save(key: String, provider: AIProvider) {
        val value = key.trim()
        val valid = when (provider) {
            AIProvider.OPENAI -> value.startsWith("sk-") && value.length >= 20
            AIProvider.GOOGLE_AI_STUDIO -> value.length >= 20
        }
        if (!valid || value.any(Char::isWhitespace)) {
            throw CredentialException.Invalid
        }

        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey(provider))
            val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val saved = preferences.edit()
                .putString(ciphertextKey(provider), Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .putString(ivKey(provider), Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
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
        return read(AIProvider.OPENAI)
    }

    @Synchronized
    fun read(provider: AIProvider): String? {
        val encodedCiphertext = preferences.getString(ciphertextKey(provider), null) ?: return null
        val encodedIv = preferences.getString(ivKey(provider), null) ?: return clearUnreadableCredential(provider)
        return try {
            val key = keyStore().getKey(keyAlias(provider), null) as? SecretKey ?: return clearUnreadableCredential(provider)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(GCM_TAG_BITS, Base64.decode(encodedIv, Base64.NO_WRAP)),
            )
            cipher.doFinal(Base64.decode(encodedCiphertext, Base64.NO_WRAP)).toString(Charsets.UTF_8)
                .takeIf { isValidStoredKey(it, provider) }
                ?: clearUnreadableCredential(provider)
        } catch (_: Exception) {
            clearUnreadableCredential(provider)
        }
    }

    @Synchronized
    fun delete() {
        delete(AIProvider.OPENAI)
    }

    @Synchronized
    fun delete(provider: AIProvider) {
        if (!preferences.edit().remove(ciphertextKey(provider)).remove(ivKey(provider)).commit()) throw CredentialException.Remove
        try {
            val store = keyStore()
            if (store.containsAlias(keyAlias(provider))) store.deleteEntry(keyAlias(provider))
        } catch (_: Exception) { }
    }

    private fun encryptionKey(provider: AIProvider): SecretKey {
        val store = keyStore()
        (store.getKey(keyAlias(provider), null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias(provider),
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

    private fun clearUnreadableCredential(provider: AIProvider): Nothing? {
        preferences.edit().remove(ciphertextKey(provider)).remove(ivKey(provider)).commit()
        try {
            val store = keyStore()
            if (store.containsAlias(keyAlias(provider))) store.deleteEntry(keyAlias(provider))
        } catch (_: Exception) {
            // A stale ciphertext is already gone; a later save can retry key replacement.
        }
        return null
    }

    private fun isValidStoredKey(value: String, provider: AIProvider): Boolean = when (provider) {
        AIProvider.OPENAI -> value.startsWith("sk-") && value.length >= 20
        AIProvider.GOOGLE_AI_STUDIO -> value.length >= 20
    } && value.none(Char::isWhitespace)

    private fun suffix(provider: AIProvider): String = when (provider) {
        AIProvider.OPENAI -> ""
        AIProvider.GOOGLE_AI_STUDIO -> "_google"
    }

    private fun ciphertextKey(provider: AIProvider) = CIPHERTEXT + suffix(provider)
    private fun ivKey(provider: AIProvider) = IV + suffix(provider)
    private fun keyAlias(provider: AIProvider) = keyAlias + suffix(provider).replace('_', '.')

    sealed class CredentialException(message: String) : IllegalStateException(message) {
        data object Invalid : CredentialException("Enter a valid API key for the selected provider.")
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
