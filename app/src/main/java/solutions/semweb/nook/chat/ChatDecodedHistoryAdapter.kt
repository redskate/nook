package solutions.semweb.nook.chat

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import solutions.semweb.nook.DecodedMessage
import solutions.semweb.nook.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatDecodedHistoryAdapter(
    private var messages: List<DecodedMessage>,
    private val onItemClick: (DecodedMessage) -> Unit
) : RecyclerView.Adapter<ChatDecodedHistoryAdapter.ViewHolder>() {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val senderText: TextView = itemView.findViewById(R.id.sender_text)
        val timeText: TextView = itemView.findViewById(R.id.time_text)
        val previewText: TextView = itemView.findViewById(R.id.preview_text)
        val statusIcon: TextView = itemView.findViewById(R.id.status_icon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_decoded_message, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val message = messages[position]
        val dateFormat = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())

        holder.senderText.text = message.senderName ?: message.sender
        holder.timeText.text = dateFormat.format(Date(message.timestamp))
        val preview = if (message.text.length > 50) {
            message.text.substring(0, 50) + "..."
        } else {
            message.text
        }
        holder.previewText.text = preview

        holder.statusIcon.text = if (message.isDecoded) "✅" else "❌"
        holder.statusIcon.setTextColor(
            if (message.isDecoded)
                Color.parseColor("@color/middle_green")
            else
                Color.parseColor("@color/red")
        )

        val typeInfo = when (message.messageType) {
            "sms" -> "📱 SMS"
            "encrypted" -> "🔐 Crittato"
            "plaintext" -> "⚠️ Testo chiaro"
            else -> message.messageType
        }

        // Click on whole l'item
        holder.itemView.setOnClickListener {
            onItemClick(message)
        }
    }

    override fun getItemCount(): Int = messages.size

    fun updateData(newMessages: List<DecodedMessage>) {
        messages = newMessages
        notifyDataSetChanged()
    }
}