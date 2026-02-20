package solutions.semweb.nook.keyboards

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import solutions.semweb.nook.Constants
import solutions.semweb.nook.LogUtils
import solutions.semweb.nook.MainActivity
import solutions.semweb.nook.R
import solutions.semweb.nook.SharedPreferencesManager
import java.lang.reflect.Type
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class KeyboardSafetyManager(private val context: Context) {

    private val prefs = SharedPreferencesManager.getInstance(context)
    private val gson = Gson()

    // Dynamic Whitelist and Blacklist
    private var userWhitelist: MutableSet<String> = mutableSetOf()
    private var userBlacklist: MutableSet<String> = mutableSetOf()
    private var userDecisions: MutableMap<String, KeyboardDecision> = mutableMapOf()

    // From user removed keyboards:
    private val removedDefaultKeyboards: MutableSet<String> = mutableSetOf()

    // Users decisions for keyboards:
    data class KeyboardDecision(
        val packageId: String,
        val decision: DecisionType,
        val timestamp: Long,
        val comment: String? = null,
        val userName: String = "user"
    ) {
        enum class DecisionType {
            APPROVED,       // User has approved the keyboard
            REJECTED,       // User has rejected the keyboard
            IGNORED         // User has ignored the keyboard
        }

    }

    enum class KeyboardSafetyStatus {
        APPROVED,
        REJECTED,
        IGNORED
    }
    companion object {
        private const val PREF_KEY_FIRST_KEYBOARD_CHECK = "first_keyboard_check_done"
        private const val PREF_KEY_LAST_KEYBOARD_ID = "detected_keyboard_id"
        private const val PREF_KEY_REMOVED_DEFAULT_KEYBOARDS = "removed_default_keyboards"
    }

    private var keyboardChangeReceiver: KeyboardChangeReceiver? = null

    init {
        loadKeyboardLists()
        setupKeyboardChangeMonitoring()
    }

    private fun loadKeyboardLists() {
        // Load user whitelist
        val whitelistJson = prefs.prefs.getString(Constants.KEY_KEYBOARD_WHITELIST, "[]")
        val whitelistType: Type = object : TypeToken<Set<String>>() {}.type
        userWhitelist = gson.fromJson(whitelistJson, whitelistType) ?: mutableSetOf()

        // Load user blacklist
        val blacklistJson = prefs.prefs.getString(Constants.KEY_KEYBOARD_BLACKLIST, "[]")
        val blacklistType: Type = object : TypeToken<Set<String>>() {}.type
        userBlacklist = gson.fromJson(blacklistJson, blacklistType) ?: mutableSetOf()

        // Load user decisions
        val decisionsJson = prefs.prefs.getString(Constants.KEY_KEYBOARD_USER_DECISIONS, "{}")
        val decisionsType: Type = object : TypeToken<Map<String, KeyboardDecision>>() {}.type
        userDecisions = gson.fromJson(decisionsJson, decisionsType) ?: mutableMapOf()

        // Load list of removed default keyboards
        val removedJson = prefs.prefs.getString(PREF_KEY_REMOVED_DEFAULT_KEYBOARDS, "[]")
        val removedType: Type = object : TypeToken<Set<String>>() {}.type
        removedDefaultKeyboards.clear()
        removedDefaultKeyboards.addAll(gson.fromJson(removedJson, removedType) ?: mutableSetOf())

        // Add default (non removed) keyboards
        Constants.DEFAULT_WHITELISTED_KEYBOARDS.forEach {
            if (!removedDefaultKeyboards.contains(it) && !userWhitelist.contains(it)) {
                userWhitelist.add(it)
            }
        }

        // Add default keyboards if not removed
        Constants.DEFAULT_BLACKLISTED_KEYBOARDS.forEach {
            if (!removedDefaultKeyboards.contains(it) && !userBlacklist.contains(it)) {
                userBlacklist.add(it)
            }
        }
    }

    private fun saveKeyboardLists() {
        val editor = prefs.prefs.edit()

        // Save whitelist
        val whitelistJson = gson.toJson(userWhitelist)
        editor.putString(Constants.KEY_KEYBOARD_WHITELIST, whitelistJson)

        // Save blacklist
        val blacklistJson = gson.toJson(userBlacklist)
        editor.putString(Constants.KEY_KEYBOARD_BLACKLIST, blacklistJson)

        // Save decisions
        val decisionsJson = gson.toJson(userDecisions)
        editor.putString(Constants.KEY_KEYBOARD_USER_DECISIONS, decisionsJson)

        // Save list of removed keyboards
        val removedJson = gson.toJson(removedDefaultKeyboards)
        editor.putString(PREF_KEY_REMOVED_DEFAULT_KEYBOARDS, removedJson)

        editor.apply()
    }

    fun reloadKeyboardLists() {
        loadKeyboardLists()
    }

    fun checkKeyboardSafety(showAlert: Boolean = true, forceCheck: Boolean = false): KeyboardSafetyStatus {
        val currentKeyboardId = getCurrentKeyboardId() ?: return KeyboardSafetyStatus.IGNORED

        // if forceCheck, bypass first start check
        if (!forceCheck && prefs.getBoolean(PREF_KEY_FIRST_KEYBOARD_CHECK, false)) {
            val lastKeyboardId = prefs.prefs.getString(PREF_KEY_LAST_KEYBOARD_ID, null)
            if (lastKeyboardId == currentKeyboardId) {
                return KeyboardSafetyStatus.APPROVED // Same keyboard, no check necessary
            }
        }

        // Save last detected keyboard
        prefs.prefs.edit { putString(PREF_KEY_LAST_KEYBOARD_ID, currentKeyboardId) }
        val keyboardName = getKeyboardDisplayName()

        // 1. CHeck whether user has already taken a cecision for this keyboard
        val userDecision = userDecisions[currentKeyboardId]
        if (userDecision != null) {
            when (userDecision.decision) {
                KeyboardDecision.DecisionType.APPROVED -> {
                    // User has approved this keyboard
                    markFirstCheckDone()
                    return KeyboardSafetyStatus.APPROVED
                }
                KeyboardDecision.DecisionType.REJECTED -> {
                    // User has rejected this keyboard
                    if (showAlert) {
                        showKeyboardSecurityAlert(
                            isBlacklisted = true,
                            keyboardId = currentKeyboardId,
                            keyboardName = keyboardName,
                            showUserOptions = true
                        )
                    }
                    markFirstCheckDone()
                    return KeyboardSafetyStatus.REJECTED
                }
                KeyboardDecision.DecisionType.IGNORED -> {
                    // User has chosen to ignore keyboard (for now)
                    if (showAlert) {
                        showKeyboardSecurityAlert(
                            isBlacklisted = false,
                            keyboardId = currentKeyboardId,
                            keyboardName = keyboardName,
                            showUserOptions = true
                        )
                    }
                    markFirstCheckDone()
                    return KeyboardSafetyStatus.IGNORED
                }
            }
        }

        // 2. Check blacklist (user + default)
        if (userBlacklist.contains(currentKeyboardId)) {
            if (showAlert) {
                showKeyboardSecurityAlert(
                    isBlacklisted = true,
                    keyboardId = currentKeyboardId,
                    keyboardName = keyboardName,
                    showUserOptions = true
                )
            }
            markFirstCheckDone()
            return KeyboardSafetyStatus.REJECTED
        }

        // 3. Check whitelist (user + default)
        if (userWhitelist.contains(currentKeyboardId)) {
            markFirstCheckDone()
            return KeyboardSafetyStatus.APPROVED
        }

        // 4. Unknown keyboard - show alert with options
        if (showAlert) {
            showKeyboardSecurityAlert(
                isBlacklisted = false,
                keyboardId = currentKeyboardId,
                keyboardName = keyboardName,
                showUserOptions = true
            )
        }

        markFirstCheckDone()
        return KeyboardSafetyStatus.IGNORED
    }

    fun showKeyboardSecurityAlert(
        isBlacklisted: Boolean,
        keyboardId: String,
        keyboardName: String,
        showUserOptions: Boolean = true
    ) {
        try {
            val title = if (isBlacklisted) {
                context.getString(R.string.keyboard_security_alert_blacklisted_title)
            } else {
                context.getString(R.string.keyboard_security_alert_unknown_title)
            }

            val message = if (isBlacklisted) {
                context.getString(R.string.keyboard_security_alert_blacklisted_message, keyboardName, keyboardId)
            } else {
                context.getString(R.string.keyboard_security_alert_unknown_message, keyboardName, keyboardId)
            }

            val alertDialog = AlertDialog.Builder(context)
                .setTitle(title)
                .setMessage(message)
                .setCancelable(false)

            if (showUserOptions) {
                alertDialog
                    .setPositiveButton(context.getString(R.string.approve_keyboard)) { dialog, _ ->
                        approveKeyboard(keyboardId, keyboardName)
                        dialog.dismiss()
                    }
                    .setNeutralButton(context.getString(R.string.ignore_once)) { dialog, _ ->
                        ignoreKeyboardOnce(keyboardId, keyboardName)
                        dialog.dismiss()
                    }
                    .setNegativeButton(context.getString(R.string.reject_keyboard)) { dialog, _ ->
                        rejectKeyboard(keyboardId, keyboardName)
                        dialog.dismiss()
                    }
            } else {
                alertDialog.setPositiveButton(context.getString(R.string.ok)) { dialog, _ ->
                    dialog.dismiss()
                }
            }

            alertDialog.show()

        } catch (e: Exception) {
            // Fallback
        }
    }

    private fun approveKeyboard(packageId: String, keyboardName: String) {
        // Add to user whitelist
        userWhitelist.add(packageId)

        // Remove from removed default if exists
        removedDefaultKeyboards.remove(packageId)

        // Save user decisions
        val decision = KeyboardDecision(
            packageId = packageId,
            decision = KeyboardDecision.DecisionType.APPROVED,
            timestamp = System.currentTimeMillis(),
            comment = context.getString(R.string.manually_approved_by_user, getCurrentDate()),
            userName = context.getString(R.string.user )
        )
        userDecisions[packageId] = decision

        saveKeyboardLists()

        MainActivity.showToast(context.getString(R.string.added_to_keyboards_whitelist, keyboardName), false)
    }

    private fun rejectKeyboard(packageId: String, keyboardName: String) {
        // Add to user blacklist
        userBlacklist.add(packageId)

        // Remove from list of removed default ones if present
        removedDefaultKeyboards.remove(packageId)

        // Remove from whitelist if existing
        userWhitelist.remove(packageId)

        // Save user decision
        val decision = KeyboardDecision(
            packageId = packageId,
            decision = KeyboardDecision.DecisionType.REJECTED,
            timestamp = System.currentTimeMillis(),
            comment = context.getString(R.string.manually_rejected_by_user, getCurrentDate()),
            userName = context.getString(R.string.user )
        )
        userDecisions[packageId] = decision

        saveKeyboardLists()

        MainActivity.showToast(context.getString(R.string.added_to_keyboards_blacklist, keyboardName), false)
    }

    private fun ignoreKeyboardOnce(packageId: String, keyboardName: String) {
        // Salva che l'utente ha ignorato per questa sessione
        val decision = KeyboardDecision(
            packageId = packageId,
            decision = KeyboardDecision.DecisionType.IGNORED,
            timestamp = System.currentTimeMillis(),
            comment = context.getString(R.string.remporary_ignored_by_user, getCurrentDate()),
            userName = context.getString(R.string.user )
        )
        userDecisions[packageId] = decision

        saveKeyboardLists()

        MainActivity.showToast(context.getString(R.string.keyboard_temporarily_ignored, keyboardName), false)
    }

    // Metodi pubblici per gestire le tastiere manualmente
    fun addToUserWhitelist(packageId: String, comment: String? = null) {
        userWhitelist.add(packageId)
        userBlacklist.remove(packageId)

        // Rimuovi dalla lista delle default rimosse
        removedDefaultKeyboards.remove(packageId)

        val decision = KeyboardDecision(
            packageId = packageId,
            decision = KeyboardDecision.DecisionType.APPROVED,
            timestamp = System.currentTimeMillis(),
            comment = context.getString(R.string.keyboard_added_manually_to_whitelist),
            userName = context.getString(R.string.user )
        )
        userDecisions[packageId] = decision

        saveKeyboardLists()

        notifyWhitelistChanged()
    }

    private fun notifyWhitelistChanged() {
        try {
            val intent = Intent(Constants.mainpackage+".KEYBOARD_WHITELIST_CHANGED")
            // Per Android 13+ (API 33+), non esportare il receiver
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            LogUtils.e(context, "KeyboardSafetyManager", "Error Keyboards Whitelist Notification", e)
        }
    }

    fun addToUserBlacklist(packageId: String, comment: String? = null) {
        userBlacklist.add(packageId)
        userWhitelist.remove(packageId)

        // Rimuovi dalla lista delle default rimosse
        removedDefaultKeyboards.remove(packageId)

        val decision = KeyboardDecision(
            packageId = packageId,
            decision = KeyboardDecision.DecisionType.REJECTED,
            timestamp = System.currentTimeMillis(),
            comment = context.getString(R.string.keyboard_added_manually_to_blacklist),
            userName = context.getString(R.string.user )
        )
        userDecisions[packageId] = decision

        saveKeyboardLists()
    }

    fun getUserApprovedKeyboards(): List<Pair<String, String>> {
        // Return all keyboards out of the whitelist
        return userWhitelist.map { id ->
            id to getKeyboardDisplayNameById(id)
        }.sortedBy { it.second }
    }

    fun getUserRejectedKeyboards(): List<Pair<String, String>> {
        return userDecisions
            .filter { it.value.decision == KeyboardDecision.DecisionType.REJECTED }
            .map { it.key to getKeyboardDisplayNameById(it.key) }
            .sortedBy { it.second }
    }

    fun getKeyboardInfo(packageId: String): String {
        val decision = userDecisions[packageId]
        val displayName = getKeyboardDisplayNameById(packageId)
        val isDefaultWhite = Constants.DEFAULT_WHITELISTED_KEYBOARDS.contains(packageId)
        val isDefaultBlack = Constants.DEFAULT_BLACKLISTED_KEYBOARDS.contains(packageId)
        val isRemovedDefault = removedDefaultKeyboards.contains(packageId)

        return buildString {
            appendLine("📱 "+context.getString(R.string.keyboard)+": $displayName")
            appendLine("📦 "+context.getString(R.string.packageId)+": $packageId")
            appendLine("")
            appendLine("📊 "+context.getString(R.string.status)+":")
            appendLine("  • "+context.getString(R.string.user_whitelist)+": ${userWhitelist.contains(packageId)}")
            appendLine("  • "+context.getString(R.string.user_blacklist)+": ${userBlacklist.contains(packageId)}")
            appendLine("  • "+context.getString(R.string.user_default_whitelist)+": $isDefaultWhite")
            appendLine("  • "+context.getString(R.string.user_default_blacklist)+": $isDefaultBlack")
            appendLine("  • "+context.getString(R.string.removed_default_keyboards)+": $isRemovedDefault")
            appendLine("")

            if (decision != null) {
                appendLine(context.getString(R.string.user_decision)+":")
                appendLine(context.getString(R.string.user_decision_type)+": ${decision.decision}")
                appendLine(context.getString(R.string.user_decision_date)+": ${formatDate(decision.timestamp)}")
                decision.comment?.let { appendLine(context.getString(R.string.user_decision_comment)+": $it") }
            } else {
                appendLine(context.getString(R.string.user_nodecision))
            }
        }
    }

    // Helpers
    private fun getCurrentDate(): String {
        val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        return sdf.format(Date())
    }

    private fun formatDate(timestamp: Long): String {
        val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    fun getKeyboardDisplayNameById(packageId: String): String {
        return when {
            packageId.contains("google", ignoreCase = true) -> "Gboard"
            packageId.contains("samsung", ignoreCase = true) -> "Samsung Keyboard"
            packageId.contains("swiftkey", ignoreCase = true) -> "SwiftKey"
            packageId.contains("honeyboard", ignoreCase = true) -> "Samsung Keyboard"
            packageId.contains("android.inputmethod.latin", ignoreCase = true) -> "Android Keyboard"
            packageId.contains("pckeyboard", ignoreCase = true) -> "Hacker's Keyboard"
            else -> packageId.substringAfterLast(".")
        }
    }

    public fun getCurrentKeyboardId(): String? {
        return try {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD
            )?.let { defaultInputMethod ->
                defaultInputMethod.split("/").firstOrNull()
            }
        } catch (e: Exception) {
            null
        }
    }

    fun getKeyboardDisplayName(): String {
        val keyboardId = getCurrentKeyboardId() ?: return context.getString(R.string.unknown_keyboard)
        return getKeyboardDisplayNameById(keyboardId)
    }

    private fun markFirstCheckDone() {
        prefs.putBoolean(PREF_KEY_FIRST_KEYBOARD_CHECK, true)
    }

    fun resetCheck() {
        prefs.putBoolean(PREF_KEY_FIRST_KEYBOARD_CHECK, false)
        prefs.prefs.edit().remove(PREF_KEY_LAST_KEYBOARD_ID).apply()
    }

    fun cleanup() {
        keyboardChangeReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: Exception) {
                // Ignore if not registered
            }
        }
    }

    // Inner class to receive keyboard changes
    private class KeyboardChangeReceiver(private val onKeyboardChanged: () -> Unit) : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Constants.ACTION_KEYBOARD_CHANGED) {
                onKeyboardChanged()
            }
        }
    }

    private fun setupKeyboardChangeMonitoring() {
        keyboardChangeReceiver = KeyboardChangeReceiver { onKeyboardChanged() }

        val filter = IntentFilter(Constants.ACTION_KEYBOARD_CHANGED)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // Android 13+ (API 33+): requests RECEIVER_EXPORTED or RECEIVER_NOT_EXPORTED
                    context.registerReceiver(
                        keyboardChangeReceiver,
                        filter,
                        Context.RECEIVER_EXPORTED
                    )
                } else {
                    // Android 8-12: without export flag
                    @Suppress("DEPRECATION")
                    ContextCompat.registerReceiver(
                        context,
                        keyboardChangeReceiver,
                        filter,
                        ContextCompat.RECEIVER_NOT_EXPORTED
                    )
                }
            } else {
                // Android < 8: original version
                @Suppress("DEPRECATION")
                ContextCompat.registerReceiver(
                    context,
                    keyboardChangeReceiver,
                    filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
            }

            LogUtils.d(context, "KeyboardSafetyManager", "Receiver per cambiamenti tastiera registrato")
        } catch (e: Exception) {
            LogUtils.e(context, "KeyboardSafetyManager", "Errore registrazione receiver", e)
        }
    }

    fun removeKeyboardCompletely(packageId: String) {
        userWhitelist.remove(packageId)
        userBlacklist.remove(packageId)
        userDecisions.remove(packageId)

        // If it is a default keyboard add it to the list of default removed
        if (Constants.DEFAULT_WHITELISTED_KEYBOARDS.contains(packageId) ||
            Constants.DEFAULT_BLACKLISTED_KEYBOARDS.contains(packageId)) {
            removedDefaultKeyboards.add(packageId)
        }

        saveKeyboardLists()

        LogUtils.d(context, "KeyboardSafetyManager", "Keyboard $packageId removed completely")
    }

    fun clearAllKeyboardDecisions() {
        userWhitelist.clear()
        userBlacklist.clear()
        userDecisions.clear()
        removedDefaultKeyboards.clear()

        prefs.prefs.edit()
            .remove(Constants.KEY_KEYBOARD_WHITELIST)
            .remove(Constants.KEY_KEYBOARD_BLACKLIST)
            .remove(Constants.KEY_KEYBOARD_USER_DECISIONS)
            .remove(PREF_KEY_REMOVED_DEFAULT_KEYBOARDS)
            .apply()

        // Reload lists
        loadKeyboardLists()

        LogUtils.d(context, "KeyboardSafetyManager", "All user decisions deleted")
    }

    fun resetToDefaults() {
        userWhitelist.clear()
        userBlacklist.clear()
        userDecisions.clear()
        removedDefaultKeyboards.clear()

        prefs.prefs.edit()
            .remove(Constants.KEY_KEYBOARD_WHITELIST)
            .remove(Constants.KEY_KEYBOARD_BLACKLIST)
            .remove(Constants.KEY_KEYBOARD_USER_DECISIONS)
            .remove(PREF_KEY_REMOVED_DEFAULT_KEYBOARDS)
            .apply()

        // Save only the default lists
        val editor = prefs.prefs.edit()
        editor.putString(Constants.KEY_KEYBOARD_WHITELIST, gson.toJson(Constants.DEFAULT_WHITELISTED_KEYBOARDS))
        editor.putString(Constants.KEY_KEYBOARD_BLACKLIST, gson.toJson(Constants.DEFAULT_BLACKLISTED_KEYBOARDS))
        editor.apply()

        // Reload lists
        loadKeyboardLists()

        LogUtils.d(context, "KeyboardSafetyManager", "All default lists reloaded")
    }

    private fun onKeyboardChanged() {
        Handler(Looper.getMainLooper()).postDelayed({
            checkKeyboardSafety(true, true)
        }, 500)
    }


}
