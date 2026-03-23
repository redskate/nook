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
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import solutions.semweb.nook.ChatMessage
import solutions.semweb.nook.Constants
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
    private val onLoadMoreClick: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_MESSAGE = 0
        private const val TYPE_CONTINUATION = 1
    }

    private var showContinuation = false
    private var isLoadingMore = false
    private var hasLoadedAll = false
    private var remainingMessages = 0
    private var fontSize = 14f
    private var isDefaultEncryptionMode = false
    var onContinuationStateChanged: ((show: Boolean, loading: Boolean) -> Unit)? = null
    private var adapterMsgSeq = 1
    var useDefaultEncryption = isDefaultEncryptionMode


    class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val incomingMessageLayout: View = itemView.findViewById(R.id.enc_incoming_message_layout)
        val plaintextIncomingMessageLayout: View = itemView.findViewById(R.id.plaintext_incoming_message_layout)
        val encOutgoingMessageLayout: View = itemView.findViewById(R.id.enc_outgoing_message_layout)
        val plaintextOutgoingLayout: View = itemView.findViewById(R.id.plaintext_outgoing_layout)
        val defaultEncryptionIncomingMessageLayout: View = itemView.findViewById(R.id.default_encryption_incoming_message_layout)
        val defaultEncryptionOutgoingLayout: View = itemView.findViewById(R.id.default_encryption_outgoing_layout)
        val defaultEncryptionOutgoingReceiptStatusLayout: LinearLayout = itemView.findViewById(R.id.default_encryption_outgoing_receipt_status_layout)
        val encOutgoingReceiptStatusLayout: LinearLayout = itemView.findViewById(R.id.enc_outgoing_receipt_status_layout)
        val encOutgoingReceiptTimestamp: TextView = itemView.findViewById(R.id.enc_outgoing_receipt_timestamp)
        val encOutgoingReceiptIconMsgArrived: android.widget.ImageView = itemView.findViewById(R.id.enc_outgoing_receipt_icon_msg_arrived)
        val encOutgoingReceiptIconStatus: android.widget.ImageView = itemView.findViewById(R.id.enc_outgoing_receipt_icon_status)
        val defaultEncryptionOutgoingReceiptIconArrived: android.widget.ImageView = itemView.findViewById(R.id.default_encryption_outgoing_receipt_icon_arrived)
        val defaultEncryptionOutgoingReceiptIconStatus: android.widget.ImageView = itemView.findViewById(R.id.default_encryption_outgoing_receipt_icon_status)
        val defaultEncryptionOutgoingReceiptTimestamp: TextView = itemView.findViewById(R.id.default_encryption_outgoing_receipt_timestamp)
        val plaintextOutgoingMessageText: TextView = itemView.findViewById(R.id.plaintext_outgoing_message_text)
        val incomingMessageText: TextView = itemView.findViewById(R.id.incoming_message_text)
        val plaintextIncomingMessageText: TextView = itemView.findViewById(R.id.plaintext_incoming_message_text)
        val encOutgoingMessageText: TextView = itemView.findViewById(R.id.enc_outgoing_message_text)
        val defaultEncryptionMessageText: TextView = itemView.findViewById(R.id.default_encryption_message_text)
        val defaultEncryptionOutgoingText: TextView = itemView.findViewById(R.id.default_encryption_outgoing_text)
        val plaintextOutgoingTimeTop: TextView = itemView.findViewById(R.id.plaintext_outgoing_time_top)
        val encIncomingSendingTimeTop: TextView = itemView.findViewById(R.id.enc_incoming_sending_time_top)
        val encIncomingTimeTop: TextView = itemView.findViewById(R.id.enc_incoming_time_top)
        val plaintextIncomingSendingTimeTop: TextView = itemView.findViewById(R.id.plaintext_incoming_sending_time_top)
        val plaintextIncomingReceivingTimeTop: TextView = itemView.findViewById(R.id.plaintext_incoming_receiving_time_top)
        val encOutgoingTimeTop: TextView = itemView.findViewById(R.id.enc_outgoing_time_top)
        val defaultEncryptionIncomingSendingTimeTop: TextView = itemView.findViewById(R.id.default_encryption_incoming_sending_time_top)
        val defaultEncryptionIncomingReceivingTimeTop: TextView = itemView.findViewById(R.id.default_encryption_incoming_receiving_time_top)
        val defaultEncryptionOutgoingTimeTop: TextView = itemView.findViewById(R.id.default_encryption_outgoing_time_top)
        val encIncomingEncryptionIndicator: TextView = itemView.findViewById(R.id.enc_incoming_encryption_indicator)
        val encOutgoingEncryptionIndicator: TextView = itemView.findViewById(R.id.enc_outgoing_encryption_indicator)
        val defaultEncryptionIncomingIndicator: TextView = itemView.findViewById(R.id.default_encryption_incoming_indicator)
        val defaultEncryptionOutgoingIndicator: TextView = itemView.findViewById(R.id.default_encryption_outgoing_indicator)
        val plaintextIncomingWarningIcon: TextView = itemView.findViewById(R.id.plaintext_incoming_warning_icon)
        val plaintextOutgoingWarningIcon: TextView = itemView.findViewById(R.id.plaintext_outgoing_warning_icon)
    }

    class ContinuationViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val progressIndicator: com.google.android.material.progressindicator.CircularProgressIndicator =
            itemView.findViewById(R.id.continuation_progress)
        val textView: TextView = itemView.findViewById(R.id.continuation_text)
    }

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
            else -> {
                val isLastPosition = position == messages.size
                if (isLastPosition) TYPE_CONTINUATION else TYPE_MESSAGE
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is ContinuationViewHolder -> {
                bindContinuationViewHolder(holder, position)
            }
            is MessageViewHolder -> {
                bindMessageViewHolder(holder, position)
            }
        }
    }

    private fun bindContinuationViewHolder(holder: ContinuationViewHolder, position: Int) {
        val prefs = SharedPreferencesManager.getInstance(context)
        val msgSeq = prefs.msgSeq

        val isContinuationPosition = when (msgSeq) {
            1 -> showContinuation && position == 0
            else -> showContinuation && position == messages.size
        }

        if (!isContinuationPosition) {
            holder.progressIndicator.visibility = View.GONE
            holder.textView.text = ""
            holder.itemView.isClickable = false
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
                    val nextLoad = minOf(Constants.MESSAGES_LIMIT, remainingMessages)
                    val remainingFormatted = when {
                        remainingMessages > 1000 -> "${remainingMessages / 1000}k+"
                        remainingMessages > 100 -> "~${remainingMessages}"
                        else -> "$remainingMessages"
                    }

                    if (nextLoad == remainingMessages) {
                        if (nextLoad == 1)
                            context.getString(R.string.load_last_message)
                        else
                            context.getString(R.string.load_last_with_count, nextLoad)
                    } else {
                        context.getString(R.string.load_more_with_count, nextLoad, remainingMessages)
                    }
                } else {
                    context.getString(R.string.load_more_messages)
                }

                holder.textView.text = remainingText
                holder.itemView.isClickable = true
            }
        }

        holder.itemView.setOnClickListener {
            if (!isLoadingMore && !hasLoadedAll) {
                onLoadMoreClick()
                isLoadingMore = true
                holder.progressIndicator.visibility = View.VISIBLE
                holder.textView.text = context.getString(R.string.loading_messages)
                holder.itemView.isClickable = false
            }
        }
    }

    private fun bindMessageViewHolder(holder: MessageViewHolder, position: Int) {
        val prefs = SharedPreferencesManager.getInstance(context)
        val msgSeq = prefs.msgSeq

        val messageIndex = when (msgSeq) {
            1 -> if (showContinuation) position - 1 else position
            else -> position
        }

        if (messageIndex < 0 || messageIndex >= messages.size) {
            hideAllMessageLayouts(holder)
            return
        }

        val message = messages[messageIndex]

        // Encryption indicator from metadata
        val encryptionIndicator = message.metadata?.get("e_ind") ?: ""

        //recalculate from metadata
        useDefaultEncryption = encryptionIndicator.equals("@b3")
                || encryptionIndicator.equals("@b2")
                || encryptionIndicator.equals("@b1")

        val formattedTransTime = if (message.trans_timestamp > 0 && message.trans_timestamp != message.timestamp)
            formatTimestamp(message.trans_timestamp)
        else formatTimestamp(message.timestamp)

        //Sometime we have the one but not the other
        val formattedTime =  formatTimestamp(message.timestamp)

        val timestampNeeded = formattedTime.isNotBlank() && formattedTransTime.isNotBlank() &&
                !formattedTime.equals(formattedTransTime)

        hideAllMessageLayouts(holder)

        when {
            // INCOMING PLAINTEXT MESSAGE (LEFT)
            !message.isOutgoing && encryptionIndicator.isBlank() -> {
                holder.plaintextIncomingMessageLayout.visibility = View.VISIBLE
                holder.plaintextIncomingMessageText.text = makeLinksClickable(message.text)
                holder.plaintextIncomingMessageText.movementMethod = LinkMovementMethod.getInstance()
                holder.plaintextIncomingMessageText.textSize = fontSize
                holder.plaintextIncomingMessageText.setTextColor(
                    ContextCompat.getColor(context, android.R.color.black)
                )

                // Show timestamps
                holder.plaintextIncomingSendingTimeTop.visibility = if (formattedTransTime.isNotEmpty()) View.VISIBLE else View.GONE
                holder.plaintextIncomingSendingTimeTop.text = formattedTransTime

                if (timestampNeeded) {
                    holder.plaintextIncomingReceivingTimeTop.text = formattedTime
                    holder.plaintextIncomingReceivingTimeTop.visibility = View.VISIBLE
                } else {
                    holder.plaintextIncomingReceivingTimeTop.visibility = View.GONE
                }

                // Show warning icon for plaintext
                holder.plaintextIncomingWarningIcon.text = "⚠️"
                holder.plaintextIncomingWarningIcon.visibility = View.VISIBLE

                holder.plaintextIncomingMessageText.setOnLongClickListener {
                    onMessageLongClick(message)
                    true
                }
            }

            // OUTGOING PLAINTEXT MESSAGE (RIGHT)
            message.isOutgoing && encryptionIndicator.isBlank() -> {
                holder.plaintextOutgoingLayout.visibility = View.VISIBLE
                holder.plaintextOutgoingMessageText.text = makeLinksClickable(message.text)
                holder.plaintextOutgoingMessageText.movementMethod = LinkMovementMethod.getInstance()
                holder.plaintextOutgoingMessageText.textSize = fontSize
                holder.plaintextOutgoingMessageText.setTextColor(
                    ContextCompat.getColor(context, android.R.color.white)
                )

                // Set timestamp
                holder.plaintextOutgoingTimeTop.text = formattedTime
                holder.plaintextOutgoingTimeTop.visibility = if (formattedTime.isNotEmpty()) View.VISIBLE else View.GONE

                // Show warning icon
                holder.plaintextOutgoingWarningIcon.visibility = View.VISIBLE
                holder.plaintextOutgoingWarningIcon.text = "⚠️"

                holder.plaintextOutgoingMessageText.setOnLongClickListener {
                    onMessageLongClick(message)
                    true
                }
            }

            // INCOMING DECODED MESSAGE (Encrypted & Decoded)
            !message.isOutgoing && message.isDecoded -> {
                if (useDefaultEncryption) {
                    // DEFAULT ENCRYPTION MODE - Incoming Decoded
                    holder.defaultEncryptionIncomingMessageLayout.visibility = View.VISIBLE

                    val spannableText = makeLinksClickable(message.text)
                    holder.defaultEncryptionMessageText.text = spannableText
                    holder.defaultEncryptionMessageText.movementMethod = LinkMovementMethod.getInstance()
                    holder.defaultEncryptionMessageText.textSize = fontSize
                    // Make sure bubble color comes from drawable, not set here
                    // holder.defaultEncryptionMessageText.setTextColor(...) // DON'T SET COLOR HERE

                    // Set timestamps
                    holder.defaultEncryptionIncomingSendingTimeTop.visibility = if (formattedTransTime.isNotEmpty()) View.VISIBLE else View.GONE
                    holder.defaultEncryptionIncomingSendingTimeTop.text = formattedTransTime

                    if (timestampNeeded) {
                        holder.defaultEncryptionIncomingReceivingTimeTop.visibility = View.VISIBLE
                        holder.defaultEncryptionIncomingReceivingTimeTop.text = formattedTime
                    } else {
                        holder.defaultEncryptionIncomingReceivingTimeTop.visibility = View.GONE
                    }

                    // Encryption indicator from metadata
                    val encryptionIndicator = message.metadata?.get("e_ind") ?: ""
                    holder.defaultEncryptionIncomingIndicator.text = encryptionIndicator
                    holder.defaultEncryptionIncomingIndicator.visibility = if (encryptionIndicator.isNotEmpty()) View.VISIBLE else View.GONE

                    holder.defaultEncryptionMessageText.setOnLongClickListener {
                        onMessageLongClick(message)
                        true
                    }
                } else {
                    // REGULAR MODE - Incoming Decoded
                    holder.incomingMessageLayout.visibility = View.VISIBLE

                    val spannableText = makeLinksClickable(message.text)
                    holder.incomingMessageText.text = spannableText
                    holder.incomingMessageText.movementMethod = LinkMovementMethod.getInstance()
                    holder.incomingMessageText.textSize = fontSize
                    holder.incomingMessageText.setTextColor(
                        ContextCompat.getColor(context, android.R.color.black)
                    )

                    // Set timestamps
                    holder.encIncomingTimeTop.text = formattedTime
                    if (timestampNeeded) {
                        holder.encIncomingTimeTop.visibility = View.VISIBLE
                    }
                    else {
                        holder.encIncomingTimeTop.visibility = View.GONE
                    }

                    holder.encIncomingSendingTimeTop.text = formattedTransTime
                    holder.encIncomingSendingTimeTop.visibility = if (formattedTransTime.isNotEmpty()) View.VISIBLE else View.GONE

                    // Encryption indicator
                    val encryptionIndicator = message.metadata?.get("e_ind") ?: ""
                    holder.encIncomingEncryptionIndicator.text = encryptionIndicator
                    holder.encIncomingEncryptionIndicator.visibility = if (encryptionIndicator.isNotEmpty()) View.VISIBLE else View.GONE

                    holder.incomingMessageText.setOnLongClickListener {
                        onMessageLongClick(message)
                        true
                    }
                }
            }

            // OUTGOING MESSAGE (Encrypted/Decoded)
            message.isOutgoing -> {
                if (useDefaultEncryption) {
                    // DEFAULT ENCRYPTION MODE - Outgoing
                    holder.defaultEncryptionOutgoingLayout.visibility = View.VISIBLE

                    val spannableText = makeLinksClickable(message.text)
                    holder.defaultEncryptionOutgoingText.text = spannableText
                    holder.defaultEncryptionOutgoingText.movementMethod = LinkMovementMethod.getInstance()
                    holder.defaultEncryptionOutgoingText.textSize = fontSize
                    // DON'T set text color here - let drawable handle it

                    // Set timestamp

                    holder.defaultEncryptionOutgoingTimeTop.text = formattedTime
                    holder.defaultEncryptionOutgoingTimeTop.visibility = if (formattedTime.isNotEmpty()) View.VISIBLE else View.GONE

                    // Encryption indicator
                    val encryptionIndicator = message.metadata?.get("e_ind") ?: ""
                    holder.defaultEncryptionOutgoingIndicator.text = encryptionIndicator
                    holder.defaultEncryptionOutgoingIndicator.visibility = if (encryptionIndicator.isNotEmpty()) View.VISIBLE else View.GONE

                    holder.defaultEncryptionOutgoingText.setOnLongClickListener {
                        onMessageLongClick(message)
                        true
                    }

                    // Setup receipt status for default encryption
                    setupReceiptStatus(
                        message.metadata,
                        holder.defaultEncryptionOutgoingReceiptStatusLayout,
                        holder.defaultEncryptionOutgoingReceiptIconArrived,
                        holder.defaultEncryptionOutgoingReceiptIconStatus,
                        holder.defaultEncryptionOutgoingReceiptTimestamp
                    )
                } else {
                    // REGULAR MODE DECODED - Outgoing
                    holder.encOutgoingMessageLayout.visibility = View.VISIBLE

                    val spannableText = makeLinksClickable(message.text)
                    holder.encOutgoingMessageText.text = spannableText
                    holder.encOutgoingMessageText.movementMethod = LinkMovementMethod.getInstance()
                    holder.encOutgoingMessageText.textSize = fontSize
                    holder.encOutgoingMessageText.setTextColor(
                        ContextCompat.getColor(context, android.R.color.white)
                    )

                    // Set timestamp
                    holder.encOutgoingTimeTop.text = formattedTime
                    holder.encOutgoingTimeTop.visibility = if (formattedTime.isNotEmpty()) View.VISIBLE else View.GONE

                    // Encryption indicator
                    val encryptionIndicator = message.metadata?.get("e_ind") ?: ""
                    holder.encOutgoingEncryptionIndicator.text = encryptionIndicator
                    holder.encOutgoingEncryptionIndicator.visibility = if (encryptionIndicator.isNotEmpty()) View.VISIBLE else View.GONE

                    holder.encOutgoingMessageText.setOnLongClickListener {
                        onMessageLongClick(message)
                        true
                    }

                    // Setup receipt status for regular mode
                    setupReceiptStatus(
                        message.metadata,
                        holder.encOutgoingReceiptStatusLayout,
                        holder.encOutgoingReceiptIconMsgArrived,
                        holder.encOutgoingReceiptIconStatus,
                        holder.encOutgoingReceiptTimestamp
                    )
                }
            }

            // FALLBACK: NON-DECODED INCOMING MESSAGE (Encrypted but not decoded)
            else -> {
                if (useDefaultEncryption) {
                    holder.defaultEncryptionIncomingMessageLayout.visibility = View.VISIBLE
                    holder.defaultEncryptionMessageText.text = makeLinksClickable(message.text)
                    holder.defaultEncryptionMessageText.movementMethod = LinkMovementMethod.getInstance()
                    holder.defaultEncryptionMessageText.textSize = fontSize
                    // DON'T set text color here

                    // Set timestamps
                    holder.defaultEncryptionIncomingSendingTimeTop.text = formattedTransTime
                    holder.defaultEncryptionIncomingReceivingTimeTop.text = formattedTime
                    holder.defaultEncryptionIncomingSendingTimeTop.visibility = if (formattedTransTime.isNotEmpty()) View.VISIBLE else View.GONE
                    holder.defaultEncryptionIncomingReceivingTimeTop.visibility = if (formattedTime.isNotEmpty()) View.VISIBLE else View.GONE

                    // Encryption indicator
                    val encryptionIndicator = message.metadata?.get("e_ind") ?: "🔒"
                    holder.defaultEncryptionIncomingIndicator.text = encryptionIndicator
                    holder.defaultEncryptionIncomingIndicator.visibility = View.VISIBLE

                    holder.defaultEncryptionMessageText.setOnLongClickListener {
                        onMessageLongClick(message)
                        true
                    }
                } else {
                    //This is the place where decryption system errors come
                    holder.plaintextIncomingMessageLayout.visibility = View.VISIBLE
                    holder.plaintextIncomingMessageText.text = makeLinksClickable(message.text)
                    holder.plaintextIncomingMessageText.movementMethod = LinkMovementMethod.getInstance()
                    holder.plaintextIncomingMessageText.textSize = fontSize
                    holder.plaintextIncomingMessageText.setTextColor(
                        ContextCompat.getColor(context, android.R.color.black)
                    )

                    // Show timestamps
                    holder.plaintextIncomingSendingTimeTop.visibility = if (formattedTransTime.isNotEmpty()) View.VISIBLE else View.GONE
                    holder.plaintextIncomingSendingTimeTop.text = formattedTransTime

                    if (timestampNeeded) {
                        holder.plaintextIncomingReceivingTimeTop.text = formattedTime
                        holder.plaintextIncomingReceivingTimeTop.visibility = View.VISIBLE
                    } else {
                        holder.plaintextIncomingReceivingTimeTop.visibility = View.GONE
                    }

                    // Show warning icon for plaintext
                    holder.plaintextIncomingWarningIcon.text = "⚠️"
                    holder.plaintextIncomingWarningIcon.visibility = View.VISIBLE

                    holder.plaintextIncomingMessageText.setOnLongClickListener {
                        onMessageLongClick(message)
                        true
                    }
                }
            }
        }
    }

    fun setLoadingState(loading: Boolean) {
        isLoadingMore = loading
        notifyItemChanged(0)
    }

    override fun getItemCount(): Int = messages.size + (if (showContinuation) 1 else 0)

    fun setDefaultEncryptionMode(enabled: Boolean) {
        if (isDefaultEncryptionMode != enabled) {
            isDefaultEncryptionMode = enabled
            notifyDataSetChanged()
        }
    }

    fun addMoreMessages(newMessages: List<ChatMessage>, msgSeq: Int) {
        if (newMessages.isEmpty()) return

        val sortedNewMessages = newMessages.sortedBy { it.trans_timestamp }

        val updatedList = messages.toMutableList()

        when (msgSeq) {
            1 -> {
                updatedList.addAll(0, sortedNewMessages)
                messages = updatedList.sortedBy { it.timestamp }
            }
            else -> {
                updatedList.addAll(sortedNewMessages)
                messages = updatedList.sortedByDescending { it.timestamp }
            }
        }

        val insertionPosition = when (msgSeq) {
            1 -> if (showContinuation) 1 else 0
            else -> {
                val basePosition = messages.size - sortedNewMessages.size
                if (showContinuation) basePosition else basePosition
            }
        }

        notifyItemRangeInserted(insertionPosition, sortedNewMessages.size)

        if (remainingMessages > 0) {
            remainingMessages = maxOf(0, remainingMessages - sortedNewMessages.size)
        }
    }

    fun updateMessages(newMessages: List<ChatMessage>) {
        messages = emptyList()
        val prefs = SharedPreferencesManager.getInstance(context)
        addMoreMessages(newMessages, prefs.msgSeq)
        notifyDataSetChanged()
    }

    fun addMessages(newMessages: List<ChatMessage>) {
        val prefs = SharedPreferencesManager.getInstance(context)
        addMoreMessages(newMessages, prefs.msgSeq)
    }

    fun setMsgSeq(msgSeq: Int) {
        if (adapterMsgSeq != msgSeq) {
            adapterMsgSeq = msgSeq
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

        onContinuationStateChanged?.invoke(show, loading)
        notifyDataSetChanged()
    }

    fun setFontSize(newSize: Float) {
        fontSize = newSize
        notifyDataSetChanged()
    }

    private fun hideAllMessageLayouts(holder: MessageViewHolder) {
        holder.incomingMessageLayout.visibility = View.GONE
        holder.plaintextIncomingMessageLayout.visibility = View.GONE
        holder.plaintextOutgoingLayout.visibility = View.GONE
        holder.encOutgoingMessageLayout.visibility = View.GONE
        holder.defaultEncryptionIncomingMessageLayout.visibility = View.GONE
        holder.defaultEncryptionOutgoingLayout.visibility = View.GONE
    }

    private fun setupReceiptStatus(
        metadata: Map<String, String>?,
        receiptLayout: LinearLayout,
        iconArrived: android.widget.ImageView,
        iconStatus: android.widget.ImageView,
        timestampView: TextView
    ) {
        if (metadata != null && metadata["rr"] == "true" && metadata["rres"] != null) {
            receiptLayout.visibility = View.VISIBLE
            iconArrived.visibility = View.VISIBLE
            iconArrived.setImageResource(R.drawable.ic_check_green)

            val receiptTimestamp = metadata["rrt"]?.toLongOrNull()
            if (receiptTimestamp != null) {
                timestampView.visibility = View.VISIBLE
                timestampView.text = formatShortTime(receiptTimestamp)
            } else {
                timestampView.visibility = View.GONE
            }

            when (metadata["rres"]) {
                "OK" -> {
                    iconStatus.visibility = View.VISIBLE
                    iconStatus.setImageResource(R.drawable.ic_check_blue)
                }
                "NOK" -> {
                    iconStatus.visibility = View.VISIBLE
                    iconStatus.setImageResource(R.drawable.ic_cross_red)
                }
                else -> iconStatus.visibility = View.GONE
            }
        } else {
            receiptLayout.visibility = View.GONE
        }
    }

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

    private fun formatShortTime(timestamp: Long): String {
        val date = Date(timestamp)
        val now = Date()
        val diff = now.time - date.time

        return if (diff < 24 * 60 * 60 * 1000) {
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
        } else {
            SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(date)
        }
    }
}