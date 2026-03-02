// File: DecryptionValidator.kt
package solutions.semweb.nook.data.database

import android.content.Context
import solutions.semweb.nook.LogUtils
import solutions.semweb.nook.crypto.AppCryptoManager

/**
 * Centralized validator for all decryption operations
 * Used by all Entity classes to ensure data is properly decrypted
 */
object DecryptionValidator {
    private const val TAG = "DecryptionValidator"

    // Pattern to detect encrypted content (Base64 with typical markers)
    private val ENCRYPTED_PATTERN = Regex("^[A-Za-z0-9+/=]+$")

    // Statistics tracking (optional)
    private val stats = mutableMapOf<String, ValidationStats>()

    data class ValidationStats(
        var attempts: Int = 0,
        var successes: Int = 0,
        var failures: Int = 0,
        var recoveries: Int = 0
    )

    /**
     * Check if a string appears to be encrypted
     */
    fun looksLikeEncrypted(text: String?): Boolean {
        if (text.isNullOrEmpty()) return false

        // Very short strings are unlikely to be encrypted
        if (text.length < 2) return false

        // Check if it's a long Base64-like string
        return ENCRYPTED_PATTERN.matches(text) &&
                text.contains(Regex("[+/=]")) &&
                text.length % 4 == 0 // Base64 padding
    }

    /**
     * Safely decrypt a value with automatic recovery
     * @param encryptedValue The encrypted string to decrypt
     * @param fieldName Name of the field (for logging and error message)
     * @param context Android context
     * @param entityType Name of the entity type (for stats)
     * @return Decrypted value, or field-specific error message if decryption fails
     */
    fun safeDecrypt(
        encryptedValue: String?,
        fieldName: String,
        context: Context,
        entityType: String = "Unknown"
    ): String? {
        if (encryptedValue == null) return null

        // Update stats
        val entityStats = stats.getOrPut(entityType) { ValidationStats() }
        entityStats.attempts++

        try {
            // First attempt
            val decrypted = AppCryptoManager.decrypt64Value(encryptedValue)

            // Verify the decryption actually worked
            if (!looksLikeEncrypted(decrypted)) {
                entityStats.successes++
                return decrypted
            }

            // First attempt produced something that still looks encrypted
            LogUtils.w(context, TAG,
                "⚠️ [$entityType] First decryption of $fieldName still looks encrypted")

            // Try recovery - maybe it's double-encrypted or corrupted
            val recovered = tryRecovery(encryptedValue, fieldName, context, entityType)
            if (recovered != null) {
                return recovered
            }

        } catch (e: Exception) {
            // First attempt failed completely
            LogUtils.e(context, TAG,
                "❌ [$entityType] Decryption failed for $fieldName", e)
            entityStats.failures++

            // Try recovery
            val recovered = tryRecovery(encryptedValue, fieldName, context, entityType)
            if (recovered != null) {
                return recovered
            }
        }

        // All attempts failed - return field-specific error message
        return "<$fieldName decryption error>"
    }

    /**
     * Safe decrypt for non-nullable strings (provides fallback)
     */
    fun safeDecryptNonNull(
        encryptedValue: String,
        fieldName: String,
        context: Context,
        entityType: String = "Unknown"
    ): String {
        return safeDecrypt(encryptedValue, fieldName, context, entityType)
            ?: "<$fieldName decryption error>"
    }

    /**
     * Safe decrypt for optional strings
     */
    fun safeDecryptOptional(
        encryptedValue: String?,
        fieldName: String,
        context: Context,
        entityType: String = "Unknown"
    ): String? {
        return safeDecrypt(encryptedValue, fieldName, context, entityType)
    }

    /**
     * Safe decrypt for map/metadata (JSON string that needs to be parsed)
     */
    fun safeDecryptMap(
        encryptedJson: String?,
        context: Context,
        entityType: String = "Unknown"
    ): Map<String, String>? {
        if (encryptedJson == null) return null

        try {
            val decryptedJson = safeDecryptNonNull(encryptedJson, "metadata_json", context, entityType)

            // If we got an error message instead of valid JSON, return null
            if (decryptedJson.startsWith("<") && decryptedJson.endsWith(">")) {
                LogUtils.w(context, TAG,
                    "⚠️ [$entityType] Metadata decryption failed, returning null")
                return null
            }

            // Parse JSON
            return com.google.gson.Gson().fromJson(decryptedJson, Map::class.java) as Map<String, String>

        } catch (e: Exception) {
            LogUtils.e(context, TAG, "❌ [$entityType] Failed to parse metadata", e)
            return null
        }
    }

    /**
     * Batch validation for lists of strings
     */
    fun safeDecryptList(
        encryptedValues: List<String>,
        fieldName: String,
        context: Context,
        entityType: String = "Unknown"
    ): List<String> {
        return encryptedValues.mapIndexed { index, value ->
            safeDecryptNonNull(value, "$fieldName[$index]", context, entityType)
        }
    }

    /**
     * Try to recover a failed decryption
     */
    private fun tryRecovery(
        encryptedValue: String,
        fieldName: String,
        context: Context,
        entityType: String
    ): String? {
        try {
            LogUtils.i(context, TAG,
                "🔄 [$entityType] Attempting recovery for $fieldName")

            // Try alternate decryption methods
            val recoveryAttempts = listOf(
                // Attempt 1: Standard decryption again (maybe transient error)
                { AppCryptoManager.decrypt64Value(encryptedValue) },

                // Attempt 2: Try with different encoding assumptions
                {
                    // Try to clean the string first
                    val cleaned = encryptedValue.replace(Regex("\\s+"), "")
                    AppCryptoManager.decrypt64Value(cleaned)
                },

                // Attempt 3: Try as raw bytes
                {
                    val bytes = android.util.Base64.decode(encryptedValue, android.util.Base64.DEFAULT)
                    String(bytes, Charsets.UTF_8)
                },

                // Attempt 4: Try URL-safe Base64
                {
                    val urlSafe = encryptedValue.replace('-', '+').replace('_', '/')
                    val bytes = android.util.Base64.decode(urlSafe, android.util.Base64.DEFAULT)
                    String(bytes, Charsets.UTF_8)
                }
            )

            for ((index, attempt) in recoveryAttempts.withIndex()) {
                try {
                    val result = attempt()
                    if (!looksLikeEncrypted(result)) {
                        LogUtils.i(context, TAG,
                            "✅ [$entityType] Recovery attempt ${index + 1} succeeded for $fieldName")

                        // Update stats
                        stats.getOrPut(entityType) { ValidationStats() }.recoveries++

                        return result
                    }
                } catch (e: Exception) {
                    // Continue to next attempt
                }
            }

            LogUtils.e(context, TAG,
                "❌ [$entityType] All recovery attempts failed for $fieldName")

        } catch (e: Exception) {
            LogUtils.e(context, TAG,
                "❌ [$entityType] Recovery process failed for $fieldName", e)
        }

        return null
    }

    /**
     * Get statistics for all entity types
     */
    fun getStats(): Map<String, ValidationStats> {
        return stats.toMap()
    }

    /**
     * Reset all statistics
     */
    fun resetStats() {
        stats.clear()
    }

    /**
     * Get formatted stats string
     */
    fun getStatsString(): String {
        if (stats.isEmpty()) return "No decryption statistics available"

        val sb = StringBuilder()
        sb.appendLine("📊 Decryption Statistics")
        sb.appendLine("═".repeat(40))

        var totalAttempts = 0
        var totalSuccesses = 0
        var totalFailures = 0
        var totalRecoveries = 0

        stats.forEach { (entityType, entityStats) ->
            sb.appendLine("\n📁 $entityType:")
            sb.appendLine("  • Attempts: ${entityStats.attempts}")
            sb.appendLine("  • Successes: ${entityStats.successes}")
            sb.appendLine("  • Failures: ${entityStats.failures}")
            sb.appendLine("  • Recoveries: ${entityStats.recoveries}")

            totalAttempts += entityStats.attempts
            totalSuccesses += entityStats.successes
            totalFailures += entityStats.failures
            totalRecoveries += entityStats.recoveries
        }

        sb.appendLine("\n" + "═".repeat(40))
        sb.appendLine("📈 TOTAL:")
        sb.appendLine("  • Total Attempts: $totalAttempts")
        sb.appendLine("  • Success Rate: ${if (totalAttempts > 0) (totalSuccesses * 100 / totalAttempts) else 0}%")
        sb.appendLine("  • Total Recoveries: $totalRecoveries")

        return sb.toString()
    }
}