package solutions.semweb.nook.crypto

import android.content.Context
import android.os.Build
import android.security.keystore.KeyProperties
import solutions.semweb.nook.LogUtils
import java.security.KeyStore
import java.security.KeyStore.SecretKeyEntry
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import android.security.keystore.KeyProtection
import android.util.Base64

/**
 * Manages cached AES keys derived from passwords
 * Stores keys directly in Android Keystore for maximum security
 * NO in-memory caching - always reads from KeyStore
 */
object SisaKeyCache {
    private const val TAG = "SisaKeyCache"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val PREFS_FALLBACK_NAME = "sisa_key_fallback"

    // NO memory cache - we always read from KeyStore directly

    /**
     * Get cached AES key for a phone number and date
     * Always reads from KeyStore, never from memory
     * Returns null if not in cache or expired
     */
    fun getCachedKey(context: Context, phoneNumber: String, dateStr: String): SecretKey? {
        val keystoreAlias = generateKeystoreAlias(phoneNumber, dateStr)

        // Try KeyStore first (Android 6+)
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
                    // Fallback to encrypted prefs if KeyStore entry exists but is invalid
                    getKeyFromEncryptedPrefs(context, keystoreAlias)
                }
            } else {
                // Try fallback storage for older Android versions
                getKeyFromEncryptedPrefs(context, keystoreAlias)
            }
        } catch (e: Exception) {
            LogUtils.e(context, TAG, "❌ Error accessing Keystore", e)
            // Fallback to encrypted prefs
            getKeyFromEncryptedPrefs(context, keystoreAlias)
        }
    }

    /**
     * Store a derived AES key in cache
     * Stores directly in KeyStore when possible, falls back to encrypted SharedPreferences
     */
    fun cacheKey(context: Context, phoneNumber: String, dateStr: String, key: SecretKey) {
        storeKeyInKeystore(context, phoneNumber, dateStr, key)
    }

    private fun storeKeyInKeystore(context: Context, phoneNumber: String, dateStr: String, key: SecretKey) {
        val keystoreAlias = generateKeystoreAlias(phoneNumber, dateStr)

        try {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
            keyStore.load(null)

            // Delete existing key if present
            if (keyStore.containsAlias(keystoreAlias)) {
                keyStore.deleteEntry(keystoreAlias)
                LogUtils.d(context, TAG, "🗑️ Removed existing key for alias: $keystoreAlias")
            }

            when {
                // Android 11+ (API 30+) - Can import keys directly
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                    importKeyToKeystore(keystoreAlias, key)
                    LogUtils.d(context, TAG, "🔐 Key imported to Keystore (API 30+): $keystoreAlias")
                }

                // Android 9-10 (API 28-29) - Can use KeyProtection.Builder
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> {
                    importKeyToKeystoreWithProtection(keystoreAlias, key)
                    LogUtils.d(context, TAG, "🔐 Key imported to Keystore (API 28-29): $keystoreAlias")
                }

                // Android 7-8 (API 24-27) - Can use Keystore with SecretKeyEntry
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.N -> {
                    importKeyToKeystoreLegacy(keystoreAlias, key)
                    LogUtils.d(context, TAG, "🔐 Key imported to Keystore (API 24-27): $keystoreAlias")
                }

                // Android 6 (API 23) - Limited support
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                    importKeyToKeystoreLegacy(keystoreAlias, key)
                    LogUtils.d(context, TAG, "🔐 Key imported to Keystore (API 23): $keystoreAlias")
                }

                // Below Android 6 - Fallback to encrypted SharedPreferences
                else -> {
                    LogUtils.w(context, TAG, "⚠️ Android ${Build.VERSION.SDK_INT} < 6, using encrypted prefs fallback")
                    storeKeyInEncryptedPrefs(context, keystoreAlias, key)
                }
            }
        } catch (e: Exception) {
            LogUtils.e(context, TAG, "❌ Failed to store in Keystore, using fallback", e)
            // Fallback to encrypted SharedPreferences
            storeKeyInEncryptedPrefs(context, keystoreAlias, key)
        }
    }

    private fun importKeyToKeystore(alias: String, key: SecretKey) {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

        // For Android 11+, we can use SecretKeyEntry directly
        val entry = SecretKeyEntry(key)
        keyStore.setEntry(alias, entry, null)
    }

    private fun importKeyToKeystoreWithProtection(alias: String, key: SecretKey) {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

        // For Android 9-10, use KeyProtection.Builder
        val protection = KeyProtection.Builder(KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
            .build()

        val entry = SecretKeyEntry(key)
        keyStore.setEntry(alias, entry, protection)
    }

    private fun importKeyToKeystoreLegacy(alias: String, key: SecretKey) {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

        // For Android 7-8, we can still use SecretKeyEntry
        // The protection parameters are more limited but still work
        val entry = SecretKeyEntry(key)
        keyStore.setEntry(alias, entry, null)
    }

    /**
     * Fallback: Store key in encrypted SharedPreferences using Android's keystore
     * This is less secure but works on all Android versions
     */
    private fun storeKeyInEncryptedPrefs(context: Context, alias: String, key: SecretKey) {
        try {
            val prefs = context.getSharedPreferences(PREFS_FALLBACK_NAME, Context.MODE_PRIVATE)

            // Serialize the key
            val keyBytes = key.encoded
            val keyData = mapOf(
                "algorithm" to key.algorithm,
                "format" to key.format,
                "encoded" to Base64.encodeToString(keyBytes, Base64.NO_WRAP)
            )

            // Convert to JSON and encrypt using AppCryptoManager
            val json = com.google.gson.Gson().toJson(keyData)
            val encryptedJson = AppCryptoManager.encrypt64Value(json)

            prefs.edit().putString(alias, encryptedJson).apply()

            LogUtils.d(context, TAG, "📱 Key stored in encrypted prefs fallback: $alias")
        } catch (e: Exception) {
            LogUtils.e(context, TAG, "❌ Failed to store key in encrypted prefs", e)
        }
    }

    /**
     * Fallback: Retrieve key from encrypted SharedPreferences
     */
    private fun getKeyFromEncryptedPrefs(context: Context, alias: String): SecretKey? {
        try {
            val prefs = context.getSharedPreferences(PREFS_FALLBACK_NAME, Context.MODE_PRIVATE)
            val encryptedJson = prefs.getString(alias, null) ?: return null

            // Decrypt using AppCryptoManager
            val json = AppCryptoManager.decrypt64Value(encryptedJson)

            val type = object : com.google.gson.reflect.TypeToken<Map<String, String>>() {}.type
            val keyData: Map<String, String> = com.google.gson.Gson().fromJson(json, type)

            val algorithm = keyData["algorithm"] ?: return null
            val encodedStr = keyData["encoded"] ?: return null
            val keyBytes = Base64.decode(encodedStr, Base64.DEFAULT)

            return SecretKeySpec(keyBytes, algorithm)
        } catch (e: Exception) {
            LogUtils.e(context, TAG, "❌ Failed to get key from encrypted prefs", e)
            return null
        }
    }

    /**
     * Clear cache for a specific phone number
     * Call this when password changes
     */
    fun clearCacheForPhone(context: Context, phoneNumber: String) {
        // Clear from KeyStore (Android 6+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
                keyStore.load(null)

                // Enumerate all aliases and delete those matching our pattern
                val aliases = keyStore.aliases()
                val phoneHash = hashString(phoneNumber)
                val pattern = "sisa_key_${phoneHash}_.*".toRegex()

                var deletedCount = 0
                while (aliases.hasMoreElements()) {
                    val alias = aliases.nextElement()
                    if (alias.matches(pattern)) {
                        keyStore.deleteEntry(alias)
                        deletedCount++
                        LogUtils.d(context, TAG, "🗑️ Deleted Keystore entry: $alias")
                    }
                }

                LogUtils.d(context, TAG, "🗑️ Cleared Keystore cache for $phoneNumber ($deletedCount keys)")
            } catch (e: Exception) {
                LogUtils.e(context, TAG, "❌ Error clearing Keystore entries", e)
            }
        }

        // Clear from fallback prefs
        try {
            val prefs = context.getSharedPreferences(PREFS_FALLBACK_NAME, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            val phoneHash = hashString(phoneNumber)
            val pattern = "sisa_key_${phoneHash}_.*".toRegex()

            val allKeys = prefs.all.keys
            var deletedCount = 0
            allKeys.forEach { key ->
                if (key.matches(pattern)) {
                    editor.remove(key)
                    deletedCount++
                }
            }
            editor.apply()

            LogUtils.d(context, TAG, "🗑️ Cleared fallback cache for $phoneNumber ($deletedCount keys)")
        } catch (e: Exception) {
            LogUtils.e(context, TAG, "❌ Error clearing fallback entries", e)
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
     * Get cache statistics for debugging
     */
    fun getCacheStats(context: Context): String {
        val stats = StringBuilder()
        stats.appendLine("🔐 SisaKeyCache Statistics")
        stats.appendLine("═".repeat(40))
        stats.appendLine("Memory cache: DISABLED (0 keys)")
        stats.appendLine("Android API: ${Build.VERSION.SDK_INT}")

        // KeyStore stats
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
                keyStore.load(null)

                val aliases = keyStore.aliases()
                var keystoreCount = 0
                val recentKeys = mutableListOf<String>()

                while (aliases.hasMoreElements()) {
                    val alias = aliases.nextElement()
                    if (alias.startsWith("sisa_key_")) {
                        keystoreCount++
                        if (recentKeys.size < 5) {
                            recentKeys.add(alias)
                        }
                    }
                }

                stats.appendLine("Keystore cache size: $keystoreCount keys")

                if (recentKeys.isNotEmpty()) {
                    stats.appendLine("\n📋 Recent keys in Keystore:")
                    recentKeys.forEach { alias ->
                        stats.appendLine("  • $alias")
                    }
                }
            } catch (e: Exception) {
                stats.appendLine("Keystore: Error accessing")
            }
        } else {
            stats.appendLine("Keystore: Not available (Android < 6)")
        }

        // Fallback prefs stats
        try {
            val prefs = context.getSharedPreferences(PREFS_FALLBACK_NAME, Context.MODE_PRIVATE)
            val fallbackKeys = prefs.all.keys.filter { it.startsWith("sisa_key_") }
            stats.appendLine("Fallback cache size: ${fallbackKeys.size} keys")

            if (fallbackKeys.isNotEmpty()) {
                stats.appendLine("\n📋 Recent keys in fallback:")
                fallbackKeys.take(5).forEach { key ->
                    stats.appendLine("  • $key")
                }
            }
        } catch (e: Exception) {
            stats.appendLine("Fallback: Error accessing")
        }

        return stats.toString()
    }
}