package solutions.semweb.nook.keyboards

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import solutions.semweb.nook.R

class KeyboardWhitelistAdapter(
    private val keyboards: List<String>,
    private val onRemoveClick: (String) -> Unit
) : RecyclerView.Adapter<KeyboardWhitelistAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val keyboardIdText: TextView = view.findViewById(R.id.keyboard_id_text)
        val removeButton: ImageButton = view.findViewById(R.id.remove_button)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_keyboard_whitelist, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val keyboardId = keyboards[position]

        // Show only the ID, trunked if too long
        val displayText = if (keyboardId.length > 30) {
            "${keyboardId.take(27)}..."
        } else {
            keyboardId
        }

        holder.keyboardIdText.text = displayText
        holder.removeButton.setOnClickListener {
            onRemoveClick(keyboardId)
        }
    }

    override fun getItemCount() = keyboards.size
}