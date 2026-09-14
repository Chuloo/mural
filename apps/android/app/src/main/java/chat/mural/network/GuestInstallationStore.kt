package chat.mural.network

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import chat.mural.core.AccountFailure
import chat.mural.core.GuestInstallation
import chat.mural.core.GuestInstallationStorage
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Separate from learning exports and OpenAI keys; app backup excludes these preferences. */
class GuestInstallationStore(context: Context, origin: String) : GuestInstallationStorage {
    private val preferences = context.applicationContext.getSharedPreferences("mural_guest_installation", Context.MODE_PRIVATE)
    private val binding = "${context.packageName}|$origin|v1".toByteArray(Charsets.UTF_8)
    private val alias = "chat.mural.guest.aes"
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun key(): SecretKey = (keyStore().getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
        init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        generateKey()
    }
    override suspend fun save(value: GuestInstallation) = withContext(Dispatchers.IO) { access.withLock {
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD(binding) }
            val encrypted = cipher.doFinal(Json.encodeToString(value).toByteArray(Charsets.UTF_8))
            if (!preferences.edit().putString("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                    .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).commit()) throw AccountFailure.SecureStorage
        } catch (_: Exception) { throw AccountFailure.SecureStorage }
    } }
    override suspend fun read(): GuestInstallation? = withContext(Dispatchers.IO) { access.withLock {
        try {
            val encoded = preferences.getString("ciphertext", null) ?: return@withLock null
            if (encoded.length > 4096) throw AccountFailure.SecureStorage
            val iv = preferences.getString("iv", null) ?: throw AccountFailure.SecureStorage
            if (iv.length > 64) throw AccountFailure.SecureStorage
            val storedKey = keyStore().getKey(alias, null) as? SecretKey ?: throw AccountFailure.SecureStorage
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, storedKey, GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
                updateAAD(binding)
            }
            Json.decodeFromString<GuestInstallation>(cipher.doFinal(Base64.decode(encoded, Base64.NO_WRAP)).toString(Charsets.UTF_8))
        } catch (_: Exception) {
            throw AccountFailure.SecureStorage
        }
    } }
    companion object {
        // Keep installation identity stable across concurrent controller instances. Corruption fails
        // closed; silently replacing this identity could issue a second welcome allowance.
        private val access = Mutex()
    }
}
