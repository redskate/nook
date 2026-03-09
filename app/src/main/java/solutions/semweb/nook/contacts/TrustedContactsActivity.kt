package solutions.semweb.nook.contacts

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import solutions.semweb.nook.BaseActivity
import solutions.semweb.nook.ContactInfo
import solutions.semweb.nook.LogUtils
import solutions.semweb.nook.MainActivity
import solutions.semweb.nook.PhoneUtils
import solutions.semweb.nook.R
import solutions.semweb.nook.SharedPreferencesManager
import solutions.semweb.nook.TrustedContact
import solutions.semweb.nook.data.database.DatabaseActor
import java.util.Locale

class TrustedContactsActivity : BaseActivity() {

    private lateinit var prefs: SharedPreferencesManager
    private lateinit var contactsRecyclerView: RecyclerView
    private lateinit var adapter: TrustedContactsAdapter
    private lateinit var selectContactBtn: Button
    private lateinit var clearAllBtn: Button
    private lateinit var backButton: AppCompatImageView
    private lateinit var searchContactsButton: ImageView

    companion object {
        private const val PERMISSION_REQUEST_CONTACTS = 200
        private const val PICK_CONTACT_REQUEST = 201
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trusted_contacts)

        prefs = SharedPreferencesManager.getInstance(this)

        contactsRecyclerView = findViewById(R.id.contacts_recycler_view)
        selectContactBtn = findViewById(R.id.select_contact_btn)
        clearAllBtn = findViewById(R.id.clear_all_btn)
        backButton = findViewById(R.id.back_button)
        searchContactsButton = findViewById(R.id.search_contacts_4_chats_button)

        setupUI()
    }

    private fun setupUI() {
        backButton.setOnClickListener {
            onBackPressed()
        }

        searchContactsButton.setOnClickListener {
            showSearchDialogForTrustedContacts()
        }

        contactsRecyclerView.layoutManager = LinearLayoutManager(this)
        adapter = TrustedContactsAdapter(
            contacts = prefs.trustedContacts,
            onRemoveClick = { contactId ->
                removeContact(contactId)
            },
            onActiveChange = { contactId, isActive ->
                prefs.setTrustedContactActive(contactId, isActive)
                MainActivity.showToast(getString(
                    R.string.contact_state_changed, if (isActive) getString(R.string.activated) else getString(
                        R.string.deactivated)))
                adapter.updateContacts(prefs.trustedContacts)
            }
        )
        contactsRecyclerView.adapter = adapter

        selectContactBtn.setOnClickListener {
            if (checkContactsPermission()) {
                pickContact()
            } else {
                requestContactsPermission()
            }
        }

        clearAllBtn.setOnClickListener {
            clearAllContacts()
        }
    }

    private fun checkContactsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
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
            PERMISSION_REQUEST_CONTACTS -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    pickContact()
                } else {
                    MainActivity.showToast(getString(R.string.contacts_permission_denied), isError = true)
                }
            }
        }
    }

    private fun pickContact() {
        val intent = Intent(Intent.ACTION_PICK, ContactsContract.Contacts.CONTENT_URI)
        startActivityForResult(intent, PICK_CONTACT_REQUEST)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == PICK_CONTACT_REQUEST && resultCode == RESULT_OK && data != null) {
            val contactUri = data.data
            if (contactUri != null) {
                processSelectedContact(contactUri)
            }
        }
    }

    private fun processSelectedContact(contactUri: Uri) {
        val cursor: Cursor? = contentResolver.query(
            contactUri,
            null, null, null, null
        )

        cursor?.use {
            if (it.moveToFirst()) {
                val idColumnIndex = it.getColumnIndex(ContactsContract.Contacts._ID)
                val nameColumnIndex = it.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)

                if (idColumnIndex >= 0 && nameColumnIndex >= 0) {
                    val contactId = it.getString(idColumnIndex)
                    val displayName = it.getString(nameColumnIndex)

                    val phoneNumbers = getAllPhoneNumbers(contactId)

                    when (phoneNumbers.size) {
                        0 -> {
                            MainActivity.showToast(getString(R.string.contact_without_phone_number), isError = true)
                        }
                        else -> { // 1
                            val phoneNumber = phoneNumbers[0]
                            addTrustedContact(contactId, displayName, phoneNumber)
                        }
                    }
                } else {
                    MainActivity.showToast(getString(R.string.error_reading_contact_data), isError = true)
                }
            }
        }
        cursor?.close()
    }

    private fun getAllPhoneNumbers(contactId: String): List<String> {
        val phoneNumbers = mutableListOf<String>()

        val phoneCursor: Cursor? = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            null,
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(contactId),
            null
        )

        phoneCursor?.use {
            val numberColumnIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

            while (it.moveToNext()) {
                if (numberColumnIndex >= 0) {
                    val phoneNumber = it.getString(numberColumnIndex)
                    if (!phoneNumber.isNullOrBlank()) {
                        phoneNumbers.add(phoneNumber)
                    }
                }
            }
        }
        phoneCursor?.close()

        return phoneNumbers
    }

    private fun addTrustedContact(contactId: String, displayName: String, phoneNumber: String) {
        LogUtils.d(this, "ADD_CONTACT", "=== ADDING NEW CONTACT ===")
        LogUtils.d(this, "ADD_CONTACT", "Original - Name: '$displayName'")
        LogUtils.d(this, "ADD_CONTACT", "Original - Phone: '$phoneNumber'")

        val contact = TrustedContact(
            contactId = contactId,
            phoneNumber = phoneNumber,
            displayName = displayName,
            isActive = true
        )

        val existingContact = prefs.trustedContacts.find {
            PhoneUtils.normalizePhoneNumber(it.phoneNumber) == PhoneUtils.normalizePhoneNumber(phoneNumber)
        }

        if (existingContact != null) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.contact_already_exists))
                .setMessage(getString(R.string.contact_already_exists_message, phoneNumber, existingContact.displayName))
                .setPositiveButton(getString(R.string.ok), null)
                .show()
        } else {
            lifecycleScope.launch {
                try {
                    // Save
                    withContext(Dispatchers.IO) {
                        DatabaseActor.getInstance(this@TrustedContactsActivity)
                            .saveTrustedContact(contact)
                    }

                    // Verify immediately after save
                    val savedContacts = withContext(Dispatchers.IO) {
                        DatabaseActor.getInstance(this@TrustedContactsActivity)
                            .getTrustedContacts()
                    }

                    savedContacts.find { it.contactId == contactId }?.let { savedContact ->
                        LogUtils.d(this@TrustedContactsActivity, "ADD_CONTACT", "=== VERIFY AFTER SAVE ===")
                        debugContactFlow(savedContact)
                    }

                    withContext(Dispatchers.Main) {
                        MainActivity.showToast(getString(R.string.trusted_contact_added))
                        refreshTrustedContactsList()
                    }

                } catch (e: Exception) {
                    LogUtils.e(this@TrustedContactsActivity, "ADD_CONTACT",
                        "❌ Error saving contact", e)
                }
            }
        }
    }



    private fun debugContactFlow(contact: TrustedContact) {
        LogUtils.d(this, "CONTACT_FLOW", "=== CONTACT FLOW DEBUG ===")
        LogUtils.d(this, "CONTACT_FLOW", "Contact ID: ${contact.contactId}")
        LogUtils.d(this, "CONTACT_FLOW", "Display Name: '${contact.displayName}'")
        LogUtils.d(this, "CONTACT_FLOW", "Phone Number: '${contact.phoneNumber}'")

        // Check if phone number looks encrypted (contains non-printable chars)
        val hasNonPrintable = contact.phoneNumber.any { it.code < 32 || it.code > 126 }
        LogUtils.d(this, "CONTACT_FLOW", "Phone has non-printable chars: $hasNonPrintable")

        // Check if it's valid UTF-8
        try {
            val bytes = contact.phoneNumber.toByteArray(Charsets.UTF_8)
            val reconstructed = String(bytes, Charsets.UTF_8)
            LogUtils.d(this, "CONTACT_FLOW", "UTF-8 roundtrip: ${contact.phoneNumber == reconstructed}")
        } catch (e: Exception) {
            LogUtils.d(this, "CONTACT_FLOW", "UTF-8 test failed: ${e.message}")
        }
    }


    private fun refreshTrustedContactsList() {
        LogUtils.d(this, "TRUSTED_CONTACTS", "🔄 refreshTrustedContactsList() chiamato")

        lifecycleScope.launch {
            try {
                val refreshedContacts = withContext(Dispatchers.IO) {
                    DatabaseActor.getInstance(this@TrustedContactsActivity)
                        .getTrustedContacts()
                }

                // DEBUG: Call debugContactFlow for each contact
                refreshedContacts.forEach { contact ->
                    debugContactFlow(contact)
                }

                withContext(Dispatchers.Main) {
                    adapter.updateContacts(refreshedContacts)
                    contactsRecyclerView.invalidate()
                    contactsRecyclerView.requestLayout()
                }

            } catch (e: Exception) {
                LogUtils.e(this@TrustedContactsActivity, "TRUSTED_CONTACTS",
                    "❌ Error refreshing contacts", e)
            }
        }
    }


    private fun removeContact(contactId: String) {
        prefs.removeTrustedContact(contactId)

        refreshTrustedContactsList()

        MainActivity.showToast(getString(R.string.contact_removed))
    }

    private fun clearAllContacts() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.remove_contact_confirmation))
            .setMessage(getString(R.string.remove_contact_message))
            .setPositiveButton(getString(R.string.yes)) { _, _ ->
                prefs.clearTrustedContacts()

                // Usa refresh invece di updateContacts diretto
                refreshTrustedContactsList()

                MainActivity.showToast(getString(R.string.all_contacts_removed))
            }
            .setNegativeButton(getString(R.string.no), null)
            .show()
    }

    private fun showSearchDialogForTrustedContacts() {
        if (!checkContactsPermission()) {
            requestContactsPermission()
            return
        }

        val allContacts = getAllContacts()
        val existingTrusted = prefs.trustedContacts
        val existingPhoneNumbers = existingTrusted.map { PhoneUtils.normalizePhoneNumber(it.phoneNumber) }.toSet()

        val availableContacts = allContacts.filter { contact ->
            val normalizedNumber = PhoneUtils.normalizePhoneNumber(contact.phoneNumber)
            !existingPhoneNumbers.contains(normalizedNumber)
        }

        showSearchDialog(
            contacts = availableContacts,
            title = getString(R.string.search_contact_to_trust_title),
            onContactSelected = { contact ->
                addTrustedContact(contact.contactId, contact.displayName ?: getString(R.string.unknown), contact.phoneNumber)
            }
        )
    }

    private fun getAllContacts(): List<ContactInfo> {
        val contacts = mutableListOf<ContactInfo>()

        try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )

            val cursor = contentResolver.query(
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
            LogUtils.e(this, "TrustedContactsActivity", "Error reading contacts", e)
            val msg = getString(R.string.error_reading_contacts_with_message, e.message ?: "")
            MainActivity.showToast(msg, isError = true)
        }

        return contacts.distinctBy {
            "${PhoneUtils.normalizePhoneNumber(it.phoneNumber)}-${it.displayName}"
        }
    }

    private fun showSearchDialog(contacts: List<ContactInfo>, title: String, onContactSelected: (ContactInfo) -> Unit) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.search_dialog, null)

        val searchInput = dialogView.findViewById<EditText>(R.id.search_input)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.search_results_recycler_view)

        recyclerView.layoutManager = LinearLayoutManager(this)
        val adapter = SearchContactAdapter(contacts, onContactSelected)
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

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(dialogView)
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                dialog.dismiss()
            }
            .show()

        searchInput.requestFocus()
        showKeyboardForView(searchInput)
    }

    private fun showKeyboardForView(view: View) {
        view.post {
            val imm = getSystemService(InputMethodManager::class.java)
            imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun onResume() {
        super.onResume()

        LogUtils.d(this, "TRUSTED_CONTACTS", "📱 onResume() - Reload contacts")

        refreshTrustedContactsList()
    }



    private fun formatPhoneNumber(number: String): String {
        return when {
            number.startsWith("+39") && number.length == 13 ->
                "+39 ${number.substring(3, 6)} ${number.substring(6, 9)} ${number.substring(9)}"
            number.length == 10 ->
                "${number.substring(0, 3)} ${number.substring(3, 6)} ${number.substring(6)}"
            else -> number
        }
    }
}