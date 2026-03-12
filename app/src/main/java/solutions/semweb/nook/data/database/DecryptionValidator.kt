// File: DecryptionValidator.kt
package solutions.semweb.nook.data.database

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import solutions.semweb.nook.BuildConfig
import solutions.semweb.nook.LogUtils
import solutions.semweb.nook.crypto.AppCryptoManager
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.ConcurrentLinkedQueue

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

    // Failure tracking for debug alerts
    private val failures = ConcurrentLinkedQueue<DecryptionFailure>()
    private var lastAlertTime = 0L
    private val ALERT_COOLDOWN = 3000L // 3 seconds between alerts

    data class ValidationStats(
        var attempts: Int = 0,
        var successes: Int = 0,
        var failures: Int = 0,
        var recoveries: Int = 0
    )

    data class DecryptionFailure(
        val timestamp: Long = System.currentTimeMillis(),
        val entityType: String,
        val fieldName: String,
        val encryptedValue: String,
        val exceptionMessage: String,
        val stackTrace: String,
        val extraInfo: String? = null,
        val conversationId: Long? = null,
        val messageId: Long? = null
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
     * Check if decrypted text looks valid for its field type
     */
    private fun isValidForField(decrypted: String, fieldName: String): Boolean {
        if (decrypted.startsWith("[DECRYPTION_FAILED]") ||
            decrypted.startsWith("[RECOVERY_FAILED]")) {
            return false
        }

        return when {
            fieldName.contains("phone", ignoreCase = true) -> isValidPhoneNumber(decrypted)
            fieldName.contains("name", ignoreCase = true) -> isValidContactName(decrypted)
            fieldName.contains("text", ignoreCase = true) ||
                    fieldName.contains("message", ignoreCase = true) -> isValidMessageText(decrypted)
            else -> !looksLikeEncrypted(decrypted) && decrypted.isNotBlank()
        }
    }

    fun isValidPhoneNumber(text: String): Boolean {
        if (text.isBlank()) return false
        // Phone should have digits and optional +, spaces, hyphens
        val validChars = text.all {
            it.isDigit() || it == '+' || it == ' ' || it == '-' || it == '(' || it == ')'
        }
        return validChars && text.any { it.isDigit() }
    }

    fun isValidContactName(text: String): Boolean {
        if (text.isBlank()) return true // Empty name is allowed

        // SUPER PERMISSIVE - just check that it's not obviously still encrypted

        // 1. Check for decryption failure markers
        if (text.startsWith("[DECRYPTION_FAILED]") ||
            text.startsWith("[RECOVERY_FAILED]")) {
            return false
        }

        // 2. If it looks like long Base64 (all alphanumeric + /+ =) and is long, reject
        // But be careful: some names might accidentally match this pattern
        val looksLikeBase64 = text.matches(Regex("^[A-Za-z0-9+/=]+$")) && text.length > 50

        // 3. Check for excessive non-printable characters (gibberish)
        val nonPrintableCount = text.count { it.code < 32 && it.code != 9 && it.code != 10 && it.code != 13 }
        val highNonPrintableRatio = nonPrintableCount > text.length * 0.3

        // 4. If it's all uppercase/lowercase with no spaces and very long, suspicious
        val allSameCase = text == text.uppercase() || text == text.lowercase()
        val suspiciousPattern = allSameCase && text.length > 40 && !text.contains(" ")

        return !looksLikeBase64 && !highNonPrintableRatio && !suspiciousPattern
    }


    fun isValidMessageText(text: String): Boolean {
        if (text.isEmpty()) return true

        // Check if it's likely still encrypted
        val looksStillEncrypted = looksLikeEncrypted(text) && text.length > 20

        // Check for excessive non-printable characters
        val nonPrintableCount = text.count { it.code < 32 && it.code != 9 && it.code != 10 && it.code != 13 }
        val highNonPrintableRatio = nonPrintableCount > text.length * 0.3

        return !looksStillEncrypted && !highNonPrintableRatio
    }

    /**
     * Safely decrypt a value with automatic recovery
     * @param encryptedValue The encrypted string to decrypt
     * @param fieldName Name of the field (for logging and error message)
     * @param context Android context
     * @param entityType Name of the entity type (for stats)
     * @param conversationId Optional conversation ID for tracking
     * @param messageId Optional message ID for tracking
     * @return Decrypted value
     * @throws RuntimeException if decryption fails
     */
    fun safeDecrypt(
        encryptedValue: String?,
        fieldName: String,
        context: Context,
        entityType: String = "Unknown",
        conversationId: Long? = null,
        messageId: Long? = null
    ): String? {
        // Handle null
        if (encryptedValue == null) return null

        // Handle empty string - return empty string immediately without trying to decrypt
        if (encryptedValue.isEmpty()) {
            return ""
        }

        val entityStats = stats.getOrPut(entityType) { ValidationStats() }
        entityStats.attempts++

        try {
            val decrypted = AppCryptoManager.decrypt64Value(encryptedValue)

            // on problem we stop here...
            if (decrypted == encryptedValue)
            {
                LogUtils.d("badDecryption"," condersation id: "+conversationId+" wrong decryption: "+fieldName+": "+decrypted)
            }

            // Validate the decryption result
            if (isValidForField(decrypted, fieldName)) {
                entityStats.successes++
                return decrypted
            } else {
                // Suspicious result - log and attempt recovery
                LogUtils.w(context, TAG,
                    "⚠️ [$entityType] Decryption produced suspicious result for $fieldName: '${decrypted.take(50)}...'")

                // Try recovery
                val recovered = tryRecovery(encryptedValue, fieldName, context, entityType)
                if (recovered != null && isValidForField(recovered, fieldName)) {
                    entityStats.recoveries++
                    return recovered
                }
            }
        } catch (e: Exception) {
            // Special handling for fields that might be empty or optional
            if (fieldName == "contactName" || fieldName == "senderName" ||
                fieldName == "encodingPassword" || fieldName == "additionalInfo") {
                LogUtils.w(context, TAG, "⚠️ [$entityType] Failed to decrypt optional field $fieldName, returning empty/null")
                entityStats.failures++
                return "" // Return empty string for optional fields instead of throwing
            }

            LogUtils.e(context, TAG, "❌ [$entityType] Decryption failed for $fieldName", e)
            entityStats.failures++

            // Record failure for debug alert
            recordFailure(
                entityType = entityType,
                fieldName = fieldName,
                encryptedValue = encryptedValue,
                exception = e,
                context = context,
                conversationId = conversationId,
                messageId = messageId
            )
        }

        // Try recovery if we haven't already
        val recovered = tryRecovery(encryptedValue, fieldName, context, entityType)
        if (recovered != null && isValidForField(recovered, fieldName)) {
            entityStats.recoveries++
            return recovered
        }

        // For optional fields, return empty string instead of throwing
        if (fieldName == "contactName" || fieldName == "senderName" ||
            fieldName == "encodingPassword" || fieldName == "additionalInfo") {
            LogUtils.w(context, TAG, "⚠️ [$entityType] All recovery attempts failed for optional field $fieldName, returning empty string")
            return ""
        }

        // All failed - throw with detailed error for required fields
        val errorMsg = buildString {
            append("Failed to decrypt $fieldName for $entityType")
            conversationId?.let { append(" (conversation: $it)") }
            messageId?.let { append(" (message: $it)") }
        }

        val exception = RuntimeException(errorMsg)

        // Record final failure
        recordFailure(
            entityType = entityType,
            fieldName = fieldName,
            encryptedValue = encryptedValue,
            exception = exception,
            context = context,
            conversationId = conversationId,
            messageId = messageId,
            isFinal = true
        )

        throw exception
    }

    /**
     * Record a decryption failure and show alert in DEBUG mode
     */
    private fun recordFailure(
        entityType: String,
        fieldName: String,
        encryptedValue: String,
        exception: Exception,
        context: Context,
        conversationId: Long? = null,
        messageId: Long? = null,
        isFinal: Boolean = false
    ) {
        val stackTrace = StringWriter().apply {
            exception.printStackTrace(PrintWriter(this))
        }.toString()

        val failure = DecryptionFailure(
            entityType = entityType,
            fieldName = fieldName,
            encryptedValue = encryptedValue.take(200) +
                    if (encryptedValue.length > 200) "..." else "",
            exceptionMessage = exception.message ?: "Unknown error",
            stackTrace = stackTrace,
            extraInfo = if (isFinal) "FINAL_FAILURE" else "RECOVERABLE",
            conversationId = conversationId,
            messageId = messageId
        )

        failures.add(failure)

        // Log detailed failure
        LogUtils.e(TAG, """
            ========================================
            ❌ DECRYPTION FAILURE (${if (isFinal) "FINAL" else "ATTEMPT"})
            Entity: $entityType
            Field: $fieldName
            ${conversationId?.let { "Conversation: $it" } ?: ""}
            ${messageId?.let { "Message: $it" } ?: ""}
            Error: ${exception.message}
            Encrypted (first 100): ${encryptedValue.take(100)}
            ========================================
        """.trimIndent())

        // Log stack trace
        LogUtils.e(TAG, "Stack trace:", exception)

        // Show alert in debug mode for FINAL failures
        if (BuildConfig.DEBUG && isFinal) {
            showDialogFailureAlert(context, failure)
        }
    }

    /**
     * Show alert dialog for decryption failure (DEBUG only)
     */
    private fun showDialogFailureAlert(context: Context, failure: DecryptionFailure) {
        // Rate limit alerts
        val now = System.currentTimeMillis()
        if (now - lastAlertTime < ALERT_COOLDOWN) {
            return
        }
        lastAlertTime = now

        try {
            Handler(Looper.getMainLooper()).post {
                try {
                    // Create the dialog
                    val alertDialog = AlertDialog.Builder(context)
                        .setTitle("🔐 DECRYPTION FAILED (DEBUG)")
                        .setMessage(buildAlertMessage(failure))
                        .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                        .setNeutralButton("Copy Details") { _, _ ->
                            copyToClipboard(context, buildDetailsText(failure))
                        }
                        .create()

                    // Only try to set window type if we have the permission and it's necessary
                    // Better approach: Use the dialog as-is without overlay type
                    try {
                        alertDialog.show()
                    } catch (e: WindowManager.BadTokenException) {
                        // If showing fails, try to get an activity context
                        LogUtils.w( context,TAG, "Could not show dialog with current context, trying to get activity")

                        // Try to get an activity from the context
                        var activity: Activity? = null
                        if (context is Activity) {
                            activity = context
                        } else if (context is ContextWrapper) {
                            activity = findActivity(context)
                        }

                        if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                            // Recreate dialog with activity context
                            val activityDialog = AlertDialog.Builder(activity)
                                .setTitle("🔐 DECRYPTION FAILED (DEBUG)")
                                .setMessage(buildAlertMessage(failure))
                                .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                                .setNeutralButton("Copy Details") { _, _ ->
                                    copyToClipboard(activity, buildDetailsText(failure))
                                }
                                .create()
                            activityDialog.show()
                        } else {
                            // Fall back to just logging
                            LogUtils.e(TAG, "Cannot show dialog - no valid activity context")
                        }
                    }
                } catch (e: Exception) {
                    LogUtils.e(TAG, "Failed to show alert dialog", e)
                }
            }
        } catch (e: Exception) {
            LogUtils.e(TAG, "Failed to post alert", e)
        }
    }

    /**
     * Helper to find an Activity from a Context
     */
    private fun findActivity(context: Context): Activity? {
        return when (context) {
            is Activity -> context
            is ContextWrapper -> findActivity(context.baseContext)
            else -> null
        }
    }

    private fun buildAlertMessage(failure: DecryptionFailure): String {
        return """
            📱 Entity: ${failure.entityType}
            🔑 Field: ${failure.fieldName}
            ⚠️ Error: ${failure.exceptionMessage}
            
            ${failure.conversationId?.let { "💬 Conversation ID: $it\n" } ?: ""}
            ${failure.messageId?.let { "📝 Message ID: $it\n" } ?: ""}
            
            📝 First 50 chars: ${failure.encryptedValue.take(50)}
            
            💡 Check Logcat for full stack trace.
            
            This indicates a data corruption or encryption mismatch.
        """.trimIndent()
    }

    private fun buildDetailsText(failure: DecryptionFailure): String {
        return """
            DECRYPTION FAILURE DETAILS
            =========================
            Timestamp: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(failure.timestamp)}
            Entity: ${failure.entityType}
            Field: ${failure.fieldName}
            Error: ${failure.exceptionMessage}
            Conversation ID: ${failure.conversationId ?: "N/A"}
            Message ID: ${failure.messageId ?: "N/A"}
            Extra: ${failure.extraInfo ?: "N/A"}
            
            Encrypted Value (full):
            ${failure.encryptedValue}
            
            Stack Trace:
            ${failure.stackTrace}
        """.trimIndent()
    }

    private fun copyToClipboard(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Decryption Failure Details", text)
        clipboard.setPrimaryClip(clip)
        android.widget.Toast.makeText(context, "Details copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
    }

    /**
     * Safe decrypt for non-nullable strings
     */
    fun safeDecryptNonNull(
        encryptedValue: String,
        fieldName: String,
        context: Context,
        entityType: String = "Unknown",
        conversationId: Long? = null,
        messageId: Long? = null
    ): String {
        return safeDecrypt(
            encryptedValue = encryptedValue,
            fieldName = fieldName,
            context = context,
            entityType = entityType,
            conversationId = conversationId,
            messageId = messageId
        ) ?: throw RuntimeException("Unexpected null from safeDecrypt for $fieldName")
    }

    /**
     * Safe decrypt for optional strings
     */
    fun safeDecryptOptional(
        encryptedValue: String?,
        fieldName: String,
        context: Context,
        entityType: String = "Unknown",
        conversationId: Long? = null,
        messageId: Long? = null
    ): String? {
        // Handle empty string - return empty string without trying to decrypt
        if (encryptedValue != null && encryptedValue.isEmpty()) {
            return ""
        }

        return safeDecrypt(
            encryptedValue = encryptedValue,
            fieldName = fieldName,
            context = context,
            entityType = entityType,
            conversationId = conversationId,
            messageId = messageId
        )
    }

    /**
     * Safe decrypt for map/metadata (JSON string that needs to be parsed)
     */
    fun safeDecryptMap(
        encryptedJson: String?,
        context: Context,
        entityType: String = "Unknown",
        conversationId: Long? = null,
        messageId: Long? = null
    ): Map<String, String>? {
        if (encryptedJson == null) return null

        try {
            val decryptedJson = safeDecryptNonNull(
                encryptedValue = encryptedJson,
                fieldName = "metadata_json",
                context = context,
                entityType = entityType,
                conversationId = conversationId,
                messageId = messageId
            )

            // Parse JSON
            return com.google.gson.Gson().fromJson(decryptedJson, Map::class.java) as Map<String, String>

        } catch (e: Exception) {
            LogUtils.e(context, TAG, "❌ [$entityType] Failed to parse metadata", e)

            // Record failure
            recordFailure(
                entityType = entityType,
                fieldName = "metadata_json",
                encryptedValue = encryptedJson,
                exception = e,
                context = context,
                conversationId = conversationId,
                messageId = messageId,
                isFinal = true
            )

            return null
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
                },

                // Attempt 5: Try ISO-8859-1 to UTF-8 conversion
                {
                    val corrupted = AppCryptoManager.decrypt64Value(encryptedValue)
                    val bytes = corrupted.toByteArray(Charsets.ISO_8859_1)
                    String(bytes, Charsets.UTF_8)
                }
            )

            for ((index, attempt) in recoveryAttempts.withIndex()) {
                try {
                    val result = attempt()
                    if (!looksLikeEncrypted(result) && isValidForField(result, fieldName)) {
                        LogUtils.i(context, TAG,
                            "✅ [$entityType] Recovery attempt ${index + 1} succeeded for $fieldName")
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
     * Get all recorded failures
     */
    fun getFailures(): List<DecryptionFailure> = failures.toList()

    /**
     * Get failures for a specific entity
     */
    fun getFailuresForEntity(entityType: String): List<DecryptionFailure> {
        return failures.filter { it.entityType == entityType }
    }

    /**
     * Clear recorded failures
     */
    fun clearFailures() {
        failures.clear()
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
        failures.clear()
    }

    /**
     * Get formatted stats string
     */
    fun getStatsString(): String {
        if (stats.isEmpty() && failures.isEmpty()) return "No decryption statistics available"

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
        sb.appendLine("  • Total Failures: ${failures.size}")

        return sb.toString()
    }

    /**
     * Get a simple failure summary for debugging
     */
    fun getFailureSummary(): String {
        if (failures.isEmpty()) return "✅ No failures"

        val grouped = failures.groupBy { it.entityType }
        return grouped.map { (type, list) ->
            "$type: ${list.size} failures"
        }.joinToString(", ")
    }
}