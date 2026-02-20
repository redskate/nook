package solutions.semweb.nook.crypto

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.widget.Toast
import solutions.semweb.nook.LogUtils
import solutions.semweb.nook.MainActivity
import solutions.semweb.nook.R
import solutions.semweb.nook.SharedPreferencesManager
import java.security.KeyStore
import java.util.Calendar
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES256-GCM based cut&paste between messages
 */

object ChatSafeCopyManager {

    private const val KEY_ALIAS = "safecopy_aes_key_v2"
    private const val KEY_TIMEOUT_HOURS = 6
    private const val AES_KEY_SIZE = 256
    private const val GCM_TAG_LENGTH = 128
    private const val GCM_IV_LENGTH = 12
    private const val PBKDF2_ITERATIONS = 50000
    private const val PREVIEW_MAX_LENGTH = 20
    private var cachedSecretKey: SecretKey? = null
    private var keyGenerationTime: Long = 0

    fun copyTextSafely(context: Context, text: String): Boolean {
        return try {
            LogUtils.d(context, "SafeCopyManager", "🔐 Safe copying...")

            val secretKey = getOrGenerateSecretKey(context)
            if (secretKey == null) {
                LogUtils.e(context, "SafeCopyManager", "❌ Error getting AES key")
                return false
            }

            val encryptedData = encryptWithGCM(context, secretKey, text)
            if (encryptedData == null) {
                LogUtils.e(context, "SafeCopyManager", "❌ GCM Encryption failed")
                return false
            }

            val base64Encoded = Base64.encodeToString(encryptedData, Base64.NO_WRAP)

            val prefs = SharedPreferencesManager.getInstance(context)
            prefs.saveSafeCopyText(base64Encoded)

            val creationTime = System.currentTimeMillis()
            val cipherType = if (isKeyFromAndroidKeyStore(secretKey)) "GCM-Keystore" else "GCM-PBKDF2"

            prefs.prefs.edit()
                .putLong("safecopy_timestamp", creationTime)
                .putString("safecopy_cipher_type", cipherType)
                .apply()

            val preview = if (text.length > PREVIEW_MAX_LENGTH) {
                "${text.take(PREVIEW_MAX_LENGTH - 3)}..."
            } else {
                text
            }
            prefs.prefs.edit()
                .putString("safecopy_preview", preview)
                .apply()

            LogUtils.d(context, "SafeCopyManager", "✅ Safe copy completed")
            LogUtils.d(context, "SafeCopyManager", "  Type: $cipherType")
            LogUtils.d(context, "SafeCopyManager", "  Data: ${encryptedData.size} byte")
            LogUtils.d(context, "SafeCopyManager", "  Base64: ${base64Encoded.length} char")

            Toast.makeText(context, context.getString(R.string.safe_copy_success), Toast.LENGTH_SHORT).show()
            true

        } catch (e: Exception) {
            LogUtils.e(context, "SafeCopyManager", "❌ Error safe copying", e)
            Toast.makeText(context, context.getString(R.string.safe_copy_error), Toast.LENGTH_SHORT).show()
            false
        }
    }

    fun pasteTextSafely(context: Context): String? {
        return try {
            LogUtils.d(context, "SafeCopyManager", "🔓 Safe pasting...")

            val prefs = SharedPreferencesManager.getInstance(context)

            // 1. Verify existence
            val encryptedBase64 = prefs.getSafeCopyText()
            if (encryptedBase64 == null) {
                LogUtils.d(context, "SafeCopyManager", "⚠️ No safe copy text found")
                MainActivity.showToast(context.getString(R.string.no_safe_text_saved_found))
                return null
            }

            val creationTime = prefs.prefs.getLong("safecopy_timestamp", 0)
            if (!isKeyStillValid(creationTime)) {
                LogUtils.w(context, "SafeCopyManager", "⏰ Key validity elapsed (6h)")
                clearSafeCopy(context)
                Toast.makeText(context, "The safe text validity is elapsed (6h)", Toast.LENGTH_SHORT).show()
                return null
            }

            val encryptedData = Base64.decode(encryptedBase64, Base64.DEFAULT)
            if (encryptedData.size < GCM_IV_LENGTH + 1) {
                LogUtils.e(context, "SafeCopyManager", "❌ Encrypted data corrupted")
                clearSafeCopy(context)
                return null
            }

            val secretKey = getOrGenerateSecretKey(context)
            if (secretKey == null) {
                LogUtils.e(context, "SafeCopyManager", "❌ AES key not available")
                clearSafeCopy(context)
                return null
            }

            val decryptedText = decryptWithGCM(context, secretKey, encryptedData)
            if (decryptedText == null) {
                LogUtils.e(context, "SafeCopyManager", "❌ Decryption failed")
                clearSafeCopy(context)
                return null
            }

            // 6. Cancella dopo uso
            clearSafeCopy(context)

            LogUtils.d(context, "SafeCopyManager", "✅ Safe pasting completed")

            decryptedText

        } catch (e: Exception) {
            LogUtils.e(context, "SafeCopyManager", "❌ Error safe pasting", e)
            clearSafeCopy(context)
            Toast.makeText(context, context.getString(R.string.safe_copy_error), Toast.LENGTH_SHORT).show()
            null
        }
    }

    fun getPreviewText(context: Context): String? {
        return try {
            val prefs = SharedPreferencesManager.getInstance(context)

            val encryptedBase64 = prefs.getSafeCopyText() ?: return null
            val creationTime = prefs.prefs.getLong("safecopy_timestamp", 0)

            if (!isKeyStillValid(creationTime)) {
                clearSafeCopy(context)
                return null
            }

            prefs.prefs.getString("safecopy_preview", null)

        } catch (e: Exception) {
            LogUtils.e(context, "SafeCopyManager", "❌ Error getting preview", e)
            null
        }
    }

    fun clearSafeCopy(context: Context) {
        val prefs = SharedPreferencesManager.getInstance(context)
        prefs.clearSafeCopyText()
        prefs.prefs.edit()
            .remove("safecopy_timestamp")
            .remove("safecopy_preview")
            .remove("safecopy_cipher_type")
            .apply()

        cachedSecretKey = null
        keyGenerationTime = 0

        LogUtils.d(context, "SafeCopyManager", "🗑️ Safe copy cleared")
    }


    fun hasValidSafeCopy(context: Context): Boolean {
        val prefs = SharedPreferencesManager.getInstance(context)
        val hasText = prefs.getSafeCopyText() != null

        if (!hasText) return false

        val creationTime = prefs.prefs.getLong("safecopy_timestamp", 0)
        return isKeyStillValid(creationTime)
    }

    // =============================================
    // CORE: GCM encryption/decryption
    // =============================================

    private fun encryptWithGCM(context: Context, secretKey: SecretKey, plainText: String): ByteArray? {
        return try {
            LogUtils.d(context, "SafeCopyManager", "🔐 Start GCM Encryption...")

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val isKeyStoreKey = isKeyFromAndroidKeyStore(secretKey)

            if (isKeyStoreKey) {
                cipher.init(Cipher.ENCRYPT_MODE, secretKey)
                val iv = cipher.iv

                if (iv.size != GCM_IV_LENGTH) {
                    LogUtils.e(context, "SafeCopyManager", "❌ wrong IV: ${iv.size} byte")
                    return null
                }

                val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
                iv + cipherText

            } else {
                val iv = ByteArray(GCM_IV_LENGTH).also {
                    java.security.SecureRandom().nextBytes(it)
                }

                val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
                cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)

                val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
                iv + cipherText
            }

        } catch (e: Exception) {
            LogUtils.e(context, "SafeCopyManager", "❌ Error GCM Encrypting", e)
            null
        }
    }

    private fun decryptWithGCM(context: Context, secretKey: SecretKey, encryptedData: ByteArray): String? {
        return try {
            LogUtils.d(context, "SafeCopyManager", "🔓 Start GCM Decrypting...")

            if (encryptedData.size < GCM_IV_LENGTH + 1) {
                LogUtils.e(context, "SafeCopyManager", "❌ Data too short")
                return null
            }

            val iv = encryptedData.copyOfRange(0, GCM_IV_LENGTH)
            val cipherText = encryptedData.copyOfRange(GCM_IV_LENGTH, encryptedData.size)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")

            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)

            val plainTextBytes = cipher.doFinal(cipherText)
            String(plainTextBytes, Charsets.UTF_8)

        } catch (e: Exception) {
            LogUtils.e(context, "SafeCopyManager", "❌ Error GCM decrypting", e)
            null
        }
    }

    // =============================================
    // CORE: Key management
    // =============================================

    private fun getOrGenerateSecretKey(context: Context): SecretKey? {
        return try {
            if (cachedSecretKey != null && isCachedKeyValid()) {
                LogUtils.d(context, "SafeCopyManager", "♻️ Usando chiave cache")
                return cachedSecretKey
            }

            val newKey = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                generateKeyWithAndroidKeyStore()
            } else {
                generateKeyLegacy(context)
            }

            cachedSecretKey = newKey
            keyGenerationTime = System.currentTimeMillis()

            LogUtils.d(context, "SafeCopyManager", "🔑 New AES key generated")
            newKey

        } catch (e: Exception) {
            LogUtils.e(context, "SafeCopyManager", "❌ Error generating AES key", e)
            null
        }
    }

    private fun generateKeyWithAndroidKeyStore(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)

        if (keyStore.containsAlias(KEY_ALIAS)) {
            val entry = keyStore.getEntry(KEY_ALIAS, null)
            if (entry is KeyStore.SecretKeyEntry) {
                return entry.secretKey
            }
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )

        val keySpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setKeySize(AES_KEY_SIZE)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(false)
            .setKeyValidityStart(Date()) // From now 6h
            .apply {
                val calendar = Calendar.getInstance()
                calendar.add(Calendar.HOUR, KEY_TIMEOUT_HOURS)
                setKeyValidityEnd(calendar.time)
            }
            .build()

        keyGenerator.init(keySpec)
        return keyGenerator.generateKey()
    }

    private fun generateKeyLegacy(context: Context): SecretKey {
        val password = generateEphemeralPassword(context)
        val salt = generateSalt()

        LogUtils.d(context, "SafeCopyManager",
            "🔐 PBKDF2: ${password.length} char, $PBKDF2_ITERATIONS iter")

        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = javax.crypto.spec.PBEKeySpec(
            password.toCharArray(),
            salt,
            PBKDF2_ITERATIONS,
            AES_KEY_SIZE
        )

        val tmpKey = factory.generateSecret(spec)
        return SecretKeySpec(tmpKey.encoded, "AES")
    }

    private fun generateEphemeralPassword(context: Context): String {
        // Entropia massima per password temporanea
        val sources = mutableListOf<String>()

        sources.add(System.currentTimeMillis().toString())
        sources.add(System.nanoTime().toString())

        val secureRandom = java.security.SecureRandom()
        sources.add(secureRandom.nextLong().toString())
        sources.add(secureRandom.nextInt().toString())

        try {
            val deviceId = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            )
            if (!deviceId.isNullOrEmpty()) {
                sources.add(deviceId)
            }
        } catch (_: Exception) { }

        sources.add(android.os.Build.FINGERPRINT)
        sources.add(android.os.Build.SERIAL)

        val separator = "_${secureRandom.nextInt(9999)}_"
        val combined = sources.joinToString(separator)

        val digest = java.security.MessageDigest.getInstance("SHA-512")

        val firstHash = digest.digest(combined.toByteArray())

        val salt = byteArrayOf(0x73, 0x61, 0x66, 0x65, 0x63, 0x6f, 0x70, 0x79) // "safecopy"
        digest.update(salt)
        val finalHash = digest.digest(firstHash + combined.toByteArray())

        return android.util.Base64.encodeToString(finalHash, android.util.Base64.NO_WRAP)
            .replace("[^A-Za-z0-9]".toRegex(), "")
            .take(32)
    }

    private fun generateSalt(): ByteArray {
        return ByteArray(32).also {
            java.security.SecureRandom().nextBytes(it)
        }
    }

    // =============================================
    // UTILITY: Validation
    // =============================================

    private fun isKeyStillValid(creationTime: Long): Boolean {
        if (creationTime == 0L) return false

        val now = System.currentTimeMillis()
        val hoursElapsed = (now - creationTime) / (1000 * 60 * 60)

        return hoursElapsed < KEY_TIMEOUT_HOURS
    }

    private fun isCachedKeyValid(): Boolean {
        if (keyGenerationTime == 0L) return false

        val now = System.currentTimeMillis()
        val hoursElapsed = (now - keyGenerationTime) / (1000 * 60 * 60)

        return hoursElapsed < (KEY_TIMEOUT_HOURS / 2)
    }

    private fun isKeyFromAndroidKeyStore(secretKey: SecretKey): Boolean {
        return try {
            secretKey.javaClass.name.contains("AndroidKeyStore", ignoreCase = true)
        } catch (e: Exception) {
            false
        }
    }

    // =============================================
    // DEBUG & INFO
    // =============================================


    fun getSafeCopyInfo(context: Context): SafeCopyInfo? {
        return try {
            val prefs = SharedPreferencesManager.getInstance(context)
            val encryptedBase64 = prefs.getSafeCopyText() ?: return null

            val creationTime = prefs.prefs.getLong("safecopy_timestamp", 0)
            val preview = prefs.prefs.getString("safecopy_preview", "")
            val cipherType = prefs.prefs.getString("safecopy_cipher_type", "Unknown")

            val isValid = isKeyStillValid(creationTime)
            val hoursLeft = calculateHoursLeft(creationTime)

            SafeCopyInfo(
                hasData = encryptedBase64.isNotEmpty(),
                isValid = isValid,
                creationTime = creationTime,
                preview = preview ?: "",
                cipherType = cipherType ?: "Unknown",
                hoursLeft = hoursLeft,
                dataLength = encryptedBase64.length
            )

        } catch (e: Exception) {
            LogUtils.e(context, "SafeCopyManager", "❌ Error getting safe copy info", e)
            null
        }
    }

    private fun calculateHoursLeft(creationTime: Long): Int {
        if (creationTime == 0L) return 0

        val now = System.currentTimeMillis()
        val hoursElapsed = (now - creationTime) / (1000 * 60 * 60)
        val hoursLeft = KEY_TIMEOUT_HOURS - hoursElapsed.toInt()

        return if (hoursLeft > 0) hoursLeft else 0
    }


    fun testEncryption(context: Context): Boolean {
        return try {
            val testText = "Test sicuro AES256-GCM ${System.currentTimeMillis()}"
            LogUtils.d(context, "SafeCopyManager", "🧪 Encryption text: '$testText'")

            val success = copyTextSafely(context, testText)
            if (!success) {
                LogUtils.e(context, "SafeCopyManager", "❌ Encryption Test failed")
                return false
            }

            val decrypted = pasteTextSafely(context)
            val match = decrypted == testText

            LogUtils.d(context, "SafeCopyManager",
                if (match) "✅ Test PASSED" else "❌ Test FAILED")

            match

        } catch (e: Exception) {
            LogUtils.e(context, "SafeCopyManager", "❌ Test error", e)
            false
        }
    }

}

/**
 * safe copy data class
 */
data class SafeCopyInfo(
    val hasData: Boolean,
    val isValid: Boolean,
    val creationTime: Long,
    val preview: String,
    val cipherType: String,
    val hoursLeft: Int,
    val dataLength: Int
)
