// File: EncryptionVerifier.kt - Updated with retry logic and synchronization
package solutions.semweb.nook.crypto

import android.content.Context
import solutions.semweb.nook.BuildConfig
import solutions.semweb.nook.LogUtils

/**
 * Verifies encryption operations immediately after they happen
 * to catch failures at the source
 */
object EncryptionVerifier {
    private const val TAG = "EncryptionVerifier"
    private const val MAX_RETRIES = 5 // !!!
    private const val RETRY_DELAY_MS = 50L // Small delay between retries

    // Synchronization lock
    private val verifierLock = Any()

    /**
     * Wrapper function to encrypt and verify in one step with retry logic
     */
    fun encryptAndVerify(
        plainText: String,
        fieldName: String,
        entityType: String,
        context: Context,
        conversationId: Long? = null,
        messageId: Long? = null
    ): String {
        synchronized(verifierLock) {
            // Handle empty strings
            if (plainText.isEmpty()) {
                return ""
            }

            var lastException: Exception? = null
            var attempt = 1

            while (attempt <= MAX_RETRIES) {
                try {
                    // Encrypt
                    val encrypted = AppCryptoManager.encrypt64Value(plainText)

                    // Verify immediately
                    verifyEncryption(
                        original = plainText,
                        encrypted = encrypted,
                        fieldName = fieldName,
                        entityType = entityType,
                        context = context,
                        conversationId = conversationId,
                        messageId = messageId,
                        attempt = attempt
                    )

                    // If we get here, verification passed
                    if (attempt > 1) {
                        LogUtils.w(context, TAG, "⚠️ [$entityType] Encryption succeeded after $attempt attempts for $fieldName")
                    }
                    return encrypted

                } catch (e: Exception) {
                    lastException = e

                    // Log the failure but continue retrying
                    LogUtils.w(context, TAG, "⚠️ [$entityType] Encryption attempt $attempt failed for $fieldName: ${e.message}")

                    if (attempt < MAX_RETRIES) {
                        // Small delay before retry (maybe a transient issue)
                        try {
                            Thread.sleep(RETRY_DELAY_MS)
                        } catch (ie: InterruptedException) {
                            Thread.currentThread().interrupt()
                            break
                        }
                    }
                    attempt++
                }
            }

            // All retries failed
            val error = "❌ [$entityType] Encryption failed for $fieldName after $MAX_RETRIES attempts"
            LogUtils.e(TAG, error, lastException)

            // Record the failure
            DecryptionFailureMonitor.reportFailure(
                context = context,
                entityType = "ENCRYPTION_FAILURE_${entityType}",
                fieldName = fieldName,
                encryptedValue = "ENCRYPTION_FAILED",
                exception = lastException ?: RuntimeException(error),
                extraInfo = "Failed after $MAX_RETRIES attempts. Original length: ${plainText.length}",
                conversationId = conversationId,
                messageId = messageId
            )

            // In DEBUG mode, throw to catch early
            if (BuildConfig.DEBUG) {
                throw RuntimeException(error, lastException)
            }

            // In production, return empty string as fallback?
            // Or re-throw? This depends on your error handling strategy
            return "" // Or throw RuntimeException(error, lastException)
        }
    }

    /**
     * Verify that encrypted data can be properly decrypted back to the original
     * @throws RuntimeException if verification fails
     */
    private fun verifyEncryption(
        original: String,
        encrypted: String,
        fieldName: String,
        entityType: String,
        context: Context,
        conversationId: Long? = null,
        messageId: Long? = null,
        attempt: Int = 1
    ) {
        synchronized(verifierLock) {
            // Skip verification for empty strings (they're not encrypted anyway)
            if (original.isEmpty() && encrypted.isEmpty()) {
                return
            }

            // If original is empty but encrypted isn't, that's suspicious
            if (original.isEmpty() && encrypted.isNotEmpty()) {
                val error = "Empty original became non-empty encrypted for $entityType.$fieldName"
                LogUtils.e(TAG, "❌ $error")
                throw RuntimeException(error)
            }

            try {
                // Attempt immediate decryption
                val decrypted = AppCryptoManager.decrypt64Value(encrypted)

                // Check if decryption produced a valid result
                if (decrypted == encrypted) {
                    // This likely means it wasn't actually encrypted (or decryption failed silently)
                    val error = "Encryption verification failed: decryption returned same string for $entityType.$fieldName"
                    LogUtils.e(TAG, "❌ $error")

                    recordEncryptionFailure(
                        entityType = entityType,
                        fieldName = fieldName,
                        original = original,
                        encrypted = encrypted,
                        decrypted = decrypted,
                        error = error,
                        context = context,
                        conversationId = conversationId,
                        messageId = messageId
                    )

                    throw RuntimeException(error)
                }

                // Check if decrypted matches original
                if (decrypted != original) {
                    val error = buildString {
                        append("Encryption verification failed for $entityType.$fieldName: ")
                        append("decrypted != original")
                        if (original.length <= 100 && decrypted.length <= 100) {
                            append("\n  Original: '$original'")
                            append("\n  Decrypted: '$decrypted'")
                        }
                    }

                    LogUtils.e(TAG, "❌ $error")

                    // Log hex dumps for debugging encoding issues
                    if (BuildConfig.DEBUG) {
                        logHexDiffs(original, decrypted, fieldName)
                    }

                    recordEncryptionFailure(
                        entityType = entityType,
                        fieldName = fieldName,
                        original = original,
                        encrypted = encrypted,
                        decrypted = decrypted,
                        error = error,
                        context = context,
                        conversationId = conversationId,
                        messageId = messageId
                    )

                    throw RuntimeException(error)
                } else {
                    // Success! Log at verbose level only
                    if (attempt > 1) {
                        LogUtils.d(TAG, "✅ Encryption verified for $entityType.$fieldName on attempt $attempt")
                    } else {
                        LogUtils.d(TAG, "✅ Encryption verified for $entityType.$fieldName")
                    }
                }

            } catch (e: Exception) {
                // Decryption threw an exception - this is a serious encryption failure
                val error = "Encryption verification failed with exception for $entityType.$fieldName: ${e.message}"
                LogUtils.e(TAG, "❌ $error", e)

                recordEncryptionFailure(
                    entityType = entityType,
                    fieldName = fieldName,
                    original = original,
                    encrypted = encrypted,
                    decrypted = null,
                    error = error,
                    exception = e,
                    context = context,
                    conversationId = conversationId,
                    messageId = messageId
                )

                throw RuntimeException(error, e)
            }
        }
    }

    private fun logHexDiffs(original: String, decrypted: String, fieldName: String) {
        try {
            val originalBytes = original.toByteArray(Charsets.UTF_8)
            val decryptedBytes = decrypted.toByteArray(Charsets.UTF_8)

            LogUtils.e(TAG, "🔍 Hex dump for $fieldName:")
            LogUtils.e(TAG, "  Original (${originalBytes.size} bytes): ${
                originalBytes.joinToString("") { "%02x".format(it) }
            }")
            LogUtils.e(TAG, "  Decrypted (${decryptedBytes.size} bytes): ${
                decryptedBytes.joinToString("") { "%02x".format(it) }
            }")

            // Check if it's an encoding issue
            val isoBytes = original.toByteArray(Charsets.ISO_8859_1)
            val utf8FromIso = String(isoBytes, Charsets.UTF_8)
            if (utf8FromIso == decrypted) {
                LogUtils.e(TAG, "  ⚠️ This appears to be an encoding issue: original interpreted as ISO-8859-1 then as UTF-8 matches decrypted")
            }
        } catch (e: Exception) {
            // Ignore hex logging errors
        }
    }

    private fun recordEncryptionFailure(
        entityType: String,
        fieldName: String,
        original: String,
        encrypted: String,
        decrypted: String?,
        error: String,
        exception: Exception? = null,
        context: Context,
        conversationId: Long? = null,
        messageId: Long? = null
    ) {
        // Reuse DecryptionFailureMonitor's infrastructure
        val exceptionToUse = exception ?: RuntimeException(error)

        DecryptionFailureMonitor.reportFailure(
            context = context,
            entityType = "ENCRYPTION_VERIFICATION_FAILURE",
            fieldName = fieldName,
            encryptedValue = encrypted,
            exception = exceptionToUse,
            extraInfo = buildString {
                append("ENCRYPTION_VERIFICATION_FAILED | ")
                append("Original length: ${original.length}, ")
                append("Encrypted length: ${encrypted.length}, ")
                decrypted?.let { append("Decrypted length: ${it.length}, ") }
                append("Error: $error")
            },
            conversationId = conversationId,
            messageId = messageId
        )
    }
}