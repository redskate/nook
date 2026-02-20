package solutions.semweb.nook.chat

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.textfield.TextInputLayout
import solutions.semweb.nook.ChatConversation
import solutions.semweb.nook.LogUtils
import solutions.semweb.nook.MainActivity
import solutions.semweb.nook.PhoneUtils
import solutions.semweb.nook.R
import solutions.semweb.nook.SharedPreferencesManager
import java.io.File


class ChatImportExportManager(private val context: Context) {

    companion object {
        private const val TAG = "ImportExportManager"

        /**
         * Factory method to create an instance
         */
        fun create(context: Context): ChatImportExportManager {
            return ChatImportExportManager(context)
        }
    }

    @SuppressLint("StringFormatInvalid")
    fun showExportPasswordDialog(
        conversation: ChatConversation,
        chatManager: ChatManager,
        requirePassword: Boolean
    ) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_export_password, null)
        val passwordEditText = dialogView.findViewById<EditText>(R.id.password_edit_text)
        val passwordConfirmEditText = dialogView.findViewById<EditText>(R.id.password_confirm_edit_text)
        val confirmButton = dialogView.findViewById<Button>(R.id.confirm_button)
        val generateButton = dialogView.findViewById<Button>(R.id.generate_password_button)
        val passwordLayout = dialogView.findViewById<TextInputLayout>(R.id.password_layout)
        val confirmLayout = dialogView.findViewById<TextInputLayout>(R.id.password_confirm_layout)
        val messageTextView = dialogView.findViewById<TextView>(R.id.message_text)

        if (!requirePassword) {
            messageTextView.text = context.getString(R.string.chatesport_no_crypt)
            passwordLayout.visibility = View.GONE
            confirmLayout.visibility = View.GONE
            generateButton.visibility = View.GONE
        } else {
            messageTextView.text = context.getString(R.string.chatesport_password_crypt)
            passwordLayout.visibility = View.VISIBLE
            confirmLayout.visibility = View.VISIBLE
            generateButton.visibility = View.VISIBLE

            // Listener per il generatore password
            generateButton.setOnClickListener {
                val generatedPassword = generateRandomPassword(16)
                passwordEditText.setText(generatedPassword)
                passwordConfirmEditText.setText(generatedPassword)
                confirmButton.isEnabled = true
            }
        }

        val textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (requirePassword) {
                    val password = passwordEditText.text.toString()
                    val confirm = passwordConfirmEditText.text.toString()
                    val isValid = (password.isEmpty() && confirm.isEmpty()) ||
                            (password.length >= 16 && password == confirm)
                    confirmButton.isEnabled = isValid
                } else {
                    confirmButton.isEnabled = true
                }
            }
        }

        passwordEditText.addTextChangedListener(textWatcher)
        passwordConfirmEditText.addTextChangedListener(textWatcher)

        val dialog = AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.export_encrypted_chat))
            .setView(dialogView)
            .setNegativeButton(context.getString(R.string.cancel), null)
            .create()

        confirmButton.setOnClickListener {
            val password = passwordEditText.text.toString()
            dialog.dismiss()

            val prefs = SharedPreferencesManager.getInstance(context)
            val myName = prefs.appOwnerName

            ChatExportTask(
                context = context,
                myName = myName,
                phoneNumber = PhoneUtils.normalizePhoneNumber(conversation.phoneNumber),
                contactName = conversation.contactName ?: conversation.phoneNumber,
                isYChat = conversation.isYChat,
                yUserId = if (conversation.phoneNumber.startsWith("Y_")) {
                    conversation.phoneNumber.removePrefix("Y_")
                } else {
                    null
                },
                password = if (requirePassword) password else null,
                onExportComplete = { success, filePath ->
                    if (context is MainActivity) {
                        context.runOnUiThread {
                            if (!success) {
                                MainActivity.showToast(context.getString(R.string.export_failed))
                            }
                        }
                    }
                }
            ).execute()
        }

        dialog.show()
        if (requirePassword) {
            confirmButton.isEnabled = false
        }
    }

    fun showImportPasswordDialog() {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_import_password, null)

        // Riferimenti agli elementi
        val passwordEditText = dialogView.findViewById<EditText>(R.id.password_edit_text)
        val passwordConfirmEditText = dialogView.findViewById<EditText>(R.id.password_confirm_edit_text)
        val confirmButton = dialogView.findViewById<Button>(R.id.confirm_button)
        val passwordLayout = dialogView.findViewById<TextInputLayout>(R.id.password_layout)
        val confirmLayout = dialogView.findViewById<TextInputLayout>(R.id.password_confirm_layout)
        val messageTextView = dialogView.findViewById<TextView>(R.id.message_text)
        val browseButton = dialogView.findViewById<Button>(R.id.browse_button)
        val selectedFileTextView = dialogView.findViewById<TextView>(R.id.selected_file_text)
        val selectedFileContainer = dialogView.findViewById<LinearLayout>(R.id.selected_file_container)

        var selectedFileUri: Uri? = null
        var selectedFilePath: String? = null
        var fileRequiresPassword = false

        messageTextView.text = context.getString(R.string.select_import_file_message)
        passwordLayout.visibility = View.GONE
        confirmLayout.visibility = View.GONE
        selectedFileContainer.visibility = View.GONE
        confirmButton.isEnabled = false
        confirmButton.alpha = 0.5f

        val dialog = AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.dialog_import_title))
            .setView(dialogView)
            .setNegativeButton(context.getString(R.string.cancel)) { d, _ ->
                d.dismiss()
            }
            .create()

        browseButton.setOnClickListener {
            if (context is MainActivity) {
                val activity = context as MainActivity

                // File picker
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }

                try {
                    activity.currentImportDialogHandler = { uri, filePath ->
                        selectedFileUri = uri
                        selectedFilePath = filePath

                        val fileName = File(filePath).name
                        selectedFileTextView.text = fileName
                        selectedFileContainer.visibility = View.VISIBLE

                        fileRequiresPassword = ChatImporter.requiresPassword(fileName)

                        if (fileRequiresPassword) {
                            passwordLayout.visibility = View.VISIBLE
                            confirmLayout.visibility = View.VISIBLE
                        } else {
                            passwordLayout.visibility = View.GONE
                            confirmLayout.visibility = View.GONE
                        }

                        // Valida gli input
                        validateImportInputs(
                            passwordEditText,
                            passwordConfirmEditText,
                            confirmButton,
                            fileRequiresPassword,
                            true
                        )
                    }

                    activity.currentImportDialog = dialog

                    // Start file picker
                    activity.filePickerLauncher.launch(intent)

                } catch (e: Exception) {
                    LogUtils.e(context, TAG, "Error file picker")
                    MainActivity.showToast(context.getString(R.string.error_filepicker) )
                }
            } else {
                LogUtils.e(context, TAG, "Context Error file picker")
            }
        }

        val textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                validateImportInputs(
                    passwordEditText,
                    passwordConfirmEditText,
                    confirmButton,
                    fileRequiresPassword,
                    selectedFileUri != null
                )
            }
        }

        passwordEditText.addTextChangedListener(textWatcher)
        passwordConfirmEditText.addTextChangedListener(textWatcher)

        // Confirm button listener
        confirmButton.setOnClickListener {
            val password = if (fileRequiresPassword) {
                passwordEditText.text.toString()
            } else {
                null
            }

            if (selectedFileUri != null && selectedFilePath != null) {
                dialog.dismiss()

                if (context is MainActivity) {
                    context.currentImportDialogHandler = null
                    context.currentImportDialog = null
                }

                // Start import
                ChatImporter.importChat(
                    context = context,
                    fileUri = selectedFileUri!!,
                    filePath = selectedFilePath!!,
                    password = password
                ) { success, errorMessage, messageCount ->
                    if (context is MainActivity) {
                        context.runOnUiThread {
                            if (success) {
                                MainActivity.showToast(context.getString(R.string.import_success, messageCount ?: 0))
                                context.loadChatConversations()
                            } else {
                                MainActivity.showToast("${context.getString(R.string.import_failed)}: $errorMessage")
                            }
                        }
                    }
                }
            } else {
                MainActivity.showToast(context.getString(R.string.import_no_file_selected))
            }
        }

        dialog.show()
    }


    private fun validateImportInputs(
        passwordEditText: EditText,
        passwordConfirmEditText: EditText,
        confirmButton: Button,
        fileRequiresPassword: Boolean,
        hasSelectedFile: Boolean
    ) {
        if (!hasSelectedFile) {
            confirmButton.isEnabled = false
            confirmButton.alpha = 0.5f
            return
        }

        if (fileRequiresPassword) {
            val password = passwordEditText.text.toString()
            val confirm = passwordConfirmEditText.text.toString()
            val isValid = password.length >= 16 && password == confirm

            confirmButton.isEnabled = isValid
            confirmButton.alpha = if (isValid) 1.0f else 0.5f
        } else {
            confirmButton.isEnabled = true
            confirmButton.alpha = 1.0f
        }
    }

    private fun generateRandomPassword(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*()"
        return (1..length)
            .map { chars.random() }
            .joinToString("")
    }


}