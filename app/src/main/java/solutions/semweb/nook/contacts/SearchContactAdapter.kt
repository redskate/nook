package solutions.semweb.nook.contacts

import android.content.res.Configuration
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import solutions.semweb.nook.ContactInfo
import solutions.semweb.nook.R

class SearchContactAdapter(
    private var contacts: List<ContactInfo>,
    private val onContactClick: (ContactInfo) -> Unit
) : RecyclerView.Adapter<SearchContactAdapter.ViewHolder>() {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val avatarText: TextView = itemView.findViewById(R.id.avatar_text)
        val contactNameText: TextView = itemView.findViewById(R.id.contact_name_text)
        val phoneNumberText: TextView = itemView.findViewById(R.id.phone_number_text)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_search_contact, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val contact = contacts[position]

        val displayName = contact.displayName ?: contact.phoneNumber
        val avatarChar = displayName.firstOrNull()?.uppercaseChar() ?: '?'
        holder.avatarText.text = avatarChar.toString()

        holder.contactNameText.text = displayName
        holder.phoneNumberText.text = formatPhoneNumber(contact.phoneNumber)
        val currentNightMode = holder.itemView.context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK

        when (currentNightMode) {
            Configuration.UI_MODE_NIGHT_YES -> {
                holder.contactNameText.setTextColor(ContextCompat.getColor(holder.itemView.context, android.R.color.darker_gray))
                holder.phoneNumberText.setTextColor(ContextCompat.getColor(holder.itemView.context, android.R.color.darker_gray))
            }
            Configuration.UI_MODE_NIGHT_NO -> {
                holder.contactNameText.setTextColor(ContextCompat.getColor(holder.itemView.context, android.R.color.black))
                holder.phoneNumberText.setTextColor(ContextCompat.getColor(holder.itemView.context, android.R.color.black))
            }
        }

        // Click listener
        holder.itemView.setOnClickListener {
            onContactClick(contact)
        }
    }

    override fun getItemCount(): Int = contacts.size

    fun updateContacts(newContacts: List<ContactInfo>) {
        contacts = newContacts
        notifyDataSetChanged()
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