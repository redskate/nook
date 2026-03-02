package solutions.semweb.nook

import solutions.semweb.nook.crypto.EncryptionMapper

object Constants {
    //////////////////////////////////////////////////////
    // 🔴 CENTRALIZED VERSIONING - Edit only these values
    const val VERSION_MAJOR = 1
    const val VERSION_MINOR = 3
    const val VERSION_PATCH = 6
    const val VERSION_CODE = 271  // next: 272

    // Computed version name (DO NOT EDIT DIRECTLY)
    const val VERSION_NAME = "$VERSION_MAJOR.$VERSION_MINOR.$VERSION_PATCH.$VERSION_CODE"

    // SHA APP CHECKS uses VERSION_NAME
    const val VERSION = VERSION_NAME
    //////////////////////////////////////////////////////


    // This is the only "brand" I have:
    const val COPYRIGHT = "©2026 semweb.solutions"
    const val VISITME = "https://semweb.solutions/nook"

    // App Protection Constants
    const val KEY_APP_PROTECTION_ENABLED = "app_protection_enabled"
    const val KEY_APP_PROTECTION_PASSWORD = "app_protection_password"
    const val KEY_APP_PROTECTION_TIMEOUT = "app_protection_timeout" // in seconds
    const val KEY_LAST_ACTIVE_TIME = "last_active_time"
    const val DEFAULT_PROTECTION_TIMEOUT = 120 // 2 minutes in seconds
    const val KEY_APP_PROTECTION_FAILED_ATTEMPTS = "app_protection_failed_attempts"
    const val KEY_APP_PROTECTION_LOCK_UNTIL = "app_protection_lock_until"
    const val MAX_PASSWORD_ATTEMPTS = 5 // retries
    const val LOCK_DURATION_MINUTES = 5 // block minutes after failed attempts

    const val mainpackage = "solutions.semweb.nook"

    // MSG_SEQ: 1 = Last message at bottom, 0 = Last message on top (growing to the upper part)
    const val MSG_SEQ = 1 // Default - chronological message order
    val MESSAGES_LIMIT = 10 // Load max. n messages at a time

    // SMS
    const val SMSTAG = "SMSReceiver"
    const val SMS_OBF_PREFIX = "#e"


    // Notifications
    const val NOTIFICATION_CHANNEL_ID = "NooKCh"
    const val NOTIFICATION_ID = 1

    // SharedPreferences
    const val KEY_DECODING_SCHEME = "decoding_scheme"
    const val KEY_SILENT_MODE = "silent_mode"
    const val KEY_LOG_ENABLED = "log_enabled"
    const val KEY_USE_ALL_CONTACTS = "use_all_contacts"
    const val KEY_USE_CLIPBOARD = "use_clipboard"
    const val KEY_ALLOW_SCREENSHOTS = "allow_screenshots"
    const val KEY_MSG_SEQ = "msg_seq" // ADDED


    //////////////////////////////////////////////////////////////////
    // Keyboard security
    const val KEY_KEYBOARD_WHITELIST = "keyboard_whitelist"
    const val KEY_KEYBOARD_BLACKLIST = "keyboard_blacklist"
    const val KEY_KEYBOARD_USER_DECISIONS = "keyboard_user_decisions"
    const val ACTION_KEYBOARD_CHANGED = Constants.mainpackage+".ACTION_KEYBOARD_CHANGED"


    // Default keyboard whitelist
    val DEFAULT_WHITELISTED_KEYBOARDS = listOf(
        "com.google.android.inputmethod.latin",
        "com.samsung.android.honeyboard",
        "com.sec.android.inputmethod",
        "com.android.inputmethod.latin",
        "com.touchtype.swiftkey",
        "com.swiftkey.swiftkeyconfigurator",
        "jp.co.omronsoft.iwnnime.ml",
        "com.emoji.keyboard.touchpal",
        "com.zhangxiang.inputmethod",
        "com.sogou.inputmethod.sogou",
        "com.google.android.inputmethod.arabic",
        "com.google.android.inputmethod.japanese",
        "com.google.android.inputmethod.korean"
    )

    // Blacklist of keyboards
    val DEFAULT_BLACKLISTED_KEYBOARDS = listOf(
        "com.hackers.keyboard",
        "org.pocketworkstation.pckeyboard",
        "com.kl.ime.oh",
        "com.spy.ime",
        "com.keylogger.input",
        "com.track.ime",
        "com.malware.ime",
        "com.unknown.ime",
        "com.third.party.keylogger",
        "com.suspicious.keyboard"
    )

    // Sounds and notifications
    const val KEY_NOTIFICATION_SOUND_URI = "notification_sound_uri"
    const val KEY_VIBRATION_ENABLED = "vibration_enabled"
    const val DEFAULT_NOTIFICATION_SOUND_URI = "content://settings/system/notification_sound"

    // Codes for permissions and requests
    const val PERMISSION_REQUEST_NOTIFICATIONS = 1000
    const val PERMISSION_REQUEST_READ_PHONE_STATE = 1001
    const val REQUEST_CODE_SOUND_PICKER = 1100
    const val REQUEST_CODE_IMPORT_FILE = 2001


    const val SAFE_COPY_KEY = "safe_copy_text"
    const val SAFE_COPY_TIMESTAMP = "safe_copy_timestamp"
    const val PREF_CHAT_FONT_SIZE =  "14f"
    const val KEY_APP_OWNER_NAME = "app_owner_name"

    const val DEFAULT_encryptionScheme = "NONE"
    const val DEFAULT_encoding = EncryptionMapper.ENCODING_BASE256

    // SMS Scanner constants
    const val MULTIPART_DELAY = 60000L // ms
    const val SCANNER_TAG = "SmsScanner"
    const val KEY_MULTIPART_INFO = "multipart_info"
    const val MULTIPART_DUMMY_ID_PREFIX = "multipart_"

    //Protected - Now using BuildConfig values from gradle
    const val GITHUB_SHA256_URL = BuildConfig.GITHUB_SHA256_URL
    const val GITHUB_RELEASES_URL = BuildConfig.GITHUB_RELEASES_URL
}