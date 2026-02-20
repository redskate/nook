package solutions.semweb.nook

data class TrustedContact(
    val contactId: String,
    val phoneNumber: String,
    val displayName: String?,
    val isActive: Boolean = true
)

data class ChatMessage(
    val id: Long = System.currentTimeMillis(),
    val text: String,
    val sender: String,
    val senderName: String? = null,
    val trans_timestamp: Long = -1,
    val timestamp: Long = System.currentTimeMillis(),
    val isDecoded: Boolean = true,
    val isOutgoing: Boolean = false,
    val isSent: Boolean = false,
    val isSystemMessage: Boolean = false,
    val metadata: Map<String, String>? = null,
    val isYMessage: Boolean = false,
    val isReplaced: Boolean = false
)

data class ChatConversation(
    val id: Long = -1,
    val phoneNumber: String,
    val contactName: String?,
    val lastMessage: String,
    val lastTimestamp: Long,
    val unreadCount: Int = 0,
    val messages: MutableList<ChatMessage> = mutableListOf(),
    val isYChat: Boolean = false,
    val encryptionScheme: String = Constants.DEFAULT_encryptionScheme,
    val encoding: String = Constants.DEFAULT_encoding,
    val encodingPassword: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

data class ContactInfo(
    val contactId: String,
    val phoneNumber: String,
    val displayName: String?
)

data class DecodedMessage(
    val id: String = "${System.currentTimeMillis()}_${(0..9999).random()}",
    val text: String,
    val sender: String,
    val senderName: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val messageType: String = "sms", // "sms", "y", "encrypted", "plaintext"
    val encryptionScheme: String? = null,
    val isDecoded: Boolean = true,
    val notes: String? = null
)


data class MultipartInfo(
    val sender: String,
    val dummyId: String,
    val partCount: Int,
    val firstTimestamp: Long,
    val timestamp: Long = System.currentTimeMillis()
)
