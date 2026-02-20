package solutions.semweb.nook.contacts

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import solutions.semweb.nook.R
import solutions.semweb.nook.TrustedContact

class TrustedContactsAdapter(
    private var contacts: List<TrustedContact>,
    private val onRemoveClick: (String) -> Unit,
    private val onActiveChange: (String, Boolean) -> Unit
) : RecyclerView.Adapter<TrustedContactsAdapter.ViewHolder>() {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val nameText: TextView = itemView.findViewById(R.id.contact_name_text)
        val phoneText: TextView = itemView.findViewById(R.id.contact_phone_text)
        val removeBtn: MaterialButton = itemView.findViewById(R.id.remove_btn)
        val activeSwitch: Switch = itemView.findViewById(R.id.active_switch)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_trusted_contact, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val trustedContact = contacts[position]

        // Formatta e mostra il numero
        holder.nameText.text = trustedContact.displayName
        holder.phoneText.text = formatPhoneNumber(trustedContact.phoneNumber)

        // Imposta colore del testo in base allo stato
        if (trustedContact.isActive) {
            holder.nameText.setTextColor(holder.itemView.context.getColor(R.color.middle_green))
            holder.phoneText.setTextColor(holder.itemView.context.getColor(R.color.middle_green))
        } else {
            holder.nameText.setTextColor(holder.itemView.context.getColor(R.color.light_orange))
            holder.phoneText.setTextColor(holder.itemView.context.getColor(R.color.light_orange))
        }

        // IMPORTANTE: Rimuovi listener precedenti per evitare loop infiniti
        holder.activeSwitch.setOnCheckedChangeListener(null)

        // Imposta lo stato dello switch
        holder.activeSwitch.isChecked = trustedContact.isActive

        // Aggiungi listener per il cambio stato
        holder.activeSwitch.setOnCheckedChangeListener { _, isChecked ->
            // Evita chiamate multiple
            if (isChecked != trustedContact.isActive) {
                onActiveChange(trustedContact.contactId, isChecked)

                // Aggiorna immediatamente il colore del testo
                if (isChecked) {
                    holder.nameText.setTextColor(holder.itemView.context.getColor(R.color.middle_green))
                    holder.phoneText.setTextColor(holder.itemView.context.getColor(R.color.middle_green))
                } else {
                    holder.nameText.setTextColor(holder.itemView.context.getColor(R.color.light_orange))
                    holder.phoneText.setTextColor(holder.itemView.context.getColor(R.color.light_orange))
                }
            }
        }

        // Listener per il pulsante rimuovi
        holder.removeBtn.setOnClickListener {
            onRemoveClick(trustedContact.contactId)
        }

        // Per evitare problemi di riciclo, imposta un tag
        holder.activeSwitch.tag = trustedContact.contactId
    }

    override fun getItemCount(): Int = contacts.size

    fun updateContacts(newContacts: List<TrustedContact>) {
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