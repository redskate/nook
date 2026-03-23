package solutions.semweb.nook.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import solutions.semweb.nook.ChatConversation
import solutions.semweb.nook.R
import solutions.semweb.nook.crypto.EncryptionMapper
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ChatConversationAdapter(
    private var conversations: List<ChatConversation>,
    private val onItemClick: (ChatConversation) -> Unit,
    private val onItemLongClick: (ChatConversation, View) -> Unit
) : RecyclerView.Adapter<ChatConversationAdapter.ViewHolder>() {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val avatarText: TextView = itemView.findViewById(R.id.avatar_text)
        val contactNameText: TextView = itemView.findViewById(R.id.contact_name_text)
        val timeText: TextView = itemView.findViewById(R.id.time_text)
        val lastMessageText: TextView = itemView.findViewById(R.id.last_message_text)
        val unreadBadge: TextView = itemView.findViewById(R.id.unread_badge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_conversation, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val conversation = conversations[position]
        val date = Date(conversation.lastTimestamp)

        val formattedDate = formatDate(date)

        val displayName = conversation.contactName ?: conversation.phoneNumber
        val avatarChar = displayName.firstOrNull()
        holder.avatarText.text = avatarChar?.toString() ?: "?"

        // Set avatar color based on encryption scheme and password
        val context = holder.itemView.context
        val avatarColor = when {
            // Case 1: Default encryption parameters detected (encryptionScheme == "Text" OR empty, encoding == "Base256", no password)
            (conversation.encryptionScheme.isNullOrEmpty() || conversation.encryptionScheme == EncryptionMapper.ENCRYPTION_SCHEME_TEXT) &&
                    (conversation.encoding == EncryptionMapper.ENCODING_BASE256 || conversation.encoding.isNullOrEmpty()) &&
                    conversation.encodingPassword.isNullOrEmpty() ->
                R.color.default_encryption_outgoing

            // Case 2: PlainText configuration (encryptionScheme == "Text", encoding == "Text", no password)
            (conversation.encryptionScheme == EncryptionMapper.ENCRYPTION_SCHEME_TEXT) &&
                    (conversation.encoding == EncryptionMapper.ENCRYPTION_SCHEME_TEXT) &&
                    conversation.encodingPassword.isNullOrEmpty() ->
                R.color.plaintext_incoming

            // Case 3: All other configurations
            else -> R.color.middle_green
        }
        holder.avatarText.background.setTint(context.getColor(avatarColor))

        val chatManager = ChatManager(holder.itemView.context)
        val encryptionAbbr = chatManager.getEncryptionSchemeForChat(conversation.phoneNumber)
        val encoding = conversation.encoding
        val encodingPassword = conversation.encodingPassword
        val hasEncodingPassword = encodingPassword.isNotEmpty()
        val scheme = conversation.encryptionScheme
        val short_encoding = if (encoding == EncryptionMapper.ENCRYPTION_SCHEME_TEXT) "" else "${EncryptionMapper.extractShortForEncoding(encoding)}"
        val encryptionIndicator = encIndicatorWithText(
            encryptionAbbr,
            scheme,
            short_encoding,
            hasEncodingPassword,
            "",
            encoding
        )

        val nameText = {
                "${conversation.contactName ?: conversation.phoneNumber} $encryptionIndicator"
        }

        holder.contactNameText.text = nameText()
        holder.timeText.text = formattedDate

        // Chat's last message indication
        val lastMsg = if (conversation.lastMessage.length > 50) {
            conversation.lastMessage.substring(0, 50) + "..."
        } else {
            conversation.lastMessage
        }

        // Substitute URL with "[link]" in preview
        val previewMsg = lastMsg.replace(Regex("https?://\\S+"), "[link]")
        holder.lastMessageText.text = previewMsg

        // Non read Badge
        if (conversation.unreadCount > 0) {
            holder.unreadBadge.visibility = View.VISIBLE
            holder.unreadBadge.text = if (conversation.unreadCount > 9) "9+" else conversation.unreadCount.toString()
            holder.unreadBadge.background.setTint(
                holder.itemView.context.getColor(R.color.pink)
            )
        } else {
            holder.unreadBadge.visibility = View.GONE
        }

        // NORMAL CLICK
        holder.itemView.setOnClickListener {
            onItemClick(conversation)
        }

        // LONG CLICK
        holder.itemView.setOnLongClickListener {
            onItemLongClick(conversation, holder.itemView)
            true
        }
    }

    override fun getItemCount(): Int = conversations.size

    fun updateConversations(newConversations: List<ChatConversation>) {
        conversations = newConversations
        notifyDataSetChanged()
    }


    private fun formatDate(date: Date): String {
        val calendar = Calendar.getInstance()
        val today = calendar.time

        calendar.time = date
        val messageDate = calendar.time

        val dateFormatToday = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
        val todayStr = dateFormatToday.format(today)
        val messageStr = dateFormatToday.format(messageDate)

        return when {
            todayStr == messageStr -> {
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
            }
            isYesterday(date) -> {
                "Ieri " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
            }
            isThisYear(date) -> {
                SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(date)
            }
            else -> {
                SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault()).format(date)
            }
        }
    }

    private fun isYesterday(date: Date): Boolean {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, -1)
        val yesterday = calendar.time

        val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
        return dateFormat.format(yesterday) == dateFormat.format(date)
    }

    private fun isThisYear(date: Date): Boolean {
        val calendar = Calendar.getInstance()
        val currentYear = calendar.get(Calendar.YEAR)

        calendar.time = date
        val messageYear = calendar.get(Calendar.YEAR)

        return messageYear == currentYear
    }
}