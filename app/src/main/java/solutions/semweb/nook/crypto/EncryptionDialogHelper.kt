package solutions.semweb.nook.crypto

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import solutions.semweb.nook.ChatConversation
import solutions.semweb.nook.Constants
import solutions.semweb.nook.LogUtils
import solutions.semweb.nook.MainActivity
import solutions.semweb.nook.R
import solutions.semweb.nook.SharedPreferencesManager
import solutions.semweb.nook.chat.ChatActivity
import solutions.semweb.nook.chat.ChatManager

/**
 * This is use from both MainActivityUtils and ChatActivity
 * that is the reason we refactored out here
 */
object EncryptionDialogHelper {

    fun showEncryptionCodingSchemesDialogForChat(
        conversation: ChatConversation,
        view: View,
        prefs: SharedPreferencesManager,
        chatManager: ChatManager,
        activity: AppCompatActivity
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
        focusOnPasswordIfNeeded(uiElements, activity)
    }

    private fun checkAndRequestPhonePermissionIfNeeded(
        conversation: ChatConversation,
        prefs: SharedPreferencesManager,
        activity: AppCompatActivity
    ): Boolean {
        if (conversation.encryptionScheme == EncryptionMapper.ENCRYPTION_SISA ||
            prefs.decodingScheme == EncryptionMapper.ENCRYPTION_SISA) {

            // On Android 16+, no permission needed - just return true
            if (Build.VERSION.SDK_INT >= 36) { // Android 16 API level
                return true
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

    private fun initializeEncryptEncodingDialogUI(
        conversation: ChatConversation,
        dialogView: View,
        activity: AppCompatActivity
    ): AlertDialog {
        return AlertDialog.Builder(activity)
            .setTitle(activity.getString(
                R.string.encryption_for_contact,
                conversation.contactName ?: conversation.phoneNumber))
            .setView(dialogView)
            .setPositiveButton(activity.getString(R.string.save), null)  // null listener, we'll set it later
            .setNegativeButton(activity.getString(R.string.cancel)) { dialog, _ ->
                dialog.dismiss()
            }
            .setNeutralButton(activity.getString(R.string.default_button)) { dialog, _ ->
                // Use the activity as Context
                MainActivity.Companion.resetConversationToDefault(activity, conversation) {
                    // Refresh UI after reset
                    // We need to refresh the dialog or close it
                    dialog.dismiss()
                    // Optionally show the dialog again with default values
                    showEncryptionCodingSchemesDialogForChat(
                        conversation = conversation,
                        view = dialogView,
                        prefs = SharedPreferencesManager.Companion.getInstance(activity),
                        chatManager = ChatManager(activity),
                        activity = activity
                    )
                }
            }
            .create()
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
        activity: AppCompatActivity
    ) {
        val encodingAdapter = ArrayAdapter(
            activity, android.R.layout.simple_spinner_item,
            EncryptionMapper.encodingSchemes
        )
        encodingAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        ui.encodingSpinner.adapter = encodingAdapter

        val encryptionAdapter = ArrayAdapter(
            activity, android.R.layout.simple_spinner_item,
            EncryptionMapper.encryptionSchemes
        )
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
        activity: AppCompatActivity
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
        activity: AppCompatActivity
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
        activity: AppCompatActivity
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
        activity: AppCompatActivity
    ) {
        var isPasswordVisible = false

        passwordInput.inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_PASSWORD or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE

        // Start with closed lock
        toggleButton.setImageResource(R.drawable.ic_lock_closed)

        toggleButton.setOnClickListener {
            isPasswordVisible = !isPasswordVisible

            passwordInput.inputType = if (isPasswordVisible) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or
                        InputType.TYPE_TEXT_FLAG_MULTI_LINE
            }

            passwordInput.setSelection(passwordInput.text.length)

            // Closed lock when hidden, open lock when visible
            val iconRes = if (isPasswordVisible)
                R.drawable.ic_lock_open
            else
                R.drawable.ic_lock_closed

            toggleButton.setImageResource(iconRes)
        }
    }

    private fun onEncodingGeneratePasswordClicked(
        ui: EncryptionDialogUIElements,
        activity: AppCompatActivity
    ) {
        val generatedPassword = CryptoManager.generateSimplePassword()
        ui.encodingPasswordInput.setText(generatedPassword)
        ui.encodingPasswordInput.setSelection(generatedPassword.length)

        MainActivity.Companion.showToast(activity.getString(R.string.encoding_password_generated), false, activity)

        ui.encodingPasswordInput.requestFocus()
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(ui.encodingPasswordInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun onEncryptionGeneratePasswordClicked(
        ui: EncryptionDialogUIElements,
        conversation: ChatConversation,
        activity: AppCompatActivity,
        onPermissionMissing: () -> Unit
    ) {
        CryptoManager.showPasswordWarningDialog(activity) {
            val autoPassword = CryptoManager.generateAutoPassword(activity, conversation.phoneNumber)
            ui.encryptionPasswordInput.setText(autoPassword)
            ui.encryptionPasswordInput.setSelection(autoPassword.length)

            if (autoPassword.isEmpty()) {
                onPermissionMissing()
            } else {
                MainActivity.Companion.showToast(activity.getString(R.string.auto_password_generated), false, activity)
            }

            ui.encryptionPasswordInput.requestFocus()
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(ui.encryptionPasswordInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun setupSaveButton(
        dialog: AlertDialog,
        ui: EncryptionDialogUIElements,
        conversation: ChatConversation,
        chatManager: ChatManager,
        activity: AppCompatActivity,
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
                conversation,
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
        activity: AppCompatActivity,
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
                MainActivity.Companion.showToast(
                    activity.getString(R.string.encoding_password_too_short, minLength),
                    true,
                    activity
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

        dialog.dismiss()
    }

    private fun saveEncryptionEncodingConfiguration(
        selectedEncryption: String,
        selectedEncoding: String,
        encryptionPassword: String,
        encodingPassword: String,
        conversation: ChatConversation,
        chatManager: ChatManager,
        activity: AppCompatActivity
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
                MainActivity.Companion.showToast(message, false, activity)
            } else {
                val message = getEncodingMessage(selectedEncoding, activity)
                MainActivity.Companion.showToast(message)
            }

            chatManager.setEncryptionSchemeForChat(conversation.phoneNumber, selectedEncryption)
            chatManager.setEncodingSchemeAndPasswordForChat(
                conversation,
                selectedEncoding,
                encodingPassword
            )

            // Update the conversation object with new values
            conversation.encryptionScheme = selectedEncryption
            conversation.encoding = selectedEncoding
            conversation.encodingPassword = encodingPassword

            // Directly update the ChatActivity's toolbar if it's the current activity
            if (activity is ChatActivity) {
                activity.runOnUiThread {
                    activity.updateToolbarColor()
                    LogUtils.d(activity, "EncryptionDialogHelper", "🎨 Toolbar color updated directly")
                }
            } else {
                // Fallback to broadcast if not ChatActivity
                val intent = Intent("${Constants.mainpackage}.CHAT_UPDATED")
                activity.sendBroadcast(intent)
            }

        } catch (e: Exception) {
            LogUtils.e(activity, "ChatManager", "❌ Error saving configuration", e)
            MainActivity.Companion.showToast(activity.getString(R.string.errorsavingkconfig, e.message), true, activity)
        }
    }

    private fun getEncodingMessage(encoding: String, activity: AppCompatActivity): String {
        return when (encoding) {
            EncryptionMapper.ENCODING_BASE32 -> activity.getString(R.string.base32_encoding_configured)
            EncryptionMapper.ENCODING_BASE64 -> activity.getString(R.string.base64_encoding_configured)
            EncryptionMapper.ENCODING_BASE256 -> activity.getString(R.string.base256_encoding_configured)
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

    private fun focusOnPasswordIfNeeded(
        ui: EncryptionDialogUIElements,
        activity: AppCompatActivity
    ) {
        if (ui.encryptionSpinner.selectedItemPosition == 0) {
            ui.encryptionPasswordInput.requestFocus()
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(ui.encryptionPasswordInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }
}