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
 * NO in-memory caching - always reads from KeyStore
 * Supports Android 8+ (API 26+) only
 */
object SisaKeyCache {
    private const val TAG = "SisaKeyCache"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"

    /**
     * Get cached AES key for a phone number and date
     * Only reads from KeyStore, returns null if not found
     */
    fun getCachedKey(context: Context, phoneNumber: String, dateStr: String): SecretKey? {

        val keystoreAlias = generateKeystoreAlias(phoneNumber, dateStr)

        return try {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
            keyStore.load(null)

            if (keyStore.containsAlias(keystoreAlias)) {
                val secretKeyEntry = keyStore.getEntry(keystoreAlias, null) as? SecretKeyEntry
                val key = secretKeyEntry?.secretKey

                if (key != null) {
                    LogUtils.d(context, TAG, "🔐 Keystore cache hit for $phoneNumber on $dateStr")
                    key
                } else {
                    LogUtils.w(context, TAG, "⚠️ Keystore entry exists but key is null for $keystoreAlias")
                    // Clean up invalid entry
                    keyStore.deleteEntry(keystoreAlias)
                    null
                }
            } else {
                LogUtils.d(context, TAG, "❌ No cached key for $phoneNumber on $dateStr")
                null
            }
        } catch (e: Exception) {
            LogUtils.e(context, TAG, "❌ Error accessing Keystore for $keystoreAlias", e)
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
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
            keyStore.load(null)

            // Delete existing key if present
            if (keyStore.containsAlias(keystoreAlias)) {
                keyStore.deleteEntry(keystoreAlias)
                LogUtils.d(context, TAG, "🗑️ Removed existing key for alias: $keystoreAlias")
            }

            // Store key with protection parameters that ALLOW caller-provided IVs
            storeKeyWithProtection(keystoreAlias, key)

            // Verify the key was stored successfully
            val stored = keyStore.containsAlias(keystoreAlias)
            if (stored) {
                LogUtils.d(context, TAG, "✅ Key successfully cached in Keystore for $phoneNumber on $dateStr")
            } else {
                LogUtils.e(context, TAG, "❌ Failed to verify key storage for $keystoreAlias")
            }

            stored
        } catch (e: Exception) {
            LogUtils.e(context, TAG, "❌ Failed to store key in Keystore for $keystoreAlias", e)
            false
        }
    }

    /**
     * Key storage with protection parameters
     * Works on all supported Android versions (8+)
     *
     * CRITICAL: setRandomizedEncryptionRequired(false) allows us to use our own IVs
     * This maintains compatibility with SisaCrypto's deterministic key derivation
     * and existing encryption flow that generates its own IVs
     */
    private fun storeKeyWithProtection(alias: String, key: SecretKey) {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

        // Build protection parameters that ALLOW caller-provided IVs
        val protection = KeyProtection.Builder(
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            // CRITICAL: This must be false to allow external IVs
            .setRandomizedEncryptionRequired(false)
            .setUserAuthenticationRequired(false)
            .setUserAuthenticationValidityDurationSeconds(-1)
            .setInvalidatedByBiometricEnrollment(false)
            .build()

        val entry = SecretKeyEntry(key)
        keyStore.setEntry(alias, entry, protection)

        LogUtils.d(null, TAG, "🔧 Key stored with protection (randomizedEncryptionRequired=false)")
    }

    /**
     * Alternative storage method for debugging - stores without protection
     * Only use for testing!
     */
    fun cacheKeyWithoutProtection(context: Context, phoneNumber: String, dateStr: String, key: SecretKey): Boolean {
        val keystoreAlias = generateKeystoreAlias(phoneNumber, dateStr)

        return try {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
            keyStore.load(null)

            if (keyStore.containsAlias(keystoreAlias)) {
                keyStore.deleteEntry(keystoreAlias)
            }

            // Store WITHOUT protection parameters
            val entry = SecretKeyEntry(key)
            keyStore.setEntry(keystoreAlias, entry, null)

            val stored = keyStore.containsAlias(keystoreAlias)
            if (stored) {
                LogUtils.d(context, TAG, "⚠️ Key cached WITHOUT protection for $phoneNumber on $dateStr")
            }
            stored
        } catch (e: Exception) {
            LogUtils.e(context, TAG, "❌ Failed to store key without protection", e)
            false
        }
    }

    /**
     * Generate a stable Keystore alias
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
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
            keyStore.load(null)
            keyStore.containsAlias(keystoreAlias)
        } catch (e: Exception) {
            false
        }
    }
}