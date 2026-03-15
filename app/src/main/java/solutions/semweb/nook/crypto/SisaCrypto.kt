package solutions.semweb.nook.crypto

import android.content.Context
import solutions.semweb.nook.BuildConfig
import solutions.semweb.nook.LogUtils
import solutions.semweb.nook.crypto.EncryptionMapper.extractEncodingBase
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * SiSa cryptography management with key caching in KeyStore
 */
object SisaCrypto {

    const val WORKAROUND_SINGLE_CHAR_ADDITION = "ῲῳῴῶ" // special rare symbol constant for single char messages

    // =============================================
    // 1. CONSTANTS
    // =============================================
    private const val PBKDF2_ITERATIONS = 100000
    private const val AES_KEY_SIZE_BITS = 256
    private const val GCM_TAG_LENGTH = 128
    private const val IV_SIZE = 12
    private const val DATE_FORMAT = "yyyyMMdd"

    // =============================================
    // 2. KEY DERIVATION AND CACHING
    // =============================================

    /**
     * Get or create an AES key for a specific phone number and date
     * Uses KeyStore cache if available, otherwise derives new key
     */
    private fun getOrCreateKeyForDate(
        context: Context,
        phoneNumber: String,
        password: String,
        dateStr: String
    ): SecretKey? {
        // 1. Try to get from cache first (KeyStore only, no memory)
        var cachedKey = SisaKeyCache.getCachedKey(context, phoneNumber, dateStr)

        if (cachedKey != null) {
            LogUtils.d(context, "SisaCrypto", "✅ Using cached KeyStore key for $phoneNumber on $dateStr")
            return cachedKey
        }

        // 2. If not in cache, derive new key
        LogUtils.d(context, "SisaCrypto", "🔑 Deriving new key for $phoneNumber on $dateStr")
        val key = deriveAesKeyFromPassword(password, dateStr)

        if (key != null) {
            // 3. Store in cache for future use
            val stored = SisaKeyCache.cacheKey(context, phoneNumber, dateStr, key)

            if (stored) {
                // 4. Clean up previous day's key (to prevent KeyStore bombing)
                cleanupPreviousDayKey(context, phoneNumber, dateStr)

                LogUtils.d(context, "SisaCrypto", "✅ New key derived and cached for $phoneNumber on $dateStr")
            } else {
                LogUtils.e(context, "SisaCrypto", "❌ Failed to cache key for $phoneNumber on $dateStr")
                // Return the key anyway for this session
            }
        }

        return key
    }

    /**
     * Clean up the key from the previous day to prevent KeyStore bombing
     */
    private fun cleanupPreviousDayKey(context: Context, phoneNumber: String, currentDateStr: String) {
        try {
            // Parse current date
            val dateFormat = SimpleDateFormat(DATE_FORMAT, Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val currentDate = dateFormat.parse(currentDateStr) ?: return

            // Calculate previous day
            val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            calendar.time = currentDate
            calendar.add(Calendar.DAY_OF_MONTH, -1)
            val previousDateStr = dateFormat.format(calendar.time)

            // For Android 6+, we can try to delete directly
            val keystoreAlias = generateKeystoreAlias(phoneNumber, previousDateStr)

            val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)

            if (keyStore.containsAlias(keystoreAlias)) {
                keyStore.deleteEntry(keystoreAlias)
                LogUtils.d(context, "SisaCrypto", "🗑️ Cleaned up previous day key: $previousDateStr")
            }
        } catch (e: Exception) {
            LogUtils.e(context, "SisaCrypto", "⚠️ Error cleaning up previous day key", e)
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
            String.format("%016x", input.hashCode().toLong() and 0xFFFFFFFFFFFFFFF)
        }
    }

    // =============================================
    // 3. KEY DERIVATION - DETERMINISTIC
    // =============================================

    /**
     * Derive AES key deterministically from password and date string
     * Uses PBKDF2 with date as salt
     */
    private fun deriveAesKeyFromPassword(
        password: String,
        dateStr: String
    ): SecretKey? {
        return try {
            val salt = dateStr.toByteArray(Charsets.UTF_8)

            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, AES_KEY_SIZE_BITS)
            val key = factory.generateSecret(spec)

            // Take exactly 32 bytes for AES-256
            val aesKeyBytes = key.encoded.copyOfRange(0, 32)

            SecretKeySpec(aesKeyBytes, "AES")
        } catch (e: Exception) {
            LogUtils.e(null, "SisaCrypto", "❌ Key derivation failed", e)
            null
        }
    }

    /**
     * Derive IV deterministically from date string
     * IV must be 12 bytes for GCM
     */
    private fun deriveIvFromDate(dateStr: String): ByteArray {
        // Use SHA-256 to get a deterministic hash of the date string
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(dateStr.toByteArray(Charsets.UTF_8))

        // Take first 12 bytes for IV
        return hash.copyOfRange(0, IV_SIZE)
    }

    // =============================================
    // 4. SISA ENCRYPTION
    // =============================================

    fun encryptEncMessage(context: Context, phoneNumber: String, plainText: String, encoding: String): String {
        return try {
            LogUtils.d(context, "SisaCrypto", "🔐 SiSa Encryption with $encoding encoding for: $phoneNumber")

            val textToEncrypt = if (plainText.length == 1) {
                // Add special char for single character messages
                plainText + WORKAROUND_SINGLE_CHAR_ADDITION
            } else {
                plainText
            }

            // 1. Get password
            val password = PasswordManager.getStoredPassword(context, phoneNumber)
            if (password.isEmpty()) {
                LogUtils.e(context, "SisaCrypto", "❌ Password not available")
                return plainText
            }

            // 2. Get current date for key derivation
            val timestamp = System.currentTimeMillis()
            val dateStr = getDateString(timestamp)

            // 3. Get or create key for this date (using cache for today's date)
            val aesKey = getOrCreateKeyForDate(context, phoneNumber, password, dateStr)
            if (aesKey == null) {
                LogUtils.e(context, "SisaCrypto", "❌ Failed to get/create encryption key")
                return plainText
            }

            // 4. Derive IV deterministically from the date
            val iv = deriveIvFromDate(dateStr)

            // 5. Encrypt with GCM
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)

            cipher.init(Cipher.ENCRYPT_MODE, aesKey, gcmSpec)
            val cipherText = cipher.doFinal(textToEncrypt.toByteArray(Charsets.UTF_8))

            // 6. Combine IV + ciphertext (IV is deterministic, but included for compatibility)
            val combined = iv + cipherText

            // 7. Encode and add prefix
            if (encoding == EncryptionMapper.ENCRYPTION_TEXT) {
                "${EncryptionMapper.SISA_ENCR_PREFIX}$combined"
            } else {
                val base = extractEncodingBase(encoding)
                val encodedResult = BaseXXXUtils.encode(combined, base)

                val result = "${EncryptionMapper.SISA_ENCR_PREFIX}$encodedResult"

                if (BuildConfig.DEBUG) {
                    // Print IV and ciphertext as hex for clarity
                    val ivHex = iv.joinToString("") { "%02x".format(it) }
                    val ciphertextHex = cipherText.joinToString("") { "%02x".format(it) }
                    val combinedHex = combined.joinToString("") { "%02x".format(it) }

                    LogUtils.d("ECHECK","iv (hex: $ivHex)")
                    LogUtils.d("ECHECK","ciphertext (hex: $ciphertextHex)")
                    LogUtils.d("ECHECK","combined (hex: $combinedHex)")
                    LogUtils.d("ECHECK","result ("+result+")")
                }

                result
            }
        } catch (e: Exception) {
            LogUtils.e(context, "SisaCrypto", "❌ Error SiSa encryption:", e)
            plainText
        }
    }

    /**
     * Decrypts the message trying current day and previous day
     * in order to jump the day gap (sent yesterday)
     */
    fun decryptMessage(context: Context,
                       phoneNumber: String,
                       encoding: String,
                       encryptedMessage: String,
                       timestamp: Long = 0): DecodeResult {
        return try {
            LogUtils.d(context, "SisaCrypto", "🔓 SiSa Decryption for: $phoneNumber")

            // 1. Verify format
            if (!encryptedMessage.startsWith(EncryptionMapper.SISA_ENCR_PREFIX)) {
                return DecodeResult(
                    original = encryptedMessage,
                    decoded = encryptedMessage,
                    scheme = EncryptionMapper.ENCRYPTION_TEXT,
                    encoding = encoding,
                    success = false,
                    notes = "Not identified as SiSa",
                )
            }

            // 2. Extract payload
            val encodedPayload = encryptedMessage.removePrefix(EncryptionMapper.SISA_ENCR_PREFIX).trim()
            val base = extractEncodingBase(encoding)

            val decodedEncryptedCombined = try {
                if (encoding == EncryptionMapper.ENCRYPTION_SCHEME_TEXT) {
                    encodedPayload.toByteArray()
                } else {
                    BaseXXXUtils.decodeToBytes(encodedPayload, base)
                }
            } catch (e: Exception) {
                LogUtils.e(context, "Decryption", "❌ Error decoding Base$base", e)
                return DecodeResult(
                    original = encryptedMessage,
                    decoded = "[ERROR: Decoding Base$base failed: ${e.message}]",
                    scheme = EncryptionMapper.ENCRYPTION_TEXT,
                    encoding = encoding,
                    success = false,
                    notes = "Base${base} decode error",
                )
            }

            if (decodedEncryptedCombined.size < IV_SIZE) {
                return DecodeResult(
                    original = encryptedMessage,
                    decoded = encryptedMessage,
                    scheme = EncryptionMapper.ENCRYPTION_TEXT,
                    encoding = encoding,
                    success = false,
                    notes = "Data too short (${decodedEncryptedCombined.size} byte, min $IV_SIZE)",
                )
            }

            // 3. Extract IV and ciphertext
            val iv = decodedEncryptedCombined.copyOfRange(0, IV_SIZE)
            val ciphertext = decodedEncryptedCombined.copyOfRange(IV_SIZE, decodedEncryptedCombined.size)

            // 4. Get password
            val password = PasswordManager.getStoredPassword(context, phoneNumber)
            if (password.isEmpty()) {
                return DecodeResult(
                    original = encryptedMessage,
                    decoded = "[ERROR: SiSa Password not configured]",
                    scheme = EncryptionMapper.ENCRYPTION_SISA,
                    encoding = encoding,
                    success = false,
                    notes = "Password missing",
                )
            }

            // 5. Try decryption with provided timestamp (or current time)
            val decryptionTimestamp = if (timestamp > 0) timestamp else System.currentTimeMillis()

            if (BuildConfig.DEBUG) {
                LogUtils.d("DCHECK","Decrypting encrypted message ("+encryptedMessage+")")
                LogUtils.d("DCHECK","password ("+password+")")

                val ivHex = iv.joinToString("") { "%02x".format(it) }
                val ciphertextHex = ciphertext.joinToString("") { "%02x".format(it) }

                LogUtils.d("DCHECK","iv (hex: $ivHex)")
                LogUtils.d("DCHECK","ciphertext (hex: $ciphertextHex)")
                LogUtils.d("DCHECK","decryptionTimestamp ("+decryptionTimestamp+")")
            }

            // Try current day first
            var result = tryDecryptionWithDate(
                context, phoneNumber, password, ciphertext, iv,
                decryptionTimestamp, encoding, encryptedMessage
            )

            if (BuildConfig.DEBUG) {
                LogUtils.d("DCHECK","result.success ("+result.success+")")
            }



            // 6. If failed, try previous day
            if (!result.success) {
                val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                calendar.timeInMillis = decryptionTimestamp
                calendar.add(Calendar.DAY_OF_MONTH, -1)
                val previousDayTimestamp = calendar.timeInMillis

                LogUtils.d(context, "SisaCrypto", "🔄 Current day failed, trying previous day")

                result = tryDecryptionWithDate(
                    context, phoneNumber, password, ciphertext, iv,
                    previousDayTimestamp, encoding, encryptedMessage
                )

                if (result.success) {
                    result = result.copy(notes = "SiSa decrypted with previous day's key")
                }
            }

            // Remove workaround character if present
            result.decoded = result.decoded.replace(WORKAROUND_SINGLE_CHAR_ADDITION, "")

            return result

        } catch (e: Exception) {
            LogUtils.e(context, "SisaCrypto", "❌ Error decrypting SiSa", e)
            DecodeResult(
                original = encryptedMessage,
                decoded = "[ERROR decrypting SiSa: ${e.message}]",
                scheme = EncryptionMapper.ENCRYPTION_SISA,
                encoding = encoding,
                success = false,
                notes = "Exception: ${e.javaClass.simpleName}",
            )
        }
    }

    /**
     * Try decryption with a specific date
     * taken from timestamp
     */
    private fun tryDecryptionWithDate(
        context: Context,
        phoneNumber: String,
        password: String,
        ciphertext: ByteArray,
        iv: ByteArray,
        timestamp: Long,
        encoding: String,
        encryptedMessage: String
    ): DecodeResult {
        return try {
            val dateStr = getDateString(timestamp)

            // Get key for this date (from cache or derive)
            val aesKey = getOrCreateKeyForDate(context, phoneNumber, password, dateStr)
                ?: return DecodeResult(
                    original = encryptedMessage,
                    decoded = "[ERROR: Failed to get decryption key]",
                    scheme = EncryptionMapper.ENCRYPTION_SISA,
                    encoding = encoding,
                    success = false,
                    notes = "Key unavailable for date $dateStr",
                )

            // Decrypt with the provided IV (which came from the message)
            val plaintextBytes = decryptWithRetries(ciphertext, aesKey, iv)
            val plaintext = String(plaintextBytes, Charsets.UTF_8)

            // Verify that the decoded text is valid
            if (isValidPlaintext(plaintext)) {
                DecodeResult(
                    original = encryptedMessage,
                    decoded = plaintext,
                    scheme = EncryptionMapper.ENCRYPTION_SISA,
                    encoding = encoding,
                    success = true,
                    notes = "SiSa decryption successful with date $dateStr",
                )
            } else {
                LogUtils.d(context, "SisaCrypto", "⚠️ Decryption with date $dateStr produced invalid text")
                DecodeResult(
                    original = encryptedMessage,
                    decoded = "[ERROR: Invalid decoded text]",
                    scheme = EncryptionMapper.ENCRYPTION_SISA,
                    encoding = encoding,
                    success = false,
                    notes = "Invalid text with date $dateStr",
                )
            }
        } catch (e: Exception) {
            LogUtils.d(context, "SisaCrypto", "⚠️ Decryption failed: ${e.message}")
            DecodeResult(
                original = encryptedMessage,
                decoded = "[ERROR SiSa Decryption: ${e.message}]",
                scheme = EncryptionMapper.ENCRYPTION_SISA,
                encoding = encoding,
                success = false,
                notes = "Failed: ${e.javaClass.simpleName}",
            )
        }
    }

    /**
     * Get date string from timestamp (YYYYMMDD format)
     */
    private fun getDateString(timestamp: Long): String {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        calendar.timeInMillis = timestamp
        return SimpleDateFormat(DATE_FORMAT, Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(calendar.time)
    }

    // =============================================
    // 5. UTILITY FUNCTIONS
    // =============================================

    private fun decryptWithRetries(
        ciphertext: ByteArray,
        aesKey: SecretKey,
        iv: ByteArray,
        maxAttempts: Int = 3
    ): ByteArray {
        var lastException: Exception? = null

        for (attempt in 1..maxAttempts) {
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
                cipher.init(Cipher.DECRYPT_MODE, aesKey, gcmSpec)
                return cipher.doFinal(ciphertext)
            } catch (e: Exception) {
                lastException = e
                LogUtils.w(null, "SisaCrypto", "⚠️ Decryption attempt $attempt failed: ${e.message}")

                if (attempt < maxAttempts) {
                    Thread.sleep(50)
                }
            }
        }

        throw lastException ?: Exception("Decryption failed after $maxAttempts attempts")
    }

    private fun isValidPlaintext(plaintext: String): Boolean {
        return plaintext.isNotBlank() &&
                plaintext.isNotEmpty() &&
                plaintext.length <= 10000 &&
                !plaintext.contains(Char(0)) &&
                plaintext.all { char ->
                    char.isLetterOrDigit() ||
                            char.isWhitespace() ||
                            char in ",.!?;:-_()[]{}@#$%&*+-=/\\\"'"
                }
    }

    fun isSisaEncrypted(message: String): Boolean {
        return message.startsWith(EncryptionMapper.SISA_ENCR_PREFIX) ||
                (message.contains(EncryptionMapper.SISA_ENCR_PREFIX) &&
                        message.matches("^.*#[A-Za-z0-9+/=]+$".toRegex()))
    }
}