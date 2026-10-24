package alias.klips.net.core.security

import android.content.Context
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object CredentialVault {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val MASTER_KEY_ALIAS = "klipsup_byok_master_key_v1"
    private const val PREFS_NAME = "klipsup_encrypted_vault"

    private val memoryFallbackVault = mutableMapOf<String, String>()

    fun storeApiKey(context: Context?, providerId: String, plainApiKey: String) {
        if (plainApiKey.isBlank()) {
            removeApiKey(context, providerId)
            return
        }
        memoryFallbackVault[providerId] = plainApiKey.trim()
        if (context == null) return

        runCatching {
            val secretKey = getOrCreateKeystoreSecretKey()
            if (secretKey != null) {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, secretKey)
                val iv = cipher.iv
                val encrypted = cipher.doFinal(plainApiKey.trim().toByteArray(Charsets.UTF_8))
                val combined = Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
                    Base64.encodeToString(encrypted, Base64.NO_WRAP)
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString("key_$providerId", combined)
                    .apply()
            }
        }
    }

    fun getApiKey(context: Context?, providerId: String): String {
        memoryFallbackVault[providerId]?.let { if (it.isNotBlank()) return it }
        if (context == null) return ""

        return runCatching {
            val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString("key_$providerId", null) ?: return ""
            val parts = stored.split(":")
            if (parts.size != 2) return ""
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val cipherText = Base64.decode(parts[1], Base64.NO_WRAP)
            val secretKey = getOrCreateKeystoreSecretKey() ?: return ""
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        }.getOrDefault("")
    }

    fun hasApiKey(context: Context?, providerId: String): Boolean {
        return getApiKey(context, providerId).isNotBlank()
    }

    fun removeApiKey(context: Context?, providerId: String) {
        memoryFallbackVault.remove(providerId)
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.remove("key_$providerId")
            ?.apply()
    }

    /**
     * Redacts API keys, Bearer tokens, OpenAI sk- keys, and Google AIza keys from logs and error messages.
     */
    fun redactSecrets(rawText: String, additionalKeys: Collection<String> = emptyList()): String {
        var result = rawText
        // Redact explicit configured keys
        for (secret in (memoryFallbackVault.values + additionalKeys)) {
            if (secret.length >= 6) {
                result = result.replace(secret, "[REDACTED_KEY]")
            }
        }
        // Redact standard token patterns
        result = result.replace(Regex("sk-[A-Za-z0-9_\\-]{10,}"), "sk-[REDACTED]")
        result = result.replace(Regex("AIza[A-Za-z0-9_\\-]{12,}"), "AIza[REDACTED]")
        result = result.replace(Regex("Bearer\\s+[A-Za-z0-9._\\-]{8,}", RegexOption.IGNORE_CASE), "Bearer [REDACTED]")
        return result
    }

    private fun getOrCreateKeystoreSecretKey(): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = keyStore.getEntry(MASTER_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (existing != null) return existing.secretKey

            val keyGen = KeyGenerator.getInstance("AES", ANDROID_KEYSTORE)
            val spec = android.security.keystore.KeyGenParameterSpec.Builder(
                MASTER_KEY_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGen.init(spec)
            keyGen.generateKey()
        } catch (_: Throwable) {
            null
        }
    }
}
