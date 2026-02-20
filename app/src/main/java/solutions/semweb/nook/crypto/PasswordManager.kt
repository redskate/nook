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

        val password = prefs.prefs.getString(passwordKey, null)

        if (!password.isNullOrEmpty()) {
            LogUtils.d(context, "PasswordManager", "✅ Password found in preferences")
            return password
        }

        LogUtils.e(context, "PasswordManager", "❌ Password not found in preferences for: $passwordKey")
        return ""
    }

}