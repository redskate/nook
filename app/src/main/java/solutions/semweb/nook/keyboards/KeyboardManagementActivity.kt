package solutions.semweb.nook.keyboards

import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import solutions.semweb.nook.LogUtils
import solutions.semweb.nook.MainActivity
import solutions.semweb.nook.R

class KeyboardManagementActivity : AppCompatActivity() {

    private lateinit var keyboardSafetyManager: KeyboardSafetyManager
    private lateinit var adapter: KeyboardListAdapter

    // Adapter for the list of keyboards
    private inner class KeyboardListAdapter(
        var keyboards: List<KeyboardItem>,
        private val onItemClick: (KeyboardItem) -> Unit
    ) : RecyclerView.Adapter<KeyboardListAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.keyboard_name)
            val packageId: TextView = view.findViewById(R.id.keyboard_package)
            val status: TextView = view.findViewById(R.id.keyboard_status)
            val actionBtn: TextView = view.findViewById(R.id.keyboard_action_btn)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_keyboard_management, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val keyboard = keyboards[position]

            holder.name.text = keyboard.displayName
            holder.packageId.text = keyboard.packageId

            when (keyboard.status) {
                KeyboardStatus.APPROVED -> {
                    holder.status.text = "✅ ${getString(R.string.approved)}"
                    holder.status.setTextColor(ContextCompat.getColor(this@KeyboardManagementActivity, android.R.color.holo_green_dark))

                    // BOTTONE REMOVE in ROSSO
                    holder.actionBtn.text = getString(R.string.remove)
                    holder.actionBtn.setTextColor(ContextCompat.getColor(this@KeyboardManagementActivity, R.color.red))
                    holder.actionBtn.background = null

                    holder.actionBtn.setOnClickListener {
                        showRemoveConfirmationDialog(keyboard)
                    }
                }
                KeyboardStatus.REJECTED -> {
                    holder.status.text = "🚫 ${getString(R.string.rejected)}"
                    holder.status.setTextColor(ContextCompat.getColor(this@KeyboardManagementActivity, android.R.color.holo_red_dark))

                    holder.actionBtn.text = getString(R.string.approve)
                    holder.actionBtn.setTextColor(ContextCompat.getColor(this@KeyboardManagementActivity, R.color.intensive_green)) // CAMBIATO
                    holder.actionBtn.background = null

                    holder.actionBtn.setOnClickListener {
                        keyboardSafetyManager.addToUserWhitelist(keyboard.packageId, getString(R.string.manually_approved_from_settings))
                        refreshKeyboardList()
                    }
                }
                KeyboardStatus.UNKNOWN -> {
                    holder.status.text = "⚠️ ${getString(R.string.keyboard_unknown)}"
                    holder.status.setTextColor(ContextCompat.getColor(this@KeyboardManagementActivity, android.R.color.holo_orange_dark))

                    // BOTTONE APPROVE in VERDE_INTENSO (MODIFICATO)
                    holder.actionBtn.text = getString(R.string.approve)
                    holder.actionBtn.setTextColor(ContextCompat.getColor(this@KeyboardManagementActivity, R.color.intensive_green)) // CAMBIATO
                    holder.actionBtn.background = null

                    holder.actionBtn.setOnClickListener {
                        keyboardSafetyManager.addToUserWhitelist(keyboard.packageId, getString(R.string.manually_approved_from_settings))
                        refreshKeyboardList()
                    }
                }
            }

            // Click on item for details
            holder.itemView.setOnClickListener {
                showKeyboardDetailsDialog(keyboard)
            }
        }

        override fun getItemCount(): Int = keyboards.size
    }

    data class KeyboardItem(
        val packageId: String,
        val displayName: String,
        val status: KeyboardStatus
    )

    enum class KeyboardStatus {
        APPROVED, REJECTED, UNKNOWN
    }

    override fun onResume() {
        super.onResume()
        // Reload the list when the activity gets again focus
        refreshKeyboardList()
    }

    override fun onPause() {
        super.onPause()
        // NOOP
    }

    override fun onStop() {
        super.onStop()
        // NOOP
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_keyboard_management)

        keyboardSafetyManager = KeyboardSafetyManager(this)

        setupUI()
        refreshKeyboardList()
    }

    private fun setupUI() {
        val recyclerView = findViewById<RecyclerView>(R.id.keyboard_list_recycler)

        // Configure layout manager for the main list
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.setHasFixedSize(false)

        // If the RecyclerView is inside a Scrollview/NestedScrollView
        val parent = recyclerView.parent
        if (parent is NestedScrollView) {
            recyclerView.isNestedScrollingEnabled = false
        }

        adapter = KeyboardListAdapter(emptyList()) { keyboard ->
            showKeyboardDetailsDialog(keyboard)
        }
        recyclerView.adapter = adapter

        // Add separator
        recyclerView.addItemDecoration(
            DividerItemDecoration(this, DividerItemDecoration.VERTICAL).apply {
                ContextCompat.getDrawable(this@KeyboardManagementActivity, android.R.drawable.divider_horizontal_dark)?.let {
                    setDrawable(it)
                }
            }
        )

        findViewById<TextView>(R.id.clear_all_btn)?.setOnClickListener {
            showClearAllDialog()
        }

        findViewById<TextView>(R.id.reset_defaults_btn)?.setOnClickListener {
            showResetDefaultsDialog()
        }

        findViewById<Button>(R.id.back_btn)?.setOnClickListener {
            finish()
        }
    }

    private fun refreshKeyboardList() {
        keyboardSafetyManager.reloadKeyboardLists()

        val keyboards = mutableListOf<KeyboardItem>()

        // Approved keyboards
        keyboardSafetyManager.getUserApprovedKeyboards().forEach { (id, name) ->
            keyboards.add(KeyboardItem(id, name, KeyboardStatus.APPROVED))
        }

        // Rejected keyboards
        keyboardSafetyManager.getUserRejectedKeyboards().forEach { (id, name) ->
            keyboards.add(KeyboardItem(id, name, KeyboardStatus.REJECTED))
        }

        // Unknown keyboards
        val allDetectedKeyboards = getAllInstalledKeyboards()
        allDetectedKeyboards.forEach { (id, name) ->
            if (!keyboards.any { it.packageId == id }) {
                keyboards.add(KeyboardItem(id, name, KeyboardStatus.UNKNOWN))
            }
        }

        // Order per name
        keyboards.sortBy { it.displayName }

        adapter.keyboards = keyboards
        adapter.notifyDataSetChanged()

        // Update counter
        val countText = getString(R.string.keyboards_managed, keyboards.size)
        findViewById<TextView>(R.id.keyboard_count)?.text = countText
    }

    private fun getAllInstalledKeyboards(): List<Pair<String, String>> {
        val keyboards = mutableListOf<Pair<String, String>>()

        try {
            val imeList = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_INPUT_METHODS
            )?.split(":") ?: emptyList()

            imeList.forEach { ime ->
                val packageId = ime.split("/").firstOrNull()
                if (packageId != null) {
                    val name = keyboardSafetyManager.getKeyboardDisplayNameById(packageId)
                    keyboards.add(packageId to name)
                }
            }
        } catch (e: Exception) {
            LogUtils.e(this, "KeyboardManagement", getString(R.string.error_reading_installed_keyboards), e)
        }

        return keyboards.distinctBy { it.first }
    }

    private fun showKeyboardDetailsDialog(keyboard: KeyboardItem) {
        val info = keyboardSafetyManager.getKeyboardInfo(keyboard.packageId)

        AlertDialog.Builder(this)
            .setTitle("📋 " + getString(R.string.keyboard_details))
            .setMessage(info)
            .setPositiveButton(getString(R.string.ok), null)
            .setNegativeButton(getString(R.string.edit)) { dialog, _ ->
                showEditKeyboardDialog(keyboard)
                dialog.dismiss()
            }
            .show()
    }

    private fun showEditKeyboardDialog(keyboard: KeyboardItem) {
        val options = arrayOf(
            "✅ " + getString(R.string.approve),
            "🚫 " + getString(R.string.reject),
            "🗑️ " + getString(R.string.remove_from_lists)
        )

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.edit_keyboard, keyboard.displayName))
            .setItems(options) { dialog, which ->
                when (which) {
                    0 -> {
                        keyboardSafetyManager.addToUserWhitelist(keyboard.packageId, getString(R.string.manually_modified))
                        MainActivity.showToast("✅ " + getString(R.string.keyboard_approved))
                        refreshKeyboardList()
                    }
                    1 -> {
                        keyboardSafetyManager.addToUserBlacklist(keyboard.packageId, getString(R.string.manually_modified))
                        MainActivity.showToast("🚫 " + getString(R.string.keyboard_rejected))
                        refreshKeyboardList()
                    }
                    2 -> {
                        removeKeyboardFromLists(keyboard.packageId)
                        refreshKeyboardList()
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showRemoveConfirmationDialog(keyboard: KeyboardItem) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.remove_keyboard_question))
            .setMessage(getString(R.string.remove_keyboard_message, keyboard.displayName))
            .setPositiveButton(getString(R.string.yes_remove)) { dialog, _ ->
                removeKeyboardFromLists(keyboard.packageId)
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun removeKeyboardFromLists(packageId: String) {

        keyboardSafetyManager.removeKeyboardCompletely(packageId)
        MainActivity.showToast("🗑️ " + getString(R.string.keyboard_removed))

        // Update list asap
        refreshKeyboardList()
    }

    private fun showClearAllDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.clear_all_question))
            .setMessage(getString(R.string.clear_all_message))
            .setPositiveButton(getString(R.string.yes_clear_all)) { dialog, _ ->
                clearAllKeyboardDecisions()
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun clearAllKeyboardDecisions() {

        keyboardSafetyManager.clearAllKeyboardDecisions()

        // Update list
        refreshKeyboardList()

        MainActivity.showToast("🗑️ " + getString(R.string.all_decisions_cleared))
    }

    private fun showResetDefaultsDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.reset_defaults_question))
            .setMessage(getString(R.string.reset_defaults_message))
            .setPositiveButton(getString(R.string.yes_reset)) { dialog, _ ->
                resetToDefaults()
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun resetToDefaults() {
        keyboardSafetyManager.resetToDefaults()

        // Update list
        refreshKeyboardList()

        MainActivity.showToast("✅ " + getString(R.string.default_lists_restored))
    }


}