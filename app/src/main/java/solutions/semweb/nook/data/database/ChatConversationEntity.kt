package solutions.semweb.nook.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import solutions.semweb.nook.ChatConversation
import solutions.semweb.nook.Constants
import solutions.semweb.nook.PhoneUtils
import solutions.semweb.nook.crypto.AppCryptoManager

@Entity(
    tableName = "chat_conversations",
    indices = [
        Index(value = ["phone_hash"], unique = true),
        Index(value = ["phone_number"])
    ]
)
data class ChatConversationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "phone_number")
    val phoneNumber: String,

    @ColumnInfo(name = "phone_hash")
    var phoneHash: String,

    @ColumnInfo(name = "contact_name")
    val contactName: String?,

    @ColumnInfo(name = "last_message")
    var lastMessage: String,

    @ColumnInfo(name = "last_timestamp")
    var lastTimestamp: Long,

    @ColumnInfo(name = "unread_count")
    var unreadCount: Int = 0,

    @ColumnInfo(name = "is_y_chat")
    val isYChat: Boolean = false,

    @ColumnInfo(name = "encryption_scheme")
    var encryptionScheme: String = Constants.DEFAULT_encryptionScheme,

    @ColumnInfo(name = "encoding")
    var encoding: String = Constants.DEFAULT_encoding,

    @ColumnInfo(name = "encoding_password")  // <-- AGGIUNTO
    var encodingPassword: String = "",

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    var updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        // Save:
        fun fromDomain(conversation: ChatConversation, context: android.content.Context): ChatConversationEntity {
            // Normalize ALWAYS!!!
            val normalizedPhone = PhoneUtils.normalizePhoneNumber(conversation.phoneNumber)
            val encryptedPhone = AppCryptoManager.encrypt64Value(normalizedPhone)  // Usa normalizedPhone
            val phoneHash = AppCryptoManager.encrypt64Key(normalizedPhone)  // Usa normalizedPhone

            val encryptedContactName = conversation.contactName?.let { AppCryptoManager.encrypt64Value(it) }
            val encryptedLastMessage = AppCryptoManager.encrypt64Value(conversation.lastMessage)
            val encryptedEncodingPassword = AppCryptoManager.encrypt64Value(conversation.encodingPassword)

            return ChatConversationEntity(
                phoneNumber = encryptedPhone,
                phoneHash = phoneHash,
                contactName = encryptedContactName,
                lastMessage = encryptedLastMessage,
                lastTimestamp = conversation.lastTimestamp,
                unreadCount = conversation.unreadCount,
                isYChat = conversation.isYChat,
                encryptionScheme = conversation.encryptionScheme,
                encoding = conversation.encoding,
                encodingPassword = encryptedEncodingPassword,
                createdAt = conversation.createdAt,
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    // Read
    fun toDomain(context: android.content.Context): ChatConversation {
        val decryptedPhone = AppCryptoManager.decrypt64Value(phoneNumber)
        val decryptedContactName = contactName?.let { AppCryptoManager.decrypt64Value(it) }
        val decryptedLastMessage = AppCryptoManager.decrypt64Value(lastMessage)
        val decryptedEncodingPassword = AppCryptoManager.decrypt64Value(encodingPassword)

        return ChatConversation(
            id = this.id,
            phoneNumber = decryptedPhone,
            contactName = decryptedContactName,
            lastMessage = decryptedLastMessage,
            lastTimestamp = lastTimestamp,
            unreadCount = unreadCount,
            messages = mutableListOf(),
            isYChat = isYChat,
            encryptionScheme = encryptionScheme,
            encoding = encoding,
            encodingPassword = decryptedEncodingPassword,
            createdAt = createdAt
        )
    }
}