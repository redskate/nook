package solutions.semweb.nook

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import solutions.semweb.nook.crypto.AppCryptoManager
import solutions.semweb.nook.crypto.EncryptionMapper
import solutions.semweb.nook.data.database.DatabaseActor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class SharedPreferencesManager private constructor(private var context: Context) {
    private val appContext: Context = context.applicationContext
    private val gson = Gson()

    private val databaseActor by lazy {
        LogUtils.d(appContext, "SharedPreferencesManager", "🔍 LAZY creation of DatabaseActor")
        DatabaseActor.getInstance(appContext)
    }

    // Scope per coroutine (minimale)
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Prefs (normali o criptate)
    val prefs: SharedPreferences by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        // Controlla se la crittografia è pronta
        val isCryptoReady = AppCryptoManager.isEncryptionActive()

        if (isCryptoReady) {
            LogUtils.d(appContext, "SharedPreferencesManager", "📁 Creation of crypted SecurePreferences")
            AppCryptoManager.createSecurePreferences(appContext)
        } else {
            LogUtils.w(appContext, "SharedPreferencesManager", "⚠️ Cryptography not ready - using fallback")
            createFallbackPreferences()
        }
    }

    // ==================== SINGLETON ====================
    companion object {
        @Volatile
        private var INSTANCE: SharedPreferencesManager? = null
        private val encryptionInitialized = AtomicBoolean(false)

        fun getInstance(context: Context): SharedPreferencesManager {

            return INSTANCE ?: synchronized(this) {
                LogUtils.e("SHARED_PREFS", "🔒 Entered in synchronized block")

                INSTANCE ?: SharedPreferencesManager(context.applicationContext).also {
                    INSTANCE = it
                    LogUtils.e("SHARED_PREFS", "✅ Singleton instance created")

                    if (!encryptionInitialized.get()) {
                        Log.d("SHARED_PREFS", "🔐 Start init syncronous encryption...")
                        val cryptoOk = AppCryptoManager.initializeSync(context.applicationContext)
                        LogUtils.e("SHARED_PREFS", "🔐 SYNCRONOUS ENCRYPTION: ${if (cryptoOk) "✅ OK" else "❌ failed"}")
                        encryptionInitialized.set(true)
                    } else {
                        LogUtils.e("SHARED_PREFS", "🔐 SYNCRONOUS ENCRYPTION already initialized")
                    }
                }
            }
        }
    }
    // ===================================================

    init {
        LogUtils.d(context, "SharedPreferencesManager", "🔐 Singleton created with synchronous init")
    }

    private fun createFallbackPreferences(): SharedPreferences {
        return appContext.getSharedPreferences(
            "nook_prefs_fallback",
            Context.MODE_PRIVATE
        )
    }

    // App Protection
    var appProtectionEnabled: Boolean
        get() = prefs.getBoolean(Constants.KEY_APP_PROTECTION_ENABLED, false)
        set(value) = prefs.edit().putBoolean(Constants.KEY_APP_PROTECTION_ENABLED, value).apply()

    var appProtectionPassword: String
        get() = prefs.getString(Constants.KEY_APP_PROTECTION_PASSWORD, "") ?: ""
        set(value) = prefs.edit().putString(Constants.KEY_APP_PROTECTION_PASSWORD, value).apply()

    // App Protection Timeout
    var appProtectionTimeout: Int
        get() = prefs.getInt(Constants.KEY_APP_PROTECTION_TIMEOUT, Constants.DEFAULT_PROTECTION_TIMEOUT)
        set(value) {
            LogUtils.e("SHARED_PREFS", "📱 SET appProtectionTimeout: $value seconds")
            prefs.edit().putInt(Constants.KEY_APP_PROTECTION_TIMEOUT, value).apply()
        }

    var lastActiveTime: Long
        get() = prefs.getLong(Constants.KEY_LAST_ACTIVE_TIME, System.currentTimeMillis())
        set(value) {
            prefs.edit().putLong(Constants.KEY_LAST_ACTIVE_TIME, value).apply()
        }

    // App Protection Lock State
    var isAppLocked: Boolean
        get() = prefs.getBoolean("is_app_locked", false)
        set(value) = prefs.edit().putBoolean("is_app_locked", value).apply()

    // =============================================
    // 1. TRUSTED CONTACTS
    // =============================================

    var trustedContacts: List<TrustedContact>
        get() {
            return try {
                // Versione sincrona
                runBlocking {
                    databaseActor.getTrustedContacts().map { contact ->
                        TrustedContact(
                            contactId = contact.contactId,
                            phoneNumber = contact.phoneNumber,
                            displayName = contact.displayName,
                            isActive = contact.isActive
                        )
                    }
                }
            } catch (e: Exception) {
                LogUtils.e(context, "SharedPreferencesManager", "❌ Error reading trusted contacts", e)
                emptyList()
            }
        }
        set(value) {
            // Saves asynchronously without blocking
            ioScope.launch {
                value.forEach { contact ->
                    databaseActor.saveTrustedContact(contact)
                }
            }
            LogUtils.w(context, "SharedPreferencesManager", "⚠️ trustedContacts SET in background")
        }

    fun getActiveTrustedContacts() = trustedContacts.filter { it.isActive }

    fun getActiveTrustedNumbers(): Set<String> = getActiveTrustedContacts()
        .map { PhoneUtils.normalizePhoneNumber(it.phoneNumber) }
        .toSet()

    fun addTrustedContact(contact: TrustedContact) {
        LogUtils.d(context, "SharedPreferencesManager", "📱 addTrustedContact: ${contact}")
        ioScope.launch {
            databaseActor.saveTrustedContact(contact)
        }
    }

    fun removeTrustedContact(contactId: String) {
        LogUtils.d(context, "SharedPreferencesManager", "📱 removeTrustedContact: $contactId")
        ioScope.launch {
            databaseActor.removeTrustedContact(contactId)
        }
    }

    fun setTrustedContactActive(contactId: String, isActive: Boolean) {
        LogUtils.d(null, "SharedPreferencesManager", "📱 setTrustedContactActive: $contactId = $isActive")
        trustedContacts = trustedContacts.map {
            if (it.contactId == contactId) it.copy(isActive = isActive) else it
        }
    }

    fun clearTrustedContacts() {
        LogUtils.d(null, "SharedPreferencesManager", "📱 clearTrustedContacts")
        trustedContacts = emptyList()
    }

    // =============================================
    // 2. APP SETTINGS
    // =============================================

    var appOwnerName: String
        get() {
            // first try reading SharedPreferences
            val prefsName = prefs.getString(Constants.KEY_APP_OWNER_NAME, "")
            if (!prefsName.isNullOrEmpty()) {
                LogUtils.d(null, "SharedPreferencesManager", "📱 GET appOwnerName from prefs: $prefsName")
                return prefsName
            }

            // if not found, try in database
            return try {
                runBlocking {
                    val dbName = databaseActor.getSetting(Constants.KEY_APP_OWNER_NAME)
                    if (dbName.isNullOrEmpty()) {
                        context.getString(R.string.appowner)
                    } else {
                        dbName
                    }
                }
            } catch (e: Exception) {
                LogUtils.e(context, "SharedPreferencesManager", "❌ Error reading appOwnerName", e)
                context.getString(R.string.appowner)
            }
        }
        set(value) {
            LogUtils.d(null, "SharedPreferencesManager", "📱 SET appOwnerName: $value")

            // Salva sia nelle SharedPreferences che nel database
            prefs.edit().putString(Constants.KEY_APP_OWNER_NAME, value).apply()

            ioScope.launch {
                databaseActor.saveSetting(Constants.KEY_APP_OWNER_NAME, value)
            }
        }

    var decodingScheme: String
        get() {
            val scheme = prefs.getString(Constants.KEY_DECODING_SCHEME, EncryptionMapper.DEFAULT_ENCODING)
                ?: EncryptionMapper.DEFAULT_ENCODING
            LogUtils.d(null, "SharedPreferencesManager", "📱 GET decodingScheme: $scheme")
            return scheme
        }
        set(value) {
            LogUtils.d(null, "SharedPreferencesManager", "📱 SET decodingScheme: $value")
            prefs.edit().putString(Constants.KEY_DECODING_SCHEME, value).apply()
        }

    var silentMode: Boolean
        get() {
            val mode = prefs.getBoolean(Constants.KEY_SILENT_MODE, false)
            LogUtils.d(null, "SharedPreferencesManager", "📱 GET silentMode: $mode")
            return mode
        }
        set(value) {
            LogUtils.d(null, "SharedPreferencesManager", "📱 SET silentMode: $value")
            prefs.edit().putBoolean(Constants.KEY_SILENT_MODE, value).apply()
        }

    var logEnabled: Boolean
        get() {
            // Changed from false to true for fresh installations
            val enabled = prefs.getBoolean(Constants.KEY_LOG_ENABLED, true)
            LogUtils.d(null, "SharedPreferencesManager", "📱 GET logEnabled: $enabled")
            return enabled
        }
        set(value) {
            LogUtils.d(null, "SharedPreferencesManager", "📱 SET logEnabled: $value")
            prefs.edit().putBoolean(Constants.KEY_LOG_ENABLED, value).apply()
        }

    var useAllContacts: Boolean
        get() {
            val useAll = prefs.getBoolean(Constants.KEY_USE_ALL_CONTACTS, false)
            LogUtils.d(null, "SharedPreferencesManager", "📱 GET useAllContacts: $useAll")
            return useAll
        }
        set(value) {
            LogUtils.d(null, "SharedPreferencesManager", "📱 SET useAllContacts: $value")
            prefs.edit().putBoolean(Constants.KEY_USE_ALL_CONTACTS, value).apply()
        }

    var useClipboard: Boolean
        get() = prefs.getBoolean(Constants.KEY_USE_CLIPBOARD, false)
        set(value) = prefs.edit().putBoolean(Constants.KEY_USE_CLIPBOARD, value).apply()

    var allowScreenshots: Boolean
        get() = prefs.getBoolean(Constants.KEY_ALLOW_SCREENSHOTS, false)
        set(value) = prefs.edit().putBoolean(Constants.KEY_ALLOW_SCREENSHOTS, value).apply()

    // =============================================
    // 13.Management Message order (MSG_SEQ)
    // =============================================

    var msgSeq: Int
        get() {
            val seq = prefs.getInt(Constants.KEY_MSG_SEQ, Constants.MSG_SEQ)
            // LogUtils.d(null, "SharedPreferencesManager", "📱 GET msgSeq: $seq")
            return seq
        }
        set(value) {
            // LogUtils.d(null, "SharedPreferencesManager", "📱 SET msgSeq: $value")
            prefs.edit().putInt(Constants.KEY_MSG_SEQ, value).apply()

            // Salva anche nel database per persistenza
            ioScope.launch {
                databaseActor.saveSetting("msg_seq", value.toString())
            }
        }

    // =============================================
    // 4. DECODED HISTORY
    // =============================================

    data class DecodedMessage(
        val decodedMessage: String,
        val sender: String,
        val trans_timestamp: Long,
        val timestamp: Long,
        val success: Boolean,
        val senderName: String? = null
    )

    fun saveDecodedMessage(message: DecodedMessage) {
        val current = getDecodedHistory().toMutableList()
        current.add(message)
        if (current.size > 100) current.removeAt(0)

        prefs.edit().putString("decoded_history", gson.toJson(current)).apply()
    }

    fun getDecodedHistory(): List<DecodedMessage> = prefs.getString("decoded_history", null)
        ?.let { json -> gson.fromJson(json, decodedHistoryType) }
        ?: emptyList()




    // =============================================
    // 6. CHAT CONVERSATIONS
    // =============================================

    fun getChatConversations(): String {
        return try {
            runBlocking {
                val conversations = databaseActor.getChatConversations()
                gson.toJson(conversations)
            }
        } catch (e: Exception) {
            LogUtils.e(context, "SharedPreferencesManager", "❌ Error reading chat", e)
            "[]"
        }
    }

    fun saveChatConversations(json: String) {
        LogUtils.w(context, "SharedPreferencesManager", "⚠️ saveChatConversations not supported - use DatabaseManager")
    }

    fun updateChatName(phoneNumber: String, newName: String?) {
        try {
            val conversations = gson.fromJson<List<ChatConversation>>(
                getChatConversations(),
                chatConversationsType
            ) ?: return

            val updated = conversations.map {
                if (it.phoneNumber == phoneNumber) it.copy(contactName = newName) else it
            }

            val json = gson.toJson(updated)
            saveChatConversations(json)

            LogUtils.d(null, "SharedPreferencesManager", "✅ Chat name updated: $phoneNumber -> $newName")
        } catch (e: Exception) {
            LogUtils.e(null, "SharedPreferencesManager", "❌ Error updating chat name", e)
        }
    }


    fun saveDecodedMessageToHistory(
        originalMessage: String,
        decodedMessage: String,
        sender: String,
        senderName: String? = null,
        timestamp: Long = System.currentTimeMillis(),
        success: Boolean,
        decodingScheme: String
    ) {
        ioScope.launch {
            databaseActor.saveDecodedMessage(
                originalMessage = originalMessage,
                decodedMessage = decodedMessage,
                sender = sender,
                senderName = senderName,
                timestamp = timestamp,
                success = success,
                decodingScheme = decodingScheme
            )
        }
    }

    // =============================================
    // 7. SAFE COPY
    // =============================================

    fun saveSafeCopyText(encodedText: String) {
        val editor = prefs.edit()
        editor.putString(Constants.SAFE_COPY_KEY, encodedText)
        editor.putLong(Constants.SAFE_COPY_TIMESTAMP, System.currentTimeMillis())
        editor.apply()
    }

    fun getSafeCopyText(): String? {
        val timestamp = prefs.getLong(Constants.SAFE_COPY_TIMESTAMP, 0)
        val fiveMinutesAgo = System.currentTimeMillis() - (5 * 60 * 1000)

        if (timestamp < fiveMinutesAgo) {
            clearSafeCopyText()
            return null
        }

        return prefs.getString(Constants.SAFE_COPY_KEY, null)
    }

    fun clearSafeCopyText() {
        val editor = prefs.edit()
        editor.remove(Constants.SAFE_COPY_KEY)
        editor.remove(Constants.SAFE_COPY_TIMESTAMP)
        editor.apply()
    }


    // =============================================
    // 9. SOUND NOTIFICATION
    //    TOASTS & LOG
    // =============================================

    fun shouldShowToast(): Boolean {
        return !silentMode  // o qualcosa di simile
    }
    // =============================================
    // 10. UTILITY FUNCTIONS BOOLEAN
    // =============================================

    fun setChatFontSize(size: Float) {
        prefs.edit().putFloat(Constants.PREF_CHAT_FONT_SIZE, size).apply()
    }

    fun getChatFontSize(): Float? {
        return if (prefs.contains(Constants.PREF_CHAT_FONT_SIZE)) {
            prefs.getFloat(Constants.PREF_CHAT_FONT_SIZE, 14f)
        } else {
            null
        }
    }

    // =============================================
    // 12. Notification sound (automatically encrypted)
    // =============================================

    var notificationSoundUri: String
        get() = getString("notification_sound_uri", "") // Default: None - no sound
        set(value) = putString("notification_sound_uri", value)

    var vibrationEnabled: Boolean
        get() = prefs.getBoolean(Constants.KEY_VIBRATION_ENABLED, true)
        set(value) = prefs.edit().putBoolean(Constants.KEY_VIBRATION_ENABLED, value).apply()

    // =============================================
    // 13. UTILITY FUNCTIONS
    // =============================================

    fun getBoolean(key: String, defaultValue: Boolean = false): Boolean {
        return prefs.getBoolean(key, defaultValue)
    }

    fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun getString(key: String, defaultValue: String = ""): String {
        return prefs.getString(key, defaultValue) ?: defaultValue
    }

    fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    fun getInt(key: String, defaultValue: Int = 0): Int {
        return prefs.getInt(key, defaultValue)
    }

    fun putLong(key: String, value: Long) {
        prefs.edit().putLong(key, value).apply()
    }

    fun getLong(key: String, defaultValue: Long = 0L): Long {
        return prefs.getLong(key, defaultValue)
    }

    fun putFloat(key: String, value: Float) {
        prefs.edit().putFloat(key, value).apply()
    }

    fun getFloat(key: String, defaultValue: Float = 0f): Float {
        return prefs.getFloat(key, defaultValue)
    }

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun contains(key: String): Boolean {
        return prefs.contains(key)
    }

    // =============================================
    // 14. TYPE TOKENS PER GSON
    // =============================================

    private val trustedContactsType = object : TypeToken<List<TrustedContact>>() {}.type
    private val decodedHistoryType = object : TypeToken<List<DecodedMessage>>() {}.type
    private val chatConversationsType = object : TypeToken<List<ChatConversation>>() {}.type

    // =============================================
    // 15. DEBUG FUNCTIONS
    // =============================================

    fun isEncryptionWorking(): Boolean {
        return AppCryptoManager.isEncryptionActive()
    }

    fun clearAllPreferences() {
        prefs.edit().clear().apply()
        LogUtils.d(null, "SharedPreferencesManager", "🗑️ Tutte le preferenze cancellate")
    }

    fun runCompleteTest(): Boolean {
        return try {
            LogUtils.d(null, "SharedPreferencesManager", "🧪 Inizio test completo...")

            // Test 1: simple write
            val testKey = "test_key_${System.currentTimeMillis()}"
            val testValue = "test_value_${System.currentTimeMillis()}"

            prefs.edit().putString(testKey, testValue).apply()
            LogUtils.d(null, "SharedPreferencesManager", "✅ Test 1: Scrittura completata")

            // Test 2: Reading
            val readValue = prefs.getString(testKey, null)
            val test1Passed = readValue == testValue
            LogUtils.d(null, "SharedPreferencesManager",
                "✅ Test 2: Lettura ${if (test1Passed) "PASSATO" else "FALLITO"} ($readValue)")

            // Cleanup
            prefs.edit().remove(testKey).apply()

            test1Passed
        } catch (e: Exception) {
            LogUtils.e(null, "SharedPreferencesManager", "❌ Test completo fallito", e)
            false
        }
    }


    // SMS scan MultiPartInfo section

    fun saveMultipartInfo(sender: String, dummyId: String, partCount: Int, firstTimestamp: Long) {
        try {
            val info = MultipartInfo(sender, dummyId, partCount, firstTimestamp)
            val currentList = getAllMultipartInfo().toMutableList()

            // Remove any old info for this sender with close timestamp
            currentList.removeAll {
                it.sender == sender &&
                        abs(it.firstTimestamp - firstTimestamp) < 60000
            }

            currentList.add(info)

            // Keep only last 50 multipart infos
            if (currentList.size > 50) {
                currentList.sortByDescending { it.timestamp }
                while (currentList.size > 50) {
                    currentList.removeAt(currentList.size - 1)
                }
            }

            val json = gson.toJson(currentList)
            prefs.edit().putString(Constants.KEY_MULTIPART_INFO, json).apply()

            LogUtils.d(context, "SharedPreferencesManager",
                "💾 Saved multipart info: $sender, $partCount parts, dummyId=$dummyId")
        } catch (e: Exception) {
            LogUtils.e(context, "SharedPreferencesManager", "❌ Error saving multipart info", e)
        }
    }

    fun getAllMultipartInfo(): List<MultipartInfo> {
        return try {
            val json = prefs.getString(Constants.KEY_MULTIPART_INFO, null)
            if (json.isNullOrEmpty()) {
                emptyList()
            } else {
                val type = object : TypeToken<List<MultipartInfo>>() {}.type
                gson.fromJson(json, type) ?: emptyList()
            }
        } catch (e: Exception) {
            LogUtils.e(context, "SharedPreferencesManager", "❌ Error reading multipart info", e)
            emptyList()
        }
    }

    fun getMultipartInfo(sender: String, firstTimestamp: Long): MultipartInfo? {
        return getAllMultipartInfo().find {
            it.sender == sender &&
                    abs(it.firstTimestamp - firstTimestamp) < 60000
        }
    }

    fun removeMultipartInfo(sender: String, firstTimestamp: Long) {
        try {
            val currentList = getAllMultipartInfo().toMutableList()
            val removed = currentList.removeAll {
                it.sender == sender &&
                        abs(it.firstTimestamp - firstTimestamp) < 60000
            }

            if (removed) {
                val json = gson.toJson(currentList)
                prefs.edit().putString(Constants.KEY_MULTIPART_INFO, json).apply()
                LogUtils.d(context, "SharedPreferencesManager",
                    "🗑️ Removed multipart info for $sender at $firstTimestamp")
            }
        } catch (e: Exception) {
            LogUtils.e(context, "SharedPreferencesManager", "❌ Error removing multipart info", e)
        }
    }

    fun clearOldMultipartInfo(maxAgeMs: Long = 2 * 60 * 60 * 1000) { // 2 hours default
        try {
            val currentList = getAllMultipartInfo().toMutableList()
            val cutoff = System.currentTimeMillis() - maxAgeMs
            val removed = currentList.removeAll { it.timestamp < cutoff }

            if (removed) {
                val json = gson.toJson(currentList)
                prefs.edit().putString(Constants.KEY_MULTIPART_INFO, json).apply()
                LogUtils.d(context, "SharedPreferencesManager", "🧹 Cleared old multipart info")
            }
        } catch (e: Exception) {
            LogUtils.e(context, "SharedPreferencesManager", "❌ Error clearing old multipart info", e)
        }
    }


}