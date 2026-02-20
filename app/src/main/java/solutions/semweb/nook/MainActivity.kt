package solutions.semweb.nook

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import java.lang.ref.WeakReference
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.runBlocking
import solutions.semweb.nook.chat.ChatActivity
import solutions.semweb.nook.chat.ChatConversationAdapter
import solutions.semweb.nook.chat.ChatManager
import solutions.semweb.nook.contacts.TrustedContactsActivity
import solutions.semweb.nook.crypto.CryptoManager
import solutions.semweb.nook.crypto.EncryptionMapper
import solutions.semweb.nook.data.database.DatabaseActor
import solutions.semweb.nook.keyboards.KeyboardManagementActivity
import solutions.semweb.nook.keyboards.KeyboardSafetyManager
import solutions.semweb.nook.sms.SmsScanner
import solutions.semweb.nook.sound.MainActivitySoundPicker
import solutions.semweb.nook.sound.SoundManagement
import java.io.File
import java.io.FileOutputStream
import java.util.Timer
import java.util.TimerTask

class MainActivity : AppCompatActivity(), MainActivitySoundPicker {

    private lateinit var appProtectionToggle: Switch
    private lateinit var keyboardSafetyManager: KeyboardSafetyManager
    private lateinit var prefs: SharedPreferencesManager
    private lateinit var chatManager: ChatManager
    private lateinit var decodingSchemeSpinner: Spinner
    private lateinit var silentModeToggle: Switch
    private lateinit var logToggleContainer: View
    private lateinit var logToggle: Switch
    private lateinit var useAllContactsToggle: Switch
    private lateinit var allContactsInfo: TextView
    private var isUIInitialized = false
    private lateinit var chatRecyclerView: RecyclerView
    private lateinit var chatAdapter: ChatConversationAdapter
    lateinit var allowScreenshotsToggle: Switch
    private lateinit var notificationSoundName: TextView
    private lateinit var notificationSoundContainer: LinearLayout
    private lateinit var vibrationToggle: Switch
    private lateinit var testNotificationContainer: LinearLayout
    private lateinit var settingsCard: MaterialCardView
    private lateinit var settingsHeader: LinearLayout
    private lateinit var settingsContent: LinearLayout
    private lateinit var settingsExpandIcon: ImageView
    private var isSettingsExpanded = false
    private var conversation: ChatConversation? = null
    private lateinit var settingsYHeader: LinearLayout
    private lateinit var settingsYContent: LinearLayout
    private lateinit var settingsYExpandIcon: ImageView
    private var isSettingsYExpanded = false
    private lateinit var chatUpdateReceiver: BroadcastReceiver
    var isAppInitialized = false
    //private lateinit var databaseManager: DatabaseManager
    private lateinit var utils: MainActivityUtils
    private lateinit var soundManager: SoundManagement
    private lateinit var databaseActor: DatabaseActor
    private lateinit var appLockManager: AppLockManager
    var currentImportDialog: AlertDialog? = null
    var currentImportDialogHandler: ((Uri, String) -> Unit)? = null
    private lateinit var chatCard: MaterialCardView

    val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) {
                try {
                    val filePath = getRealPathFromURI(uri)
                    if (filePath != null) {
                        currentImportDialogHandler?.invoke(uri, filePath)
                    } else {
                        showToast(getString(R.string.import_file_path_error))
                    }
                } catch (e: Exception) {
                    LogUtils.e("MainActivity", "File path error", e)
                    showToast(getString(R.string.import_file_path_error))
                }
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_SMS = 100
        private const val PERMISSION_REQUEST_CONTACTS = 101
        private const val PERMISSION_REQUEST_NOTIFICATIONS = 104

        // ✅ Usa WeakReference invece di riferimento diretto
        private var weakInstance: WeakReference<MainActivity>? = null

        // Helper per ottenere l'activity in modo sicuro
        private val instance: MainActivity?
            get() = weakInstance?.get()

        private var prefsInstance: SharedPreferencesManager? = null

        private fun getPrefs(): SharedPreferencesManager? {
            if (prefsInstance == null && instance != null) {
                prefsInstance = SharedPreferencesManager.getInstance(instance!!)
            }
            return prefsInstance
        }

        // make showToast available for all with silent mode control
        fun showToast(message: String, isError: Boolean = false) {
            val activity = instance ?: run {
                LogUtils.e("MAIN", "⚠️ Toast skipped - no activity instance")
                return
            }

            // Skip toasts during initialization (unless it's an error)
            if (!activity.isAppInitialized && !isError) {
                LogUtils.e("MAIN", "⏸️ Toast skipped during init: $message")
                return
            }

            activity.runOnUiThread {
                try {
                    val prefs = getPrefs() ?: return@runOnUiThread

                    if (isError || !prefs.silentMode) {
                        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
                    } else {
                        LogUtils.d("MAIN", "🔇 Toast suppressed (silent mode): $message")
                    }
                } catch (e: Exception) {
                    LogUtils.e("MAIN", "❌ Error showing toast", e)
                }
            }
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        DatabaseActor.onDatabaseTestFailed = { reason ->
            handleDatabaseFailure(reason)
        }

        LogUtils.d(this, "ChatActivity", "=== DEBUG onCreate ===")
        LogUtils.d(this, "ChatActivity", "Intent: $intent")
        LogUtils.d(this, "ChatActivity", "Intent extras: ${intent.extras?.keySet()}")
        LogUtils.d(this, "ChatActivity", "phone_number extra: ${intent.getStringExtra("phone_number")}")

        try {
            LogUtils.e("MAIN", "🚀 onCreate() - LINEAR APPROACH")
            setContentView(R.layout.activity_main)

            // 1. Init utils
            utils = MainActivityUtils(this)

            // 2. Init prefs now
            prefs = SharedPreferencesManager.getInstance(this)

            // 3. Control disclaimer
            val disclaimerAccepted = prefs.getBoolean("disclaimer_accepted", false)
            if (!disclaimerAccepted) {
                utils.showDisclaimerDialog(prefs, this)
                return
            }

            // 5. Setup toggle (after views)
            setupAppProtectionToggle()

            if (prefs.appProtectionEnabled) {
                LogUtils.e("MAIN", "🔒 APP protection active - show block panel")

                appLockManager = AppLockManager.getInstance(this)

                if (appLockManager.isAppCurrentlyLocked() || prefs.isAppLocked) {
                    showPasswordPromptDialog()
                } else {
                    LogUtils.e("MAIN", "🔓 APP not blocked - go for init")
                    initializeAppLinearly()
                }
                return
            }

            // 7. Init all
            initializeAppLinearly()

        } catch (e: Exception) {
            LogUtils.e("MAIN", "❌ Error in onCreate", e)
            Toast.makeText(this, getString(R.string.toast_initialization_error, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    private fun initializeAppLinearly() {
        LogUtils.e("MAIN", "🔄 Linear initialization...")

        try {
            // 1. Setup basic views FIRST
            setupBasicViews()

            // 2. HIDE CHATS
            runOnUiThread {
                chatCard.visibility = View.GONE
            }

            // 3. Setup app protection toggle
            setupAppProtectionToggle()

            // 4. Init AppLockManager
            appLockManager = AppLockManager.getInstance(this)
            if (prefs.appProtectionEnabled) {
                appLockManager.startMonitoring()
                setupInactivityReset()
            }

            // 5. Keyboard manager
            keyboardSafetyManager = KeyboardSafetyManager(this)

            // 6. Database Actor - this starts background test
            databaseActor = DatabaseActor.getInstance(this)

            // 7. Initialize ALL UI components (these are fast)
            chatManager = ChatManager(this)
            soundManager = SoundManagement(this)

            // Setup adapter with empty list
            chatAdapter = ChatConversationAdapter(
                conversations = emptyList(),
                onItemClick = { conversation ->
                    val intent = Intent(this, ChatActivity::class.java).apply {
                        putExtra("conversation_id", conversation.id)
                        putExtra("phone_number", conversation.phoneNumber)
                    }
                    startActivity(intent)
                },
                onItemLongClick = { conversation, view ->
                    utils.showConversationContextMenu(conversation, view, prefs, chatManager, this)
                }
            )
            chatRecyclerView.layoutManager = LinearLayoutManager(this)
            chatRecyclerView.adapter = chatAdapter

            setupSpinnersAndToggles()
            setupButtons()
            setVersionFooter()
            setupSettings()
            setupToggleLabelClickListeners()
            setupNotificationSoundPreferences()
            setupOwnerNameField()

            // 8. Permissions
            Handler(Looper.getMainLooper()).postDelayed({
                requestAllRequiredPermissionsSimple()
            }, 300)

            // 9. Final setup
            utils.createNotificationChannels(this)
            chatUpdateReceiver = utils.registerChatUpdateReceiver()
            utils.setupKeyboardSafetyForAllEditTexts(keyboardSafetyManager)

            // 10. Check security
            Thread { checkRootSecurity() }.start()

            // 11. Start notification service
            Handler(Looper.getMainLooper()).postDelayed({
                startForegroundNotification()
            }, 1000)

            // 12. Handle intent extras
            Handler(Looper.getMainLooper()).postDelayed({
                handleIntentExtras()
            }, 1000)

            // 13. Mark UI as initialized
            isAppInitialized = true
            isUIInitialized = true

            LogUtils.e("MAIN", "✅✅✅ App UI initialized")

            // 14. SIMPLE POLLING - check every second
            startPollingForDatabaseReady()

        } catch (e: Exception) {
            LogUtils.e("MAIN", "❌ Error", e)
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun startPollingForDatabaseReady() {
        val handler = Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            var attempts = 0
            override fun run() {
                attempts++

                if (databaseActor.isReady) {
                    LogUtils.e("MAIN", "✅ Database ready after $attempts seconds!")
                    chatCard.visibility = View.VISIBLE
                    loadChatConversations()
                    return
                }

                if (attempts >= 30) { // 30 seconds timeout
                    LogUtils.e("MAIN", "⚠️ Database timeout")
                    chatCard.visibility = View.VISIBLE
                    loadChatConversations()
                    Toast.makeText(this@MainActivity,
                        "Database timeout - showing chats anyway",
                        Toast.LENGTH_LONG).show()
                    return
                }

                // Check again in 1 second
                handler.postDelayed(this, 1000)
            }
        }

        // Start polling after a small delay to ensure UI is settled
        handler.postDelayed(runnable, 500)
    }



    // Keep initializeCompleteOnCreate simple
    fun initializeCompleteOnCreate() {
        LogUtils.e("MAIN", "initializeCompleteOnCreate() called from disclaimer")
        // This is only called on first install after disclaimer is accepted
        // Just continue with normal initialization
        initializeAppLinearly()
    }

    /**
     * Tries to load chats if database is ready, otherwise logs that it's delayed
     */
    private fun tryLoadChats() {
        if (databaseActor.isReady) {
            LogUtils.e("MAIN", "📱 Database ready, loading chats now")
            loadChatConversations()
        } else {
            LogUtils.e("MAIN", "⏳ Database not ready yet, chats will load when ready")
        }
    }


    private fun startForegroundNotification() {
        try {
            LogUtils.d("MainActivity", "🔔 Starting foreground notification on API ${Build.VERSION.SDK_INT}")

            // Check if we have notification permission
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED) {
                    LogUtils.d("MainActivity", "🔔 Notification permission granted, starting service")
                    NotificationHelper.startForegroundNotification(this)
                } else {
                    LogUtils.d("MainActivity", "🔔 Notification permission not granted yet")
                }
            } else {
                // Android 12 and below
                LogUtils.d("MainActivity", "🔔 Android < 13, starting service directly")
                NotificationHelper.startForegroundNotification(this)
            }
        } catch (e: Exception) {
            LogUtils.e("MainActivity", "🔔 Error starting notification", e)
        }
    }

    private fun requestAllRequiredPermissionsSimple() {
        LogUtils.e("MAIN", "🔐 Simple permission request...")

        // 1. PRIMA controlla e richiedi permesso notifiche (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                LogUtils.e("MAIN", "📢 Requesting NOTIFICATION permission for Android 13+")

                // Dialog informativo prima di richiedere
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.alert_notification_permission_title))
                    .setMessage(getString(R.string.alert_notification_permission_message))
                    .setPositiveButton(getString(R.string.alert_grant)) { _, _ ->
                        ActivityCompat.requestPermissions(
                            this,
                            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                            PERMISSION_REQUEST_NOTIFICATIONS
                        )
                    }
                    .setNegativeButton(getString(R.string.alert_later)) { dialog, _ ->
                        dialog.dismiss()
                        proceedWithOtherPermissions()
                    }
                    .setCancelable(false)
                    .show()
                return
            }
        }

        proceedWithOtherPermissions()
    }


    private fun proceedWithOtherPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        // Check SMS permissions
        val smsPermissions = arrayOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS
        )

        smsPermissions.forEach { permission ->
            if (ContextCompat.checkSelfPermission(this, permission)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(permission)
            }
        }

        // Control contacts permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.READ_CONTACTS)
        }

        if (permissionsToRequest.isNotEmpty()) {
            // Dialog informing user
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.alert_required_permissions_title))
                .setMessage(getString(R.string.disclaimer_further_permits))
                .setPositiveButton(getString(R.string.alert_grant)) { _, _ ->
                    // Ask for permissions
                    ActivityCompat.requestPermissions(
                        this,
                        permissionsToRequest.toTypedArray(),
                        PERMISSION_REQUEST_SMS
                    )
                }
                .setNegativeButton(getString(R.string.alert_later)) { dialog, _ ->
                    dialog.dismiss()
                    showToast(getString(R.string.toast_grant_permissions_later))
                }
                .setCancelable(false)
                .show()
        } else {
            LogUtils.e("MAIN", "✅ All permissions already granted")
        }
    }


    private fun setupCompleteUI() {
        try {
            LogUtils.e("MAIN", "🎨 Complete UI setup...")

            // Initialize with EMPTY list
            chatAdapter = ChatConversationAdapter(
                conversations = emptyList(),
                onItemClick = { conversation ->
                    val intent = Intent(this, ChatActivity::class.java).apply {
                        putExtra("conversation_id", conversation.id)
                        putExtra("phone_number", conversation.phoneNumber)
                    }
                    startActivity(intent)
                },
                onItemLongClick = { conversation, view ->
                    utils.showConversationContextMenu(conversation, view, prefs, chatManager, this)
                }
            )

            chatRecyclerView.layoutManager = LinearLayoutManager(this)
            chatRecyclerView.adapter = chatAdapter

            LogUtils.e("MAIN", "✅ Complete UI configured")

        } catch (e: Exception) {
            LogUtils.e("MAIN", "❌ Error in setupCompleteUI", e)
        }
    }


    private fun setupSpinnersAndToggles() {
        try {
            LogUtils.e("MAIN", "🎯 Setting up spinners and toggles")

            appProtectionToggle.isChecked = prefs.appProtectionEnabled
            appProtectionToggle.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    showSetAppProtectionPasswordDialog()
                } else {
                    removeAppProtection()
                }
            }

            // ==============================================
            // 1. SETUP DECODING SCHEME SPINNER
            // ==============================================
            val adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_item,
                arrayOf("Default (Automatic)")
            )
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            decodingSchemeSpinner.adapter = adapter

            // Disabilita lo spinner o mostra solo informativo
            decodingSchemeSpinner.isEnabled = false
            decodingSchemeSpinner.alpha = 0.7f

            // Mostra quale è il default corrente
            val defaultEncryption = EncryptionMapper.DEFAULT_ENCRYPTION_SCHEME
            val defaultEncoding = EncryptionMapper.DEFAULT_ENCODING

            LogUtils.e("MAIN", "✅ Default - Encryption: $defaultEncryption, Encoding: $defaultEncoding")

            // ==============================================
            // 2. SETUP TOGGLES
            // ==============================================

            // Setup silent mode toggle
            silentModeToggle.isChecked = prefs.silentMode
            silentModeToggle.setOnCheckedChangeListener { _, isChecked ->
                prefs.silentMode = isChecked
                LogUtils.e("MAIN", "${if (isChecked) "Silent" else "Normal"} mode")
                showToast(if (isChecked) getString(R.string.silent_mode_on) else getString(R.string.normal_mode_on))
            }

            // IMPORTANTE: Setup log toggle
            logToggle.isChecked = prefs.logEnabled

            // Init LogUtils with current value
            LogUtils.updateLoggingEnabled(prefs.logEnabled, this)

            logToggle.setOnCheckedChangeListener { _, isChecked ->
                prefs.logEnabled = isChecked
                LogUtils.updateLoggingEnabled(isChecked, this@MainActivity)

                // Test Log
                LogUtils.d(this@MainActivity, "MainActivity", "✅ Log toggle changed to: $isChecked")

                LogUtils.e("MAIN", "Log ${if (isChecked) "enabled" else "disabled"}")
                showToast(getString(R.string.log_status, if (isChecked) "ON" else "OFF"))
            }

            useAllContactsToggle.isChecked = prefs.useAllContacts
            updateAllContactsInfoVisibility()

            useAllContactsToggle.setOnCheckedChangeListener { _, isChecked ->
                prefs.useAllContacts = isChecked
                updateAllContactsInfoVisibility()
                LogUtils.e("MAIN", "${if (isChecked) "All" else "Selective"} contacts")
                showToast(if (isChecked) getString(R.string.all_contacts_mode_active) else getString(R.string.selective_mode_active))
            }

            allowScreenshotsToggle.isChecked = prefs.allowScreenshots
            allowScreenshotsToggle.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    utils.showScreenshotPermissionDialog(prefs)
                } else {
                    prefs.allowScreenshots = false
                    applyScreenshotSecurity()
                    showToast(getString(R.string.toast_screenshot_disabled))
                }
            }

            vibrationToggle.isChecked = prefs.vibrationEnabled
            vibrationToggle.setOnCheckedChangeListener { _, isChecked ->
                prefs.vibrationEnabled = isChecked
                showToast(if (isChecked) getString(R.string.toast_vibration_enabled) else getString(R.string.toast_vibration_disabled))
                LogUtils.e("MAIN", "📳 Vibration: ${if (isChecked) "ON" else "OFF"}")
            }

            LogUtils.e("MAIN", "✅ Spinners and toggles setup complete")

        } catch (e: Exception) {
            LogUtils.e("MAIN", "❌ Error in setupSpinnersAndToggles", e)
            Toast.makeText(this, "Error setting options", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupOwnerNameField() {
        try {
            val ownerNameContainer: LinearLayout? = findViewById(R.id.owner_name_container)

            if (ownerNameContainer == null) {
                LogUtils.e("MAIN", "⚠️ owner_name_container not found")
                return
            }

            ownerNameContainer.removeAllViews()

            val label = TextView(this).apply {
                text = getString(R.string.owner_name)
                textSize = 16f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(16.dpToPx(), 0, 16.dpToPx(), 8.dpToPx())
                }
            }

            val editText = EditText(this).apply {
                hint = "Inserisci il tuo nome"
                setText(prefs.appOwnerName)

                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(16.dpToPx(), 0, 16.dpToPx(), 0)
                }
                setPadding(16.dpToPx(), 12.dpToPx(), 16.dpToPx(), 12.dpToPx())

                // TextWatcher con debounce
                val watcher = DebouncedTextWatcher(delayMillis = 1500L) { newText ->
                    runOnUiThread {
                        val trimmedText = newText.trim()
                        if (trimmedText != prefs.appOwnerName) {
                            prefs.appOwnerName = trimmedText
                            LogUtils.e("MAIN", "✅ Name saved: '$trimmedText'")
                        }
                    }
                }

                addTextChangedListener(watcher)

                // Save also on focus lost
                setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus) {
                        val currentText = text.toString().trim()
                        if (currentText != prefs.appOwnerName) {
                            prefs.appOwnerName = currentText
                            LogUtils.e("MAIN", "✅ Name saved (focus lost): '$currentText'")
                        }
                    }
                }
            }

            ownerNameContainer.addView(label)
            ownerNameContainer.addView(editText)

            LogUtils.e("MAIN", "✅ Owner name field configured")

        } catch (e: Exception) {
            LogUtils.e("MAIN", "❌ Error in setupOwnerNameField", e)
        }
    }

    private fun setupButtons() {
        val manageContactsBtn: Button = findViewById(R.id.manage_contacts_btn)
        val checkPermissionsBtn: Button = findViewById(R.id.check_permissions_btn)
        val manageKeyboardsBtn: Button = findViewById(R.id.manage_keyboards_btn)
        val searchContactsForChatsButton = findViewById<ImageView>(R.id.search_contacts_4_chats_button)
        val newChatButton = findViewById<ImageView>(R.id.new_chat_button)

        manageContactsBtn.setOnClickListener {
            startActivity(Intent(this, TrustedContactsActivity::class.java))
        }

        checkPermissionsBtn.setOnClickListener {
            checkPermissions()
        }

        manageKeyboardsBtn.setOnClickListener {
            try {
                startActivity(Intent(this, KeyboardManagementActivity::class.java))
            } catch (e: Exception) {
                LogUtils.e("MAIN", "❌ Error opening keyboard management", e)
            }
        }

        searchContactsForChatsButton?.setOnClickListener {
            utils.showSearchDialogForSMSChats(
                prefs = prefs,
                chatManager = chatManager,
                checkContactsPermission = { checkContactsPermission() },
                requestContactsPermission = { requestContactsPermission() }
            )
        }

        newChatButton?.setOnClickListener {
            addNewSmsChat()
        }
    }


    private fun showResultsDialog(
        result: SmsScanner.ScanResult,
        hoursBack: Int,
        contactName: String?
    ) {
        val message = when {
            result.error != null -> getString(R.string.alert_scan_result_error, result.error)
            result.processed > 0 ->
                getString(R.string.alert_scan_result_success,
                    result.processed,
                    contactName ?: "",
                    result.decrypted,
                    result.plaintext,
                    result.errors,
                    result.alreadyExist)
            else -> getString(R.string.alert_scan_result_no_sms, contactName ?: "", hoursBack)
        }

        AlertDialog.Builder(this)
            .setTitle(if (result.processed > 0) getString(R.string.alert_scan_result_completed_title) else getString(R.string.alert_scan_result_title))
            .setMessage(message)
            .setPositiveButton(getString(R.string.alert_ok), null)
            .show()
    }

    private fun handleDatabaseFailure(reason: String) {
        runOnUiThread {
            LogUtils.e("MAIN", "💥 Received database failure: $reason")

            AlertDialog.Builder(this)
                .setTitle(getString(R.string.db_critical_error))
                .setMessage(getString(R.string.db_critical_error_expl))
                .setCancelable(false)
                .setPositiveButton(getString(R.string.exit)) { _, _ ->
                    // APP stop
                    finishAffinity()
                    System.exit(1)
                }
                .show()
        }
    }

    private fun setupBasicViews() {
        LogUtils.e("MAIN", "🔧 Basic view setup...")

        decodingSchemeSpinner = findViewById(R.id.decoding_scheme_spinner)
        logToggleContainer = findViewById<View>(R.id.log_toggle_container)
        silentModeToggle = findViewById(R.id.silent_mode_toggle)
        logToggle = findViewById(R.id.log_toggle)
        useAllContactsToggle = findViewById(R.id.use_all_contacts_toggle)
        allContactsInfo = findViewById(R.id.all_contacts_info)
        chatRecyclerView = findViewById(R.id.chat_recycler_view)
        allowScreenshotsToggle = findViewById(R.id.allow_screenshots_toggle)
        vibrationToggle = findViewById(R.id.vibration_toggle)

        settingsCard = findViewById(R.id.settings_card)
        settingsHeader = findViewById(R.id.settings_header)
        settingsContent = findViewById(R.id.settings_content)
        settingsExpandIcon = findViewById(R.id.settings_expand_icon)

        notificationSoundName = findViewById(R.id.notification_sound_name)
        notificationSoundContainer = findViewById(R.id.notification_sound_container)
        testNotificationContainer = findViewById(R.id.test_notification_container)

        // LOADING
        chatCard = findViewById(R.id.chat_card) // Make sure your chat card has this ID

        LogUtils.e("MAIN", "✅ Basic views found")
    }


    private fun checkRootSecurity() {
        Thread {
            try {
                val securityRisk = RootChecker.evaluateSecurityRisk(this@MainActivity)
                LogUtils.e("MAIN", "🔍 RootCheck: Result = $securityRisk")

                runOnUiThread {
                    val dontShowAgain = prefs.getBoolean("root_warning_dont_show_again", false)
                    if (dontShowAgain) {
                        LogUtils.e("MAIN", "⚠️ RootCheck: Warning suppressed by user choice")
                        return@runOnUiThread
                    }

                    when (securityRisk) {
                        RootChecker.SecurityRisk.MEDIUM, RootChecker.SecurityRisk.HIGH -> {
                            LogUtils.e("MAIN", "⚠️ RootCheck: Showing alert for risk $securityRisk")
                            showRootSecurityAlert(securityRisk)
                        }
                        else -> {
                            LogUtils.e("MAIN", "✅ RootCheck: No risk")
                        }
                    }
                }
            } catch (e: Exception) {
                LogUtils.e("MAIN", "❌ RootCheck: Error", e)
            }
        }.start()
    }

    private fun showRootSecurityAlert(securityRisk: RootChecker.SecurityRisk) {
        runOnUiThread {
            try {
                val title = getString(R.string.data_security)
                val positiveButton = getString(R.string.i_understand)
                val neutralButton = getString(R.string.donotshowthisagain)

                val message = when (securityRisk) {
                    RootChecker.SecurityRisk.HIGH -> getString(R.string.highrisk_root_expl)
                    RootChecker.SecurityRisk.MEDIUM -> getString(R.string.middlerisk_root_expl)
                    else -> return@runOnUiThread
                }

                val dialog = AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton(positiveButton) { dialog, _ ->
                        prefs.putBoolean("root_warning_shown", true)
                        dialog.dismiss()
                    }
                    .setNeutralButton(neutralButton) { dialog, _ ->
                        prefs.putBoolean("root_warning_dont_show_again", true)
                        dialog.dismiss()
                    }
                    .setCancelable(false)
                    .create()
                dialog.show()

            } catch (e: Exception) {
                LogUtils.e("MAIN", "❌❌❌ Critical error showing root security dialog", e)
                Toast.makeText(this, getString(R.string.root_detected_title), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setupSettings() {
        settingsHeader.setOnClickListener {
            toggleSettingsVisibility()
        }
        collapseSettings()
    }

    private fun toggleSettingsVisibility() {
        if (isSettingsExpanded) {
            collapseSettings()
        } else {
            expandSettings()
        }
    }

    private fun expandSettings() {
        settingsContent.visibility = View.VISIBLE
        settingsExpandIcon.rotation = 180f
        isSettingsExpanded = true
    }

    private fun collapseSettings() {
        settingsContent.visibility = View.GONE
        settingsExpandIcon.rotation = 0f
        isSettingsExpanded = false
    }

    private fun setupYSettings() {
        settingsYHeader.setOnClickListener {
            toggleYSettingsVisibility()
        }
        collapseYSettings()
    }

    private fun toggleYSettingsVisibility() {
        if (isSettingsYExpanded) {
            collapseYSettings()
        } else {
            expandYSettings()
        }
    }

    private fun expandYSettings() {
        settingsYContent.visibility = View.VISIBLE
        settingsYExpandIcon.rotation = 180f
        isSettingsYExpanded = true
    }

    private fun collapseYSettings() {
        settingsYContent.visibility = View.GONE
        settingsYExpandIcon.rotation = 0f
        isSettingsYExpanded = false
    }

    private fun showNewChatDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.create_chat_title))
            .setMessage(getString(R.string.create_chat_message))
            .setPositiveButton(getString(R.string.create_y_chat_button)) { dialog, _ ->
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.create_sms_chat_button)) { dialog, _ ->
                addNewSmsChat()
                dialog.dismiss()
            }
            .setNeutralButton(getString(R.string.cancel)) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun addNewSmsChat() {
        val prefs = SharedPreferencesManager.getInstance(this)
        val allContactsAllowed = prefs.useAllContacts
        val hasActiveTrustedContacts = prefs.getActiveTrustedContacts().isNotEmpty()

        if (!allContactsAllowed && !hasActiveTrustedContacts) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.chat_creation_blocked_title))
                .setMessage(getString(R.string.chat_creation_blocked_message))
                .setPositiveButton(getString(R.string.ok)) { dialog, _ ->
                    dialog.dismiss()
                }
                .setNeutralButton(getString(R.string.configure_settings)) { dialog, _ ->
                    if (!isSettingsExpanded) {
                        expandSettings()
                    }
                    dialog.dismiss()
                }
                .show()
            return
        }

        if (!checkContactsPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_CONTACTS),
                PERMISSION_REQUEST_CONTACTS
            )
            return
        }

        val existingChats = chatManager.getAllConversations()
        val existingPhoneNumbers: Set<String> = existingChats.map { PhoneUtils.normalizePhoneNumber(it.phoneNumber) }.toSet()

        if (prefs.useAllContacts) {
            showAllContactsSelector(existingPhoneNumbers)
        } else {
            utils.showTrustedContactsSelector(prefs, chatManager, existingPhoneNumbers)
        }
    }

    private fun showAllContactsSelector(existingPhoneNumbers: Set<String>) {
        val contacts = utils.getAllContacts()
        val availableContacts = contacts.filter { contact ->
            val normalizedNumber = PhoneUtils.normalizePhoneNumber(contact.phoneNumber)
            !existingPhoneNumbers.contains(normalizedNumber)
        }

        if (availableContacts.isEmpty()) {
            showToast(getString(R.string.all_contacts_already_in_chat))
            return
        }

        utils.showSearchDialog(
            contacts = availableContacts,
            title = getString(R.string.search_contact_for_new_chat_title),
            onContactSelected = { contact ->
                createSmsChat(contact.phoneNumber, contact.displayName)
            }
        )
    }

    fun createSmsChat(phoneNumber: String, contactName: String?) {
        chatManager.createNormalChat(phoneNumber, contactName)
        loadChatConversations()
        //utils.openChatForContact(chatManager, prefs, phoneNumber) // do not switch directly to chat, set it first
        showToast(getString(R.string.sms_chat_created_with_contact, contactName ?: phoneNumber))
    }

    private fun setVersionFooter() {
        try {
            val versionFooter: TextView? = findViewById(R.id.version_footer)

            if (versionFooter == null) {
                LogUtils.e("MAIN", "❌ version_footer TextView not found!")
                return
            }

            val tap2open = getString(R.string.tap2open)

            versionFooter.text = "NooK ${Constants.VERSION} - secure SMS ${Constants.COPYRIGHT} [${tap2open}]"
            versionFooter.setOnClickListener {
                openUrlInBrowser(Constants.VISITME)
            }
            versionFooter.isClickable = true
            versionFooter.setTextColor(ContextCompat.getColor(this, R.color.intensive_green))

            LogUtils.e("MAIN", "✅ Version footer set")
        } catch (e: Exception) {
            LogUtils.e("MAIN", "❌ Error in setVersionFooter", e)
        }
    }

    private fun openUrlInBrowser(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.toast_cannot_open_browser), Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateAllContactsInfoVisibility() {
        allContactsInfo.visibility = if (prefs.useAllContacts) View.VISIBLE else View.GONE
    }

    fun loadChatConversations() {
        Thread {
            val conversations = chatManager.getAllConversations()
                .sortedByDescending { it.lastTimestamp }

            runOnUiThread {
                if (this::chatAdapter.isInitialized) {
                    chatAdapter.updateConversations(conversations)
                }
                LogUtils.e("MAIN", "Chats loaded: ${conversations.size}")
            }
        }.start()
    }

    private fun setupToggleLabelClickListeners() {
        val togglePairs = listOf(
            Pair(silentModeToggle, getString(R.string.silent_mode_title)),
            Pair(logToggle, getString(R.string.enable_log_title)),
            Pair(useAllContactsToggle, getString(R.string.extend_to_all_contacts_title)),
            Pair(allowScreenshotsToggle, getString(R.string.allow_screenshots_title)),
            Pair(vibrationToggle, getString(R.string.vibration_title)),
            Pair(appProtectionToggle, getString(R.string.app_protection_title))
        )

        togglePairs.forEach { (toggle, labelText) ->
            val parentLayout = toggle.parent as? LinearLayout
            parentLayout?.let { layout ->
                layout.isClickable = true
                layout.isFocusable = true

                for (i in 0 until layout.childCount) {
                    val child = layout.getChildAt(i)
                    if (child is TextView && child.text.toString() == labelText) {
                        child.setOnClickListener {
                            toggle.performClick()
                        }
                        break
                    }
                }

                layout.setOnClickListener {
                    toggle.performClick()
                }
            }
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECEIVE_SMS)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.READ_SMS)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.SEND_SMS)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.READ_CONTACTS)
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(),
                PERMISSION_REQUEST_SMS)
        } else {
            if (prefs.shouldShowToast()) {
                Toast.makeText(this, getString(R.string.permissions_granted), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun checkContactsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun requestContactsPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.READ_CONTACTS),
            PERMISSION_REQUEST_CONTACTS
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            PERMISSION_REQUEST_NOTIFICATIONS -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    LogUtils.e("MAIN", "✅ Notification permission granted")
                    showToast(getString(R.string.toast_notification_permission_granted))
                    // Ora procedi con gli altri permessi
                    Handler(Looper.getMainLooper()).postDelayed({
                        proceedWithOtherPermissions()
                    }, 500)
                } else {
                    LogUtils.e("MAIN", "❌ Notification permission denied")
                    showToast(getString(R.string.toast_notifications_may_not_work))
                    // Procedi comunque con gli altri permessi
                    Handler(Looper.getMainLooper()).postDelayed({
                        proceedWithOtherPermissions()
                    }, 500)
                }
            }

            PERMISSION_REQUEST_SMS -> {
                val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                if (allGranted) {
                    showToast(getString(R.string.permissions_granted))

                    // Ricontrolla permesso contatti separatamente se necessario
                    if (!checkContactsPermission()) {
                        Handler(Looper.getMainLooper()).postDelayed({
                            AlertDialog.Builder(this)
                                .setTitle(getString(R.string.alert_contacts_permission_title))
                                .setMessage(getString(R.string.alert_contacts_permission_message))
                                .setPositiveButton(getString(R.string.alert_grant)) { _, _ ->
                                    ActivityCompat.requestPermissions(
                                        this,
                                        arrayOf(Manifest.permission.READ_CONTACTS),
                                        PERMISSION_REQUEST_CONTACTS
                                    )
                                }
                                .setNegativeButton(getString(R.string.alert_later)) { dialog, _ ->
                                    dialog.dismiss()
                                    showToast(getString(R.string.toast_grant_permissions_later))
                                }
                                .show()
                        }, 1000)
                    }
                } else {
                    showToast(getString(R.string.some_permissions_denied))

                    // Mostra quali permessi sono stati negati
                    val deniedPermissions = mutableListOf<String>()
                    permissions.forEachIndexed { index, permission ->
                        if (grantResults.getOrNull(index) != PackageManager.PERMISSION_GRANTED) {
                            deniedPermissions.add(
                                when (permission) {
                                    Manifest.permission.RECEIVE_SMS -> getString(R.string.alert_permission_receive_sms)
                                    Manifest.permission.READ_SMS -> getString(R.string.alert_permission_read_sms)
                                    Manifest.permission.SEND_SMS -> getString(R.string.alert_permission_send_sms)
                                    Manifest.permission.READ_CONTACTS -> getString(R.string.alert_permission_read_contacts)
                                    else -> permission
                                }
                            )
                        }
                    }

                    if (deniedPermissions.isNotEmpty()) {
                        AlertDialog.Builder(this)
                            .setTitle(getString(R.string.alert_permissions_denied_title))
                            .setMessage(getString(R.string.alert_permissions_denied_message, deniedPermissions.joinToString("\n• ")))
                            .setPositiveButton(getString(R.string.alert_ok), null)
                            .show()
                    }
                }
            }

            PERMISSION_REQUEST_CONTACTS -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    showToast(getString(R.string.contacts_permission_granted))
                } else {
                    showToast(getString(R.string.contacts_permission_denied))
                }
            }
        }
    }
    inner class DebouncedTextWatcher(
        private val delayMillis: Long = 1500L,
        private val onTextChanged: (String) -> Unit
    ) : TextWatcher {

        private var timer: Timer? = null
        private var lastText: String = ""

        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

        override fun afterTextChanged(editable: Editable?) {
            val currentText = editable?.toString() ?: ""

            if (currentText == lastText) return

            lastText = currentText

            timer?.cancel()

            timer = Timer().apply {
                schedule(object : TimerTask() {
                    override fun run() {
                        onTextChanged(currentText)
                    }
                }, delayMillis)
            }
        }

        fun cancel() {
            timer?.cancel()
            timer = null
        }
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        AppStateTracker.onActivityResumed(this)
        weakInstance = WeakReference(this)
        prefsInstance = prefs
        if (prefs.appProtectionEnabled) {
            if (this::appLockManager.isInitialized && appLockManager.isAppCurrentlyLocked()) {
                LogUtils.e("MAIN", "🔒 App blocked in onResume, show block screen")

                // Hide asap interface if visible
                if (isAppInitialized) {
                    // If app initialized, show block screen
                    showPasswordPromptDialog()
                }
                return
            }
        }

        // If it is not blocked, update activity timer
        if (this::appLockManager.isInitialized) {
            appLockManager.updateLastActiveTime()
        }

        if (isUIInitialized) {
            applyScreenshotSecurity()
            updateUIStates()
        } else {
            LogUtils.e("MAIN", "⚠️ UI not yet initialized, skipping update in onResume")
        }
    }

    private fun updateUIStates() {
        // Show logs only in debug mode
        if (isUIInitialized) {

            silentModeToggle.isChecked = prefs.silentMode

            // show Log toggle only in DEBUG
            if (BuildConfig.DEBUG) {
                logToggleContainer.visibility = View.VISIBLE
            }
            logToggle.isChecked = prefs.logEnabled
            useAllContactsToggle.isChecked = prefs.useAllContacts
            updateAllContactsInfoVisibility()
        }
    }

    private fun applyScreenshotSecurity() {
        val prefs = SharedPreferencesManager.getInstance(this)

        if (!shouldApplyScreenshotProtection()) {
            LogUtils.e("MAIN", "⚠️ ROM not supported for screenshot blocking")
            return
        }

        if (!prefs.allowScreenshots) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            LogUtils.e("MAIN", "✅ FLAG_SECURE applied")
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            LogUtils.e("MAIN", "❌ FLAG_SECURE removed")
        }
    }

    private fun shouldApplyScreenshotProtection(): Boolean {
        return when {
            Build.VERSION.SDK_INT == 36 &&
                    Build.FINGERPRINT.contains("google") &&
                    Build.VERSION.RELEASE == "16" -> {
                LogUtils.e("MAIN", "LineageOS 23/Android 16 - skipping FLAG_SECURE")
                false
            }
            Build.MANUFACTURER.contains("samsung", ignoreCase = true) &&
                    Build.VERSION.SDK_INT == 33 -> {
                true
            }
            else -> true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isAppInitialized) {
            try {
                unregisterReceiver(chatUpdateReceiver)
            } catch (e: IllegalArgumentException) {
            }
            keyboardSafetyManager.cleanup()
            appLockManager.stopMonitoring()
        }
    }

    override fun onPause() {
        super.onPause()
        if (isAppInitialized) {
            AppStateTracker.onActivityPaused(this)
            weakInstance?.clear()
            weakInstance = null
            prefsInstance = null
        }
    }



    private fun handleIntentExtras() {
        try {
            if (intent?.getBooleanExtra("show_message", false) == true) {
                val decodedText = intent.getStringExtra("decoded_text")
                val sender = intent.getStringExtra("sender")
                val senderName = intent.getStringExtra("sender_name")
                showDecodedMessageDialog(decodedText, sender, senderName)
            }

            val shouldOpenChat = intent.getBooleanExtra("open_chat", false)
            val phoneNumberToOpen = intent.getStringExtra("phone_number")

            if (shouldOpenChat && !phoneNumberToOpen.isNullOrEmpty()) {
                Handler(Looper.getMainLooper()).postDelayed({
                    utils.openChatForContact(chatManager, prefs, phoneNumberToOpen)
                }, 300)
            }

            if (intent.getBooleanExtra("create_chat", false)) {
                val phoneNumber = intent.getStringExtra("phone_number")
                val contactName = intent.getStringExtra("contact_name")

                if (!phoneNumber.isNullOrEmpty()) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        createSmsChat(phoneNumber, contactName)
                    }, 500)
                }
            }
        } catch (e: Exception) {
            LogUtils.e("MAIN", "Error handling intent extras", e)
        }
    }

    private fun showDecodedMessageDialog(decodedText: String?, sender: String?, senderName: String?) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.new_message_from, senderName ?: sender ?: ""))
            .setMessage(decodedText)
            .setPositiveButton(getString(R.string.open_chat)) { _, _ ->
                utils.openChatForContact(chatManager, prefs, sender ?: "")
            }
            .setNegativeButton(getString(R.string.ok), null)
            .show()
    }

    private val soundPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val data: Intent? = result.data
            val uri = getRingtoneUriFromIntent(data)
            soundManager.handleSoundSelectionResult(uri, this)
            notificationSoundName.text = soundManager.updateSoundNameDisplay(prefs.notificationSoundUri)
        } else if (result.resultCode == RESULT_CANCELED) {
            LogUtils.e("MAIN", "Sound selection cancelled")
        }
    }

    private fun getRingtoneUriFromIntent(data: Intent?): Uri? {
        if (data == null) return null

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == Constants.REQUEST_CODE_SOUND_PICKER && resultCode == RESULT_OK && data != null) {
            val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            }

            soundManager.handleSoundSelectionResult(uri, this)
            notificationSoundName.text = soundManager.updateSoundNameDisplay(prefs.notificationSoundUri)
        }
    }

    private fun getRealPathFromURI(uri: Uri): String? {
        return try {
            val contentResolver = contentResolver
            val displayName: String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        cursor.getString(nameIndex)
                    } else {
                        null
                    }
                }
            } else {
                uri.lastPathSegment?.substringAfterLast("/")
            }

            val fileName = displayName ?: "imported_file_${System.currentTimeMillis()}"

            // Crea file nella cache con il nome originale
            val tempFile = File(cacheDir, fileName)

            contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(tempFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
                tempFile.absolutePath
            }
        } catch (e: Exception) {
            LogUtils.e("MainActivity", "Errore getRealPathFromURI", e)
            null
        }
    }

    override fun launchSoundPicker(intent: Intent) {
        soundPickerLauncher.launch(intent)
    }

    private fun setupNotificationSoundPreferences() {
        LogUtils.e("MAIN", "🎵 Initializing notification sound preferences")

        notificationSoundName = findViewById(R.id.notification_sound_name)
        notificationSoundContainer = findViewById(R.id.notification_sound_container)
        testNotificationContainer = findViewById(R.id.test_notification_container)

        // Load prefs
        val (soundUri, vibrationEnabled) = soundManager.loadNotificationSoundPreference()

        // Update display
        notificationSoundName.text = soundManager.updateSoundNameDisplay(soundUri)
        vibrationToggle.isChecked = vibrationEnabled

        notificationSoundContainer.setOnClickListener {
            LogUtils.e("MAIN", "🎵 Click on sound container")
            soundManager.showSoundSelectionDialog(this)
        }

        // Configure listener for vibrationToggle
        vibrationToggle.setOnCheckedChangeListener { _, isChecked ->
            prefs.vibrationEnabled = isChecked
            soundManager.updateMainNotificationChannel()
            showToast(if (isChecked) getString(R.string.toast_vibration_enabled) else getString(R.string.toast_vibration_disabled))
            LogUtils.e("MAIN", "📳 Vibration: ${if (isChecked) "ON" else "OFF"}")
        }

        // Test notification
        testNotificationContainer.setOnClickListener {
            soundManager.simulateTestNotification()
        }

        if (BuildConfig.DEBUG) {
            testNotificationContainer.visibility = View.VISIBLE
        }
    }



    fun showModifySmsChatNameDialog(conversation: ChatConversation) {

        val dialogView = layoutInflater.inflate(R.layout.dialog_associate_y_user, null)

        //all fields
        val textPhoneTitle = dialogView.findViewById<TextView>(R.id.text_phone_title)
        val noEditTextPhone = dialogView.findViewById<TextView>(R.id.no_edit_text_phone)

        //val textChatNameTitle = dialogView.findViewById<TextView>(R.id.text_chat_name_title)
        val yUserNameInput = dialogView.findViewById<TextView>(R.id.y_user_name_input)

        val currentName = conversation.contactName ?: conversation.phoneNumber

        // Set phone number
        textPhoneTitle.setText(R.string.number_id)
        noEditTextPhone.text = conversation.phoneNumber

        // Set chat name
        yUserNameInput.text = conversation.contactName

        AlertDialog.Builder(this)
            .setTitle(R.string.edit_chat_name)
            .setView(dialogView)
            .setPositiveButton(getString(R.string.save)) { dialog, _ ->
                val newName = yUserNameInput.text.toString().trim()
                if (newName.isNotEmpty() && newName != currentName) {
                    renameSmsChat(conversation, newName)
                }
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                dialog.dismiss()
            }
            .show()

        yUserNameInput.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(yUserNameInput, InputMethodManager.SHOW_IMPLICIT)

    }


    private fun renameSmsChat(conversation: ChatConversation, newName: String) {
        Thread {
            try {
                runBlocking {
                    databaseActor.updateChatName(conversation.phoneNumber, newName)
                }

                prefs.updateChatName(conversation.phoneNumber, newName)

                chatManager.updateChatName(conversation.phoneNumber, newName)

                runOnUiThread {
                    loadChatConversations()
                    LogUtils.e("MAIN", "✅ Chat renamed: ${conversation.phoneNumber} -> $newName")
                }

            } catch (e: Exception) {
                LogUtils.e("MAIN", "❌ Error renaming chat", e)
                runOnUiThread {
                    showToast(getString(R.string.error_renaming_chat), true)
                }
            }
        }.start()
    }

    private fun showSetAppProtectionPasswordDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_app_protection_password, null)

        val titleText = dialogView.findViewById<TextView>(R.id.dialog_title)
        val messageText = dialogView.findViewById<TextView>(R.id.dialog_message)
        val passwordInput = dialogView.findViewById<EditText>(R.id.password_input)
        val confirmLabel = dialogView.findViewById<TextView>(R.id.confirm_label)
        val confirmPasswordInput = dialogView.findViewById<EditText>(R.id.confirm_password_input)

        titleText.text = getString(R.string.app_protection_dialog_title)
        messageText.text = getString(R.string.app_protection_dialog_message)
        confirmLabel.visibility = View.VISIBLE
        confirmLabel.text = getString(R.string.app_protection_confirm_dialog_message)
        confirmPasswordInput.visibility = View.VISIBLE

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton(getString(R.string.set), null) // NULL per gestire manualmente
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                appProtectionToggle.isChecked = false
                dialog.dismiss()
            }
            .setOnCancelListener {
                appProtectionToggle.isChecked = false
            }
            .create()

        dialog.show()

        val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        positiveButton.setOnClickListener {
            val password = passwordInput.text.toString().trim()
            val confirmPassword = confirmPasswordInput.text.toString().trim()

            if (password.length < 6) {
                showToast(getString(R.string.app_protection_password_too_short), true)
                passwordInput.error = getString(R.string.app_protection_password_too_short)
                return@setOnClickListener
            }

            if (password != confirmPassword) {
                showToast(getString(R.string.app_protection_passwords_not_match), true)
                confirmPasswordInput.error = getString(R.string.app_protection_passwords_not_match)
                return@setOnClickListener
            }

            setAppProtectionPassword(password)
            dialog.dismiss()
        }
    }

    private fun setAppProtectionPassword(password: String) {
        try {
            // Save encrypted password
            val encryptedPassword = CryptoManager.encryptSimplePassword(this, password)
            prefs.appProtectionPassword = encryptedPassword
            prefs.appProtectionEnabled = true

            showToast(getString(R.string.app_protection_set_success))
            LogUtils.e("MAIN", "✅ APP protection enabled")
        } catch (e: Exception) {
            LogUtils.e("MAIN", "❌ Error protection settings", e)
            showToast(getString(R.string.error_generic), true)
            appProtectionToggle.isChecked = false
        }
    }

    private fun removeAppProtection() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.app_protection_title))
            .setMessage(getString(R.string.app_protection_removed))
            .setPositiveButton(getString(R.string.ok)) { dialog, _ ->
                prefs.appProtectionEnabled = false
                prefs.appProtectionPassword = ""
                showToast(getString(R.string.app_protection_disabled))
                LogUtils.e("MAIN", "✅ APP protection disabled")
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                appProtectionToggle.isChecked = true
                dialog.dismiss()
            }
            .show()
    }


    fun showPasswordPromptDialog() {
        if (!prefs.appProtectionEnabled) return

        // Control tries
        if (appLockManager.isLockedDueToFailedAttempts()) {
            showToast(getString(R.string.stop_retry), true)
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_app_unlock, null)

        val passwordInput = dialogView.findViewById<EditText>(R.id.password_input)
        val unlockButton = dialogView.findViewById<Button>(R.id.unlock_button)
        val exitButton = dialogView.findViewById<Button>(R.id.exit_button)

        //GO keyboard
        passwordInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO) {
                unlockButton.performClick()
                true
            }
            false
        }

        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(dialogView)
        dialog.setCancelable(false)
        dialog.show()

        exitButton.setOnClickListener {
            dialog.dismiss()
            finishAffinity()
            finishAndRemoveTask()
        }

        unlockButton.setOnClickListener {
            // disable asap - avoid double clicks
            unlockButton.isEnabled = false

            val enteredPassword = passwordInput.text.toString()

            if (verifyAppProtectionPassword(enteredPassword)) {
                appLockManager.resetFailedAttempts()
                dialog.dismiss()
                recreate()
            } else {
                appLockManager.recordFailedAttempt()

                if (appLockManager.isLockedDueToFailedAttempts()) {
                    dialog.dismiss()
                    showToast(getString(R.string.stop_retry) , true)
                    finishAffinity()
                } else {
                    passwordInput.text.clear()
                    passwordInput.requestFocus()
                    // 🔥 re-enable only if error and non blocked
                    unlockButton.isEnabled = true
                }
            }
        }
    }



    private fun verifyAppProtectionPassword(inputPassword: String): Boolean {
        return try {
            val storedEncryptedPassword = prefs.appProtectionPassword

            if (storedEncryptedPassword.isEmpty()) {
                // No password saved - disable protection
                prefs.appProtectionEnabled = false
                appProtectionToggle.isChecked = false
                return false
            }

            val decryptedPassword = CryptoManager.decryptSimplePassword(this, storedEncryptedPassword)
            val isCorrect = inputPassword == decryptedPassword

            if (isCorrect) {
                LogUtils.e("MAIN", "✅ Password OK - open APP")

                // 🔓 FREE APP
                prefs.isAppLocked = false

                if (!this::appLockManager.isInitialized) {
                    appLockManager = AppLockManager.getInstance(this)
                }
                appLockManager.unlockApp()

                if (!isAppInitialized) {
                    initializeAppLinearly()
                }
            } else {
                LogUtils.e("MAIN", "❌ Password wrong")
            }

            isCorrect
        } catch (e: Exception) {
            LogUtils.e("MAIN", "❌ Error password verification", e)

            prefs.appProtectionEnabled = false
            appProtectionToggle.isChecked = false

            false
        }
    }


    @SuppressLint("ClickableViewAccessibility")
    private fun setupInactivityReset() {
        val rootView = findViewById<View>(android.R.id.content)
        rootView.setOnTouchListener { _, _ ->
            appLockManager.resetInactivityTimer()
            false // do not consume event
        }
    }

    private fun setupAppProtectionToggle() {
        try {
            appProtectionToggle = findViewById(R.id.app_protection_toggle)

            appProtectionToggle.isChecked = prefs.appProtectionEnabled

            appProtectionToggle.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    showSetAppProtectionPasswordDialog()
                } else {
                    removeAppProtection()
                }
            }

            LogUtils.e("MAIN", "✅ App protection toggle initialised: ${prefs.appProtectionEnabled}")
        } catch (e: Exception) {
            LogUtils.e("MAIN", "❌ Error setup app protection toggle", e)
        }
    }

    /**
     * Called when the app title TextView is clicked
     * Immediately activates the app protection lock screen
     */
    fun onAppTitleClick(view: View) {
        LogUtils.e(TAG, "🔒 App title clicked - forcing lock screen")

        // Make sure AppLockManager is initialized
        if (!this::appLockManager.isInitialized) {
            appLockManager = AppLockManager.getInstance(this)
        }

        // Force lock the app immediately
        appLockManager.lockAppImmediately()

        if (prefs.appProtectionEnabled) {
            // Block app in SharedPreferences
            prefs.isAppLocked = true

            // Mostra il dialog di sblocco
            showPasswordPromptDialog()

            LogUtils.e(TAG, "🔒 Lock screen displayed")
        }
    }

}