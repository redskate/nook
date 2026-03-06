package solutions.semweb.nook.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.UnderlineSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import solutions.semweb.nook.BuildConfig
import solutions.semweb.nook.ChatMessage
import solutions.semweb.nook.Constants
import solutions.semweb.nook.LogUtils
import solutions.semweb.nook.R
import solutions.semweb.nook.SharedPreferencesManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

class ChatMessagesAdapter(
    private var messages: List<ChatMessage>,
    private val context: Context,
    private val onMessageLongClick: (ChatMessage) -> Unit,
    private val onLoadMoreClick: () -> Unit // Callback per caricare più messaggi
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_MESSAGE = 0
        private const val TYPE_CONTINUATION = 1
    }

    // Message continuation (getting older messages)
    private var showContinuation = false
    private var isLoadingMore = false
    private var hasLoadedAll = false
    private var remainingMessages = 0

    private var fontSize = 14f

    var onContinuationStateChanged: ((show: Boolean, loading: Boolean) -> Unit)? = null
    private var adapterMsgSeq = 1

    // ==================== VIEW HOLDER CLASSES ====================

    class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        // Existing messages
        val incomingMessageLayout: View = itemView.findViewById(R.id.incoming_message_layout)
        val decodedMessageLayout: View = itemView.findViewById(R.id.decoded_message_layout)
        val plaintextMessageLayout: View = itemView.findViewById(R.id.plaintext_message_layout)
        val outgoingMessageLayout: View = itemView.findViewById(R.id.outgoing_message_layout)
        val plaintextOutgoingLayout: View = itemView.findViewById(R.id.plaintext_outgoing_layout)

        // Existing TextViews
        val plaintextOutgoingMessageText: TextView = itemView.findViewById(R.id.plaintext_outgoing_message_text)
        val incomingMessageText: TextView = itemView.findViewById(R.id.incoming_message_text)
        val decodedMessageText: TextView = itemView.findViewById(R.id.decoded_message_text)
        val plaintextMessageText: TextView = itemView.findViewById(R.id.plaintext_message_text)
        val outgoingMessageText: TextView = itemView.findViewById(R.id.outgoing_message_text)

        // Existing Timestamps
        val plaintextOutgoingTimeTop: TextView = itemView.findViewById(R.id.plaintext_outgoing_time_top)
        val incomingSendTimeTop: TextView = itemView.findViewById(R.id.sending_time_top)
        val incomingTimeTop: TextView = itemView.findViewById(R.id.incoming_time_top)
        val decodedTRansTimeTop: TextView = itemView.findViewById(R.id.decoded_sending_time_top)
        val decodedTimeTop: TextView = itemView.findViewById(R.id.decoded_receiving_time_top)
        val plaintextTransTimeTop: TextView = itemView.findViewById(R.id.plaintext_sending_time_top)
        val plaintextTimeTop: TextView = itemView.findViewById(R.id.plaintext_receiving_time_top)
        val outgoingTimeTop: TextView = itemView.findViewById(R.id.outgoing_time_top)

        // Warning icons
        val plaintextWarningIcon: TextView = itemView.findViewById(R.id.plaintext_warning_icon)
        val plaintextOutgoingWarningIcon: TextView = itemView.findViewById(R.id.plaintext_outgoing_warning_icon)
    }

    class ContinuationViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val progressIndicator: com.google.android.material.progressindicator.CircularProgressIndicator =
            itemView.findViewById(R.id.continuation_progress)
        val textView: TextView = itemView.findViewById(R.id.continuation_text)
    }

    // ==================== ADAPTER METHODS ====================

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_CONTINUATION -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_continuation, parent, false)
                ContinuationViewHolder(view)
            }
            else -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_chat_message, parent, false)
                MessageViewHolder(view)
            }
        }
    }

    override fun getItemViewType(position: Int): Int {
        val prefs = SharedPreferencesManager.getInstance(context)
        val msgSeq = prefs.msgSeq

        if (!showContinuation) return TYPE_MESSAGE

        return when (msgSeq) {
            1 -> {
                if (position == 0) TYPE_CONTINUATION else TYPE_MESSAGE
            }
            else -> { // 0
                val isLastPosition = position == messages.size
                if (isLastPosition) TYPE_CONTINUATION else TYPE_MESSAGE
            }
        }
    }

    /*
     *   Message Visualizer
     */
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is ContinuationViewHolder -> {
                val prefs = SharedPreferencesManager.getInstance(context)
                val msgSeq = prefs.msgSeq

                val isContinuationPosition = when (msgSeq) {
                    1 -> {
                        showContinuation && position == 0
                    }
                    else -> {
                        showContinuation && position == messages.size
                    }
                }

                if (!isContinuationPosition) {
                    holder.progressIndicator.visibility = View.GONE
                    holder.textView.text = ""
                    holder.itemView.isClickable = false
                    LogUtils.w(context, "ChatMessagesAdapter",
                        "⚠️ Position is not a continuation: position=$position, messages=${messages.size}, showContinuation=$showContinuation")
                    return
                }

                if (isLoadingMore) {
                    holder.progressIndicator.visibility = View.VISIBLE
                    holder.itemView.isClickable = false
                    holder.textView.text = context.getString(R.string.loading_messages)
                } else {
                    holder.progressIndicator.visibility = View.GONE

                    if (hasLoadedAll) {
                        holder.textView.text = context.getString(R.string.no_more_messages)
                        holder.itemView.isClickable = false
                    } else {
                        val remainingText = if (remainingMessages > 0) {
                            // Formatta il testo con il conteggio
                            val nextLoad = minOf(Constants.MESSAGES_LIMIT, remainingMessages)
                            val remainingFormatted = when {
                                remainingMessages > 1000 -> "${remainingMessages / 1000}k+"
                                remainingMessages > 100 -> "~${remainingMessages}"
                                else -> "$remainingMessages"
                            }

                            // do not show (out of...) if
                            // end of loading
                            if (nextLoad == remainingMessages)
                            {
                                if (nextLoad==1)
                                    context.getString(
                                        R.string.load_last_message
                                    )
                                else
                                    context.getString(
                                        R.string.load_last_with_count,
                                        nextLoad
                                    )
                            }
                            else
                                context.getString(
                                    R.string.load_more_with_count,
                                    nextLoad,
                                    remainingMessages
                                    // era: remainingFormatted.toInt()
                                )
                        } else {
                            context.getString(R.string.load_more_messages)
                        }

                        holder.textView.text = remainingText
                        holder.itemView.isClickable = true
                    }
                }

                holder.itemView.setOnClickListener {
                    if (!isLoadingMore && !hasLoadedAll) {
                        LogUtils.d(context, "ChatMessagesAdapter", "🎯 Continuation clicked, calling onLoadMoreClick")
                        onLoadMoreClick()

                        isLoadingMore = true
                        holder.progressIndicator.visibility = View.VISIBLE
                        holder.textView.text = context.getString(R.string.loading_messages)
                        holder.itemView.isClickable = false
                    }
                }
            }

            is MessageViewHolder -> {
                val prefs = SharedPreferencesManager.getInstance(context)
                val msgSeq = prefs.msgSeq

                val messageIndex = when (msgSeq) {
                    1 -> {
                        if (showContinuation) position - 1 else position
                    }
                    else -> { // 0
                        position
                    }
                }

                if (messageIndex < 0 || messageIndex >= messages.size) {
                    LogUtils.e(context, "ChatMessagesAdapter",
                        "❌ Index out of range: $messageIndex / ${messages.size}, " +
                                "position: $position, showContinuation: $showContinuation, msgSeq: $msgSeq")

                    holder.incomingMessageLayout.visibility = View.GONE
                    holder.decodedMessageLayout.visibility = View.GONE
                    holder.plaintextMessageLayout.visibility = View.GONE
                    holder.plaintextOutgoingLayout.visibility = View.GONE
                    holder.outgoingMessageLayout.visibility = View.GONE
                    return
                }

                val message = messages[messageIndex]

                holder.incomingMessageLayout.visibility = View.GONE
                holder.decodedMessageLayout.visibility = View.GONE
                holder.plaintextMessageLayout.visibility = View.GONE
                holder.plaintextOutgoingLayout.visibility = View.GONE
                holder.outgoingMessageLayout.visibility = View.GONE

                val formattedTransTime = if (message.trans_timestamp > 0 && message.trans_timestamp != message.timestamp)
                    formatTimestamp(message.trans_timestamp)
                else
                    ""
                val formattedTime = formatTimestamp(message.timestamp)

                when {
                    // INCOMING PLAINTEXT MESSAGE (LEFT)
                    !message.isOutgoing && !message.isDecoded && !message.isYMessage -> {
                        holder.plaintextMessageLayout.visibility = View.VISIBLE
                        holder.plaintextMessageText.text = message.text
                        holder.plaintextMessageText.textSize = fontSize
                        holder.plaintextMessageText.setTextColor(
                            ContextCompat.getColor(context, android.R.color.black)
                        )
                        holder.plaintextTimeTop.text = formattedTime
                        holder.plaintextWarningIcon.visibility = View.VISIBLE
                        holder.plaintextWarningIcon.text = "⚠️"
                        holder.plaintextTransTimeTop.text = formattedTransTime
                        holder.plaintextTransTimeTop.visibility = View.GONE // since no timestamp in a plaintext message ...
                        holder.plaintextMessageText.setOnLongClickListener {
                            onMessageLongClick(message)
                            true
                        }
                    }

                    // OUTGOING PLAINTEXT MESSAG (RIGHT)
                    message.isOutgoing && !message.isDecoded && !message.isYMessage -> {
                        holder.plaintextOutgoingLayout.visibility = View.VISIBLE
                        holder.plaintextOutgoingMessageText.text = message.text
                        holder.plaintextOutgoingMessageText.textSize = fontSize
                        holder.plaintextOutgoingMessageText.setTextColor(
                            ContextCompat.getColor(context, android.R.color.white)
                        )
                        holder.plaintextOutgoingTimeTop.text = formattedTime
                        holder.plaintextOutgoingWarningIcon.visibility = View.VISIBLE
                        holder.plaintextOutgoingWarningIcon.text = "⚠️"
                        holder.plaintextOutgoingMessageText.setOnLongClickListener {
                            onMessageLongClick(message)
                            true
                        }
                    }

                    // INCOMING DECODED MESSAGE - show trasmission time when different from receive time
                    !message.isOutgoing && message.isDecoded -> {
                        holder.decodedMessageLayout.visibility = View.VISIBLE
                        holder.decodedTimeTop.text = formattedTime
                        holder.decodedTRansTimeTop.text = formattedTransTime
                        holder.decodedTRansTimeTop.visibility = if (formattedTransTime.isNotEmpty() && !formattedTransTime.equals(formattedTime)) View.VISIBLE else View.GONE

                        val displayText = if (message.isYMessage) {
                            "\uD83D\uDCE1 ${message.text}"
                        } else {
                            message.text
                        }

                        val spannableText = makeLinksClickable(displayText)
                        holder.decodedMessageText.text = spannableText
                        holder.decodedMessageText.movementMethod = LinkMovementMethod.getInstance()
                        holder.decodedMessageText.setTextColor(ContextCompat.getColor(context, android.R.color.black))
                        holder.decodedMessageText.textSize = fontSize
                        holder.decodedMessageText.setOnLongClickListener {
                            onMessageLongClick(message)
                            true
                        }
                    }

                    // OUTGOING MESSAGE
                    message.isOutgoing -> {
                        holder.outgoingMessageLayout.visibility = View.VISIBLE
                        holder.outgoingTimeTop.text = formattedTime

                        val displayText = message.text

                        val spannableText = makeLinksClickable(displayText)
                        holder.outgoingMessageText.text = spannableText
                        holder.outgoingMessageText.movementMethod = LinkMovementMethod.getInstance()
                        holder.outgoingMessageText.setTextColor(
                            ContextCompat.getColor(context, android.R.color.white)
                        )
                        holder.outgoingMessageText.textSize = fontSize

                        holder.outgoingMessageText.setOnLongClickListener {
                            onMessageLongClick(message)
                            true
                        }
                    }

                    // Fallback NON DECODED INCOMING MESSAGE
                    else -> {
                        holder.incomingMessageLayout.visibility = View.VISIBLE
                        holder.incomingTimeTop.text = formattedTime
                        holder.incomingSendTimeTop.text = formattedTransTime
                        holder.incomingSendTimeTop.visibility = if (formattedTransTime.isNotEmpty()) View.VISIBLE else View.GONE

                        holder.incomingMessageText.text = message.text
                        holder.incomingMessageText.textSize = fontSize
                        holder.incomingMessageText.setTextColor(
                            ContextCompat.getColor(context, android.R.color.black)
                        )
                        holder.incomingMessageText.setOnLongClickListener {
                            onMessageLongClick(message)
                            true
                        }
                    }
                }

                // Remove previous listeners
                holder.itemView.setOnLongClickListener(null)
            }
        }
    }


    fun setLoadingState(loading: Boolean) {
        isLoadingMore = loading
        notifyItemChanged(0) // Notifica l'elemento di continuazione
    }

    override fun getItemCount(): Int {
        return messages.size + (if (showContinuation) 1 else 0)
    }

    // ==================== PUBLIC INTERFACE ====================

    fun addMoreMessages(newMessages: List<ChatMessage>, msgSeq: Int) {
        if (newMessages.isEmpty()) {
            LogUtils.d(context, "ChatMessagesAdapter", "⚠️ No new messge to add")
            return
        }

        LogUtils.d(context, "ChatMessagesAdapter",
            "➕ addMoreMessages called with ${newMessages.size} messages, " +
                    "${newMessages.size}, MSG_SEQ: $msgSeq")

        val sortedNewMessages = newMessages.sortedBy { it.trans_timestamp }

        if (BuildConfig.DEBUG) {
            sortedNewMessages.forEachIndexed { index, msg ->
                LogUtils.d(context, "ChatMessagesAdapter",
                    "  Nuovo[$index]: ${Date(msg.timestamp)} - '${msg.text.take(20)}...'")
            }
        }

        val updatedList = messages.toMutableList()

        when (msgSeq) {
            1 -> {
                updatedList.addAll(0, sortedNewMessages)
                messages = updatedList.sortedBy { it.timestamp }
            }
            else -> { // 0
                updatedList.addAll(sortedNewMessages)
                messages = updatedList.sortedByDescending { it.timestamp }
            }
        }

        val insertionPosition = when (msgSeq) {
            1 -> {
                if (showContinuation) 1 else 0
            }
            else -> { // 0
                val basePosition = messages.size - sortedNewMessages.size
                if (showContinuation) basePosition else basePosition
            }
        }

        notifyItemRangeInserted(insertionPosition, sortedNewMessages.size)

        if (remainingMessages > 0) {
            remainingMessages = maxOf(0, remainingMessages - sortedNewMessages.size)
        }

        LogUtils.d(context, "ChatMessagesAdapter",
            "✅ ${sortedNewMessages.size} messages added in position $insertionPosition, " +
                    "total: ${messages.size}, remaining: $remainingMessages")
    }


    fun updateMessages(newMessages: List<ChatMessage>) {
        LogUtils.d(context, "ChatMessagesAdapter", "📥 updateMessages() called with ${newMessages.size} messages")

        messages = emptyList()

        val prefs = SharedPreferencesManager.getInstance(context)
        val msgSeq = prefs.msgSeq

        addMoreMessages(newMessages, msgSeq)

        notifyDataSetChanged()

        LogUtils.d(context, "ChatMessagesAdapter",
            "🔄 List updated, total: ${messages.size} messages, MSG_SEQ: $msgSeq")
    }

    fun addMessages(newMessages: List<ChatMessage>) {
        val prefs = SharedPreferencesManager.getInstance(context)
        addMoreMessages(newMessages, prefs.msgSeq)
    }

    fun setMsgSeq(msgSeq: Int) {
        if (adapterMsgSeq != msgSeq) {
            adapterMsgSeq = msgSeq
            LogUtils.d(context, "ChatMessagesAdapter", "🔄 MSG_SEQ Adapter updated: $msgSeq")
            notifyDataSetChanged()
        }
    }

    fun setShowContinuation(
        show: Boolean,
        loading: Boolean = false,
        allLoaded: Boolean = false,
        remainingCount: Int = 0
    ) {
        val changed = showContinuation != show ||
                isLoadingMore != loading ||
                hasLoadedAll != allLoaded ||
                remainingMessages != remainingCount

        if (!changed) return

        showContinuation = show
        isLoadingMore = loading
        hasLoadedAll = allLoaded
        remainingMessages = remainingCount

        LogUtils.d(context, "ChatMessagesAdapter",
            "🔄 Continuation state: show=$show, loading=$loading, " +
                    "allLoaded=$allLoaded, remaining=$remainingMessages")

        onContinuationStateChanged?.invoke(show, loading)

        notifyDataSetChanged()
    }

    fun setFontSize(newSize: Float) {
        fontSize = newSize
        notifyDataSetChanged()
    }

    // ==================== PRIVATE METHODS ====================

    private fun makeLinksClickable(text: String): SpannableString {
        val spannableString = SpannableString(text)
        val urlPattern = Pattern.compile(
            "(https?://(?:www\\.|(?!www))[a-zA-Z0-9][a-zA-Z0-9-]+[a-zA-Z0-9]\\.[^\\s]{2,}|www\\.[a-zA-Z0-9][a-zA-Z0-9-]+[a-zA-Z0-9]\\.[^\\s]{2,}|https?://(?:www\\.|(?!www))[a-zA-Z0-9]+\\.[^\\s]{2,}|www\\.[a-zA-Z0-9]+\\.[^\\s]{2,})",
            Pattern.CASE_INSENSITIVE
        )

        val matcher = urlPattern.matcher(text)
        while (matcher.find()) {
            val url = matcher.group()
            val start = matcher.start()
            val end = matcher.end()

            val clickableSpan = object : ClickableSpan() {
                override fun onClick(widget: View) {
                    val intent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse(if (url.startsWith("http")) url else "http://$url")
                    )
                    context.startActivity(intent)
                }
            }

            spannableString.setSpan(clickableSpan, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannableString.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        return spannableString
    }

    private fun formatTimestamp(timestamp: Long): String {
        val messageDate = Date(timestamp)
        val today = Calendar.getInstance()
        val messageCalendar = Calendar.getInstance().apply { time = messageDate }

        val isToday = today.get(Calendar.YEAR) == messageCalendar.get(Calendar.YEAR) &&
                today.get(Calendar.DAY_OF_YEAR) == messageCalendar.get(Calendar.DAY_OF_YEAR)

        return if (isToday) {
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(messageDate)
        } else {
            SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(messageDate)
        }
    }
}