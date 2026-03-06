package solutions.semweb.nook

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.BackgroundServiceStartNotAllowedException
import android.app.Dialog
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
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
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern

class MainActivity : AppCompatActivity(), MainActivitySoundPicker {

    private var apkFileToInstall: File? = null
    private lateinit var updateChecker: UpdateChecker
    private lateinit var updateBadgeContainer: LinearLayout
    private lateinit var updateBadgeIcon: ImageView
    private lateinit var updateBadgeText: TextView
    private lateinit var upgradeNookBtn: Button
    private var isCheckingUpdates = AtomicBoolean(false)
    private lateinit var shaVerificationManager: ShaVerificationManager
    private lateinit var shaStatusIcon: ImageView
    private lateinit var shaTimestamp: TextView
    private var isShaVerificationComplete = false
    private var shaVerificationReceiver: BroadcastReceiver? = null
    private lateinit var appProtectionToggle: Switch
    private lateinit var pureSmsToggle: Switch
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

        try {
            LogUtils.e("MAIN", "🚀 onCreate() - LINEAR APPROACH")
            setContentView(R.layout.activity_main)

            // 1. Init utils
            utils = MainActivityUtils(this)

            // 2. Init prefs now
            prefs = SharedPreferencesManager.getInstance(this)

            // 3. START SHA VERIFICATION (do not block yet)
            shaVerificationManager = ShaVerificationManager.getInstance(this)

            // 4. Call SHA test in sinchronically (with callback)
            shaVerificationManager.verifyApkIntegrity(
                forceDownload = true  // First installation - force download
            ) { result ->
                runOnUiThread {
                    handleInitialShaResult(result)
                }
            }

        } catch (e: Exception) {
            LogUtils.e("MAIN", "❌ Error in onCreate", e)
            Toast.makeText(this, getString(R.string.toast_initialization_error, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    private var initialVerificationHandled = false

    private fun handleInitialShaResult(result: ShaVerificationManager.SHAVerificationResult) {
        if (initialVerificationHandled) {
            LogUtils.e("MAIN", "⚠️ Initial SHA result already handled, ignoring duplicate")
            return
        }

        initialVerificationHandled = true

        if (result.isValid) {
            // ✅ SHA OK - normal
            proceedWithNormalInit()
        } else if (result.isOffline) {
            // 🌐 No internet - show choice dialog
            showInitialShaNoInternetDialog()
        } else {
            // ❌ Corrupted - block asap
            showShaCompromisedDialog(result)
        }
    }

    private fun showInitialShaNoInternetDialog() {
        val dialogView = if (prefs.pureSmsMode) {
            // Create custom view with small hint text
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(40, 20, 40, 20)

                // Main message
                TextView(context).apply {
                    text = getString(R.string.sha_verification_no_internet_message)
                    textSize = 16f
                    setPadding(0, 0, 0, 20)
                }.also { addView(it) }

                // Small hint text for Pure SMS mode
                TextView(context).apply {
                    text = "⚠️ ${getString(R.string.sha_pure_sms_hint)}"
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
                    setPadding(0, 0, 0, 0)
                }.also { addView(it) }
            }
        } else {
            null
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.sha_verification_title))
            .setMessage(if (!prefs.pureSmsMode) getString(R.string.sha_verification_no_internet_message) else null)
            .setView(dialogView)
            .setCancelable(false)
            .setPositiveButton(
                if (prefs.pureSmsMode) getString(R.string.sha_retry_with_internet)
                else getString(R.string.sha_verification_retry)
            ) { dialog, _ ->
                // If Pure SMS is active, ask for confirmation before retry
                if (prefs.pureSmsMode) {
                    dialog.dismiss()
                    showPureSmsDeactivationDialog {
                        shaVerificationManager.verifyApkIntegrity(forceDownload = true) { result ->
                            runOnUiThread { handleInitialShaResult(result) }
                        }
                    }
                } else {
                    dialog.dismiss()
                    shaVerificationManager.verifyApkIntegrity(forceDownload = true) { result ->
                        runOnUiThread { handleInitialShaResult(result) }
                    }
                }
            }
            .setNegativeButton(getString(R.string.sha_verification_continue_risk)) { dialog, _ ->
                dialog.dismiss()
                // Continue at risk
                proceedWithNormalInit()
            }
            .setNeutralButton(getString(R.string.sha_verification_exit)) { dialog, _ ->
                dialog.dismiss()
                stopForegroundService()
                finishAffinity()
                finishAndRemoveTask()
            }
            .create()

        dialog.show()
    }


    @RequiresApi(Build.VERSION_CODES.S)
    private fun proceedWithNormalInit() {
        // Controlla disclaimer
        val disclaimerAccepted = prefs.getBoolean("disclaimer_accepted", false)
        if (!disclaimerAccepted) {
            utils.showDisclaimerDialog(prefs, this)
            return
        }

        // Setup app protection toggle
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

        // Init all
        initializeAppLinearly()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun initializeAppLinearly() {
        LogUtils.e("MAIN", "🔄 Linear initialization...")

        try {
            // 0. SHA Verification
            setupShaVerificationUI()

            // 1. Setup basic views FIRST
            setupBasicViews()  // <-- QUI vengono inizializzati tutti i toggle

            // 2. HIDE CHATS
            runOnUiThread {
                chatCard.visibility = View.GONE
            }

            // 2.5 Setup update checker
            setupUpdateChecker()

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

            setupSpinnersAndToggles()  // <-- QUI SI USANO I TOGGLE GIÀ INIZIALIZZATI
            setupButtons()
            setVersionFooter()
            setupSettings()
            setupToggleLabelClickListeners()  // <-- QUESTA DEVE ESSERE DOPO setupSpinnersAndToggles
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
            LogUtils.e("MAIN", "❌ Error in initializeAppLinearly", e)
            LogUtils.e("MAIN", "Error details: ${e.message}")
            LogUtils.e("MAIN", "Stack trace: ${e.stackTraceToString()}")
            Toast.makeText(this, "Initialization Error: ${e.message}", Toast.LENGTH_LONG).show()
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



    /**
     * Open APK directly with package installer
     */
    private fun openApkWithInstallerViaSAF(apkFile: File) {
        try {
            LogUtils.e(TAG, "📲 Opening APK with installer via SAF: ${apkFile.absolutePath}")

            // For Android 7+, use FileProvider
            val apkUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val authority = "${packageName}.fileprovider"
                androidx.core.content.FileProvider.getUriForFile(this, authority, apkFile)
            } else {
                Uri.fromFile(apkFile)
            }

            // Create intent specifically for package installer
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

                // Try to target package installer directly on some devices
                setPackage("com.android.packageinstaller")
            }

            // First try: with specific package
            if (intent.resolveActivity(packageManager) != null) {
                LogUtils.e(TAG, "✅ Package installer found (specific package)")
                startActivity(intent)
                closeAppAfterDelay(killing = true, delay = 1500)
                return
            }

            // Second try: without specific package, but with chooser title
            val genericIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            // Create chooser with explicit title
            val chooser = Intent.createChooser(
                genericIntent,
                getString(R.string.install_apk_chooser_title)
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            if (genericIntent.resolveActivity(packageManager) != null) {
                LogUtils.e(TAG, "✅ Package installer found (via chooser)")
                startActivity(chooser)
                closeAppAfterDelay(killing = true, delay = 1500)
                return
            }

            // Fallback: try different package installer package names
            val packageInstallerPackages = arrayOf(
                "com.google.android.packageinstaller",  // Google/Stock
                "com.android.packageinstaller",          // AOSP
                "com.samsung.android.packageinstaller",  // Samsung
                "com.xiaomi.packageinstaller",           // Xiaomi
                "com.huawei.packageinstaller"            // Huawei
            )

            for (pkg in packageInstallerPackages) {
                val pkgIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(apkUri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    setPackage(pkg)
                }

                if (pkgIntent.resolveActivity(packageManager) != null) {
                    LogUtils.e(TAG, "✅ Package installer found: $pkg")
                    startActivity(pkgIntent)
                    closeAppAfterDelay(killing = true, delay = 1500)
                    return
                }
            }

            // Last resort: show the file location
            LogUtils.e(TAG, "❌ No package installer found")
            showFileLocationDialog(apkFile.absolutePath)

        } catch (e: Exception) {
            LogUtils.e(TAG, "❌ Error opening APK with installer", e)
            showFileLocationDialog(apkFile.absolutePath)
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

    private fun addDebugQueueButton() {
        try {
            // Only show in debug builds
            if (!BuildConfig.DEBUG) return

            // Find the existing ImageButton from XML
            val debugButton = findViewById<ImageButton>(R.id.debug_queue_button)

            if (debugButton != null) {
                // Make it visible and set up click listener
                debugButton.visibility = View.VISIBLE
                debugButton.setOnClickListener {
                    utils.showSMSQueueStatus()
                }
                LogUtils.e("MAIN", "✅ Debug queue button configured")
            } else {
                LogUtils.e("MAIN", "❌ Could not find debug queue button in layout")
            }
        } catch (e: Exception) {
            LogUtils.e("MAIN", "❌ Failed to configure debug button", e)
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
            decodingSchemeSpinner.isEnabled = false
            decodingSchemeSpinner.alpha = 0.7f

            val defaultEncryption = EncryptionMapper.DEFAULT_ENCRYPTION_SCHEME
            val defaultEncoding = EncryptionMapper.DEFAULT_ENCODING
            LogUtils.e("MAIN", "✅ Default - Encryption: $defaultEncryption, Encoding: $defaultEncoding")

            // ==============================================
            // 2. SETUP TOGGLES - SET DATA AND LISTENER
            // ==============================================

            // App Protection Toggle
            appProtectionToggle.isChecked = prefs.appProtectionEnabled
            appProtectionToggle.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    showSetAppProtectionPasswordDialog()
                } else {
                    removeAppProtection()
                }
            }

            // Silent Mode Toggle
            silentModeToggle.isChecked = prefs.silentMode
            silentModeToggle.setOnCheckedChangeListener { _, isChecked ->
                prefs.silentMode = isChecked
                LogUtils.e("MAIN", "${if (isChecked) "Silent" else "Normal"} mode")
                showToast(if (isChecked) getString(R.string.silent_mode_on) else getString(R.string.normal_mode_on))
            }

            // Log Toggle
            logToggle.isChecked = prefs.logEnabled
            LogUtils.updateLoggingEnabled(prefs.logEnabled, this)
            logToggle.setOnCheckedChangeListener { _, isChecked ->
                prefs.logEnabled = isChecked
                LogUtils.updateLoggingEnabled(isChecked, this@MainActivity)
                LogUtils.d(this@MainActivity, "MainActivity", "✅ Log toggle changed to: $isChecked")
                LogUtils.e("MAIN", "Log ${if (isChecked) "enabled" else "disabled"}")
                showToast(getString(R.string.log_status, if (isChecked) "ON" else "OFF"))
            }

            // Use All Contacts Toggle
            useAllContactsToggle.isChecked = prefs.useAllContacts
            updateAllContactsInfoVisibility()
            useAllContactsToggle.setOnCheckedChangeListener { _, isChecked ->
                prefs.useAllContacts = isChecked
                updateAllContactsInfoVisibility()
                LogUtils.e("MAIN", "${if (isChecked) "All" else "Selective"} contacts")
                showToast(if (isChecked) getString(R.string.all_contacts_mode_active) else getString(R.string.selective_mode_active))
            }

            // Allow Screenshots Toggle
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

            // Vibration Toggle
            vibrationToggle.isChecked = prefs.vibrationEnabled
            vibrationToggle.setOnCheckedChangeListener { _, isChecked ->
                prefs.vibrationEnabled = isChecked
                showToast(if (isChecked) getString(R.string.toast_vibration_enabled) else getString(R.string.toast_vibration_disabled))
                LogUtils.e("MAIN", "📳 Vibration: ${if (isChecked) "ON" else "OFF"}")
            }

            // PURE SMS TOGGLE - ORA È INIZIALIZZATO!
            pureSmsToggle.isChecked = prefs.pureSmsMode
            pureSmsToggle.setOnCheckedChangeListener { _, isChecked ->
                prefs.pureSmsMode = isChecked
                LogUtils.e("MAIN", "${if (isChecked) "Pure SMS" else "Normal"} mode")
                showToast(if (isChecked) getString(R.string.pure_sms_mode_active) else getString(R.string.pure_sms_mode_inactive))
            }

            // Add a debug queue button (not in release apks)
            addDebugQueueButton()

            LogUtils.e("MAIN", "✅ Spinners and toggles setup complete")

        } catch (e: Exception) {
            LogUtils.e("MAIN", "❌ Error in setupSpinnersAndToggles", e)
            LogUtils.e("MAIN", "Error details: ${e.message}")
            LogUtils.e("MAIN", "Stack trace: ${e.stackTraceToString()}")
            Toast.makeText(this, "Error setting options: ${e.message}", Toast.LENGTH_LONG).show()
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
        pureSmsToggle = findViewById(R.id.pure_sms_toggle)
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
            val debuginfo = if (BuildConfig.DEBUG) "D" else ""
            versionFooter.text = "NooK ${Constants.VERSION}${debuginfo} - secure SMS ${Constants.COPYRIGHT} [${tap2open}]"
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
            Pair(appProtectionToggle, getString(R.string.app_protection_title)) ,
            Pair(pureSmsToggle, getString(R.string.pure_sms_title))
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

    @RequiresApi(Build.VERSION_CODES.S)
    override fun onResume() {
        super.onResume()
        AppStateTracker.onActivityResumed(this)
        weakInstance = WeakReference(this)
        prefsInstance = prefs

        // Check if we need to start foreground service
        if (prefs.getBoolean("pending_foreground_service", false)) {
            LogUtils.e("MAIN", "🔔 Starting pending foreground service from onResume")
            startForegroundNotification()
        }

        if (prefs.appProtectionEnabled) {
            if (this::appLockManager.isInitialized && appLockManager.isAppCurrentlyLocked()) {
                LogUtils.e("MAIN", "🔒 App blocked in onResume, show block screen")
                if (isAppInitialized) {
                    showPasswordPromptDialog()
                }
                return
            }
        }

        if (this::appLockManager.isInitialized) {
            appLockManager.updateLastActiveTime()
        }

        if (isUIInitialized) {
            applyScreenshotSecurity()
            updateUIStates()
            if (this::updateChecker.isInitialized) {
                checkForUpdates()
            }
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
                shaVerificationReceiver?.let { unregisterReceiver(it) }
                keyboardSafetyManager.cleanup()
                appLockManager.stopMonitoring()
                stopForegroundService()
                val notificationManager = NotificationManagerCompat.from(this)
                notificationManager.cancel(Constants.NOTIFICATION_ID)
                LogUtils.e(TAG, "🛑 ForegroundService destroyed, notification removed")
            } catch (e: IllegalArgumentException) {
                LogUtils.e(TAG, "⚠️ Error during cleanup: ${e.message}")
            }
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
        val simplyExitButton = dialogView.findViewById<Button>(R.id.simply_exit_button)

        titleText.text = getString(R.string.app_protection_dialog_title)
        messageText.text = getString(R.string.app_protection_dialog_message)
        confirmLabel.visibility = View.VISIBLE
        confirmLabel.text = getString(R.string.app_protection_confirm_dialog_message)
        confirmPasswordInput.visibility = View.VISIBLE

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton(getString(R.string.set), null)
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                // Just dismiss and turn off the toggle WITHOUT showing any Toast
                appProtectionToggle.isChecked = false
                dialog.dismiss()
            }
            .setOnCancelListener {
                // Just dismiss and turn off the toggle WITHOUT showing any Toast
                appProtectionToggle.isChecked = false
            }
            .create()

        dialog.show()

        // Handle Simply Exit button
        simplyExitButton.setOnClickListener {
            stopForegroundService()
            dialog.dismiss()
            appProtectionToggle.isChecked = false
            finishAffinity()
            finishAndRemoveTask()
        }

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

            // Save encrypted password
            try {
                val encryptedPassword = CryptoManager.encryptSimplePassword(this, password)
                prefs.appProtectionPassword = encryptedPassword
                prefs.appProtectionEnabled = true

                // ALWAYS show success Toast when SET is clicked (whether from toggle or app title)
                showToast(getString(R.string.app_protection_set_success))

                LogUtils.e("MAIN", "✅ APP protection enabled")
            } catch (e: Exception) {
                LogUtils.e("MAIN", "❌ Error protection settings", e)
                showToast(getString(R.string.error_generic), true)
                appProtectionToggle.isChecked = false
            }

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
        // Simply disable protection with a Toast confirmation
        prefs.appProtectionEnabled = false
        prefs.appProtectionPassword = ""
        showToast(getString(R.string.app_protection_disabled))
        LogUtils.e("MAIN", "✅ APP protection disabled")
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
            stopForegroundService()
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
                    // Normal flow from toggle - show toast
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
     * If app lock is enabled: immediately activates the app protection lock screen
     * If app lock is disabled: enables it and shows password setup dialog
     */
    fun onAppTitleClick(view: View) {
        LogUtils.e(TAG, "🔒 App title clicked")

        if (prefs.appProtectionEnabled) {
            // Case 1: App lock is already enabled - force lock screen
            LogUtils.e(TAG, "🔒 App lock enabled - forcing lock screen")

            if (!this::appLockManager.isInitialized) {
                appLockManager = AppLockManager.getInstance(this)
            }

            appLockManager.lockAppImmediately()
            prefs.isAppLocked = true
            showPasswordPromptDialog()

            LogUtils.e(TAG, "🔒 Lock screen displayed")
        } else {
            // Case 2: App lock is disabled - enable it and show setup dialog WITH silent mode
            LogUtils.e(TAG, "🔒 App lock disabled - enabling and showing setup (silent mode)")

            // Pass true for silentMode (no toast)
            showSetAppProtectionPasswordDialog()
        }
    }

    fun stopForegroundService() {
        try {
            LogUtils.e(TAG, "🛑 stopForegroundService() called")

            // First, try to stop the service normally
            val intent = Intent(this, ForegroundService::class.java)
            stopService(intent)

            // Send broadcast to service for Android 16+
            val stopIntent = Intent("${Constants.mainpackage}.STOP_FOREGROUND_SERVICE")
            sendBroadcast(stopIntent)
            LogUtils.e(TAG, "📢 Stop broadcast sent")

            // Cancel NooK notification directly as fallback
            val notificationManager = NotificationManagerCompat.from(this)
            notificationManager.cancel(2)  // NOTIFICATION_ID = 2

            LogUtils.e(TAG, "✅ All stop commands sent")
        } catch (e: Exception) {
            LogUtils.e(TAG, "❌ Error stopping foreground service", e)
        }
    }

    private fun performManualShaVerification() {
        // Questa è SOLO per verifiche manuali (click dell'utente)
        shaVerificationManager.verifyApkIntegrity(
            forceDownload = false
        ) { result ->
            runOnUiThread {
                handleShaVerificationResult(result)
            }
        }
    }

    private fun setupShaVerificationUI() {
        shaStatusIcon = findViewById(R.id.sha_status_icon)
        shaTimestamp = findViewById(R.id.sha_timestamp)
        val shaVerifiedText = findViewById<TextView>(R.id.sha_verified_text)

        shaVerificationManager = ShaVerificationManager.getInstance(this)

        // Create a common click listener for all SHA elements
        val shaClickableListener = View.OnClickListener {
            LogUtils.e("MAIN", "🔄 Manual SHA verification triggered by user click")
            // SOLO verifiche manuali, non automatiche
            performManualShaVerification()
        }

        // Add click listener to all SHA elements
        shaStatusIcon.setOnClickListener(shaClickableListener)
        shaTimestamp.setOnClickListener(shaClickableListener)
        shaVerifiedText.setOnClickListener(shaClickableListener)

        // Make all elements clickable and focusable
        listOf(shaStatusIcon, shaTimestamp, shaVerifiedText).forEach { view ->
            view.isClickable = true
            view.isFocusable = true
        }

        // Register receiver for background failure notifications
        registerShaVerificationReceiver()
    }


    /**
     * Setup SHA Failure notifications
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerShaVerificationReceiver() {
        val filter = IntentFilter("${Constants.mainpackage}.SHA_VERIFICATION_FAILED")

        shaVerificationReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent?.getParcelableExtra("result", ShaVerificationManager.SHAVerificationResult::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent?.getParcelableExtra("result")
                }

                if (result != null) {
                    runOnUiThread {
                        // SKULL
                        shaStatusIcon.setImageResource(R.drawable.ic_skull)
                        shaStatusIcon.visibility = View.VISIBLE
                        shaTimestamp.visibility = View.GONE

                        // Show only it not yet shown
                        if (!initialVerificationHandled) {
                            showShaCompromisedDialog(result)
                        }
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(shaVerificationReceiver, filter, RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(shaVerificationReceiver, filter)
        }
    }

    /**
     * Esegue verifica SHA
     */
    private fun performShaVerification() {
        shaVerificationManager.verifyApkIntegrity(
            forceDownload = false
        ) { result ->
            runOnUiThread {
                handleShaVerificationResult(result)
            }
        }
    }


    private fun handleShaVerificationResult(result: ShaVerificationManager.SHAVerificationResult) {
        isShaVerificationComplete = true

        val shaTimestamp = findViewById<TextView>(R.id.sha_timestamp)
        val shaVerifiedText = findViewById<TextView>(R.id.sha_verified_text)
        val shaStatusIcon = findViewById<ImageView>(R.id.sha_status_icon)

        // Ensure all SHA elements remain clickable
        listOf(shaStatusIcon, shaTimestamp, shaVerifiedText).forEach { view ->
            view.isClickable = true
            view.isFocusable = true
        }

        if (result.isValid) {
            // ✅ OK - show icon (yellow if offline, green if online)
            if (result.isOffline) {
                shaVerifiedText.text = getString(R.string.app_verified_offline)
                shaVerifiedText.setTextColor(ContextCompat.getColor(this, R.color.orange_yellow))
                shaStatusIcon.setImageResource(R.drawable.ic_shield_orange_yellow)
            }
            else {
                shaVerifiedText.text = getString(R.string.app_verified)
                shaVerifiedText.setTextColor(ContextCompat.getColor(this, R.color.middle_green))
                shaStatusIcon.setImageResource(R.drawable.ic_shield_green)
            }
            shaStatusIcon.visibility = View.VISIBLE
            shaVerifiedText.visibility = View.VISIBLE
            shaTimestamp.text = shaVerificationManager.formatShortTimestamp(result.timestamp)
            shaTimestamp.visibility = View.VISIBLE

            LogUtils.e("MAIN", "✅ SHA Verification OK - ${result.version}")

        } else if (result.isOffline) {
            // 🌐 NO INTERNET - show only red shield
            shaStatusIcon.setImageResource(R.drawable.ic_shield_aaa)
            shaStatusIcon.visibility = View.VISIBLE
            shaVerifiedText.visibility = View.GONE
            shaTimestamp.visibility = View.GONE

            // Show dialog only if it is a manual verification or not yet shown
            if (!initialVerificationHandled) {
                showShaNoInternetDialog()
            }

        } else {
            // ❌ COMPROMISED APP - show skull
            shaStatusIcon.setImageResource(R.drawable.ic_skull)
            shaStatusIcon.visibility = View.VISIBLE
            shaVerifiedText.visibility = View.GONE
            shaTimestamp.visibility = View.GONE

            // Show dialog only if manual verification or not yet shown once
            if (!initialVerificationHandled) {
                showShaCompromisedDialog(result)
            }
        }
    }


    private var isNoInternetDialogShowing = false
    private var isCompromisedDialogShowing = false

    /**
     * Show dialog for missing internet
     */
    private fun showShaNoInternetDialog() {
        // Prevent multiple dialogs
        if (isNoInternetDialogShowing) return
        isNoInternetDialogShowing = true

        val dialogView = if (prefs.pureSmsMode) {
            // Create custom view with small hint text
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(40, 20, 40, 20)

                // Main message
                TextView(context).apply {
                    text = getString(R.string.sha_verification_no_internet_message)
                    textSize = 16f
                    setPadding(0, 0, 0, 20)
                }.also { addView(it) }

                // Small hint text for Pure SMS mode
                TextView(context).apply {
                    text = "⚠️ ${getString(R.string.sha_pure_sms_hint)}"
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
                    setPadding(0, 0, 0, 0)
                }.also { addView(it) }
            }
        } else {
            null
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.sha_verification_title))
            .setMessage(if (!prefs.pureSmsMode) getString(R.string.sha_verification_no_internet_message) else null)
            .setView(dialogView)
            .setCancelable(false)
            .setPositiveButton(
                if (prefs.pureSmsMode) getString(R.string.sha_retry_with_internet)
                else getString(R.string.sha_verification_retry)
            ) { dialog, _ ->
                // If Pure SMS is active, ask for confirmation before retry
                if (prefs.pureSmsMode) {
                    dialog.dismiss()
                    showPureSmsDeactivationDialog {
                        performShaVerification()
                    }
                } else {
                    dialog.dismiss()
                    performShaVerification()
                }
            }
            .setNegativeButton(getString(R.string.sha_verification_continue_risk)) { dialog, _ ->
                isNoInternetDialogShowing = false
                dialog.dismiss()
                // Continue at risk
                shaStatusIcon.setImageResource(R.drawable.ic_shield_aaa)
                shaStatusIcon.visibility = View.VISIBLE

                val disclaimerAccepted = prefs.getBoolean("disclaimer_accepted", false)
                if (!disclaimerAccepted) {
                    utils.showDisclaimerDialog(prefs, this)
                } else {
                    if (!isAppInitialized) {
                        initializeAppLinearly()
                    }
                }
            }
            .setNeutralButton(getString(R.string.sha_verification_exit)) { dialog, _ ->
                isNoInternetDialogShowing = false
                dialog.dismiss()
                stopForegroundService()
                finishAffinity()
                finishAndRemoveTask()
            }
            .setOnDismissListener {
                isNoInternetDialogShowing = false
            }
            .create()

        dialog.show()
    }

    private fun showPureSmsDeactivationDialog(onConfirmed: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.pure_sms_deactivation_title))
            .setMessage(getString(R.string.pure_sms_deactivation_message))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.pure_sms_deactivation_just_now)) { dialog, _ ->
                // Disable Pure SMS just for this time
                dialog.dismiss()

                // Show info toast
                showToast(getString(R.string.pure_sms_temporarily_disabled))

                // Proceed with action
                onConfirmed.invoke()
            }
            .setNeutralButton(getString(R.string.pure_sms_deactivation_always)) { dialog, _ ->
                // Disable Pure SMS permanently
                prefs.pureSmsMode = false
                pureSmsToggle.isChecked = false

                dialog.dismiss()

                // Show info toast
                showToast(getString(R.string.pure_sms_permanently_disabled))

                // Proceed with action
                onConfirmed.invoke()
            }
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                dialog.dismiss()
                // Return to previous dialog (cancel operation)
            }
            .show()
    }


    /**
     * Show dialog for compromised App - FORCE EXIT
     */
    private fun showShaCompromisedDialog(result: ShaVerificationManager.SHAVerificationResult) {
        var message = result.message

        // Add APK info
        if (result.apkInfo != null) {
            val apkInfo = result.apkInfo
            message += "\n\n📱 APK Installation Info:" +
                    "\n• ExpHash: ${apkInfo.expectedHash}" +
                    "\n• ApkHash: ${apkInfo.apkHash}" +
                    "\n• Path: ${apkInfo.path}" +
                    "\n• Last Modified: ${ShaVerificationManager.getInstance(this).formatTimestamp(apkInfo.lastModified)}"
        }

        val dialogView = if (prefs.pureSmsMode) {
            // Create custom view with small hint text
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(40, 20, 40, 20)

                // Main message
                TextView(context).apply {
                    text = "${getString(R.string.sha_verification_compromised_message, BuildConfig.VERSION_NAME)}\n\n$message"
                    textSize = 14f
                    setPadding(0, 0, 0, 20)
                }.also { addView(it) }

                // Small hint text for Pure SMS mode
                TextView(context).apply {
                    text = "⚠️ ${getString(R.string.sha_pure_sms_hint)}"
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
                    setPadding(0, 0, 0, 0)
                }.also { addView(it) }
            }
        } else {
            null
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.sha_verification_title))
            .setMessage(if (!prefs.pureSmsMode) "${getString(R.string.sha_verification_compromised_message, BuildConfig.VERSION_NAME)}\n\n$message" else null)
            .setView(dialogView)
            .setCancelable(true)
            .setPositiveButton(getString(R.string.sha_verification_exit)) { _, _ ->
                stopForegroundService()
                finishAffinity()
                finishAndRemoveTask()
            }
            .show()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun setupUpdateChecker() {
        updateChecker = UpdateChecker.getInstance(this)
        updateBadgeContainer = findViewById(R.id.update_badge_container)
        updateBadgeIcon = findViewById(R.id.update_badge_icon)
        updateBadgeText = findViewById(R.id.update_badge_text)
        upgradeNookBtn = findViewById(R.id.upgrade_nook_btn)

        // Make badge clickable
        updateBadgeContainer.setOnClickListener {
            showUpgradeDialog()
        }

        // Setup upgrade button
        upgradeNookBtn.setOnClickListener {
            showUpgradeDialog()
        }

        // Check for updates in background
        checkForUpdates()
    }

    private fun checkForUpdates(forceCheck: Boolean = false) {
        if (isCheckingUpdates.get()) return

        isCheckingUpdates.set(true)

        updateChecker.checkForUpdates(forceCheck) { updateInfo ->
            runOnUiThread {
                isCheckingUpdates.set(false)

                if (updateInfo.isUpdateAvailable) {
                    // Show badge
                    updateBadgeContainer.visibility = View.VISIBLE

                    // Optionally show a discreet toast
                    if (forceCheck) {
                        showToast(getString(R.string.new_version_available) + ": " + updateInfo.latestVersion)
                    }
                } else {
                    // Hide badge
                    updateBadgeContainer.visibility = View.GONE

                    // Show message if force check
                    if (forceCheck) {
                        showToast(getString(R.string.already_latest))
                    }
                }
            }
        }
    }

    private fun showUpgradeDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_upgrade, null)

        val currentVersionText = dialogView.findViewById<TextView>(R.id.current_version_text)
        val latestVersionText = dialogView.findViewById<TextView>(R.id.latest_version_text)
        val versionsContainer = dialogView.findViewById<LinearLayout>(R.id.versions_container)
        val downloadProgress = dialogView.findViewById<ProgressBar>(R.id.download_progress)
        val progressText = dialogView.findViewById<TextView>(R.id.progress_text)
        val cancelButton = dialogView.findViewById<Button>(R.id.cancel_button)
        val upgradeButton = dialogView.findViewById<Button>(R.id.upgrade_button)
        val versionsLabel = dialogView.findViewById<TextView>(R.id.available_versions_label)
        val versionsScrollview = dialogView.findViewById<ScrollView>(R.id.versions_scrollview)

        val currentVersion = BuildConfig.VERSION_NAME

        currentVersionText.text = getString(R.string.current_version, currentVersion)
        latestVersionText.text = getString(R.string.checking_for_updates)

        // HIDE THE UPGRADE BUTTON COMPLETELY
        upgradeButton.visibility = View.GONE

        // Add Pure SMS hint if active
        if (prefs.pureSmsMode) {
            val titleContainer = dialogView.findViewById<LinearLayout>(R.id.title_container)
            titleContainer?.let { container ->
                // Check if hint already exists to avoid duplicates
                if (container.childCount < 2 || container.getChildAt(1) !is TextView) {
                    val hintText = TextView(this).apply {
                        text = "⚠️ ${getString(R.string.sha_pure_sms_hint)}"
                        textSize = 12f
                        setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.darker_gray))
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            setMargins(44, 4, 0, 0) // 44dp to align under the title text (32dp icon + 12dp margin)
                        }
                    }
                    container.addView(hintText)
                }
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.show()

        // Fetch available versions
        Thread {
            try {
                val versions = fetchAvailableVersions()

                runOnUiThread {
                    if (versions.isEmpty()) {
                        // If Pure SMS is active, show appropriate message with options
                        if (prefs.pureSmsMode) {
                            latestVersionText.text = getString(R.string.update_check_pure_sms)
                            versionsLabel.visibility = View.GONE
                            versionsScrollview.visibility = View.GONE

                            // Create layout for options
                            val optionsLayout = LinearLayout(this@MainActivity).apply {
                                orientation = LinearLayout.VERTICAL
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                ).apply {
                                    setMargins(0, 16, 0, 0)
                                }
                            }

                            // Add hint text
                            val hintTextView = TextView(this@MainActivity).apply {
                                text = getString(R.string.pure_sms_deactivation_message)
                                textSize = 14f
                                setPadding(0, 0, 0, 16)
                            }
                            optionsLayout.addView(hintTextView)

                            // "Just now" button
                            val justNowButton = Button(this@MainActivity).apply {
                                text = getString(R.string.pure_sms_deactivation_just_now)
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                ).apply {
                                    setMargins(0, 0, 0, 8)
                                }
                                setOnClickListener {
                                    dialog.dismiss()
                                    showPureSmsDeactivationDialog {
                                        // After deactivation, reopen upgrade dialog
                                        showUpgradeDialog()
                                    }
                                }
                            }
                            optionsLayout.addView(justNowButton)

                            // "Always" button
                            val alwaysButton = Button(this@MainActivity).apply {
                                text = getString(R.string.pure_sms_deactivation_always)
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                )
                                setOnClickListener {
                                    // Disable Pure SMS permanently
                                    prefs.pureSmsMode = false
                                    pureSmsToggle.isChecked = false
                                    showToast(getString(R.string.pure_sms_permanently_disabled))
                                    dialog.dismiss()
                                    // Reopen upgrade dialog
                                    showUpgradeDialog()
                                }
                            }
                            optionsLayout.addView(alwaysButton)

                            // Add cancel button
                            val cancelOptionButton = Button(this@MainActivity).apply {
                                text = getString(R.string.cancel)
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                ).apply {
                                    setMargins(0, 16, 0, 0)
                                }
                                setOnClickListener {
                                    dialog.dismiss()
                                }
                            }
                            optionsLayout.addView(cancelOptionButton)

                            versionsContainer.addView(optionsLayout)
                        } else {
                            latestVersionText.text = getString(R.string.update_check_failed)
                            versionsLabel.visibility = View.GONE
                        }
                        return@runOnUiThread
                    }

                    val latestVersion = versions.last()
                    latestVersionText.text = getString(R.string.latest_version, latestVersion)

                    // Filter versions, hiding current version
                    val filteredVersions = versions.filter { version ->
                        version != currentVersion
                    }

                    // Reverse the list to show newest first
                    val comparator = UpdateChecker.VersionComparator()
                    val sortedVersionsDescending = filteredVersions.sortedWith(comparator.reversed())

                    // If no versions after filtering, show message
                    if (sortedVersionsDescending.isEmpty()) {
                        versionsLabel.visibility = View.GONE
                        val noVersionsText = TextView(this@MainActivity).apply {
                            text = getString(R.string.no_new_versions)
                            textSize = 14f
                            gravity = Gravity.CENTER
                            setPadding(0, 20, 0, 20)
                            setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.darker_gray))
                        }
                        versionsContainer.addView(noVersionsText)
                        return@runOnUiThread
                    }

                    // Create version buttons
                    sortedVersionsDescending.forEach { version ->
                        val versionButton = Button(this@MainActivity).apply {
                            text = version
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply {
                                setMargins(0, 4, 0, 4)
                            }

                            // Check if this is a downgrade
                            val isDowngrade = UpdateChecker.VersionComparator().compare(version, currentVersion) < 0

                            // Different color for downgrade
                            if (isDowngrade) {
                                backgroundTintList = ColorStateList.valueOf(
                                    ContextCompat.getColor(this@MainActivity, R.color.gray_3)
                                )
                            } else {
                                backgroundTintList = ColorStateList.valueOf(
                                    ContextCompat.getColor(this@MainActivity, R.color.intensive_green)
                                )
                            }

                            setTextColor(Color.WHITE)
                            isAllCaps = false

                            setOnClickListener {
                                // Disable all buttons to prevent double-tap
                                for (i in 0 until versionsContainer.childCount) {
                                    versionsContainer.getChildAt(i).isEnabled = false
                                }
                                cancelButton.isEnabled = false

                                // Show confirmation dialog with warning if downgrade
                                val message = if (isDowngrade) {
                                    "⚠️ ${getString(R.string.downgrade_warning_message, version, currentVersion)}"
                                } else {
                                    getString(R.string.confirm_installation_message, version)
                                }

                                // If Pure SMS is active, show special confirmation
                                if (prefs.pureSmsMode && !isDowngrade) {
                                    AlertDialog.Builder(this@MainActivity)
                                        .setTitle(getString(R.string.pure_sms_active_title))
                                        .setMessage(getString(R.string.pure_sms_upgrade_message))
                                        .setPositiveButton(getString(R.string.pure_sms_deactivation_just_now)) { _, _ ->
                                            // Disable Pure SMS just for this download
                                            startVersionDownload(version, dialog, versionsContainer,
                                                versionsLabel, cancelButton, downloadProgress, progressText)
                                        }
                                        .setNeutralButton(getString(R.string.pure_sms_deactivation_always)) { _, _ ->
                                            // Disable Pure SMS permanently
                                            prefs.pureSmsMode = false
                                            pureSmsToggle.isChecked = false
                                            showToast(getString(R.string.pure_sms_permanently_disabled))

                                            // Start download
                                            startVersionDownload(version, dialog, versionsContainer,
                                                versionsLabel, cancelButton, downloadProgress, progressText)
                                        }
                                        .setNegativeButton(getString(R.string.cancel)) { dialogInterface, _ ->
                                            dialogInterface.dismiss()
                                            // Re-enable buttons
                                            for (i in 0 until versionsContainer.childCount) {
                                                versionsContainer.getChildAt(i).isEnabled = true
                                            }
                                            cancelButton.isEnabled = true
                                        }
                                        .show()
                                } else {
                                    AlertDialog.Builder(this@MainActivity)
                                        .setTitle(getString(R.string.confirm_installation_title))
                                        .setMessage(message)
                                        .setPositiveButton(getString(R.string.download)) { _, _ ->
                                            // Start download immediately
                                            startVersionDownload(version, dialog, versionsContainer,
                                                versionsLabel, cancelButton, downloadProgress, progressText)
                                        }
                                        .setNegativeButton(getString(R.string.cancel)) { _, _ ->
                                            // Re-enable buttons if user cancels
                                            for (i in 0 until versionsContainer.childCount) {
                                                versionsContainer.getChildAt(i).isEnabled = true
                                            }
                                            cancelButton.isEnabled = true
                                        }
                                        .show()
                                }
                            }
                        }
                        versionsContainer.addView(versionButton)
                    }
                }
            } catch (e: Exception) {
                LogUtils.e("MainActivity", "Error fetching versions", e)
                runOnUiThread {
                    latestVersionText.text = getString(R.string.update_check_failed)
                }
            }
        }.start()

        cancelButton.setOnClickListener {
            dialog.dismiss()
        }
    }

    /**
     * Extracted method to start the download process
     */
    @SuppressLint("SetTextI18n")
    private fun startVersionDownload(
        selectedVersion: String,
        dialog: AlertDialog,
        versionsContainer: LinearLayout,
        versionsLabel: TextView,
        cancelButton: Button,
        downloadProgress: ProgressBar,
        progressText: TextView
    ) {
        // Get references to all views
        val versionsScrollview = dialog.findViewById<ScrollView>(R.id.versions_scrollview)
        val explanationText = dialog.findViewById<TextView>(R.id.explanation_text)
        val versionInstallingText = dialog.findViewById<TextView>(R.id.version_installing_text)
        val downloadPathContainer = dialog.findViewById<LinearLayout>(R.id.download_path_container)
        val downloadPathText = dialog.findViewById<TextView>(R.id.download_path_text)
        val exitButton = dialog.findViewById<Button>(R.id.exit_button)

        // Hide version selection completely
        versionsScrollview?.visibility = View.GONE
        versionsLabel.visibility = View.GONE
        versionsContainer.visibility = View.GONE

        // Change cancel button text initially
        cancelButton.text = getString(R.string.cancel)
        cancelButton.isEnabled = true

        // SHOW DOWNLOAD PATH CONTAINER
        downloadPathContainer?.visibility = View.VISIBLE
        val downloadsPath = getDownloadsFolderPath()
        downloadPathText?.text = "$downloadsPath/nook-v$selectedVersion.apk"

        // SHOW EXPLANATORY TEXT
        explanationText?.visibility = View.VISIBLE

        // Show progress
        downloadProgress.visibility = View.VISIBLE
        progressText.visibility = View.VISIBLE

        versionInstallingText?.visibility = View.VISIBLE
        versionInstallingText?.text = getString(R.string.downloading_version, selectedVersion)

        // Cancel button during download
        cancelButton.setOnClickListener {
            dialog.dismiss()
        }

        // Start download
        updateChecker.downloadAPKAndStopAPP(
            version = selectedVersion,
            onProgress = { progress ->
                runOnUiThread {
                    progressText.text = getString(R.string.downloading, progress)
                    downloadProgress.progress = progress
                }
            },
            onComplete = { success, result ->
                runOnUiThread {
                    if (success && result != null) {
                        // Download completed successfully
                        progressText.visibility = View.GONE
                        downloadProgress.visibility = View.GONE
                        versionInstallingText?.visibility = View.GONE

                        // Update path text with actual final path
                        downloadPathText?.text = result

                        // Show PROCEED and CANCEL buttons
                        cancelButton.text = getString(R.string.cancel)
                        cancelButton.visibility = View.VISIBLE

                        exitButton?.visibility = View.VISIBLE
                        exitButton?.text = getString(R.string.proceed)

                        // Update explanation text for completion
                        explanationText?.text = getString(R.string.update_explanation_complete)

                        // PROCEED button - opens APK directly with system installer
                        exitButton?.setOnClickListener {
                            dialog.dismiss()

                            val version = selectedVersion
                            val fileName = "nook-v$version.apk"

                            // Launch installer using AlarmManager (survives app death)
                            launchInstallerIndependent(fileName)

                            // Close NooK
                            Handler(Looper.getMainLooper()).postDelayed({
                                stopForegroundService()
                                finishAffinity()
                                finishAndRemoveTask()
                            }, 500)
                        }

                    } else {
                        // Download failed - just show error and cancel button
                        progressText.text = getString(R.string.download_failed,
                            result ?: getString(R.string.unknown_error))
                        downloadProgress.visibility = View.GONE
                        versionInstallingText?.visibility = View.GONE

                        // Hide explanatory text on failure
                        explanationText?.visibility = View.GONE

                        cancelButton.text = getString(R.string.cancel)
                        cancelButton.visibility = View.VISIBLE
                        exitButton?.visibility = View.GONE

                        cancelButton.setOnClickListener {
                            dialog.dismiss()
                        }
                    }
                }
            }
        )
    }

    /**
     * Launches APK installer independent of app lifecycle using AlarmManager
     * Works on all Android versions 7-16+
     */
    private fun launchInstallerIndependent(fileName: String) {
        try {
            LogUtils.e(TAG, "🚀 Launching installer independent of app")

            // Get the APK file
            val apkFile = getApkFile(fileName)

            if (apkFile == null || !apkFile.exists()) {
                LogUtils.e(TAG, "❌ APK file not found: $fileName")
                // Fallback: open Downloads folder
                launchExplorerViaAlarm()
                return
            }

            // Create URI based on Android version
            val apkUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // For Android 7+, use FileProvider
                androidx.core.content.FileProvider.getUriForFile(
                    this,
                    "${packageName}.fileprovider",
                    apkFile
                )
            } else {
                // For older Android, use direct file URI
                Uri.fromFile(apkFile)
            }

            // Create install intent
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
                addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

                // Try to target package installer directly
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    // On newer Android, try common package installer packages
                    val installerPackages = arrayOf(
                        "com.android.packageinstaller",
                        "com.google.android.packageinstaller",
                        "com.samsung.android.packageinstaller",
                        "com.xiaomi.packageinstaller",
                        "com.huawei.packageinstaller"
                    )

                    for (pkg in installerPackages) {
                        if (packageManager.resolveActivity(
                                Intent(Intent.ACTION_VIEW).setPackage(pkg),
                                0
                            ) != null) {
                            setPackage(pkg)
                            break
                        }
                    }
                }
            }

            // Create PendingIntent that will survive app death
            val pendingIntent = PendingIntent.getActivity(
                this,
                (System.currentTimeMillis() % 10000).toInt(), // Unique ID
                installIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )

            // Use AlarmManager to launch after app dies
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + 300, // 300ms delay
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + 300,
                    pendingIntent
                )
            }

            LogUtils.e(TAG, "✅ Installer alarm scheduled")

        } catch (e: Exception) {
            LogUtils.e(TAG, "❌ Error launching installer", e)
            // Fallback: open Downloads folder
            launchExplorerViaAlarm()
        }
    }


    private fun launchExplorerViaAlarm() {
        try {
            LogUtils.e(TAG, "⏰ Launching explorer via AlarmManager (nohup style)")

            // Create intent to open Downloads folder
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val uri = DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents",
                    "primary:Download"
                )
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "vnd.android.document/directory")
                }
            } else {
                Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
            }

            // Critical flags for independent task
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)

            // Create PendingIntent that will survive app death
            val pendingIntent = PendingIntent.getActivity(
                this,
                System.currentTimeMillis().toInt(), // Unique request code
                intent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )

            // Get AlarmManager
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager

            // Schedule to fire in 100ms (after we close the app)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + 100,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + 100,
                    pendingIntent
                )
            }

            LogUtils.e(TAG, "⏰ Alarm scheduled, now closing app...")

            // Close NooK immediately - the alarm will fire independently
            stopForegroundService()
            finishAffinity()
            finishAndRemoveTask()

        } catch (e: Exception) {
            LogUtils.e(TAG, "❌ Error scheduling explorer via alarm", e)

            // Fallback to regular explorer
            launchExplorerIndependent()
        }
    }

    private fun launchExplorerIndependent() {
        try {
            // For Android 10+ - Use DocumentsContract
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val uri = DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents",
                    "primary:Download"
                )

                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "vnd.android.document/directory")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
                    addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS) // Hide from recents
                }

                startActivity(intent)
            } else {
                // For older Android - use ACTION_GET_CONTENT
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
                    addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                }

                startActivity(Intent.createChooser(intent, "Select file explorer"))
            }

            // Close NooK immediately - the explorer is now independent
            Handler(Looper.getMainLooper()).postDelayed({
                stopForegroundService()
                finishAffinity()
                finishAndRemoveTask()
            }, 500)

        } catch (e: Exception) {
            LogUtils.e(TAG, "Error launching explorer", e)
        }
    }
    /**
     * Open Downloads folder and trigger the APK file to open with installer
     */
    private fun openDownloadsFolderAndHighlightApk(apkFile: File) {
        try {
            LogUtils.e(TAG, "📂 Opening APK with installer: ${apkFile.absolutePath}")

            // First, try to open the APK directly with the installer
            // This works on most Android versions
            val apkUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // For Android 7+, we need to use FileProvider
                val authority = "${packageName}.fileprovider"
                androidx.core.content.FileProvider.getUriForFile(this, authority, apkFile)
            } else {
                Uri.fromFile(apkFile)
            }

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            // Check if there's an app that can handle this (the package installer)
            if (installIntent.resolveActivity(packageManager) != null) {
                LogUtils.e(TAG, "📲 Launching package installer directly")
                startActivity(installIntent)
                return
            }

            // If direct install doesn't work, try to open the Downloads folder
            // AND simultaneously broadcast the APK file to open
            LogUtils.e(TAG, "📂 Falling back to Downloads folder with file open intent")

            // Open Downloads folder
            openDownloadsFolder()

            // Also create a separate intent to open the APK file
            // This will show a "Complete action using" dialog
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val openFileIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(apkUri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(openFileIntent)
                } catch (e: Exception) {
                    LogUtils.e(TAG, "❌ Failed to open APK file", e)
                }
            }, 500)

        } catch (e: Exception) {
            LogUtils.e(TAG, "❌ Error in openDownloadsFolderAndHighlightApk", e)
            openDownloadsFolder()
        }
    }

    /**
     * Get APK file using appropriate method for Android version
     */
    private fun getApkFile(fileName: String): File? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ - query MediaStore
            getApkFileFromMediaStore(fileName)
        } else {
            // Android 7-9 - direct file access
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            File(downloadsDir, fileName)
        }
    }
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun getApkFileFromMediaStore(fileName: String): File? {
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DATA
        )

        val selection = "${MediaStore.Files.FileColumns.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(fileName)

        val cursor = contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )

        cursor?.use {
            if (it.moveToFirst()) {
                val dataColumn = it.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                if (dataColumn != -1) {
                    val filePath = it.getString(dataColumn)
                    return File(filePath)
                }
            }
        }
        return null
    }

    private fun proceedWithDownload(
        selectedVersion: String,
        dialog: AlertDialog,
        versionsContainer: LinearLayout,
        versionsLabel: TextView,
        upgradeButton: Button,
        cancelButton: Button,
        downloadProgress: ProgressBar,
        progressText: TextView
    ) {
        // Get references to all views
        val versionsScrollview = dialog.findViewById<ScrollView>(R.id.versions_scrollview)
        val explanationText = dialog.findViewById<TextView>(R.id.explanation_text)
        val versionInstallingText = dialog.findViewById<TextView>(R.id.version_installing_text)
        val downloadPathContainer = dialog.findViewById<LinearLayout>(R.id.download_path_container)
        val downloadPathText = dialog.findViewById<TextView>(R.id.download_path_text)
        val exitButton = dialog.findViewById<Button>(R.id.exit_button)

        // Hide version selection completely
        versionsScrollview?.visibility = View.GONE
        versionsLabel.visibility = View.GONE
        versionsContainer.visibility = View.GONE
        upgradeButton.visibility = View.GONE

        // Change cancel button text
        cancelButton.text = getString(R.string.cancel)

        // SHOW DOWNLOAD PATH CONTAINER
        downloadPathContainer?.visibility = View.VISIBLE
        val downloadsPath = getDownloadsFolderPath()
        downloadPathText?.text = "$downloadsPath/nook-v$selectedVersion.apk"

        // SHOW EXPLANATORY TEXT
        explanationText?.visibility = View.VISIBLE

        // Show progress
        downloadProgress.visibility = View.VISIBLE
        progressText.visibility = View.VISIBLE

        versionInstallingText?.visibility = View.VISIBLE
        versionInstallingText?.text = getString(R.string.downloading_version, selectedVersion)

        // Start download
        updateChecker.downloadAPKAndStopAPP(
            version = selectedVersion,
            onProgress = { progress ->
                runOnUiThread {
                    progressText.text = getString(R.string.downloading, progress)
                    downloadProgress.progress = progress
                }
            },
            onComplete = { success, result ->
                runOnUiThread {
                    if (success && result != null) {
                        // Download completed successfully
                        progressText.visibility = View.GONE
                        downloadProgress.visibility = View.GONE
                        versionInstallingText?.visibility = View.GONE

                        // Update path text with actual final path
                        downloadPathText?.text = result

                        // SHOW EXIT BUTTON, HIDE CANCEL BUTTON
                        cancelButton.visibility = View.GONE
                        exitButton?.visibility = View.VISIBLE

                        // Update explanation text for completion
                        explanationText?.text = getString(R.string.update_explanation_complete)

                        // PROCEED button - opens APK directly with system installer
                        exitButton?.setOnClickListener {
                            dialog.dismiss()

                            // Get the APK file
                            val version = selectedVersion
                            val fileName = "nook-v$version.apk"
                            val apkFile = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                getApkFileFromMediaStore(fileName)
                            } else {
                                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
                            }

                            if (apkFile != null && apkFile.exists()) {
                                try {
                                    // Create URI with FileProvider for Android 7+
                                    val apkUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                        androidx.core.content.FileProvider.getUriForFile(
                                            this@MainActivity,
                                            "${packageName}.fileprovider",
                                            apkFile
                                        )
                                    } else {
                                        Uri.fromFile(apkFile)
                                    }

                                    // Intent to install the APK
                                    val installIntent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(apkUri, "application/vnd.android.package-archive")
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }

                                    // First, launch the installer
                                    startActivity(installIntent)

                                    // Then close NooK after a brief delay
                                    Handler(Looper.getMainLooper()).postDelayed({
                                        stopForegroundService()
                                        finishAffinity()
                                        finishAndRemoveTask()
                                    }, 1000)

                                } catch (e: Exception) {
                                    LogUtils.e(TAG, "❌ Failed to launch installer", e)
                                    // Fallback: just open Downloads folder and close
                                    openDownloadsFolder()
                                    closeAppAfterDelay(killing = true, delay = 2000)
                                }
                            } else {
                                LogUtils.e(TAG, "❌ APK file not found: $fileName")
                                openDownloadsFolder()
                                closeAppAfterDelay(killing = true, delay = 2000)
                            }
                        }


                    } else {
                        // Download failed
                        progressText.text = getString(R.string.download_failed,
                            result ?: getString(R.string.unknown_error))
                        downloadProgress.visibility = View.GONE
                        versionInstallingText?.visibility = View.GONE

                        // Hide explanatory text on failure
                        explanationText?.visibility = View.GONE

                        cancelButton.text = getString(R.string.cancel)
                        upgradeButton.visibility = View.GONE

                        cancelButton.setOnClickListener {
                            dialog.dismiss()
                        }
                    }
                }
            }
        )

        // Handle cancel button during download
        cancelButton.setOnClickListener {
            dialog.dismiss()
        }
    }


    @RequiresApi(Build.VERSION_CODES.S)
    private fun startForegroundNotification() {
        try {
            LogUtils.d("MainActivity", "🔔 Starting foreground notification on API ${Build.VERSION.SDK_INT}")

            // Check if app is in foreground
            if (!AppStateTracker.isAppInForeground) {
                LogUtils.d("MainActivity", "🔔 App in background, cannot start service directly. Will start when app resumes")
                // Mark that we need to start service when app resumes
                prefs.putBoolean("pending_foreground_service", true)
                return
            }

            // Check if we have notification permission
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED) {
                    LogUtils.d("MainActivity", "🔔 Notification permission granted, starting service")
                    try {
                        NotificationHelper.startForegroundNotification(this)
                        // Clear pending flag
                        prefs.putBoolean("pending_foreground_service", false)
                    } catch (e: BackgroundServiceStartNotAllowedException) {
                        LogUtils.e("MainActivity", "🔔 Background start not allowed, will retry when app resumes")
                        prefs.putBoolean("pending_foreground_service", true)
                    }
                } else {
                    LogUtils.d("MainActivity", "🔔 Notification permission not granted yet")
                }
            } else {
                // Android 12 and below
                LogUtils.d("MainActivity", "🔔 Android < 13, starting service directly")
                try {
                    NotificationHelper.startForegroundNotification(this)
                    prefs.putBoolean("pending_foreground_service", false)
                } catch (e: BackgroundServiceStartNotAllowedException) {
                    LogUtils.e("MainActivity", "🔔 Background start not allowed, will retry")
                    prefs.putBoolean("pending_foreground_service", true)
                }
            }
        } catch (e: Exception) {
            LogUtils.e("MainActivity", "🔔 Error starting notification", e)
        }
    }

    /**
     * Show dialog with APK location and instructions
     */
    private fun showFileLocationDialog(apkPath: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.update_explanation_complete)
            .setMessage(getString(R.string.manual_install_instructions, apkPath))
            .setPositiveButton(R.string.open_downloads_folder) { _, _ ->
                openDownloadsFolder()
                closeAppAfterDelay(killing = true, delay = 2000)
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                closeAppAfterDelay(killing = true, delay = 500)
            }
            .show()
    }


    /**
     * Open Downloads folder as a separate app (not inside NooK)
     */
    private fun openDownloadsFolder() {
        try {
            LogUtils.e(TAG, "📂 Opening Downloads folder as separate app")

            // Try multiple methods to open Downloads as a separate app

            // Method 1: For Android 10+ - Use ACTION_VIEW with DocumentsContract but with FLAG_ACTIVITY_NEW_TASK
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val uri = DocumentsContract.buildDocumentUri(
                        "com.android.externalstorage.documents",
                        "primary:Download"
                    )

                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "vnd.android.document/directory")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)  // Open in new task
                        addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                        addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)  // Don't keep in history
                    }

                    if (intent.resolveActivity(packageManager) != null) {
                        LogUtils.e(TAG, "📂 Launching DocumentsUI as separate app")
                        startActivity(intent)
                        return
                    }
                } catch (e: Exception) {
                    LogUtils.e(TAG, "Error opening Downloads with DocumentsContract", e)
                }
            }

            // Method 2: Use ACTION_GET_CONTENT with a file explorer - this opens as separate app
            try {
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)  // Open in new task
                    addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
                }

                val chooser = Intent.createChooser(intent, getString(R.string.select_file_explorer)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                }

                LogUtils.e(TAG, "📂 Launching file chooser as separate app")
                startActivity(chooser)
                return

            } catch (e: Exception) {
                LogUtils.e(TAG, "Error opening file chooser", e)
            }

            // Method 3: For Android 9 and below
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                try {
                    val downloadsFolder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val uri = Uri.fromFile(downloadsFolder)

                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "vnd.android.document/directory")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                        addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
                    }

                    if (intent.resolveActivity(packageManager) != null) {
                        LogUtils.e(TAG, "📂 Launching file manager as separate app")
                        startActivity(intent)
                        return
                    }
                } catch (e: Exception) {
                    LogUtils.e(TAG, "Error opening Downloads folder", e)
                }
            }

            // Method 4: Try to open any file manager app directly
            try {
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_APP_FILES)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                    setPackage("com.android.documentsui")  // Try Android's DocumentsUI
                }

                if (intent.resolveActivity(packageManager) != null) {
                    LogUtils.e(TAG, "📂 Launching DocumentsUI directly")
                    startActivity(intent)
                    return
                }
            } catch (e: Exception) {
                LogUtils.e(TAG, "Error launching DocumentsUI", e)
            }

            // Last resort: Show a dialog with instructions
            val version = updateChecker.getLatestVersion()
            val downloadsPath = getDownloadsFolderPath()
            val apkPath = "$downloadsPath/nook-v$version.apk"

            AlertDialog.Builder(this)
                .setTitle(R.string.manual_installation)
                .setMessage(getString(R.string.installation_indication_apk_saved_in, apkPath))
                .setPositiveButton(R.string.ok, null)
                .show()

        } catch (e: Exception) {
            LogUtils.e(TAG, "Error in openDownloadsFolder", e)
            Toast.makeText(this, getString(R.string.error_opening_folder), Toast.LENGTH_LONG).show()
        }
    }



    /**
     * Get Downloads folder path for Android 10+ and older versions
     */
    private fun getDownloadsFolderPath(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ - use MediaStore
            val contentResolver = contentResolver
            val cursor = contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.DATA),
                null,
                null,
                null
            )

            var path = ""
            cursor?.use {
                if (it.moveToFirst()) {
                    val dataIndex = it.getColumnIndex(MediaStore.Downloads.DATA)
                    if (dataIndex != -1) {
                        val filePath = it.getString(dataIndex)
                        path = filePath.substringBeforeLast("/")
                    }
                }
            }

            path.ifEmpty {
                // Fallback to direct path
                "/storage/emulated/0/Download"
            }
        } else {
            // Android 9 and below - direct path works too
            "/storage/emulated/0/Download"
        }
    }

    fun fetchSha256FromGitHub(uri: String): String? {
        // If Pure SMS mode is enabled, don't contact GitHub
        if (prefs.pureSmsMode) {
            LogUtils.e("releaseDownload", "📡 Pure SMS mode active - skipping GitHub fetch")
            return null
        }

        var connection: HttpURLConnection? = null
        try {
            LogUtils.d("releaseDownload", "CALLING ${Constants.GITHUB_SHA256_URL}")

            val url = URL(uri)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.connect()

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                LogUtils.e("releaseDownload", "Server returned HTTP $responseCode")
                return null
            }

            // Read the text response exactly like we read the binary file
            return connection.inputStream.bufferedReader().use { reader ->
                reader.readText().trim()
            }

        } catch (e: Exception) {
            LogUtils.e("releaseDownload", "Failed to fetch SHA256", e)
            return null
        } finally {
            connection?.disconnect()
        }
    }


    private fun fetchAvailableVersions(): List<String> {
        // If Pure SMS mode is enabled, return empty list
        if (prefs.pureSmsMode) {
            LogUtils.d("MainActivity", "📡 Pure SMS mode active - skipping version fetch")
            return emptyList()
        }

        return try {
            LogUtils.d("releaseDownload","CALLING "+Constants.GITHUB_SHA256_URL)
            val content = fetchSha256FromGitHub(Constants.GITHUB_SHA256_URL)

            val versionPattern = Pattern.compile("v(\\d+\\.\\d+\\.\\d+\\.\\d+)")
            val matcher = versionPattern.matcher(content)

            val versions = mutableListOf<String>()
            while (matcher.find()) {
                versions.add(matcher.group(1))
            }

            versions.sortWith(UpdateChecker.VersionComparator())
            versions
        } catch (e: Exception) {
            LogUtils.e("MainActivity", "Error fetching versions", e)
            emptyList()
        }
    }

    /**
     * Open APK directly with system package installer (bypassing explorer)
     */
    private fun openApkWithInstaller(apkFile: File) {
        try {
            LogUtils.e(TAG, "📲 Opening APK directly with package installer: ${apkFile.absolutePath}")

            // Create URI for the APK file
            val apkUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // For Android 7+, use FileProvider
                val authority = "${packageName}.fileprovider"
                androidx.core.content.FileProvider.getUriForFile(this, authority, apkFile)
            } else {
                // For older Android, use direct file URI
                Uri.fromFile(apkFile)
            }

            // Create intent for package installer
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION) // Add this for safety
            }

            // Verify there's an app to handle this (should be package installer)
            if (intent.resolveActivity(packageManager) != null) {
                LogUtils.e(TAG, "✅ Package installer found, launching...")
                startActivity(intent)

                // Close app after launching installer
                closeAppAfterDelay(killing = true, delay = 1500)

            } else {
                LogUtils.e(TAG, "❌ No package installer found")
                // Fallback to showing the folder
                openDownloadsFolder()
                closeAppAfterDelay(killing = true, delay = 2000)
            }

        } catch (e: Exception) {
            LogUtils.e(TAG, "❌ Error opening APK with installer", e)
            // Fallback to showing the folder
            openDownloadsFolder()
            closeAppAfterDelay(killing = true, delay = 2000)
        }
    }

    private fun closeAppAfterDelay(killing: Boolean = false, delay: Long = 1000) {
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                LogUtils.e(TAG, "🛑 CLOSING APP NOW (killing=$killing)")

                // Always do clean shutdown first
                stopForegroundService()
                finishAffinity()
                finishAndRemoveTask()

                // If killing is true, force process termination after clean shutdown
                if (killing) {
                    LogUtils.e(TAG, "💀 FORCE KILLING PROCESS")
                    // Small delay to let clean shutdown complete
                    Thread.sleep(100)
                    android.os.Process.killProcess(android.os.Process.myPid())
                    System.exit(0)
                }

            } catch (e: Exception) {
                LogUtils.e(TAG, "❌ Error during app close", e)
                // Ultimate fallback
                System.exit(0)
            }
        }, delay)
    }


}