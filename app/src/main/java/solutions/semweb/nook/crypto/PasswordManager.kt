package solutions.semweb.nook.crypto

import android.content.Context
import solutions.semweb.nook.LogUtils
import solutions.semweb.nook.PhoneUtils
import solutions.semweb.nook.SharedPreferencesManager

object PasswordManager {

    fun getStoredPassword(context: Context, phoneNumber: String): String {
        val prefs = SharedPreferencesManager.getInstance(context)
        val normalizedNumber = PhoneUtils.normalizePhoneNumber(phoneNumber)
        val passwordKey = "encryption_password_$normalizedNumber"

        val encryptedPassword = prefs.prefs.getString(passwordKey, null)

        if (encryptedPassword.isNullOrEmpty()) {
            LogUtils.e(context, "PasswordManager", "❌ Password not found in preferences for: $passwordKey")
            return ""
        }

        try {
            // Decrypt the password
            val decryptedPassword = CryptoManager.decryptSimplePassword(context, encryptedPassword)

            if (decryptedPassword.isNotEmpty() && decryptedPassword != encryptedPassword) {
                LogUtils.d(context, "PasswordManager", "✅ Password successfully decrypted")
                return decryptedPassword
            } else {
                LogUtils.e(context, "PasswordManager", "❌ Password decryption returned invalid result")
                return ""
            }
        } catch (e: Exception) {
            LogUtils.e(context, "PasswordManager", "❌ Failed to decrypt password", e)
            return ""
        }
    }

}