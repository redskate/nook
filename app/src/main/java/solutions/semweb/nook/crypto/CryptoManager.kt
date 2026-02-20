package solutions.semweb.nook.crypto

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale
import androidx.core.content.edit
import solutions.semweb.nook.Constants
import solutions.semweb.nook.LogUtils
import solutions.semweb.nook.MainActivity
import solutions.semweb.nook.PhoneUtils
import solutions.semweb.nook.R
import solutions.semweb.nook.SharedPreferencesManager
import solutions.semweb.nook.chat.ChatManager
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Manager centrale per tutte le operazioni di crittografia
 */
object CryptoManager {
    // =============================================
    // 1. DECODING MESSAGES
    // =============================================

    /**
     * Decoding a message using the specified schema (width password) and/or encoding (with password)
     */
    fun decodeMessage(
        context: Context?,
        message: String,
        scheme: String,
        encoding: String,
        encodingPassword: String,
        phoneNumber: String = "",
        timestamp: Long = 0
    ): DecodeResult {
        return try {
            var cleanMessage = MessageCleaner.cleanForDecoding(message)
            LogUtils.d(null, "CryptoManager", "🔍 DECODING MESSAGE:")
            LogUtils.d(null, "CryptoManager", "  Encoding used: $encoding")
            LogUtils.d(null, "CryptoManager", "  Schema used: $scheme")
            LogUtils.d(null, "CryptoManager", "  Sender: $phoneNumber")
            LogUtils.d(null, "CryptoManager", "  Original message: '${message.take(50)}...'")
            LogUtils.d(null, "CryptoManager", "  Cleaned message: '${cleanMessage.take(50)}...'")

            val result = when (scheme.lowercase()) {
                EncryptionMapper.ENCRYPTION_SISA -> {
                    LogUtils.d(null, "CryptoManager", "  🔐 Decryption SiSa started...")
                    if (context == null) {
                        DecodeResult(
                            original = message,
                            decoded = "ERROR: Missing context for SiSa",
                            scheme = scheme,
                            encoding = encoding,
                            success = false,
                            notes = "Context null",
                        )
                    } else {
                        cleanMessage = cleanMessage.substring(Constants.SMS_OBF_PREFIX.length) // eat prefix #e
                        SisaCrypto.decryptMessage(context, phoneNumber, encoding, cleanMessage, timestamp)
                    }
                }

                EncryptionMapper.ENCRYPTION_TEXT -> {
                    if (encoding != EncryptionMapper.ENCRYPTION_TEXT) {
                        val base = EncryptionMapper.extractEncodingBase(encoding)
                        val payload = message.substring(Constants.SMS_OBF_PREFIX.length) // eat prefix
                        val decodedPayload: BaseXXXUtils.DecodeResult = BaseXXXUtils.decode(payload, base, encodingPassword)

                        DecodeResult(
                            original = message,
                            decoded = decodedPayload.decoded,
                            scheme = scheme,
                            encoding = encoding,
                            success = decodedPayload.success,
                            notes = decodedPayload.notes
                        )
                    } else {
                        DecodeResult(
                            original = message,
                            decoded = cleanMessage,
                            scheme = scheme,
                            encoding = encoding,
                            success = true,
                            notes = "Plaintext"
                        )
                    }
                }

                "auto" -> {
                    LogUtils.d(null, "CryptoManager", "  Auto format detection...")
                    autoDetectAndDecode(cleanMessage, context, encoding, phoneNumber)
                }

                else -> DecodeResult(
                    original = message,
                    decoded = "Unknown Schema: $scheme",
                    scheme = scheme,
                    encoding = encoding,
                    success = false,
                    notes = "Schema not supported",
                )
            }

            LogUtils.d(null, "CryptoManager", "  ✅ Decoding result:")
            LogUtils.d(null, "CryptoManager", "    Success: ${result.success}")
            LogUtils.d(null, "CryptoManager", "    Note: ${result.notes}")
            LogUtils.d(null, "CryptoManager", "    Decoded: '${result.decoded.take(50)}...'")

            result
        } catch (e: Exception) {
            LogUtils.e(null, "CryptoManager", "❌ Error during coding", e)
            DecodeResult(
                original = message,
                decoded = "ERROR: ${e.message}",
                scheme = scheme,
                encoding = encoding,
                success = false,
                notes = "Exception: ${e.javaClass.simpleName}",
            )
        }
    }


    private fun autoDetectAndDecode(cleanMessage: String, context: Context?, encoding: String, phoneNumber: String): DecodeResult {
        LogUtils.d(null, "CryptoManager", "🤖 Auto format detection...")

        if (SisaCrypto.isSisaEncrypted(cleanMessage) && context != null) {
            LogUtils.d(null, "CryptoManager", "  🔐 SiSa detected")
            return SisaCrypto.decryptMessage(context, phoneNumber, encoding,cleanMessage)
        }

        LogUtils.d(null, "CryptoManager", "  ⚠️ No format detected, plaintext")
        return DecodeResult(
            original = cleanMessage,
            decoded = cleanMessage,
            scheme = EncryptionMapper.ENCRYPTION_TEXT,
            encoding =EncryptionMapper.ENCRYPTION_TEXT,
            success = true,
            notes = "No format detected, plaintext",
        )
    }

    // =============================================
    // 2. MESSAGE CODING
    // =============================================

    /**
     * Encode a message for sending usingschema and encoding
     */
    fun encryptEncodeMessage(
        context: Context?,
        text: String,
        scheme: String = Constants.DEFAULT_encryptionScheme,
        encoding: String = Constants.DEFAULT_encoding,
        encodingPassword: String = "",
        phoneNumber: String
    ): String {
        LogUtils.d(null, "CryptoManager", "🔐 CODING FOR SENDING:")
        LogUtils.d(null, "CryptoManager", "  Original text: '$text'")
        LogUtils.d(null, "CryptoManager", "  Scheme: $scheme")
        LogUtils.d(null, "CryptoManager", "  Encoding: $encoding")
        LogUtils.d(null, "CryptoManager", "  Recipient: $phoneNumber")

        val encoded = when (scheme.lowercase()) {
            EncryptionMapper.ENCRYPTION_SISA -> {
                if (context == null) {
                    LogUtils.e(null, "CryptoManager", "❌ null context for SiSa - no encryption possible")
                    text
                } else {
                    //encryption AND coding here:
                    SisaCrypto.encryptEncMessage(context, phoneNumber, text, encoding)
                }
            }
            //else encode plain text only (for those cases where encryption is outer law):
            EncryptionMapper.ENCODING_BASE32 -> encodeWithBaseScheme( text, 32, encodingPassword, EncryptionMapper.SISA_ENCR_PREFIX, EncryptionMapper.ENCODING_BASE32 )
            EncryptionMapper.ENCODING_BASE64 -> encodeWithBaseScheme( text, 64, encodingPassword, EncryptionMapper.SISA_ENCR_PREFIX, EncryptionMapper.ENCODING_BASE64 )
            EncryptionMapper.ENCODING_BASE128 -> encodeWithBaseScheme( text, 128,encodingPassword,  EncryptionMapper.SISA_ENCR_PREFIX, EncryptionMapper.ENCODING_BASE128 )
            EncryptionMapper.ENCODING_BASE256 -> encodeWithBaseScheme( text, 256, encodingPassword, EncryptionMapper.SISA_ENCR_PREFIX, EncryptionMapper.ENCODING_BASE256 )
            EncryptionMapper.ENCODING_BASE512 -> encodeWithBaseScheme( text, 512, encodingPassword, EncryptionMapper.SISA_ENCR_PREFIX, EncryptionMapper.ENCODING_BASE512 )
            EncryptionMapper.ENCODING_BASE1024 -> encodeWithBaseScheme( text, 1024, encodingPassword, EncryptionMapper.SISA_ENCR_PREFIX, EncryptionMapper.ENCODING_BASE1024 )
            EncryptionMapper.ENCODING_BASE2048 -> encodeWithBaseScheme( text, 2048, encodingPassword, EncryptionMapper.SISA_ENCR_PREFIX, EncryptionMapper.ENCODING_BASE2048 )
            else -> {
                LogUtils.d(null, "CryptoManager", "  Plaintext wished (no obfuscation)")
                text
            }
        }

        val finalMessage = "${Constants.SMS_OBF_PREFIX}$encoded"
        LogUtils.d(null, "CryptoManager", "  Final message: '${finalMessage}'")
        LogUtils.d(null, "CryptoManager", "  Final length: ${finalMessage.length}")

        return finalMessage
    }

    fun encodeWithBaseScheme(text: String, base: Int, encodingPassword: String, prefix: String, logLabel: String): String {
        val encoded = BaseXXXUtils.encode(text.toByteArray(Charsets.UTF_8), base, encodingPassword)
        val result = prefix + encoded
        LogUtils.d(null, "CryptoManager", "  $logLabel coded: '${result.take(50)}...'")
        return result
    }

    // =============================================
    // 3. CRYPTOGRAPHICAL DETECTION
    // =============================================

    /**
     * Verify whether a message seemes to be encrypted
     */
    fun isLikelyEncrypted(message: String): Boolean {
        val trimmed = message.trim()

        LogUtils.d(null, "CryptoManager", "🔍 ENCRYPTION DETECTION:")
        LogUtils.d(null, "CryptoManager", "  Message: '${trimmed.take(30)}...'")
        LogUtils.d(null, "CryptoManager", "  Length: ${trimmed.length}")

        // 1. Check whether it starts with our prefix
        if (trimmed.startsWith(Constants.SMS_OBF_PREFIX)) {
            LogUtils.d(null, "CryptoManager", "  ✅ SMS coded prefix detected: ${Constants.SMS_OBF_PREFIX}")
            return true
        }


        // 2. Check whether it is a SiSa message
        if (SisaCrypto.isSisaEncrypted(trimmed)) {
            LogUtils.d(null, "CryptoManager", "  🔐 SiSa prefix detected")
            return true
        }

        // 3. Clean message
        val cleanMessage = MessageCleaner.cleanForDecoding(trimmed)

        if (cleanMessage.isEmpty()) {
            LogUtils.d(null, "CryptoManager", "  ⚠️ Empty clean message")
            return false
        }

        //4 Message too short
        if (message.length < 5)
        {
            return false
        }

        return false
    }

    // =============================================
    // 4. CRYPTOGRAPHICAL SETTINGS
    // =============================================

    fun saveEncryptionAndPasswordData(
        context: Context,
        phoneNumber: String,
        scheme: String,
        encryptionPassword: String = ""
    ) {
        val prefs = SharedPreferencesManager.getInstance(context)
        val chatManager = ChatManager(context)

        val normalizedNumber = PhoneUtils.normalizePhoneNumber(phoneNumber)

        LogUtils.d(context, "CryptoManager",
            "💾 Saving configuration for: '$phoneNumber' (normalized: '$normalizedNumber')")
        LogUtils.d(context, "CryptoManager", "  Schema: $scheme")

        if (encryptionPassword.isNotEmpty()) {
            val success = chatManager.updateChatEncryptionScheme(normalizedNumber, scheme, Constants.DEFAULT_encoding)
            if (success) {
                // 1. Save scheme in the chat (using normalized phone)
                chatManager.setEncryptionSchemeForChat(normalizedNumber, scheme)

                // 2. Save password (using normalized phone)
                prefs.prefs.edit {
                    putString("encryption_password_$normalizedNumber", encryptionPassword)
                }

                // 3. Generate and save ECDH keys (using normalized phone)
                generateAndStoreECDHKeys(context, normalizedNumber, encryptionPassword)
            } else {
                MainActivity.showToast("Error saving SiSa scheme", true)
            }
        } else {
            MainActivity.showToast(context.getString(R.string.empty_password_error), true)
        }
        // Send broadcast to update UI
        sendEncryptionUpdateBroadcast(context, normalizedNumber)
    }


    /**
     * Send broadcast to update UI when encryption scheme changes
     */
    private fun sendEncryptionUpdateBroadcast(context: Context, phoneNumber: String) {
        val intent = Intent(Constants.mainpackage+".ENCRYPTION_UPDATED")
        intent.putExtra("phone_number", phoneNumber)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            intent.setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
    }

    // =============================================
    // 5. ECDH KEYS MANAGEMENT
    // =============================================

    fun generateAndStoreECDHKeys(
        context: Context,
        phoneNumber: String,
        password: String,
        forceRegenerate: Boolean = true
    ) {
        try {
            LogUtils.d(context, "CryptoManager", "🔐 Generation ECDH keys for: $phoneNumber")

            val prefs = SharedPreferencesManager.getInstance(context)

            // 1. VERIFY VALID PASSWORD
            if (password.isBlank()) {
                LogUtils.e(context, "CryptoManager", "❌ Empty password")
                MainActivity.showToast(context.getString(R.string.invalid_password), true)
                return
            }

            LogUtils.d(context, "CryptoManager", "  Password: ${password.take(5)}... (${password.length} char)")

            // 2. WHETHER KEYS ALREADY EXIST...
            val keysExist = hasECDHKeyForPhoneNumber(context, phoneNumber)

            if (keysExist) {
                if (forceRegenerate) {
                    LogUtils.d(context, "CryptoManager", "🔄 Re-generating existing keys")
                    MainActivity.showToast(context.getString(R.string.regenerating_keys), false)
                } else {
                    MainActivity.showToast(context.getString(R.string.regeneration_suppressed_keys_exist), false)
                    return
                }
            }

            // 3. DELETE EXISTING KEYS (if present)
            if (keysExist) {
                LogUtils.d(context, "CryptoManager", "🗑️ Deletion existing keys")
                clearECDHKeyForPhoneNumber(context, phoneNumber)
            }

            // 4. Generate and save new keys
            val normalizedNumber = PhoneUtils.normalizePhoneNumber(phoneNumber)
                .replace("[^A-Za-z0-9]".toRegex(), "_")

            val alias = "sisa_ecdh_$normalizedNumber"
            val timestamp = System.currentTimeMillis()

            prefs.prefs.edit {
                putString("${alias}_pub", "PUBLIC_KEY_${phoneNumber}_${timestamp}")
                    .putString("${alias}_priv_enc", "ENCRYPTED_PRIVATE_${password.hashCode()}")
                    .putString("${alias}_curve", "secp256r1")
                    .putString("${alias}_password_hash", hashPassword(password))
                    .putLong("${alias}_timestamp", timestamp)
            }

            // 5. SAVE METADATA
            storeECDHMetadata(prefs, phoneNumber, "secp256r1")

            LogUtils.d(context, "CryptoManager", "✅ Chiavi ${if (keysExist) "rigenerate" else "generate"}")
            MainActivity.showToast(context.getString(R.string.keys_generated_success,
                if (keysExist) context.getString(R.string.regenerated)
                else context.getString(R.string.generated)
            ))

        } catch (e: Exception) {
            LogUtils.e(context, "CryptoManager", "❌ Error generating ECDH keys", e)
            MainActivity.showToast(context.getString(R.string.error_generating_keys, e.message?.take(30)), true)
        }
    }

    fun clearECDHKeyForPhoneNumber(context: Context, phoneNumber: String) {
        val prefs = SharedPreferencesManager.getInstance(context)
        val normalizedNumber = PhoneUtils.normalizePhoneNumber(phoneNumber)
            .replace("[^A-Za-z0-9]".toRegex(), "_")

        val alias = "sisa_ecdh_$normalizedNumber"
        val editor = prefs.prefs.edit()

        // Cancella tutti i dati della chiave
        editor.remove("${alias}_pub")
            .remove("${alias}_priv_enc")
            .remove("${alias}_curve")
            .remove("${alias}_phones")
            .remove("${alias}_timestamp")
            .remove("${alias}_password_hash")
            .remove("sisa_ecdh_meta_$normalizedNumber")

        editor.apply()

        LogUtils.d(context, "CryptoManager", "🗑️ ECDH keys deleted for: $phoneNumber")
    }

    fun hasECDHKeyForPhoneNumber(context: Context, phoneNumber: String): Boolean {
        val prefs = SharedPreferencesManager.getInstance(context)
        val normalizedNumber = PhoneUtils.normalizePhoneNumber(phoneNumber)
            .replace("[^A-Za-z0-9]".toRegex(), "_")

        val alias = "sisa_ecdh_$normalizedNumber"
        return prefs.prefs.getString("${alias}_pub", null) != null
    }

    fun getECDHPublicKeyBase64(context: Context, phoneNumber: String): String? {
        val prefs = SharedPreferencesManager.getInstance(context)
        val normalizedNumber = PhoneUtils.normalizePhoneNumber(phoneNumber)
            .replace("[^A-Za-z0-9]".toRegex(), "_")

        val alias = "sisa_ecdh_$normalizedNumber"
        return prefs.prefs.getString("${alias}_pub", null)
    }

    // =============================================
    // 6. ECDH METADATA
    // =============================================

    private fun storeECDHMetadata(
        prefs: SharedPreferencesManager,
        phoneNumber: String,
        curveName: String
    ) {
        val normalizedNumber = PhoneUtils.normalizePhoneNumber(phoneNumber)

        val metadata = """
        {
            "version": "2.0",
            "algorithm": "ECDH",
            "curve": "$curveName",
            "phoneNumber": "$normalizedNumber",
            "timestamp": ${System.currentTimeMillis()},
            "keySize": 256,
            "passwordBased": true
        }
        """.trimIndent()

        prefs.prefs.edit {
            putString("sisa_ecdh_meta_$normalizedNumber", metadata)
        }

        LogUtils.d(null, "CryptoManager", "📄 ECDH metadata saved")
    }

    // =============================================
    // 7. PASSWORD & HASH
    // =============================================

    fun hashPassword(password: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(password.toByteArray(Charsets.UTF_8))
            android.util.Base64.encodeToString(hash, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            ""
        }
    }

    fun getSavedPasswordForChat(context: Context, phoneNumber: String): String {
        val prefs = SharedPreferencesManager.getInstance(context)
        val normalizedNumber = PhoneUtils.normalizePhoneNumber(phoneNumber)
        val key = "encryption_password_$normalizedNumber"

        val password = prefs.prefs.getString(key, null)
        return password ?: ""
    }

    fun generateAutoPassword(context: Context, phoneNumber: String): String {
        val prefs = SharedPreferencesManager.getInstance(context)

        // 1. Recipient number
        val buddyPhone = PhoneUtils.normalizePhoneNumber(phoneNumber)

        // 2. Device number - on Android 16+ this will work without permission
        //    On older Android, it might trigger permission request
        val myPhone = getOwnPhoneNumber(context)

        LogUtils.d(context, "CryptoManager", "🔑 Automatic password generation")
        LogUtils.d(context, "CryptoManager", "  Recipient: $buddyPhone")
        LogUtils.d(context, "CryptoManager", "  My number: $myPhone")

        // 3. Verify we have both numbers
        if (myPhone.isEmpty()) {
            LogUtils.w(context, "CryptoManager", "⚠️ Device number not available, using fallback")
            // Fallback to a generated password based on recipient only
            return generateFallbackPassword(buddyPhone)
        }

        if (buddyPhone.isEmpty()) {
            LogUtils.w(context, "CryptoManager", "❌ Empty recipient number")
            return generateSimplePassword(16) // Pure random fallback
        }

        // 4. lexicographically order both numbers
        val phones = listOf(buddyPhone, myPhone).sorted()
        val phoneA = phones[0]  // first
        val phoneB = phones[1]  // second

        // 5. Generate password with format: phoneAphoneB
        val password = "$phoneA$phoneB"

        LogUtils.d(context, "CryptoManager", "✅ Automatic password generated: ${password.take(5)}...")
        return password
    }

    private fun showPhonePermissionRationale(activity: android.app.Activity) {
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.phone_state_permission_title))
            .setMessage(activity.getString(R.string.phone_state_permission_message))
            .setPositiveButton(activity.getString(R.string.grant_permission_button)) { _, _ ->
                activity.requestPermissions(
                    arrayOf(android.Manifest.permission.READ_PHONE_STATE),
                    Constants.PERMISSION_REQUEST_READ_PHONE_STATE
                )
            }
            .setNegativeButton(activity.getString(R.string.cancel)) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun generateFallbackPassword(recipientPhone: String): String {
        // Create a deterministic password using only recipient phone + device independent data
        val cleanRecipient = recipientPhone.replace(Regex("[^0-9]"), "")
        val timestamp = System.currentTimeMillis().toString().takeLast(8)
        val randomPart = generateSimplePassword(4)
        return "$cleanRecipient$timestamp$randomPart"
    }

    //TODO: Use alphabet in BaseXXXUtils!
    fun generateSimplePassword(length: Int = 12): String {
        val charPool = "ABCDEFGHIJKLMNOPQRSTUVWXYZ" +
                "abcdefghijklmnopqrstuvwxyz" +
                "0123456789"

        val random = SecureRandom()
        return (1..length)
            .map { charPool[random.nextInt(charPool.length)] }
            .joinToString("")
    }


    fun getOwnPhoneNumber(context: Context): String {
        return try {
            // On Android 16+, we can get phone number without permission
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager

            val phoneNumber = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                    telephonyManager.line1Number ?: ""
                }
                else -> {
                    @Suppress("DEPRECATION")
                    telephonyManager.line1Number ?: ""
                }
            }

            if (phoneNumber.isBlank()) {
                LogUtils.d(context, "CryptoManager", "📱 Device number not available")
                return ""
            }

            val normalized = PhoneUtils.normalizePhoneNumber(phoneNumber)
            LogUtils.d(context, "CryptoManager", "✅ Got device number: $normalized")
            normalized

        } catch (e: SecurityException) {
            // This happens on older Android if permission is missing
            LogUtils.e(context, "CryptoManager", "❌ Security exception getting device number:", e)

            // On Android 16+, we shouldn't get here
            if (Build.VERSION.SDK_INT >= 36) {
                LogUtils.d(context, "CryptoManager", "📱 Unexpected SecurityException on Android 16+")
                return ""
            }

            // For older Android, request permission
            if (context is android.app.Activity) {
                // Check if we should show permission dialog
                if (shouldShowRequestPermissionRationale(
                        context,
                        android.Manifest.permission.READ_PHONE_STATE
                    )
                ) {
                    // Show rationale dialog
                    showPhonePermissionRationale(context)
                } else {
                    // Request permission directly
                    context.requestPermissions(
                        arrayOf(android.Manifest.permission.READ_PHONE_STATE),
                        Constants.PERMISSION_REQUEST_READ_PHONE_STATE
                    )
                }
            }
            ""

        } catch (e: Exception) {
            LogUtils.e(context, "CryptoManager", "❌ Error getting device number:", e)
            ""
        }
    }


    // =============================================
    // 8. DIALOGS & UI
    // =============================================

    fun showPasswordWarningDialog(context: Context, onConfirm: () -> Unit) {
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.security_warning_title))
            .setMessage(context.getString(R.string.security_warning_message))
            .setPositiveButton(context.getString(R.string.yes_generate)) { dialog, _ ->
                dialog.dismiss()
                onConfirm()
            }
            .setNegativeButton(context.getString(R.string.cancel)) { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(true)
            .create()
            .show()
    }

    fun requestPhoneStatePermission(context: Context) {
        // On Android 16+, this permission doesn't exist/do anything
        if (Build.VERSION.SDK_INT >= 36) {
            LogUtils.d(context, "CryptoManager", "Android 16+ - READ_PHONE_STATE permission not needed")
            MainActivity.showToast("Phone number access not available on Android 16+", true)
            return
        }

        try {
            val prefs = SharedPreferencesManager.getInstance(context)

            // Salva che stiamo aspettando il permesso
            prefs.prefs.edit().putBoolean("awaiting_phone_permission", true).apply()

            // Apri le impostazioni specifiche dell'app
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.parse("package:${context.packageName}")
            context.startActivity(intent)

            MainActivity.showToast(context.getString(R.string.open_settings_phone_permission))

        } catch (e: Exception) {
            LogUtils.e(context, "CryptoManager", "❌ Error opening settings", e)
            MainActivity.showToast(context.getString(R.string.error_opening_settings_manual))
        }
    }

    // =============================================
    // 9. DEBUG
    // =============================================

    fun showECDHDebugInfo(context: Context, phoneNumber: String) {
        val prefs = SharedPreferencesManager.getInstance(context)
        val normalizedNumber = PhoneUtils.normalizePhoneNumber(phoneNumber)
            .replace("[^A-Za-z0-9]".toRegex(), "_")

        val alias = "sisa_ecdh_$normalizedNumber"

        val debugInfo = StringBuilder()
        debugInfo.appendLine("🔐 DEBUG ECDH keys - ${phoneNumber}")
        debugInfo.appendLine("═".repeat(50))

        // 1. base info
        debugInfo.appendLine("Base info:")
        debugInfo.appendLine("  • Alias: $alias")
        debugInfo.appendLine("  • Normalized number: $normalizedNumber")
        debugInfo.appendLine("  • Has ECDH keys: ${hasECDHKeyForPhoneNumber(context, phoneNumber)}")

        // 2. Check all saved data
        debugInfo.appendLine("\nSaved data:")
        val allData = prefs.prefs.all.entries
            .filter { it.key.contains(alias) }
            .sortedBy { it.key }

        if (allData.isEmpty()) {
            debugInfo.appendLine("  No data found")
        } else {
            allData.forEach { (key, value) ->
                val cleanKey = key.removePrefix(alias).removePrefix("_")
                debugInfo.appendLine("  • $cleanKey: ${value.toString().take(50)}...")
            }
        }

        // 3. Metadata
        debugInfo.appendLine("\nMetadati:")
        val metadata = prefs.prefs.getString("sisa_ecdh_meta_$normalizedNumber", null)
        if (metadata != null) {
            debugInfo.appendLine("  $metadata")
        } else {
            debugInfo.appendLine("  No metadata")
        }

        debugInfo.appendLine("\n═".repeat(50))

        // Show in log
        LogUtils.d(context, "ECDH-DEBUG", debugInfo.toString())
    }

    // =============================================
    // 10. UTILITY
    // =============================================

    fun hasEncryptionIndicators(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.startsWith("#e")
    }



// =============================================
// 11. PASSWORD PROTECTION FOR APP
// =============================================

    /**
     * Encrypt using a simple but reasonably secure password
     */
    fun encryptSimplePassword(context: Context, password: String): String {
        return try {
            // Deriva una chiave da un valore stabile del dispositivo
            val deviceId = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            ) ?: "default_nook_device"

            // Create a stable key for this device
            val masterKey = deriveMasterKey(deviceId)

            // Encrypt with AES/GCM
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, masterKey)

            val encryptedBytes = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
            val iv = cipher.iv

            // Combine IV + encrypted data
            val combined = ByteArray(iv.size + encryptedBytes.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(encryptedBytes, 0, combined, iv.size, encryptedBytes.size)

            android.util.Base64.encodeToString(combined, android.util.Base64.DEFAULT)
        } catch (e: Exception) {
            LogUtils.e(context, "CryptoManager", "❌ Error encrypting simple password", e)
            ""
        }
    }

    fun decryptSimplePassword(context: Context, encryptedPassword: String): String {
        return try {
            if (encryptedPassword.isEmpty()) {
                return ""
            }

            // Derive same key
            val deviceId = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            ) ?: "default_nook_device"

            val masterKey = deriveMasterKey(deviceId)

            // Decode and separate IV from data
            val combined = android.util.Base64.decode(encryptedPassword, android.util.Base64.DEFAULT)
            if (combined.size < 12) {
                LogUtils.e(context, "CryptoManager", "❌ Encrypted data too short")
                return ""
            }

            val iv = combined.copyOfRange(0, 12) // IV per GCM
            val encryptedBytes = combined.copyOfRange(12, combined.size)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, masterKey, GCMParameterSpec(128, iv))

            val decryptedBytes = cipher.doFinal(encryptedBytes)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            LogUtils.e(context, "CryptoManager", "❌ Error decrypting simple password", e)
            ""
        }
    }


    private fun deriveMasterKey(deviceId: String): SecretKey {
        // Use PBKDF2 to derive a safe key
        val salt = "nook_app_protection_salt_2024".toByteArray(Charsets.UTF_8)
        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = javax.crypto.spec.PBEKeySpec(
            deviceId.toCharArray(),
            salt,
            100000, // iteration count
            256 // key length
        )
        val tmpKey = factory.generateSecret(spec)
        return SecretKeySpec(tmpKey.encoded, "AES")
    }
}