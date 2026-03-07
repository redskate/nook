package solutions.semweb.nook.crypto

import android.content.Context
import android.os.Build
import solutions.semweb.nook.LogUtils
import solutions.semweb.nook.crypto.EncryptionMapper.extractEncodingBase
import java.security.SecureRandom
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
 * Gestione della crittografia SiSa con caching delle chiavi in KeyStore
 */
object SisaCrypto {

    const val workaround_1_char_adding_constant = "ῲῳῴῶ" // special rare symbol constant

    // =============================================
    // 1. KEY DERIVATION AND CACHING
    // =============================================

    /**
     * Get or create an AES key for a specific phone number and date
     * Uses KeyStore cache if available, otherwise derives new key
     */
    private fun getOrCreateKeyForDate(
        context: Context,
        phoneNumber: String,
        password: String,
        dateStr: String,
        timestamp: Long
    ): SecretKey? {
        // 1. Try to get from cache first (KeyStore only, no memory)
        var key = SisaKeyCache.getCachedKey(context, phoneNumber, dateStr)

        if (key != null) {
            LogUtils.d(context, "SisaCrypto", "✅ Using cached KeyStore key for $phoneNumber on $dateStr")
            return key
        }

        // 2. If not in cache, derive new key
        LogUtils.d(context, "SisaCrypto", "🔑 Deriving new key for $phoneNumber on $dateStr")
        key = deriveAesKeyFromPassword(context, password, timestamp)

        if (key != null) {
            // 3. Store in cache for future use
            SisaKeyCache.cacheKey(context, phoneNumber, dateStr, key)

            // 4. Clean up previous day's key (to prevent KeyStore bombing)
            cleanupPreviousDayKey(context, phoneNumber, dateStr)

            LogUtils.d(context, "SisaCrypto", "✅ New key derived and cached for $phoneNumber on $dateStr")
        }

        return key
    }

    /**
     * Clean up the key from the previous day to prevent KeyStore bombing
     */
    private fun cleanupPreviousDayKey(context: Context, phoneNumber: String, currentDateStr: String) {
        try {
            // Parse current date
            val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val currentDate = dateFormat.parse(currentDateStr) ?: return

            // Calculate previous day
            val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            calendar.time = currentDate
            calendar.add(Calendar.DAY_OF_MONTH, -1)
            val previousDateStr = dateFormat.format(calendar.time)

            // For Android 6+, we can try to delete directly
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val keystoreAlias = generateKeystoreAlias(phoneNumber, previousDateStr)

                val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore")
                keyStore.load(null)

                if (keyStore.containsAlias(keystoreAlias)) {
                    keyStore.deleteEntry(keystoreAlias)
                    LogUtils.d(context, "SisaCrypto", "🗑️ Cleaned up previous day key: $previousDateStr")
                }
            }

            // Also clear from SisaKeyCache's internal structures
            // Note: SisaKeyCache only uses memory cache temporarily, but we should also clear its references
            // Since SisaKeyCache is an object, we can access its internal map via reflection or add a method
            // For now, we rely on KeyStore deletion and SisaKeyCache's expiration mechanism
        } catch (e: Exception) {
            LogUtils.e(context, "SisaCrypto", "⚠️ Error cleaning up previous day key", e)
        }
    }

    /**
     * Generate a stable Keystore alias (mirroring SisaKeyCache's logic)
     */
    private fun generateKeystoreAlias(phoneNumber: String, dateStr: String): String {
        val phoneHash = hashString(phoneNumber)
        return "sisa_key_${phoneHash}_${dateStr}"
    }

    /**
     * Generate a stable hash string (mirroring SisaKeyCache's logic)
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
    // 2. SISA ENCRYPTION WITH CACHING
    // =============================================

    fun encryptEncMessage(context: Context, phoneNumber: String, plainText: String, encoding: String): String {
        return try {
            LogUtils.d(context, "SisaCrypto", "🔐 Sisa Encryption with $encoding Encoding for: $phoneNumber")

            val textToEncrypt = if (plainText.length == 1) {
                // Add char (invisible)
                plainText + workaround_1_char_adding_constant
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

            // 3. Get or create key for this date (uses cache)
            val aesKey = getOrCreateKeyForDate(context, phoneNumber, password, dateStr, timestamp)
            if (aesKey == null) {
                LogUtils.e(context, "SisaCrypto", "❌ Failed to get/create encryption key")
                return plainText
            }

            // 4. Generate IV randomly (but check if key requires Keystore-generated IV)
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }

// 5. Check if this is a Keystore key and handle appropriately
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val cipherText: ByteArray
            val finalIv: ByteArray

            if (aesKey.toString().contains("AndroidKeyStore") ||
                aesKey::class.java.name.contains("KeyStore")) {

                LogUtils.d(context, "SisaCrypto", "🔑 Using Keystore key - letting Keystore generate IV")

                // For Keystore keys, let the Keystore generate the IV
                cipher.init(Cipher.ENCRYPT_MODE, aesKey)
                cipherText = cipher.doFinal(textToEncrypt.toByteArray(Charsets.UTF_8))
                finalIv = cipher.iv  // Get the IV that Keystore generated

                LogUtils.d(context, "SisaCrypto", "🔑 Keystore generated IV of size: ${finalIv.size}")
            } else {
                // For regular SecretKeySpec keys, use our own IV
                LogUtils.d(context, "SisaCrypto", "🔑 Using regular key - using caller-provided IV")
                val gcmSpec = GCMParameterSpec(128, iv)
                cipher.init(Cipher.ENCRYPT_MODE, aesKey, gcmSpec)
                cipherText = cipher.doFinal(textToEncrypt.toByteArray(Charsets.UTF_8))
                finalIv = iv
            }

            val combined = finalIv + cipherText

            // 6. Encode and add prefix
            if (encoding == EncryptionMapper.ENCRYPTION_TEXT) {
                // no encoding specified (dangerous...)
                "${EncryptionMapper.SISA_ENCR_PREFIX}$combined"
            } else {
                val base = extractEncodingBase(encoding)
                val encodedResult = BaseXXXUtils.encode(combined, base)
                "${EncryptionMapper.SISA_ENCR_PREFIX}$encodedResult"
            }
        } catch (e: Exception) {
            LogUtils.e(context, "SisaCrypto", "❌ Error SiSa encryption:", e)
            plainText
        }
    }

    /**
     * Decrypts the message trying current day and previous day
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
            val payload = encryptedMessage.removePrefix(EncryptionMapper.SISA_ENCR_PREFIX).trim()
            val base = extractEncodingBase(encoding)

            val combined = try {
                if (encoding == EncryptionMapper.ENCRYPTION_SCHEME_TEXT) {
                    payload.toByteArray()
                } else {
                    BaseXXXUtils.decodeToBytes(payload, base)
                }
            } catch (e: Exception) {
                LogUtils.e(context, "Decryption", "❌ Error decoding Base$base", e)
                return DecodeResult(
                    original = encryptedMessage,
                    decoded = "[ERRORE: Decoding Base$base failed: ${e.message}]",
                    scheme = EncryptionMapper.ENCRYPTION_TEXT,
                    encoding = encoding,
                    success = false,
                    notes = "Base${base} decode error",
                )
            }

            val ivSize = 12
            if (combined.size < ivSize) {
                return DecodeResult(
                    original = encryptedMessage,
                    decoded = encryptedMessage,
                    scheme = EncryptionMapper.ENCRYPTION_TEXT,
                    encoding = encoding,
                    success = false,
                    notes = "Data too short (${combined.size} byte, min $ivSize)",
                )
            }

            // 3. Extract IV and ciphertext
            val iv = combined.copyOfRange(0, ivSize)
            val ciphertext = combined.copyOfRange(ivSize, combined.size)

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

            // Try current day first
            var result = tryDecryptionWithDate(
                context, phoneNumber, password, ciphertext, iv,
                decryptionTimestamp, encoding, encryptedMessage
            )

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
            result.decoded = result.decoded.replace(workaround_1_char_adding_constant, "")

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
            val aesKey = getOrCreateKeyForDate(context, phoneNumber, password, dateStr, timestamp)

            if (aesKey == null) {
                return DecodeResult(
                    original = encryptedMessage,
                    decoded = "[ERROR: Failed to get decryption key]",
                    scheme = EncryptionMapper.ENCRYPTION_SISA,
                    encoding = encoding,
                    success = false,
                    notes = "Key unavailable for date $dateStr",
                )
            }

            // Decrypt
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
        return SimpleDateFormat("yyyyMMdd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(calendar.time)
    }

    // =============================================
    // 3. KEY DERIVATION (unchanged but now returns nullable)
    // =============================================

    private fun deriveAesKeyFromPassword(
        context: Context,
        password: String,
        timestamp: Long = 0
    ): SecretKey? {
        return try {
            // Get the DAY of timestamp
            val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            calendar.timeInMillis = if (timestamp > 0) timestamp else System.currentTimeMillis()

            // Reset to start of day
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)

            val dateStr = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(calendar.time)

            val salt = dateStr.toByteArray(Charsets.UTF_8)

            val iterations = 100000
            val keyLength = 512

            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val spec = PBEKeySpec(password.toCharArray(), salt, iterations, keyLength)
            val key = factory.generateSecret(spec)

            // Take first 256 bits (32 bytes) for AES-256
            val aesKeyBytes = key.encoded.copyOfRange(0, 32)

            LogUtils.d(context, "SisaCrypto", "🔑 Key derived: ${aesKeyBytes.size} bytes")

            SecretKeySpec(aesKeyBytes, "AES")
        } catch (e: Exception) {
            LogUtils.e(context, "SisaCrypto", "❌ Key derivation failed", e)
            null
        }
    }

    // =============================================
    // 4. UTILITY FUNCTIONS (unchanged)
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
                val gcmSpec = GCMParameterSpec(128, iv)
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
                plaintext.length >= 1 &&
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