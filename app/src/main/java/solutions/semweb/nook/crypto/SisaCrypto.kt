package solutions.semweb.nook.crypto

import android.content.Context
import solutions.semweb.nook.LogUtils
import solutions.semweb.nook.crypto.EncryptionMapper.extractEncodingBase
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Gestione della crittografia SiSa
 */
object SisaCrypto {

    // =============================================
    // 1. CIFRATURA SISA con Encoding
    // =============================================
   
    fun encryptEncMessage(context: Context, phoneNumber: String, plainText: String, encoding: String): String {
        return try {
            LogUtils.d(context, "SisaCrypto", "🔐 Sisa Encryption with $encoding Encoding for: $phoneNumber")

            // 1. Get password
            val password = PasswordManager.getStoredPassword(context, phoneNumber)
            if (password.isEmpty()) {
                LogUtils.e(context, "SisaCrypto", "❌ Password not available")
                return plainText
            }

            // 2. Generate IV ramdomly
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }

            // 3. Derive AES key
            val aesKey = deriveAesKeyFromPassword(context, password)

            // 4. Encrypt with AES-GCM
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val gcmSpec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, gcmSpec)

            val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            val combined = iv + cipherText

            // 5. Encode and add prefix
            if (encoding == EncryptionMapper.ENCRYPTION_TEXT)
                // no encoding specified (dangerous...)
                "${EncryptionMapper.SISA_ENCR_PREFIX}$combined"
            else {
                val base = extractEncodingBase(encoding)
                val encodedResult = BaseXXXUtils.encode(combined , base)
                "${EncryptionMapper.SISA_ENCR_PREFIX}$encodedResult"
            }
        } catch (e: Exception) {
            LogUtils.e(context, "SisaCrypto", "❌ Error SiSa encryption:", e)
            plainText
        }
    }


    /**
     * Decrypts the timestamp (long)
     * tries with iv date of today and of yesterday
     */

    fun decryptMessage(context: Context,
                       phoneNumber: String,
                       encoding: String,
                       encryptedMessage:
                       String,
                       timestamp: Long = 0): DecodeResult {
        return try {
            LogUtils.d(context, "SisaCrypto", "🔓 SiSa Decription for: $phoneNumber")

            // 1. Verifica formato
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
            // Encryption
            // 2. Extract payload according to chat
            val payload = encryptedMessage.removePrefix(EncryptionMapper.SISA_ENCR_PREFIX).trim()
            val base = extractEncodingBase(encoding)

            val combined = try {
                if (encoding == EncryptionMapper.ENCRYPTION_SCHEME_TEXT) // no encoding specified (but encryption?)
                    payload.toByteArray()
                else {
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

            val grenze = 12
            if (combined.size < grenze) {
                return DecodeResult(
                    original = encryptedMessage,
                    decoded = encryptedMessage,
                    scheme = EncryptionMapper.ENCRYPTION_TEXT,
                    encoding = encoding,
                    success = false,
                    notes = "Data too short (${combined.size} byte, min $grenze)",
                )
            }

            // 3. Extract IV and ciphertext
            val iv = combined.copyOfRange(0, grenze)
            val ciphertext = combined.copyOfRange(grenze, combined.size)

            // 4. Get used encryption password
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

            // 5. Try decryption with delivered timestamp (first try)
            var result = tryDecryptionWithTimestamp(context, password, ciphertext, iv, timestamp, encoding, encryptedMessage)

            // 6. Try again with the day before timestamp
            if (!result.success && timestamp > 0) {
                val oneDayInMillis = 24 * 60 * 60 * 1000L
                val previousDayTimestamp = timestamp - oneDayInMillis
                LogUtils.d(context, "SisaCrypto", "🔄 First decryption try failed, retry with timestamp -1 day: $previousDayTimestamp")

                result = tryDecryptionWithTimestamp(context, password, ciphertext, iv, previousDayTimestamp, encoding, encryptedMessage)
                if (result.success) {
                    result = result.copy(notes = "SiSa decrypted with success (using timestamp -1 day)")
                }
            }

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

    private fun tryDecryptionWithTimestamp(
        context: Context,
        password: String,
        ciphertext: ByteArray,
        iv: ByteArray,
        timestamp: Long,
        encoding: String,
        encryptedMessage: String
    ): DecodeResult {
        return try {
            // Derive AES key using specified timestamp
            val aesKey = deriveAesKeyFromPassword(context, password, timestamp)

            // Decrypt
            val plaintextBytes = decryptWithRetries(ciphertext, aesKey, iv)
            val plaintext = String(plaintextBytes, Charsets.UTF_8)

            // Verify that the decoded text be text
            if (isValidPlaintext(plaintext)) {
                DecodeResult(
                    original = encryptedMessage,
                    decoded = plaintext,
                    scheme = EncryptionMapper.ENCRYPTION_SISA,
                    encoding = encoding,
                    success = true,
                    notes = "SiSa decryption successful",
                )
            } else {
                LogUtils.d(context, "SisaCrypto", "⚠️ SiSa  Decryption with timestamp $timestamp has produced an invalid text")
                DecodeResult(
                    original = encryptedMessage,
                    decoded = "[ERROR: Invalid decoded text]",
                    scheme = EncryptionMapper.ENCRYPTION_SISA,
                    encoding = encoding,
                    success = false,
                    notes = "Invalid text with timestamp $timestamp",
                )
            }
        } catch (e: Exception) {
            LogUtils.d(context, "SisaCrypto", "⚠️ Decryption failed width timestamp $timestamp: ${e.message}")
            DecodeResult(
                original = encryptedMessage,
                decoded = "[ERROR SiSa Decryption: ${e.message}]",
                scheme = EncryptionMapper.ENCRYPTION_SISA,
                encoding = encoding,
                success = false,
                notes = "Failed with timestamp $timestamp: ${e.javaClass.simpleName}",
            )
        }
    }


    /**
     * Validation function to verify decrypted text be ok
     * Customisable
     */
    private fun isValidPlaintext(plaintext: String): Boolean {
        return plaintext.isNotBlank() &&
                plaintext.length >= 1 &&
                plaintext.length <= 10000 && // Reasonable length
                !plaintext.contains(Char(0)) && // No null char
                plaintext.all { char ->
                    char.isLetterOrDigit() ||
                            char.isWhitespace() ||
                            char in ",.!?;:-_()[]{}@#$%&*+-=/\\\"'"
                }
    }

    // =============================================
    // 2. DETERMINISTIC KEY DERIVATION
    // =============================================
    private fun deriveAesKeyFromPassword(
        context: Context,
        password: String,
        timestamp: Long = 0
    ): SecretKeySpec {
        // Get the DAY of timestamp
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        calendar.timeInMillis = if (timestamp > 0) timestamp else System.currentTimeMillis()

        // Reset hours, minutes, seconds, millis....
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

        // Prendi i primi 256 bit (32 byte) per AES-256
        val aesKeyBytes = key.encoded.copyOfRange(0, 32)

        // Log per debug
        LogUtils.d(context, "SisaCrypto", "🔑 Key derived: ${aesKeyBytes.size} bytes, first byte: ${aesKeyBytes[0]}")

        return SecretKeySpec(aesKeyBytes, "AES")
    }


    // =============================================
    // 3. INTERNAL DECRYPTION FUNCTION WITH RETRY
    // Sometimes it is necessary to add resiliency
    // =============================================

    private fun decryptWithRetries(
        ciphertext: ByteArray,
        aesKey: SecretKeySpec,
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
                LogUtils.w(null,"SisaCrypto", "⚠️ Decryption Try $attempt failed: ${e.message}")

                if (attempt < maxAttempts) {
                    Thread.sleep(50) // 50ms breath
                }
            }
        }

        // If we get here, all tries have failed
        throw lastException ?: Exception("Decryption failed after $maxAttempts tries")
    }


    // =============================================
    // 4. DETECTION
    // =============================================

    fun isSisaEncrypted(message: String): Boolean {
        return message.startsWith(EncryptionMapper.SISA_ENCR_PREFIX) ||
                (message.contains(EncryptionMapper.SISA_ENCR_PREFIX) &&
                        message.matches("^.*#[A-Za-z0-9+/=]+$".toRegex()))
    }
}