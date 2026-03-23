package solutions.semweb.nook.crypto

import android.content.Context
import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import solutions.semweb.nook.LogUtils
import java.security.KeyStore
import java.security.KeyStore.SecretKeyEntry
import javax.crypto.SecretKey

object SisaKeyCache {
    private const val TAG = "SisaKeyCache"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"

    private var keyStore: KeyStore? = null

    private fun getKeyStore(): KeyStore {
        if (keyStore == null) {
            keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        }
        return keyStore!!
    }

    /**
     * Get cached AES key - NOW INCLUDES PASSWORD
     */
    fun getCachedKey(context: Context, phoneNumber: String, dateStr: String, password: String): SecretKey? {
        val passwordHash = hashString(password)
        val keystoreAlias = generateKeystoreAlias(phoneNumber, dateStr, passwordHash)

        return try {
            val keyStore = getKeyStore()

            if (keyStore.containsAlias(keystoreAlias)) {
                val secretKeyEntry = keyStore.getEntry(keystoreAlias, null) as? SecretKeyEntry
                val key = secretKeyEntry?.secretKey

                if (key != null && key.algorithm == "AES") {
                    LogUtils.d(context, TAG, "🔐 KeyStore cache HIT for $phoneNumber on $dateStr with password hash: ${passwordHash.take(8)}")
                    key
                } else {
                    LogUtils.w(context, TAG, "⚠️ KeyStore entry exists but key is invalid")
                    keyStore.deleteEntry(keystoreAlias)
                    null
                }
            } else {
                LogUtils.d(context, TAG, "❌ KeyStore cache MISS for $phoneNumber on $dateStr with password hash: ${passwordHash.take(8)}")
                null
            }
        } catch (e: Exception) {
            LogUtils.e(context, TAG, "❌ Error accessing KeyStore", e)
            keyStore = null
            null
        }
    }

    /**
     * Store a derived AES key - NOW INCLUDES PASSWORD
     */
    fun cacheKey(context: Context, phoneNumber: String, dateStr: String, password: String, key: SecretKey): Boolean {
        val passwordHash = hashString(password)
        val keystoreAlias = generateKeystoreAlias(phoneNumber, dateStr, passwordHash)

        return try {
            val keyStore = getKeyStore()

            // Delete existing key if present
            if (keyStore.containsAlias(keystoreAlias)) {
                keyStore.deleteEntry(keystoreAlias)
                LogUtils.d(context, TAG, "🗑️ Removed existing key for alias: $keystoreAlias")
            }

            storeKeyWithProtection(context, keystoreAlias, key)

            val stored = keyStore.containsAlias(keystoreAlias)
            if (stored) {
                LogUtils.d(context, TAG, "✅ Key successfully cached for $phoneNumber on $dateStr with password hash: ${passwordHash.take(8)}")
            }
            stored
        } catch (e: Exception) {
            LogUtils.e(context, TAG, "❌ Failed to store key", e)
            keyStore = null
            false
        }
    }

    /**
     * Clear all keys for a specific phone number (when password changes)
     */
    fun clearKeysForPhoneNumber(context: Context, phoneNumber: String) {
        try {
            val keyStore = getKeyStore()
            val phoneHash = hashString(phoneNumber)
            val aliases = keyStore.aliases()

            var deletedCount = 0
            aliases.toList().forEach { alias ->
                if (alias.startsWith("sisa_key_${phoneHash}_")) {
                    keyStore.deleteEntry(alias)
                    deletedCount++
                    LogUtils.d(context, TAG, "🗑️ Deleted key: $alias")
                }
            }

            LogUtils.d(context, TAG, "🗑️ Cleared $deletedCount keys for phone number: $phoneNumber")
        } catch (e: Exception) {
            LogUtils.e(context, TAG, "❌ Error clearing keys", e)
        }
    }

    private fun storeKeyWithProtection(context: Context, alias: String, key: SecretKey) {
        val keyStore = getKeyStore()

        val protection = KeyProtection.Builder(
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(false)
            .setUserAuthenticationRequired(false)
            .build()

        val entry = SecretKeyEntry(key)
        keyStore.setEntry(alias, entry, protection)

        LogUtils.d(context, TAG, "🔧 Key stored with alias: ${alias.take(50)}")
    }

    private fun generateKeystoreAlias(phoneNumber: String, dateStr: String, passwordHash: String): String {
        val phoneHash = hashString(phoneNumber)
        return "sisa_key_${phoneHash}_${dateStr}_${passwordHash}"
    }

    private fun hashString(input: String): String {
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(input.toByteArray())
            hash.joinToString("") { "%02x".format(it) }.take(16)
        } catch (e: Exception) {
            String.format("%016x", input.hashCode().toLong() and 0xFFFFFFFFFFFFFFF)
        }
    }

    // Keep this for backward compatibility if needed, but mark as deprecated
    @Deprecated("Use getCachedKey with password parameter instead")
    fun getCachedKey(context: Context, phoneNumber: String, dateStr: String): SecretKey? {
        LogUtils.w(context, TAG, "⚠️ Using deprecated getCachedKey without password - this may return wrong keys!")
        // Try to find any key for this phone/date (for migration)
        return try {
            val keyStore = getKeyStore()
            val phoneHash = hashString(phoneNumber)
            val aliases = keyStore.aliases()

            aliases.toList().firstOrNull { alias ->
                alias.startsWith("sisa_key_${phoneHash}_${dateStr}_")
            }?.let { alias ->
                val entry = keyStore.getEntry(alias, null) as? SecretKeyEntry
                entry?.secretKey
            }
        } catch (e: Exception) {
            null
        }
    }
}