// File: DecryptionFailureMonitor.kt
package solutions.semweb.nook.crypto

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import solutions.semweb.nook.BuildConfig
import solutions.semweb.nook.LogUtils
import solutions.semweb.nook.data.database.DecryptionValidator
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.ConcurrentLinkedQueue

object DecryptionFailureMonitor {

    data class DecryptionFailure(
        val timestamp: Long = System.currentTimeMillis(),
        val entityType: String,
        val fieldName: String,
        val encryptedValue: String,
        val exceptionMessage: String,
        val stackTrace: String,
        val context: String?,
        val conversationId: Long? = null,
        val messageId: Long? = null
    )

    private val failures = ConcurrentLinkedQueue<DecryptionFailure>()
    private var lastAlertTime = 0L
    private val ALERT_COOLDOWN = 5000L // 5 seconds between alerts

    /**
     * Report a decryption failure - shows alert in DEBUG mode
     */
    fun reportFailure(
        context: Context,
        entityType: String,
        fieldName: String,
        encryptedValue: String,
        exception: Exception,
        extraInfo: String? = null,
        conversationId: Long? = null,
        messageId: Long? = null
    ) {
        val stackTrace = StringWriter().apply {
            exception.printStackTrace(PrintWriter(this))
        }.toString()

        val failure = DecryptionFailure(
            entityType = entityType,
            fieldName = fieldName,
            encryptedValue = encryptedValue.take(100) + if (encryptedValue.length > 100) "..." else "",
            exceptionMessage = exception.message ?: "Unknown error",
            stackTrace = stackTrace,
            context = extraInfo,
            conversationId = conversationId,
            messageId = messageId
        )

        failures.add(failure)

        // Log the failure
        LogUtils.e("DECRYPT_FAIL", """
            ========================================
            ❌ DECRYPTION FAILURE DETECTED
            Entity: $entityType
            Field: $fieldName
            Error: ${exception.message}
            ${extraInfo?.let { "Info: $it" } ?: ""}
            ${conversationId?.let { "Conv ID: $it" } ?: ""}
            ${messageId?.let { "Msg ID: $it" } ?: ""}
            Encrypted (first 100): ${encryptedValue.take(100)}
            ========================================
        """.trimIndent())

        // Log full stack trace
        LogUtils.e("DECRYPT_FAIL", "Stack trace:", exception)

        // Show alert in debug mode
        if (BuildConfig.DEBUG) {
            showFailureAlert(context, failure)
        }
    }

    /**
     * Check if a decryption result is valid
     * Returns true if the decrypted text looks valid
     */
    fun isValidDecryption(decrypted: String, fieldType: String = "text"): Boolean {
        if (decrypted.startsWith("[DECRYPTION_FAILED]") ||
            decrypted.startsWith("[RECOVERY_FAILED]")) {
            return false
        }

        return when (fieldType) {
            "phone" -> DecryptionValidator.isValidPhoneNumber(decrypted)
            "name" -> DecryptionValidator.isValidContactName(decrypted)
            "text" -> DecryptionValidator.isValidMessageText(decrypted)
            else -> decrypted.isNotBlank()
        }
    }




    private fun showFailureAlert(context: Context, failure: DecryptionFailure) {
        // Rate limit alerts
        val now = System.currentTimeMillis()
        if (now - lastAlertTime < ALERT_COOLDOWN) {
            return
        }
        lastAlertTime = now

        try {
            // First, try to show the dialog with the given context
            showDialogWithContext(context, failure)
        } catch (e: WindowManager.BadTokenException) {
            // If that fails, try to find an activity context
            LogUtils.w(context,"DECRYPT_FAIL", "Could not show dialog with current context, trying to find activity")

            val activity = findActivity(context)
            if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                showDialogWithContext(activity, failure)
            } else {
                LogUtils.e("DECRYPT_FAIL", "Cannot show debug alert dialog - no valid window token or activity")
            }
        } catch (e: Exception) {
            LogUtils.e("DECRYPT_FAIL", "Failed to show alert dialog", e)
        }
    }

    private fun showDialogWithContext(context: Context, failure: DecryptionFailure) {
        AlertDialog.Builder(context)
            .setTitle("🔐 DECRYPTION FAILURE (DEBUG)")
            .setMessage(buildAlertMessage(failure))
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            .setNeutralButton("Copy Details") { _, _ ->
                copyToClipboard(context, buildDetailsText(failure))
            }
            .show()
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
            
            📝 First 50 chars: ${failure.encryptedValue.take(50)}
            
            💡 This indicates a data corruption or encryption mismatch.
            
            Check Logcat for full stack trace.
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
            Context: ${failure.context ?: "N/A"}
            
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

        // Show toast
        android.widget.Toast.makeText(context, "Details copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
    }

    /**
     * Get all recorded failures (for debugging)
     */
    fun getFailures(): List<DecryptionFailure> = failures.toList()

    /**
     * Clear recorded failures
     */
    fun clearFailures() {
        failures.clear()
    }
}