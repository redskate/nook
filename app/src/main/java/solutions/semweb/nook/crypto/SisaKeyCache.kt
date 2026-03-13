package solutions.semweb.nook.crypto

import android.content.Context
import android.os.Build
import android.security.keystore.KeyProperties
import solutions.semweb.nook.LogUtils
import java.security.KeyStore
import java.security.KeyStore.SecretKeyEntry
import javax.crypto.SecretKey
import android.security.keystore.KeyProtection

/**
 * Manages cached AES keys derived from passwords
 * Stores keys directly in Android Keystore
 * No in-memory caching - always reads from KeyStore
 * Supports Android 8+ (API 26+) only
 */
object SisaKeyCache {
    private const val TAG = "SisaKeyCache"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"

    // Cache the KeyStore instance to avoid repeated loading
    private var keyStore: KeyStore? = null

    /**
     * Get the KeyStore instance (lazy initialization)
     */
    private fun getKeyStore(): KeyStore {
        if (keyStore == null) {
            keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        }
        return keyStore!!
    }

    /**
     * Get cached AES key for a phone number and date
     * Only reads from KeyStore, returns null if not found
     */
    fun getCachedKey(context: Context, phoneNumber: String, dateStr: String): SecretKey? {
        val keystoreAlias = generateKeystoreAlias(phoneNumber, dateStr)

        return try {
            val keyStore = getKeyStore()

            if (keyStore.containsAlias(keystoreAlias)) {
                val secretKeyEntry = keyStore.getEntry(keystoreAlias, null) as? SecretKeyEntry
                val key = secretKeyEntry?.secretKey

                if (key != null) {
                    LogUtils.d(context, TAG, "🔐 KeyStore cache hit for $phoneNumber on $dateStr")

                    // Verify the key is valid for AES
                    if (key.algorithm == "AES") {
                        key
                    } else {
                        LogUtils.w(context, TAG, "⚠️ Key algorithm is ${key.algorithm}, expected AES")
                        // Delete invalid key
                        keyStore.deleteEntry(keystoreAlias)
                        null
                    }
                } else {
                    LogUtils.w(context, TAG, "⚠️ KeyStore entry exists but key is null for $keystoreAlias")
                    // Clean up invalid entry
                    keyStore.deleteEntry(keystoreAlias)
                    null
                }
            } else {
                LogUtils.d(context, TAG, "❌ No cached key for $phoneNumber on $dateStr")
                null
            }
        } catch (e: Exception) {
            LogUtils.e(context, TAG, "❌ Error accessing KeyStore for $keystoreAlias", e)
            // Reset KeyStore instance on error
            keyStore = null
            null
        }
    }

    /**
     * Store a derived AES key in KeyStore
     * Works on Android 8+ (API 26+) through Android 16+
     */
    fun cacheKey(context: Context, phoneNumber: String, dateStr: String, key: SecretKey): Boolean {
        val keystoreAlias = generateKeystoreAlias(phoneNumber, dateStr)

        return try {
            val keyStore = getKeyStore()

            // Delete existing key if present
            if (keyStore.containsAlias(keystoreAlias)) {
                keyStore.deleteEntry(keystoreAlias)
                LogUtils.d(context, TAG, "🗑️ Removed existing key for alias: $keystoreAlias")
            }

            // Store key with protection parameters that allow deterministic encryption
            storeKeyWithProtection(context, keystoreAlias, key)

            // Verify the key was stored successfully
            val stored = keyStore.containsAlias(keystoreAlias)
            if (stored) {
                LogUtils.d(context, TAG, "✅ Key successfully cached in KeyStore for $phoneNumber on $dateStr")
            } else {
                LogUtils.e(context, TAG, "❌ Failed to verify key storage for $keystoreAlias")
            }

            stored
        } catch (e: Exception) {
            LogUtils.e(context, TAG, "❌ Failed to store key in KeyStore for $keystoreAlias", e)
            // Reset KeyStore instance on error
            keyStore = null
            false
        }
    }

    /**
     * Key storage with protection parameters
     * Works on all supported Android versions (8+)
     *
     * CRITICAL:
     * - setRandomizedEncryptionRequired(false) allows us to use deterministic IVs
     * - This maintains compatibility with SisaCrypto's deterministic encryption
     *   where IV is derived from the date
     */
    private fun storeKeyWithProtection(context: Context, alias: String, key: SecretKey) {
        val keyStore = getKeyStore()

        // Build protection parameters that ALLOW deterministic encryption
        val protection = KeyProtection.Builder(
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            // CRITICAL: This must be false to allow deterministic IVs
            .setRandomizedEncryptionRequired(false)
            .setUserAuthenticationRequired(false)
            .build()

        val entry = SecretKeyEntry(key)
        keyStore.setEntry(alias, entry, protection)

        LogUtils.d(context, TAG, "🔧 Key stored with protection (randomizedEncryptionRequired=false)")
    }

    /**
     * Generate a stable KeyStore alias
     */
    private fun generateKeystoreAlias(phoneNumber: String, dateStr: String): String {
        val phoneHash = hashString(phoneNumber)
        return "sisa_key_${phoneHash}_${dateStr}"
    }

    /**
     * Generate a stable hash string
     */
    private fun hashString(input: String): String {
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(input.toByteArray())
            hash.joinToString("") { "%02x".format(it) }.take(16)
        } catch (e: Exception) {
            // Fallback to simple hash if SHA-256 not available
            String.format("%016x", input.hashCode().toLong() and 0xFFFFFFFFFFFFFFF)
        }
    }

    /**
     * Check if a key exists in cache
     */
    fun hasKey(context: Context, phoneNumber: String, dateStr: String): Boolean {
        val keystoreAlias = generateKeystoreAlias(phoneNumber, dateStr)
        return try {
            val keyStore = getKeyStore()
            keyStore.containsAlias(keystoreAlias)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Clear all keys for a specific phone number (useful for testing)
     */
    fun clearKeysForPhoneNumber(context: Context, phoneNumber: String) {
        try {
            val keyStore = getKeyStore()
            val aliases = keyStore.aliases()
            val phoneHash = hashString(phoneNumber)

            aliases.toList().forEach { alias ->
                if (alias.startsWith("sisa_key_${phoneHash}_")) {
                    keyStore.deleteEntry(alias)
                    LogUtils.d(context, TAG, "🗑️ Deleted key: $alias")
                }
            }
        } catch (e: Exception) {
            LogUtils.e(context, TAG, "❌ Error clearing keys", e)
        }
    }
}