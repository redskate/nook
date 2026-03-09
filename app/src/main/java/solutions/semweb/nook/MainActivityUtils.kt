package solutions.semweb.nook

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.ALARM_SERVICE
import android.content.Context.INPUT_METHOD_SERVICE
import android.content.Context.RECEIVER_EXPORTED
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import solutions.semweb.nook.chat.ChatActivity
import solutions.semweb.nook.chat.ChatImportExportManager
import solutions.semweb.nook.chat.ChatManager
import solutions.semweb.nook.chat.SMSQueueManager
import solutions.semweb.nook.contacts.SearchContactAdapter
import solutions.semweb.nook.crypto.CryptoManager
import solutions.semweb.nook.crypto.EncryptionMapper
import solutions.semweb.nook.data.database.DatabaseActor
import solutions.semweb.nook.keyboards.KeyboardSafetyManager
import java.util.Locale

/**
 * Utility class for MainActivity UI processing methods
 * This helps keep MainActivity.kt organized and maintainable
 */
class MainActivityUtils(private val activity: MainActivity) {

    companion object {
        private const val TAG = "MainActivityUtils"
    }

    /**
     * Shows the disclaimer dialog - wait 2 secs - wait until scrolled
     */
    fun showDisclaimerDialog(prefs: SharedPreferencesManager, context: Context) {
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_disclaimer, null)

        val messageView = dialogView.findViewById<TextView>(R.id.disclaimer_message)
        val btnAgree = dialogView.findViewById<Button>(R.id.btn_agree)
        val btnExit = dialogView.findViewById<Button>(R.id.btn_exit)

        // Cerca lo ScrollView nel layout
        var scrollView: ScrollView? = null

        fun findScrollView(view: View): ScrollView? {
            if (view is ScrollView) {
                return view
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    val child = view.getChildAt(i)
                    val found = findScrollView(child)
                    if (found != null) {
                        return found
                    }
                }
            }
            return null
        }

        scrollView = findScrollView(dialogView)

        if (scrollView == null) {
            LogUtils.e(activity, TAG, "❌ ScrollView not found in disclaimer!")
            btnAgree.isEnabled = true
            btnAgree.alpha = 1.0f
        }

        // Disabilita inizialmente il pulsante OK
        if (scrollView != null) {
            btnAgree.isEnabled = false
            btnAgree.alpha = 0.5f
        }

        val rawText = activity.getString(R.string.disclaimer_message)
        messageView.text = rawText

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        if (scrollView != null) {
            var hasScrolledToBottom = false
            val handler = Handler(Looper.getMainLooper())
            var canEnableAgreeButton = false

            handler.postDelayed({
                canEnableAgreeButton = true
                if (hasScrolledToBottom) {
                    btnAgree.isEnabled = true
                    btnAgree.alpha = 1.0f
                }
            }, 5000) // wait 5 secs until going OK

            scrollView.viewTreeObserver.addOnScrollChangedListener {
                val scrollViewHeight = scrollView.height
                val childHeight = scrollView.getChildAt(0).height
                val isAtBottom = childHeight - scrollViewHeight <= scrollView.scrollY

                if (isAtBottom && canEnableAgreeButton) {
                    hasScrolledToBottom = true
                    btnAgree.isEnabled = true
                    btnAgree.alpha = 1.0f
                }
            }

            btnAgree.setOnClickListener {
                if (hasScrolledToBottom) {
                    prefs.putBoolean("disclaimer_accepted", true)
                    dialog.dismiss()
                    activity.initializeCompleteOnCreate()
                } else {
                    MainActivity.showToast(context.getString(R.string.pleasescrolltoendindisclaimer), true)
                }
            }
        } else {
            btnAgree.setOnClickListener {
                prefs.putBoolean("disclaimer_accepted", true)
                dialog.dismiss()
                activity.initializeCompleteOnCreate()
            }
        }

        btnExit.setOnClickListener {
            activity.stopForegroundService()
            dialog.dismiss()
            activity.finishAffinity()
            activity.finishAndRemoveTask()
        }

        dialog.show()

        val displayMetrics = activity.resources.displayMetrics
        dialog.window?.setLayout(
            (displayMetrics.widthPixels * 0.9).toInt(),
            (displayMetrics.heightPixels * 0.85).toInt()
        )
    }


    /**
     * Shows SMS queue status (for debugging)
     */
    fun showSMSQueueStatus() {
        val queueSize = SMSQueueManager.getQueueSize()
        if (queueSize > 0) {
            MainActivity.showToast(activity.getString(R.string.sms_queue_size, queueSize))
        } else {
            MainActivity.showToast("No queued pending outgoing SMS messages")
        }
    }

    /**
     * Creates notification channels for the app
     */
    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val channelId = Constants.NOTIFICATION_CHANNEL_ID
            val channelName = context.getString(R.string.notification_channel_name)

            val prefs = SharedPreferencesManager.getInstance(context)
            val vibrationEnabled = prefs.vibrationEnabled
            val soundUriString = prefs.notificationSoundUri

            val soundUri = when {
                soundUriString.isEmpty() -> null
                soundUriString == Constants.DEFAULT_NOTIFICATION_SOUND_URI ->
                    Settings.System.DEFAULT_NOTIFICATION_URI
                else -> {
                    try {
                        Uri.parse(soundUriString)
                    } catch (e: Exception) {
                        LogUtils.e(context, "NotificationUtils", "Error parsing URI sound name", e)
                        null
                    }
                }
            }
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notification_channel_description)

                enableVibration(vibrationEnabled)
                if (vibrationEnabled) {
                    vibrationPattern = longArrayOf(0, 500, 250, 500)
                }

                if (soundUri != null) {
                    setSound(
                        soundUri,
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .build()
                    )
                }

                enableLights(true)
                lightColor = Color.GREEN
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            notificationManager.createNotificationChannel(channel)
            LogUtils.d(context, "NotificationUtils", "✅ Notification channel created: $channelId")
        }
    }

    /**
     * Checks exact alarm permission for Android 12+
     */
    fun checkExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val alarmManager = activity.getSystemService(ALARM_SERVICE)
                if (alarmManager is AlarmManager) {
                    LogUtils.d(activity, TAG, activity.getString(R.string.alarm_manager_available_android_12))
                }

                if (false) {
                    val prefs = SharedPreferencesManager.getInstance(activity)
                    if (!prefs.getBoolean("shown_alarm_info", false)) {
                        AlertDialog.Builder(activity)
                            .setTitle(activity.getString(R.string.android_12_title))
                            .setMessage(activity.getString(R.string.android_12_message))
                            .setPositiveButton(activity.getString(R.string.ok)) { _, _ ->
                                prefs.putBoolean("shown_alarm_info", true)
                            }
                            .setCancelable(false)
                            .show()
                    }
                }
            } catch (e: Exception) {
                LogUtils.e(activity, TAG, activity.getString(R.string.error_checking_alarm), e)
            }
        }
    }

    /**
     * Registers chat update receiver
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun registerChatUpdateReceiver(): BroadcastReceiver {
        val chatUpdateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                activity.runOnUiThread {
                    activity.loadChatConversations()
                    LogUtils.d(activity, "MainActivity", activity.getString(R.string.chat_list_updated_after_new_message))
                }
            }
        }

        val filter = IntentFilter(Constants.mainpackage+".CHAT_UPDATED")

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    activity.registerReceiver(
                        chatUpdateReceiver,
                        filter,
                        RECEIVER_EXPORTED
                    )
                } else {
                    @Suppress("DEPRECATION")
                    activity.registerReceiver(chatUpdateReceiver, filter)
                }
            } else {
                @Suppress("DEPRECATION")
                activity.registerReceiver(chatUpdateReceiver, filter)
            }
            LogUtils.d(activity, "MainActivity", activity.getString(R.string.chat_update_receiver_registered))
        } catch (e: Exception) {
            LogUtils.e(activity, "MainActivity", activity.getString(R.string.error_registering_receiver), e)
        }

        return chatUpdateReceiver
    }

    /**
     * Shows trusted contacts selector for new chat
     */
    fun showTrustedContactsSelector(
        prefs: SharedPreferencesManager,
        chatManager: ChatManager,
        existingPhoneNumbers: Set<String>
    ) {
        val trustedContacts = prefs.getActiveTrustedContacts()
        val availableContacts = trustedContacts.map {
            ContactInfo(it.contactId, it.phoneNumber, it.displayName)
        }.filter { contact ->
            val normalizedNumber = PhoneUtils.normalizePhoneNumber(contact.phoneNumber)
            !existingPhoneNumbers.contains(normalizedNumber)
        }

        if (availableContacts.isEmpty()) {
            MainActivity.showToast(activity.getString(R.string.all_trusted_contacts_already_in_chat))
            return
        }

        showSearchDialog(
            contacts = availableContacts,
            title = activity.getString(R.string.search_contact_for_trusted_chat_title),
            onContactSelected = { contact ->
                activity.createSmsChat(contact.phoneNumber, contact.displayName)
            }
        )
    }

    /**
     * Shows search dialog for chats
     * SPEEDUP METHOD
     * Adds contactss as trusted contacts
     */
    fun showSearchDialogForSMSChats(
        prefs: SharedPreferencesManager,
        chatManager: ChatManager,
        checkContactsPermission: () -> Boolean,
        requestContactsPermission: () -> Unit
    ) {
        if (!checkContactsPermission()) {
            requestContactsPermission()
            return
        }

        val allContacts = getAllContacts()
        val existingChats = chatManager.getAllConversations()
        val existingPhoneNumbers = existingChats.map { PhoneUtils.normalizePhoneNumber(it.phoneNumber) }.toSet()

        val availableContacts = allContacts.filter { contact ->
            val normalizedNumber = PhoneUtils.normalizePhoneNumber(contact.phoneNumber)
            !existingPhoneNumbers.contains(normalizedNumber)
        }

        showSearchDialog(
            contacts = availableContacts,
            title = activity.getString(R.string.search_contact_for_chat_title),
            onContactSelected = { contact ->
                addToTrustedContactsIfNotExists(contact, prefs)
                activity.createSmsChat(contact.phoneNumber, contact.displayName)
            }
        )
    }

    private fun addToTrustedContactsIfNotExists(contact: ContactInfo, prefs: SharedPreferencesManager) {
        val normalizedNumber = PhoneUtils.normalizePhoneNumber(contact.phoneNumber)

        val alreadyTrusted = prefs.trustedContacts.any { trusted ->
            PhoneUtils.normalizePhoneNumber(trusted.phoneNumber) == normalizedNumber
        }

        if (!alreadyTrusted) {
            val trustedContact = TrustedContact(
                contactId = contact.contactId,
                phoneNumber = contact.phoneNumber,
                displayName = contact.displayName ?: contact.phoneNumber,
                isActive = true
            )

            prefs.addTrustedContact(trustedContact)

            LogUtils.d(activity, "TrustedContacts",
                "✅ Contact added to trusted: ${contact.displayName} - ${contact.phoneNumber}")
        } else {
            LogUtils.d(activity, "TrustedContacts",
                "ℹ️ Contact already existing in trusted: ${contact.displayName}")
        }
    }

    /**
     * Shows search dialog with contacts
     */
    fun showSearchDialog(
        contacts: List<ContactInfo>,
        title: String,
        onContactSelected: (ContactInfo) -> Unit
    ) {
        val dialogView = activity.layoutInflater.inflate(R.layout.search_dialog, null)

        val searchInput = dialogView.findViewById<EditText>(R.id.search_input)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.search_results_recycler_view)

        recyclerView.layoutManager = LinearLayoutManager(activity)

        var alertDialog: AlertDialog? = null

        val adapter = SearchContactAdapter(contacts) { contact ->
            alertDialog?.dismiss()
            onContactSelected(contact)
        }

        recyclerView.adapter = adapter

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s.toString().trim().lowercase(Locale.getDefault())
                val filtered = contacts.filter { contact ->
                    contact.displayName?.lowercase(Locale.getDefault())?.contains(query) == true ||
                            contact.phoneNumber.lowercase(Locale.getDefault()).contains(query) ||
                            formatPhoneNumber(contact.phoneNumber).contains(query)
                }
                adapter.updateContacts(filtered)
            }
        })

        alertDialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(dialogView)
            .setNegativeButton(activity.getString(R.string.cancel)) { dialog, _ ->
                dialog.dismiss()
            }
            .show()

        searchInput.requestFocus()
        showKeyboardForView(searchInput)
    }

    /**
     * Shows keyboard for a view
     */
    private fun showKeyboardForView(view: View) {
        view.post {
            val imm = activity.getSystemService(InputMethodManager::class.java)
            imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /**
     * Gets all contacts from device
     */
    fun getAllContacts(): List<ContactInfo> {
        val contacts = mutableListOf<ContactInfo>()
        val prefs = SharedPreferencesManager.getInstance(activity)

        try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )

            val cursor = activity.contentResolver.query(
                uri,
                projection,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE ASC"
            )

            cursor?.use {
                val idIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                while (it.moveToNext()) {
                    val contactId = it.getString(idIndex)
                    val displayName = it.getString(nameIndex)
                    val phoneNumber = it.getString(numberIndex)

                    if (!phoneNumber.isNullOrBlank()) {
                        contacts.add(ContactInfo(contactId, phoneNumber, displayName))
                    }
                }
            }
        } catch (e: Exception) {
            LogUtils.e(activity, TAG, activity.getString(R.string.error_reading_contacts), e)
            MainActivity.showToast(activity.getString(R.string.error_reading_contacts_with_message, e.message), true)
        }

        return contacts.distinctBy {
            "${PhoneUtils.normalizePhoneNumber(it.phoneNumber)}-${it.displayName}"
        }
    }

    /**
     * Opens chat for a contact
     */
    fun openChatForContact(
        chatManager: ChatManager,
        prefs: SharedPreferencesManager,
        phoneNumber: String
    ) {
        val intent = Intent(activity, ChatActivity::class.java)
        intent.putExtra("phone_number", phoneNumber)
        val conversation = chatManager.getConversation(phoneNumber)
        activity.startActivity(intent)
    }


    /**
     * Shows conversation context menu
     */
    fun showConversationContextMenu(
        conversation: ChatConversation,
        view: View,
        prefs: SharedPreferencesManager,
        chatManager: ChatManager,
        activity: MainActivity
    ) {
        val popupMenu = PopupMenu(activity, view)

        val userNameText =
            activity.getString(R.string.edit_chat_name)

        popupMenu.menu.add(0, 2, 1, activity.getString(R.string.delete_chat))
        popupMenu.menu.add(0, 4, 3, userNameText)

        if (!conversation.isYChat) {
            popupMenu.menu.add(0, 5, 4, activity.getString(R.string.set_encryption))
        }

        popupMenu.menu.add(0, 6, 5, activity.getString(R.string.export_encrypted_chat))
        popupMenu.menu.add(0, 7, 6, activity.getString(R.string.import_encrypted_chat))

        popupMenu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                2 -> {
                    deleteChatConversation(conversation, chatManager, activity)
                    true
                }
                4 -> {
                    // REALIZE HERE
                    activity.showModifySmsChatNameDialog(conversation)
                    true
                }
                5 -> {
                    showEncryptionCodingSchemesDialogForChat(conversation, view, prefs, chatManager, activity)
                    true
                }
                6 -> {
                    val chatImportExportManager = ChatImportExportManager.create(activity)
                    chatImportExportManager.showExportPasswordDialog(conversation, chatManager, true)
                    true
                }
                7 -> {
                    val chatImportExportManager = ChatImportExportManager.create(activity)
                    chatImportExportManager.showImportPasswordDialog()
                    true
                }
                else -> false
            }
        }
        popupMenu.show()
    }


    @SuppressLint("StringFormatMatches")
    fun showEncryptionCodingSchemesDialogForChat(
        conversation: ChatConversation,
        view: View,
        prefs: SharedPreferencesManager,
        chatManager: ChatManager,
        activity: MainActivity
    ) {
        LogUtils.d(activity, "ChatManager",
            "🔄 Conversation reloaded for dialog: " +
                    "Encoding: ${conversation.encoding}, " +
                    "Encryption: ${conversation.encryptionScheme}")

        if (!checkAndRequestPhonePermissionIfNeeded(conversation, prefs, activity)) {
            return
        }

        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_encryption_encoding, null)
        val dialog = initializeEncryptEncodingDialogUI(conversation, dialogView, activity)

        val uiElements = initializeUIElements(dialogView)

        setupSpinners(conversation, uiElements, activity)
        setupPasswordFields(conversation, uiElements, activity)

        setupListeners(uiElements, conversation, dialog, chatManager, prefs, activity)

        dialog.show()
        focusOnPasswordIfNeeded(uiElements)
    }

    private fun checkAndRequestPhonePermissionIfNeeded(
        conversation: ChatConversation,
        prefs: SharedPreferencesManager,
        activity: MainActivity
    ): Boolean {
        if (conversation.encryptionScheme == EncryptionMapper.ENCRYPTION_SISA ||
            prefs.decodingScheme == EncryptionMapper.ENCRYPTION_SISA) {

            // On Android 16+, no permission needed - just return true
            if (Build.VERSION.SDK_INT >= 36) { // Android 16 API level
                return true // No permission needed, auto-password will work via other means
            }

            // For older Android versions, check permission
            if (ContextCompat.checkSelfPermission(
                    activity,
                    Manifest.permission.READ_PHONE_STATE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                AlertDialog.Builder(activity)
                    .setTitle(activity.getString(R.string.phone_state_permission_title))
                    .setMessage(activity.getString(R.string.phone_state_permission_message))
                    .setPositiveButton(activity.getString(R.string.grant_permission_button)) { _, _ ->
                        CryptoManager.requestPhoneStatePermission(activity)
                        Handler(Looper.getMainLooper()).postDelayed({
                            // Reload dialog after having asked for permissions
                        }, 1000)
                    }
                    .setNegativeButton(activity.getString(R.string.cancel)) { dialog, _ ->
                        dialog.dismiss()
                    }
                    .show()
                return false
            }
        }
        return true
    }

    private data class EncryptionDialogUIElements(
        val dialogView: View,
        val encodingSpinner: Spinner,
        val encryptionSpinner: Spinner,
        val encodingPasswordContainer: LinearLayout,
        val encodingPasswordInput: EditText,
        val encodingPasswordInfo: TextView,
        val encodingTogglePasswordBtn: ImageButton,
        val encodingGenerateAutoBtn: ImageButton,
        val encryptionPasswordContainer: LinearLayout,
        val encryptionPasswordInput: EditText,
        val encryptionPasswordInfo: TextView,
        val encryptionTogglePasswordBtn: ImageButton,
        val encryptionGenerateAutoBtn: ImageButton
    )

    private fun initializeEncryptEncodingDialogUI(
        conversation: ChatConversation,
        dialogView: View,
        activity: MainActivity
    ): AlertDialog {
        return AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.encryption_for_contact,
                conversation.contactName ?: conversation.phoneNumber))
            .setView(dialogView)
            .setPositiveButton(activity.getString(R.string.save), null)
            .setNegativeButton(activity.getString(R.string.cancel)) { dialog, _ ->
                dialog.dismiss()
            }
            .create()
    }

    private fun initializeUIElements(dialogView: View): EncryptionDialogUIElements {
        return EncryptionDialogUIElements(
            dialogView = dialogView,
            encodingSpinner = dialogView.findViewById(R.id.encoding_spinner),
            encryptionSpinner = dialogView.findViewById(R.id.encryption_spinner),
            encodingPasswordContainer = dialogView.findViewById(R.id.encoding_password_container),
            encodingPasswordInput = dialogView.findViewById(R.id.encoding_password_input),
            encodingPasswordInfo = dialogView.findViewById(R.id.encoding_password_info),
            encodingTogglePasswordBtn = dialogView.findViewById(R.id.encoding_toggle_password_btn),
            encodingGenerateAutoBtn = dialogView.findViewById(R.id.encoding_generate_auto_btn),
            encryptionPasswordContainer = dialogView.findViewById(R.id.encryption_password_container),
            encryptionPasswordInput = dialogView.findViewById(R.id.encryption_password_input),
            encryptionPasswordInfo = dialogView.findViewById(R.id.encryption_password_info),
            encryptionTogglePasswordBtn = dialogView.findViewById(R.id.encryption_toggle_password_btn),
            encryptionGenerateAutoBtn = dialogView.findViewById(R.id.encryption_generate_auto_btn)
        )
    }

    private fun setupSpinners(
        conversation: ChatConversation,
        ui: EncryptionDialogUIElements,
        activity: MainActivity
    ) {
        val encodingAdapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item,
            EncryptionMapper.encodingSchemes)
        encodingAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        ui.encodingSpinner.adapter = encodingAdapter

        val encryptionAdapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item,
            EncryptionMapper.encryptionSchemes)
        encryptionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        ui.encryptionSpinner.adapter = encryptionAdapter

        val encodingIndex = EncryptionMapper.encodingValues.indexOfFirst { it == conversation.encoding }
            .coerceAtLeast(0)
        val encryptionIndex = EncryptionMapper.encryptionValues.indexOfFirst { it == conversation.encryptionScheme }
            .coerceAtLeast(0)

        ui.encodingSpinner.setSelection(encodingIndex)
        ui.encryptionSpinner.setSelection(encryptionIndex)

        updateEncodingPasswordVisibility(encodingIndex, ui, activity)
    }

    private fun setupPasswordFields(
        conversation: ChatConversation,
        ui: EncryptionDialogUIElements,
        activity: MainActivity
    ) {
        LogUtils.d(activity, "ChatManager",
            "📝 Setup password fields per conversazione:" +
                    "\n  Phone: ${conversation.phoneNumber}" +
                    "\n  Encoding: ${conversation.encoding}" +
                    "\n  EncodingPassword: ${if (conversation.encodingPassword.isNullOrEmpty()) "EMPTY" else "SET"}" +
                    "\n  EncryptionScheme: ${conversation.encryptionScheme}")

        val savedPassword = CryptoManager.getSavedPasswordForChat(activity, conversation.phoneNumber)

        if (savedPassword.isEmpty() && conversation.encryptionScheme == EncryptionMapper.ENCRYPTION_SISA) {
            val autoPassword = CryptoManager.generateAutoPassword(activity, conversation.phoneNumber)
            ui.encryptionPasswordInput.setText(autoPassword)
            LogUtils.d(activity, "ChatManager", "🔑 SiSa Password generated automatically")
        } else if (savedPassword.isNotEmpty()) {
            ui.encryptionPasswordInput.setText(savedPassword)
            LogUtils.d(activity, "ChatManager", "🔑 SiSa Password recovered: ${savedPassword.take(5)}...")
        } else {
            ui.encryptionPasswordInput.setText("")
        }

        val savedEncoding = conversation.encoding
        val savedEncodingPassword = conversation.encodingPassword

        val encodingIndex = EncryptionMapper.encodingValues.indexOfFirst { it == savedEncoding }
            .coerceAtLeast(0)
        ui.encodingSpinner.setSelection(encodingIndex)

        ui.encodingPasswordInput.setText(savedEncodingPassword)

        LogUtils.d(activity, "ChatManager",
            "📊 Selected Encoding: $savedEncoding (index: $encodingIndex)" +
                    "\n  Password encoding: ${if (savedEncodingPassword.isEmpty()) "empty" else "set"}")

        updateEncodingPasswordVisibility(encodingIndex, ui, activity)
        updateEncryptionPasswordVisibility(ui.encryptionSpinner.selectedItemPosition, ui)
    }

    private fun setupListeners(
        ui: EncryptionDialogUIElements,
        conversation: ChatConversation,
        dialog: AlertDialog,
        chatManager: ChatManager,
        prefs: SharedPreferencesManager,
        activity: MainActivity
    ) {
        var permessitelefonomancanti = false

        ui.encodingSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateEncodingPasswordVisibility(position, ui, activity)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        ui.encryptionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateEncryptionPasswordVisibility(position, ui)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        setupPasswordToggleListener(
            ui.encodingPasswordInput,
            ui.encodingTogglePasswordBtn,
            activity
        )

        setupPasswordToggleListener(
            ui.encryptionPasswordInput,
            ui.encryptionTogglePasswordBtn,
            activity
        )

        ui.encodingGenerateAutoBtn.setOnClickListener {
            onEncodingGeneratePasswordClicked(ui, activity)
        }

        ui.encryptionGenerateAutoBtn.setOnClickListener {
            onEncryptionGeneratePasswordClicked(ui, conversation, activity) {
                permessitelefonomancanti = true
            }
        }

        dialog.setOnShowListener {
            setupSaveButton(
                dialog,
                ui,
                conversation,
                chatManager,
                activity,
                permessitelefonomancanti
            )
        }
    }

    private fun updateEncodingPasswordVisibility(
        encodingPosition: Int,
        ui: EncryptionDialogUIElements,
        activity: MainActivity
    ) {
        val isTextEncoding = EncryptionMapper.encodingValues[encodingPosition] == EncryptionMapper.ENCODING_PLAIN

        val visibility = if (isTextEncoding) View.GONE else View.VISIBLE
        ui.encodingPasswordContainer.visibility = visibility
        ui.encodingPasswordInfo.visibility = visibility
        ui.encodingTogglePasswordBtn.visibility = visibility
        ui.encodingGenerateAutoBtn.visibility = visibility

        if (!isTextEncoding) {
            val selectedEncoding = EncryptionMapper.encodingValues[encodingPosition]
            ui.encodingPasswordInfo.text = activity.getString(
                R.string.encoding_password_info,
                selectedEncoding,
                EncryptionMapper.MINENCODINGPWLEN
            )
        }
    }

    private fun updateEncryptionPasswordVisibility(
        encryptionPosition: Int,
        ui: EncryptionDialogUIElements
    ) {
        val isSisa = encryptionPosition == 0
        val visibility = if (isSisa) View.VISIBLE else View.GONE
        ui.encryptionPasswordContainer.visibility = visibility
        ui.encryptionPasswordInfo.visibility = visibility
        ui.encryptionTogglePasswordBtn.visibility = visibility
        ui.encryptionGenerateAutoBtn.visibility = visibility
    }

    private fun setupPasswordToggleListener(
        passwordInput: EditText,
        toggleButton: ImageButton,
        activity: MainActivity
    ) {
        var isPasswordVisible = true

        toggleButton.setOnClickListener {
            isPasswordVisible = !isPasswordVisible

            passwordInput.inputType = if (isPasswordVisible) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or
                        InputType.TYPE_TEXT_FLAG_MULTI_LINE
            }

            passwordInput.setSelection(passwordInput.text.length)

            val iconRes = if (isPasswordVisible)
                android.R.drawable.ic_menu_view
            else
                android.R.drawable.ic_partial_secure

            toggleButton.setImageResource(iconRes)
        }
    }

    private fun onEncodingGeneratePasswordClicked(
        ui: EncryptionDialogUIElements,
        activity: MainActivity
    ) {
        val generatedPassword = CryptoManager.generateSimplePassword()
        ui.encodingPasswordInput.setText(generatedPassword)
        ui.encodingPasswordInput.setSelection(generatedPassword.length)

        MainActivity.showToast(activity.getString(R.string.encoding_password_generated), false)

        ui.encodingPasswordInput.requestFocus()
        val imm = activity.getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(ui.encodingPasswordInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun onEncryptionGeneratePasswordClicked(
        ui: EncryptionDialogUIElements,
        conversation: ChatConversation,
        activity: MainActivity,
        onPermissionMissing: () -> Unit
    ) {
        CryptoManager.showPasswordWarningDialog(activity) {
            val autoPassword = CryptoManager.generateAutoPassword(activity, conversation.phoneNumber)
            ui.encryptionPasswordInput.setText(autoPassword)
            ui.encryptionPasswordInput.setSelection(autoPassword.length)

            if (autoPassword.isEmpty()) {
                onPermissionMissing()
            } else {
                MainActivity.showToast(activity.getString(R.string.auto_password_generated), false)
            }

            ui.encryptionPasswordInput.requestFocus()
            val imm = activity.getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(ui.encryptionPasswordInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun setupSaveButton(
        dialog: AlertDialog,
        ui: EncryptionDialogUIElements,
        conversation: ChatConversation,
        chatManager: ChatManager,
        activity: MainActivity,
        missingTelephonePermissions: Boolean
    ) {
        val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        positiveButton.text = activity.getString(R.string.save)

        val validationTextWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                validateInputs(positiveButton, ui)
            }
        }

        ui.encodingPasswordInput.addTextChangedListener(validationTextWatcher)
        ui.encryptionPasswordInput.addTextChangedListener(validationTextWatcher)

        ui.encodingSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateEncodingPasswordVisibility(position, ui, activity)
                validateInputs(positiveButton, ui)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        ui.encryptionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateEncryptionPasswordVisibility(position, ui)
                validateInputs(positiveButton, ui)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        positiveButton.setOnClickListener {
            onEncSaveButtonClicked(
                ui,
                conversation, //NB this is not the global conversation
                chatManager,
                activity,
                missingTelephonePermissions,
                dialog
            )
        }

        validateInputs(positiveButton, ui)
    }

    private fun onEncSaveButtonClicked(
        ui: EncryptionDialogUIElements,
        conversation: ChatConversation,
        chatManager: ChatManager,
        activity: MainActivity,
        missingTelephonePermissions: Boolean,
        dialog: AlertDialog
    ) {
        val selectedEncoding = EncryptionMapper.encodingValues[ui.encodingSpinner.selectedItemPosition]
        val selectedEncryption = EncryptionMapper.encryptionValues[ui.encryptionSpinner.selectedItemPosition]

        var encryptionPassword = ""
        var encodingPassword = ""

        if (selectedEncryption == EncryptionMapper.ENCRYPTION_SISA) {
            encryptionPassword = ui.encryptionPasswordInput.text.toString().trim()

            if (!missingTelephonePermissions && encryptionPassword.isEmpty()) {
                encryptionPassword = CryptoManager.generateAutoPassword(activity, conversation.phoneNumber)
                LogUtils.d(activity, "MainActivity",
                    "SiSa password automatically generated: ${encryptionPassword.take(6)}...")
            }
        }

        val isTextEncoding = selectedEncoding == EncryptionMapper.ENCODING_PLAIN
        if (!isTextEncoding) {
            encodingPassword = ui.encodingPasswordInput.text.toString().trim()
            val minLength = EncryptionMapper.MINENCODINGPWLEN

            if (encodingPassword.length < minLength) {
                MainActivity.showToast(
                    activity.getString(R.string.encoding_password_too_short, minLength),
                    true
                )
                return
            }
        }

        saveEncryptionEncodingConfiguration(
            selectedEncryption,
            selectedEncoding,
            encryptionPassword,
            encodingPassword,
            conversation,
            chatManager,
            activity
        )

        //activity.loadChatConversations()
        dialog.dismiss()
    }

    /**
     * Comprehensive dump of conversation using BOTH phone number and ID
     * Call this from MainActivity or anywhere with context
     */


    private fun saveEncryptionEncodingConfiguration(
        selectedEncryption: String,
        selectedEncoding: String,
        encryptionPassword: String,
        encodingPassword: String,
        conversation: ChatConversation,
        chatManager: ChatManager,
        activity: MainActivity
    ) {
        try {
            if (selectedEncryption == EncryptionMapper.ENCRYPTION_SISA) {
                CryptoManager.saveEncryptionAndPasswordData(
                    activity,
                    conversation.phoneNumber,
                    EncryptionMapper.ENCRYPTION_SISA,
                    encryptionPassword
                )
                val isAutoPassword = encryptionPassword == CryptoManager.generateAutoPassword(activity, conversation.phoneNumber)
                val message = if (isAutoPassword) {
                    activity.getString(R.string.sisa_encryption_configured_auto)
                } else {
                    activity.getString(R.string.sisa_encryption_configured_custom)
                }
                MainActivity.showToast(message)
            } else {
                val message = getEncodingMessage(selectedEncoding, activity)
                MainActivity.showToast(message)
            }

            chatManager.setEncryptionSchemeForChat(conversation.phoneNumber, selectedEncryption)
            chatManager.setEncodingSchemeAndPasswordForChat(
                conversation,
                selectedEncoding,
                encodingPassword
            )
            /* useless - conversation are already loaded
            Thread {
                Thread.sleep(300)
                activity.runOnUiThread {
                    activity.loadChatConversations()
                    LogUtils.d(activity, "ChatManager",
                        "✅ Configuration saved and loaded - " +
                                "Encryption: $selectedEncryption, Encoding: $selectedEncoding")
                }
            }.start()
            */
        } catch (e: Exception) {
            LogUtils.e(activity, "ChatManager", "❌ Error saving configuration", e)
            MainActivity.showToast(activity.getString(R.string.errorsavingkconfig,  e.message),true)
        }
    }

    private fun getEncodingMessage(encoding: String, activity: MainActivity): String {
        return when (encoding) {
            EncryptionMapper.ENCODING_BASE32 -> activity.getString(R.string.base32_encoding_configured)
            EncryptionMapper.ENCODING_BASE64 -> activity.getString(R.string.base64_encoding_configured)
            EncryptionMapper.ENCODING_BASE128 -> activity.getString(R.string.base128_encoding_configured)
            EncryptionMapper.ENCODING_BASE256 -> activity.getString(R.string.base256_encoding_configured)
            EncryptionMapper.ENCODING_BASE512 -> activity.getString(R.string.base512_encoding_configured)
            EncryptionMapper.ENCODING_BASE1024 -> activity.getString(R.string.base1024_encoding_configured)
            EncryptionMapper.ENCODING_BASE2048 -> activity.getString(R.string.base2048_encoding_configured)
            EncryptionMapper.ENCODING_PLAIN -> activity.getString(R.string.plain_text_set)
            else -> activity.getString(R.string.encoding_set, encoding)
        }
    }

    private fun validateInputs(
        positiveButton: Button,
        ui: EncryptionDialogUIElements
    ) {
        val selectedEncoding = EncryptionMapper.encodingValues[ui.encodingSpinner.selectedItemPosition]
        val selectedEncryption = EncryptionMapper.encryptionValues[ui.encryptionSpinner.selectedItemPosition]

        val encodingPassword = ui.encodingPasswordInput.text.toString().trim()
        val encryptionPassword = ui.encryptionPasswordInput.text.toString().trim()

        val isTextEncoding = selectedEncoding == EncryptionMapper.ENCODING_PLAIN
        val isSisaSelected = selectedEncryption == EncryptionMapper.ENCRYPTION_SISA

        var isValid = false

        if (isSisaSelected) {
            isValid = encryptionPassword.length >= 16
        } else if (!isTextEncoding) {
            val minLength = EncryptionMapper.MINENCODINGPWLEN
            isValid = encodingPassword.length >= minLength
        } else {
            isValid = false
        }

        positiveButton.isEnabled = isValid

        if (isValid) {
            positiveButton.alpha = 1.0f
        } else {
            positiveButton.alpha = 0.5f
        }
    }

    private fun focusOnPasswordIfNeeded(ui: EncryptionDialogUIElements) {
        if (ui.encryptionSpinner.selectedItemPosition == 0) {
            ui.encryptionPasswordInput.requestFocus()
            val activity = ui.encodingPasswordInput.context as? MainActivity
            activity?.let {
                val imm = it.getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(ui.encryptionPasswordInput, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    /**
     * Clears chat messages
     */
    fun clearChatMessages(
        conversation: ChatConversation,
        chatManager: ChatManager,
        activity: MainActivity
    ) {
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.clear_chat_question))
            .setMessage(activity.getString(R.string.clear_chat_message, conversation.contactName ?: conversation.phoneNumber))
            .setPositiveButton(activity.getString(R.string.yes)) { _, _ ->
                val nPhoneNumber = PhoneUtils.normalizePhoneNumber(conversation.phoneNumber)
                val conversations = chatManager.getAllConversations().toMutableList()
                val index = conversations.indexOfFirst { it.phoneNumber == nPhoneNumber }

                if (index >= 0) {
                    conversations[index] = conversations[index].copy(
                        messages = mutableListOf(),
                        lastMessage = "",
                        lastTimestamp = System.currentTimeMillis(),
                        unreadCount = 0
                    )
                    chatManager.saveConversations(conversations)
                    activity.loadChatConversations()
                    MainActivity.showToast(activity.getString(R.string.chat_emptied))
                }
            }
            .setNegativeButton(activity.getString(R.string.no), null)
            .show()
    }

    /**
     * Deletes chat conversation
     */
    fun deleteChatConversation(
        conversation: ChatConversation,
        chatManager: ChatManager,
        activity: MainActivity
    ) {
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.delete_chat_question))
            .setMessage(activity.getString(R.string.delete_chat_message, conversation.contactName ?: conversation.phoneNumber))
            .setPositiveButton(activity.getString(R.string.yes)) { _, _ ->
                DatabaseActor.getInstance(activity).deleteChatConversationWithCallback(
                    phoneNumber = conversation.phoneNumber,
                    conversation.contactName,
                    onSuccess = {
                        MainActivity.showToast(activity.getString(R.string.chat_deleted))
                        activity.loadChatConversations()
                    },
                    onError = { error ->
                        LogUtils.e("DeleteChat", "❌ Error deleting chat: $error")
                        MainActivity.showToast(activity.getString(R.string.errorwhilechatdeleting),true)
                    }
                )
            }
            .setNegativeButton(activity.getString(R.string.no), null)
            .show()
    }

    /**
     * Marks chat as read
     */
    fun markChatAsRead(
        conversation: ChatConversation,
        chatManager: ChatManager,
        activity: MainActivity
    ) {
        chatManager.markAsRead(conversation.phoneNumber)
        activity.loadChatConversations()
        MainActivity.showToast(activity.getString(R.string.mark_as_read_toast))
    }

    /**
     * Sets up keyboard safety for all EditTexts
     */
    fun setupKeyboardSafetyForAllEditTexts(keyboardSafetyManager: KeyboardSafetyManager) {
        val editTexts = findAllEditTexts(activity.findViewById(android.R.id.content))

        editTexts.forEach { editText ->
            editText.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        when (keyboardSafetyManager.checkKeyboardSafety()) {
                            KeyboardSafetyManager.KeyboardSafetyStatus.REJECTED ->
                                LogUtils.d(activity, "KEYBOARD", activity.getString(R.string.keyboard_rejected_log))

                            KeyboardSafetyManager.KeyboardSafetyStatus.IGNORED ->
                                LogUtils.d(activity, "KEYBOARD", activity.getString(R.string.keyboard_unknown_log))

                            KeyboardSafetyManager.KeyboardSafetyStatus.APPROVED ->
                                LogUtils.d(activity, "KEYBOARD", activity.getString(R.string.keyboard_safe_log))
                        }
                    }, 300)
                }
            }

            editText.setOnClickListener {
                Handler(Looper.getMainLooper()).postDelayed({
                    when (keyboardSafetyManager.checkKeyboardSafety()) {
                        KeyboardSafetyManager.KeyboardSafetyStatus.REJECTED ->
                            MainActivity.showToast(activity.getString(R.string.keyboard_unsafe_warning), true)

                        KeyboardSafetyManager.KeyboardSafetyStatus.IGNORED ->
                            MainActivity.showToast(activity.getString(R.string.keyboard_unknown_warning), true)

                        else -> {}
                    }
                }, 300)
            }
        }
    }

    /**
     * Shows screenshot permission dialog
     */
    fun showScreenshotPermissionDialog(prefs: SharedPreferencesManager) {
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.screenshot_permission_title))
            .setMessage(activity.getString(R.string.screenshot_permission_message))
            .setPositiveButton(activity.getString(R.string.allow)) { dialog, _ ->
                enableScreenshotsImmediately(prefs)
                dialog.dismiss()
            }
            .setNegativeButton(activity.getString(R.string.cancel)) { dialog, _ ->
                activity.allowScreenshotsToggle.isChecked = false
                dialog.dismiss()
            }
            .setOnCancelListener {
                activity.allowScreenshotsToggle.isChecked = false
            }
            .show()
    }

    /**
     * Enables screenshots immediately
     */
    private fun enableScreenshotsImmediately(prefs: SharedPreferencesManager) {
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        prefs.allowScreenshots = true
        MainActivity.showToast(activity.getString(R.string.screenshots_enabled))
    }

    /**
     * Formats phone number for display
     */
    fun formatPhoneNumber(number: String): String {
        return when {
            number.startsWith("+39") && number.length == 13 ->
                "+39 ${number.substring(3, 6)} ${number.substring(6, 9)} ${number.substring(9)}"
            number.length == 10 ->
                "${number.substring(0, 3)} ${number.substring(3, 6)} ${number.substring(6)}"
            else -> number
        }
    }

    /**
     * Finds all EditTexts in a View recursively
     */
    private fun findAllEditTexts(root: View): List<EditText> {
        val editTexts = mutableListOf<EditText>()

        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val child = root.getChildAt(i)
                if (child is EditText) {
                    editTexts.add(child)
                } else if (child is ViewGroup) {
                    editTexts.addAll(findAllEditTexts(child))
                }
            }
        }

        return editTexts
    }


}