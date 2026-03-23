package solutions.semweb.nook.crypto

import android.content.Context
import solutions.semweb.nook.BuildConfig
import solutions.semweb.nook.LogUtils
import solutions.semweb.nook.crypto.EncryptionMapper.extractEncodingBase
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
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
        // Try to get from cache using password
        val cachedKey = SisaKeyCache.getCachedKey(
            context,
            phoneNumber,
            dateStr = dateStr,
            password = password  // ← CRITICAL: Pass the password!
        )

        if (cachedKey != null) {
            LogUtils.d(context, "SisaCrypto", "✅ Using cached KeyStore key for $phoneNumber on $dateStr")
            return cachedKey
        }

        // If not in cache, derive new key
        LogUtils.d(context, "SisaCrypto", "  Deriving new key for date $dateStr")
        LogUtils.d(context, "SisaCrypto", "  Password hash: ${hashString(password).take(8)}")
        val key = deriveAesKeyFromPassword(password, dateStr)

        if (key != null) {
            // Store in cache with the password
            val stored = SisaKeyCache.cacheKey(
                context,
                phoneNumber,
                dateStr,
                password = password,  // ← CRITICAL: Pass the password!
                key = key
            )

            if (stored) {
                LogUtils.d(context, "SisaCrypto", "✅ New key derived and cached for $phoneNumber on $dateStr")
            } else {
                LogUtils.e(context, "SisaCrypto", "❌ Failed to cache key for $phoneNumber on $dateStr")
            }
        }

        return key
    }


    /**
     * Generate a stable hash string
     */
    fun hashString(input: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
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
            LogUtils.d(null, "SisaCrypto", "  deriveAesKey: dateStr='$dateStr'")

            val salt = dateStr.toByteArray(Charsets.UTF_8)
            LogUtils.d(null, "SisaCrypto", "  salt (hex): ${salt.joinToString("") { "%02x".format(it) }}")

            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, AES_KEY_SIZE_BITS)
            val key = factory.generateSecret(spec)

            // Take exactly 32 bytes for AES-256
            val aesKeyBytes = key.encoded.copyOfRange(0, 32)
            LogUtils.d(null, "SisaCrypto", "  key bytes (first 8): ${aesKeyBytes.take(8).joinToString("") { "%02x".format(it) }}")

            SecretKeySpec(aesKeyBytes, "AES")
        } catch (e: Exception) {
            LogUtils.e(null, "SisaCrypto", "❌ Key derivation failed", e)
            null
        }
    }




    /**
     * Derive IV deterministically from password + date
     * This ensures different passwords produce different IVs
     */
    private fun deriveIvFromDate(password: String, dateStr: String): ByteArray {
        // Combine password and date for IV derivation
        val combined = "$password|$dateStr".toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(combined)

        // Take first 12 bytes for IV
        return hash.copyOfRange(0, IV_SIZE)
    }

    // =============================================
    // 4. SISA ENCRYPTION
    // =============================================

    fun encryptEncMessage(
        context: Context,
        phoneNumber: String,
        plainText: String,
        encoding: String,
        encodingPassword: String
    ): String {
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
            val iv = deriveIvFromDate(password,dateStr)

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
                val encodedResult = BaseXXXUtils.encode(combined, base, encodingPassword)

                val result = "${EncryptionMapper.SISA_ENCR_PREFIX}$encodedResult"

                if (BuildConfig.DEBUG && false) {
                    // Print IV and ciphertext as hex for clarity
                    val ivHex = iv.joinToString("") { "%02x".format(it) }
                    val ciphertextHex = cipherText.joinToString("") { "%02x".format(it) }
                    val combinedHex = combined.joinToString("") { "%02x".format(it) }

                    LogUtils.d("ECHECK","password ($password)")
                    LogUtils.d("ECHECK","iv (hex: $ivHex)")
                    LogUtils.d("ECHECK","ciphertext (hex: $ciphertextHex)")
                    LogUtils.d("ECHECK","combined (hex: $combinedHex)")

                    val digest = MessageDigest.getInstance("SHA-256")
                    val keyHash = if (aesKey.encoded != null) {
                        digest.digest(aesKey.encoded).take(8).joinToString("") { "%02x".format(it) }
                    } else {
                        // Se encoded è null, usa l'hash del toString() o dell'algoritmo
                        val fallbackString = "${aesKey.algorithm}:${aesKey.format}:${aesKey.hashCode()}"
                        digest.digest(fallbackString.toByteArray()).joinToString("") { "%02x".format(it) }
                    }

                    LogUtils.d(context, "ECHECK", "🔑 KEY HASH: $keyHash")



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
     * ULTIMA RATIO: If both fail, try DEFAULT encryption scheme
     */
    fun decryptMessage(context: Context,
                       phoneNumber: String,
                       encoding: String,
                       encodingPassword: String,
                       encryptedMessage: String,
                       timestamp: Long = 0): DecodeResult {
        return try {
            LogUtils.d(context, "SisaCrypto", "🔓 SiSa Decryption for: $phoneNumber")
            LogUtils.d(context, "SisaCrypto", "📨 encryptedMessage: '$encryptedMessage'")
            LogUtils.d(context, "SisaCrypto", "📨 encryptedMessage length: ${encryptedMessage.length}")
            LogUtils.d(context, "SisaCrypto", "🔢 expected encoding: $encoding")
            LogUtils.d(context, "SisaCrypto", "⏱️ timestamp: $timestamp (${Date(timestamp)})")

            // Check if it's a SiSa encrypted message
            val isSisaMessage = encryptedMessage.startsWith(EncryptionMapper.SISA_ENCR_PREFIX)

            if (!isSisaMessage) {
                LogUtils.d(context, "SisaCrypto", "⚠️ Not identified as SiSa, trying DEFAULT scheme")
                // Try DEFAULT scheme immediately for non-SiSa messages
                val defaultEncoding = EncryptionMapper.DEFAULT_ENCODING.ifEmpty {
                    EncryptionMapper.ENCODING_BASE256  // Default fallback
                }

                val defaultScheme = EncryptionMapper.DEFAULT_ENCRYPTION_SCHEME.ifEmpty {
                    EncryptionMapper.ENCRYPTION_TEXT  // Default fallback
                }

                LogUtils.d(context, "SisaCrypto", "📝 Using default scheme: $defaultScheme, encoding: $defaultEncoding")

                val result = tryDecryptionWithDefaultScheme(
                    context,
                    encryptedMessage,
                    defaultScheme,
                    defaultEncoding
                )

                return if (result.success) {
                    //CRUCIAL: write DEFAULT in notes (recognized as DEFAULT)
                    result.copy(notes = "Decrypted with DEFAULT scheme (non-SiSa message)")
                } else {
                    // If DEFAULT scheme fails, return original as fallback
                    DecodeResult(
                        original = encryptedMessage,
                        decoded = encryptedMessage,
                        scheme = EncryptionMapper.ENCRYPTION_TEXT,
                        encoding = encoding,
                        success = false,
                        notes = "Not identified as SiSa and DEFAULT scheme failed",
                    )
                }
            }

            LogUtils.d(context, "SisaCrypto", "✅ Identified as SiSa")

            // 2. Extract payload
            val encodedPayload = encryptedMessage.removePrefix(EncryptionMapper.SISA_ENCR_PREFIX).trim()
            LogUtils.d(context, "SisaCrypto", "📦 encodedPayload: '$encodedPayload'")
            LogUtils.d(context, "SisaCrypto", "📦 encodedPayload length: ${encodedPayload.length}")

            val base = extractEncodingBase(encoding)
            LogUtils.d(context, "SisaCrypto", "🔢 base: $base")

            val decodedEncryptedCombined = try {
                if (encoding == EncryptionMapper.ENCRYPTION_SCHEME_TEXT) {
                    encodedPayload.toByteArray()
                } else {
                    BaseXXXUtils.decodeToBytes(encodedPayload, base, encodingPassword)
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

            LogUtils.d(context, "SisaCrypto", "🔍 decodedEncryptedCombined length: ${decodedEncryptedCombined.size} bytes")
            LogUtils.d(context, "SisaCrypto", "🔍 HEX: ${decodedEncryptedCombined.joinToString("") { "%02x".format(it) }}")

            if (decodedEncryptedCombined.size < IV_SIZE) {
                LogUtils.d(context, "SisaCrypto", "❌ Data too short: ${decodedEncryptedCombined.size} < $IV_SIZE")
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

            LogUtils.d(context, "SisaCrypto", "🔑 IV extracted (hex): ${iv.joinToString("") { "%02x".format(it) }}")
            LogUtils.d(context, "SisaCrypto", "🔑 IV length: ${iv.size} bytes")
            LogUtils.d(context, "SisaCrypto", "📦 ciphertext extracted (hex): ${ciphertext.joinToString("") { "%02x".format(it) }}")
            LogUtils.d(context, "SisaCrypto", "📦 ciphertext length: ${ciphertext.size} bytes")

            // 4. Get password
            val password = PasswordManager.getStoredPassword(context, phoneNumber)
            LogUtils.d(context, "SisaCrypto", "🔑 Stored password hash: ${if(password.isNotEmpty()) hashString(password).take(8) else "EMPTY"}")

            if (password.isEmpty()) {
                LogUtils.d(context, "SisaCrypto", "❌ Password not configured")
                return DecodeResult(
                    original = encryptedMessage,
                    decoded = "[ERROR: SiSa Password not configured]",
                    scheme = EncryptionMapper.ENCRYPTION_SISA,
                    encoding = encoding,
                    success = false,
                    notes = "Password missing",
                )
            }
            LogUtils.d(context, "SisaCrypto", "🔑 Password length: ${password.length}")

            // 5. Try decryption with provided timestamp (or current time)
            val decryptionTimestamp = if (timestamp > 0) timestamp else System.currentTimeMillis()
            LogUtils.d(context, "SisaCrypto", "⏱️ decryptionTimestamp: $decryptionTimestamp (${Date(decryptionTimestamp)})")

            if (BuildConfig.DEBUG && false) {
                LogUtils.d("DCHECK","Decrypting encrypted message ("+encryptedMessage+")")
                LogUtils.d("DCHECK","password ("+password+")")

                val ivHex = iv.joinToString("") { "%02x".format(it) }
                val ciphertextHex = ciphertext.joinToString("") { "%02x".format(it) }

                LogUtils.d("DCHECK","iv (hex: $ivHex)")
                LogUtils.d("DCHECK","ciphertext (hex: $ciphertextHex)")
                LogUtils.d("DCHECK","decryptionTimestamp ("+decryptionTimestamp+")")
            }

            // Try current day first
            LogUtils.d(context, "SisaCrypto", "🔄 Trying current day decryption")
            var result = tryDecryptionWithDate(
                context, phoneNumber, password, ciphertext, iv,
                decryptionTimestamp, encoding, encryptedMessage
            )

            if (BuildConfig.DEBUG && false) {
                LogUtils.d("DCHECK","result.success ("+result.success+")")
                LogUtils.d("DCHECK","result.notes ("+result.notes+")")
            }

            // 6. If failed, try previous day
            if (!result.success) {
                LogUtils.d(context, "SisaCrypto", "🔄 Current day failed, trying previous day")

                val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                calendar.timeInMillis = decryptionTimestamp
                calendar.add(Calendar.DAY_OF_MONTH, -1)
                val previousDayTimestamp = calendar.timeInMillis

                LogUtils.d(context, "SisaCrypto", "⏱️ previousDayTimestamp: $previousDayTimestamp (${Date(previousDayTimestamp)})")

                result = tryDecryptionWithDate(
                    context, phoneNumber, password, ciphertext, iv,
                    previousDayTimestamp, encoding, encryptedMessage
                )

                if (result.success) {
                    result = result.copy(notes = "SiSa decrypted with previous day's key")
                    LogUtils.d(context, "SisaCrypto", "✅ Previous day decryption successful")
                } else {
                    LogUtils.d(context, "SisaCrypto", "❌ Previous day decryption also failed")
                }
            }

            // 7. ULTIMA RATIO: If both today and yesterday failed, try DEFAULT encryption scheme
            if (!result.success) {
                LogUtils.d(context, "SisaCrypto", "🔄 ULTIMA RATIO: Trying DEFAULT encryption scheme")

                val defaultEncoding = if (EncryptionMapper.DEFAULT_ENCODING.isNotEmpty()) {
                    EncryptionMapper.DEFAULT_ENCODING
                } else {
                    EncryptionMapper.ENCODING_BASE256  // Default fallback
                }

                val defaultScheme = if (EncryptionMapper.DEFAULT_ENCRYPTION_SCHEME.isNotEmpty()) {
                    EncryptionMapper.DEFAULT_ENCRYPTION_SCHEME
                } else {
                    EncryptionMapper.ENCRYPTION_TEXT  // Default fallback
                }

                LogUtils.d(context, "SisaCrypto", "📝 Using default scheme: $defaultScheme, encoding: $defaultEncoding")

                result = tryDecryptionWithDefaultScheme(
                    context,
                    encryptedMessage,
                    defaultScheme,
                    defaultEncoding
                )

                if (result.success) {
                    result = result.copy(notes = "ULTIMA RATIO: Decrypted with DEFAULT scheme ($defaultScheme/$defaultEncoding)")
                    LogUtils.d(context, "SisaCrypto", "✅ ULTIMA RATIO decryption successful")
                } else {
                    LogUtils.d(context, "SisaCrypto", "❌ ULTIMA RATIO decryption also failed")
                }
            }

            // Remove workaround character if present
            if (result.decoded.contains(WORKAROUND_SINGLE_CHAR_ADDITION)) {
                LogUtils.d(context, "SisaCrypto", "🔧 Removing workaround character")
                result.decoded = result.decoded.replace(WORKAROUND_SINGLE_CHAR_ADDITION, "")
            }

            LogUtils.d(context, "SisaCrypto", "📄 Final decoded text: '${result.decoded.take(50)}...'")
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
     * Try decryption with the default scheme (no password)
     * This is the ULTIMA RATIO fallback when normal SiSa decryption fails
     */
    private fun tryDecryptionWithDefaultScheme(
        context: Context,
        encodedPayload: String,
        defaultScheme: String,
        defaultEncoding: String
    ): DecodeResult {
        return try {
            LogUtils.d(context, "SisaCrypto", "🔄 Trying DEFAULT scheme decryption")

            // If default scheme is SISA, try to decrypt with DEFAULT encoding but no password

            LogUtils.d(context, "SisaCrypto", "📝 DEFAULT scheme is SISA, trying with no password")

            val base = extractEncodingBase(defaultEncoding)

            val decodedBytes = try {
                    BaseXXXUtils.decodeToBytes(encodedPayload, base)
            } catch (e: Exception) {
                LogUtils.e(context, "SisaCrypto", "❌ Error decoding for DEFAULT scheme", e)
                return DecodeResult(
                    original = encodedPayload,
                    decoded = encodedPayload,
                    scheme = EncryptionMapper.ENCRYPTION_TEXT,
                    encoding = defaultEncoding,
                    success = false,
                    notes = "ULTIMA RATIO: Base decode error: ${e.message}"
                )
            }

            // Try to interpret as plain text (no decryption needed)
            val plaintext = try {
                String(decodedBytes, Charsets.UTF_8)
            } catch (e: Exception) {
                LogUtils.e(context, "SisaCrypto", "❌ UTF-8 decoding failed", e)
                return DecodeResult(
                    original = encodedPayload,
                    decoded = encodedPayload,
                    scheme = EncryptionMapper.ENCRYPTION_TEXT,
                    encoding = defaultEncoding,
                    success = false,
                    notes = "ULTIMA RATIO: UTF-8 decode error: ${e.message}"
                )
            }

            LogUtils.d(context, "SisaCrypto", "✅ DEFAULT scheme decryption successful")
            return DecodeResult(
                original = encodedPayload,
                decoded = plaintext,
                scheme = EncryptionMapper.ENCRYPTION_SISA,
                encoding = defaultEncoding,
                success = true,
                notes = "ULTIMA RATIO: Decoded with DEFAULT scheme (no password)"
            )

        } catch (e: Exception) {
            LogUtils.e(context, "SisaCrypto", "❌ Exception in DEFAULT scheme decryption", e)
            DecodeResult(
                original = encodedPayload,
                decoded = encodedPayload,
                scheme = EncryptionMapper.ENCRYPTION_TEXT,
                encoding = defaultEncoding,
                success = false,
                notes = "ULTIMA RATIO: Exception: ${e.message}"
            )
        }
    }
    /**
     * Try decryption with a specific date
     * taken from timestamp
     */
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
            LogUtils.d(context, "SisaCrypto", "📅 Trying decryption with date: $dateStr")
            LogUtils.d(context, "SisaCrypto", "📅 Password hash: ${hashString(password).take(8)}")

            // Get key for this date (from cache or derive)
            val aesKey = getOrCreateKeyForDate(context, phoneNumber, password, dateStr)
            if (aesKey == null) {
                LogUtils.e(context, "SisaCrypto", "❌ Failed to get key for date $dateStr")
                return DecodeResult(
                    original = encryptedMessage,
                    decoded = "[ERROR: Failed to get decryption key]",
                    scheme = EncryptionMapper.ENCRYPTION_SISA,
                    encoding = encoding,
                    success = false,
                    notes = "Key unavailable for date $dateStr",
                )
            }
            LogUtils.d(context, "SisaCrypto", "✅ Key obtained for date $dateStr")

            // DEBUG: Log key hash (without exposing actual key)
            val digest = MessageDigest.getInstance("SHA-256")
            val keyHash = if (aesKey.encoded != null) {
                digest.digest(aesKey.encoded).take(8).joinToString("") { "%02x".format(it) }
            } else {
                val fallbackString = "${aesKey.algorithm}:${aesKey.format}:${aesKey.hashCode()}"
                digest.digest(fallbackString.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
            }
            LogUtils.d(context, "SisaCrypto", "🔑 KEY HASH: $keyHash")

            // Decrypt with the provided IV
            LogUtils.d(context, "SisaCrypto", "🔓 Attempting decryption with GCM...")
            LogUtils.d(context, "SisaCrypto", "  ciphertext length: ${ciphertext.size} bytes")
            LogUtils.d(context, "SisaCrypto", "  ciphertext (hex): ${ciphertext.joinToString("") { "%02x".format(it) }}")
            LogUtils.d(context, "SisaCrypto", "  iv (hex): ${iv.joinToString("") { "%02x".format(it) }}")

            val plaintextBytes = try {
                decryptWithRetries(ciphertext, aesKey, iv)
            } catch (e: Exception) {
                LogUtils.e(context, "SisaCrypto", "❌ decryptWithRetries failed", e)
                return DecodeResult(
                    original = encryptedMessage,
                    decoded = "[ERROR SiSa Decryption: ${e.message}]",
                    scheme = EncryptionMapper.ENCRYPTION_SISA,
                    encoding = encoding,
                    success = false,
                    notes = "Decryption failed: ${e.javaClass.simpleName} - ${e.message}",
                )
            }

            LogUtils.d(context, "SisaCrypto", "✅ Decryption successful, got ${plaintextBytes.size} bytes")
            LogUtils.d(context, "SisaCrypto", "🔍 RAW decrypted bytes (hex): ${plaintextBytes.joinToString("") { "%02x".format(it) }}")
            LogUtils.d(context, "SisaCrypto", "🔍 First 10 bytes as ints: ${plaintextBytes.take(10).map { it.toInt() }}")

            // Try to convert to string
            val plaintext = try {
                String(plaintextBytes, Charsets.UTF_8)
            } catch (e: Exception) {
                LogUtils.e(context, "SisaCrypto", "❌ UTF-8 decoding failed", e)
                return DecodeResult(
                    original = encryptedMessage,
                    decoded = "[ERROR: UTF-8 decoding failed: ${e.message}]",
                    scheme = EncryptionMapper.ENCRYPTION_SISA,
                    encoding = encoding,
                    success = false,
                    notes = "UTF-8 decoding error",
                )
            }

            LogUtils.d(context, "SisaCrypto", "🔍 Plaintext (raw): '$plaintext'")
            LogUtils.d(context, "SisaCrypto", "🔍 Plaintext length: ${plaintext.length}")
            LogUtils.d(context, "SisaCrypto", "🔍 Plaintext chars with codes: ${plaintext.take(20).map { "${it} (${it.code})" }}")

            // Verify that the decoded text is valid
            if (!isValidPlaintext(plaintext)) {
                LogUtils.d(context, "SisaCrypto", "⚠️ Plaintext failed validation")
                LogUtils.d(context, "SisaCrypto", "⚠️ First 20 chars (hex): ${plaintext.take(20).map { it.code.toString(16) }}")
                return DecodeResult(
                    original = encryptedMessage,
                    decoded = plaintext,
                    scheme = EncryptionMapper.ENCRYPTION_SISA,
                    encoding = encoding,
                    success = false,
                    notes = "Invalid text with date $dateStr",
                )
            }

            LogUtils.d(context, "SisaCrypto", "✅ Plaintext passed validation")
            DecodeResult(
                original = encryptedMessage,
                decoded = plaintext,
                scheme = EncryptionMapper.ENCRYPTION_SISA,
                encoding = encoding,
                success = true,
                notes = "SiSa decryption successful with date $dateStr",
            )

        } catch (e: Exception) {
            LogUtils.e(context, "SisaCrypto", "❌ Exception in tryDecryptionWithDate", e)
            DecodeResult(
                original = encryptedMessage,
                decoded = "[ERROR SiSa Decryption: ${e.message}]",
                scheme = EncryptionMapper.ENCRYPTION_SISA,
                encoding = encoding,
                success = false,
                notes = "Exception: ${e.javaClass.simpleName}",
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

    /**
     * should allow world languages
     * so the checks here are minimal
     */
    private fun isValidPlaintext(plaintext: String): Boolean {
        // empty?
        if (plaintext.isEmpty()) {
            LogUtils.d(null, "SisaCrypto", "isValidPlaintext: false (empty)")
            return false
        }

        // Too long?
        if (plaintext.length > 100000) {
            LogUtils.d(null, "SisaCrypto", "isValidPlaintext: false (too long: ${plaintext.length})")
            return false
        }

        // Valid utf-8
        if (plaintext.contains(Char(0))) {
            LogUtils.d(null, "SisaCrypto", "isValidPlaintext: false (contains null char)")
            return false
        }

        return true
    }

    fun isSisaEncrypted(message: String): Boolean {
        return message.startsWith(EncryptionMapper.SISA_ENCR_PREFIX)
    }
}