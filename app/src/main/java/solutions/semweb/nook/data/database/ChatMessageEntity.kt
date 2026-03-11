package solutions.semweb.nook.data.database

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.gson.Gson
import solutions.semweb.nook.ChatMessage
import solutions.semweb.nook.crypto.AppCryptoManager

@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["conversation_id"], name = "idx_conversation"),
        Index(value = ["timestamp"], name = "idx_timestamp"),
        Index(value = ["is_read", "conversation_id"], name = "idx_read_conversation"),
        Index(value = ["is_system_message"], name = "idx_system_message"),
        Index(value = ["metadata_type"], name = "idx_metadata_type"),
        Index(value = ["updated_at"], name = "idx_updated_at")  // NEW INDEX
    ]
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "conversation_id", index = true)
    val conversationId: Long,

    @ColumnInfo(name = "text")
    val text: String,

    @ColumnInfo(name = "sender")
    val sender: String,

    @ColumnInfo(name = "sender_name")
    val senderName: String?,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    @ColumnInfo(name = "trans_timestamp")
    val trans_timestamp: Long = -1,

    @ColumnInfo(name = "is_decoded")
    val isDecoded: Boolean,

    @ColumnInfo(name = "is_outgoing")
    val isOutgoing: Boolean = false,

    @ColumnInfo(name = "is_sent")
    val isSent: Boolean = false,

    @ColumnInfo(name = "is_y_message")
    val isYMessage: Boolean = false,

    @ColumnInfo(name = "is_read")
    val isRead: Boolean = false,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    // ADD THIS NEW FIELD - for tracking updates
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),  // NEW FIELD

    @ColumnInfo(name = "is_system_message", defaultValue = "0")
    val isSystemMessage: Boolean = false,

    @ColumnInfo(name = "metadata_type", defaultValue = "NULL")
    val metadataType: String? = null,

    @ColumnInfo(name = "metadata_json", defaultValue = "NULL")
    val metadataJson: String? = null,

    @ColumnInfo(name = "is_replaced", defaultValue = "0")
    val isReplaced: Boolean = false
) {
    companion object {
        private val gson = Gson()

        fun fromDomain(message: ChatMessage, conversationId: Long, context: Context): ChatMessageEntity {
            val now = System.currentTimeMillis()
            return ChatMessageEntity(
                conversationId = conversationId,
                text = AppCryptoManager.encrypt64Value(message.text),
                sender = AppCryptoManager.encrypt64Value(message.sender),
                senderName = message.senderName?.let { AppCryptoManager.encrypt64Value(it) },
                timestamp = message.timestamp,
                trans_timestamp = message.trans_timestamp,
                isDecoded = message.isDecoded,
                isOutgoing = message.isOutgoing,
                isSent = message.isSent,
                isYMessage = message.isYMessage,
                isRead = !message.isOutgoing && message.isDecoded,
                createdAt = now,
                updatedAt = now,  // SET INITIAL VALUE
                isSystemMessage = message.isSystemMessage,
                metadataType = message.metadata?.get("type"),
                metadataJson = message.metadata?.let { metadata ->
                    val metadataJson = gson.toJson(metadata)
                    AppCryptoManager.encrypt64Value(metadataJson)
                },
                isReplaced = message.isReplaced
            )
        }

        // Optional: Add a method for creating updated copies
        fun copyWithUpdate(
            entity: ChatMessageEntity,
            metadataJson: String? = null,
            metadataType: String? = null,
            isReplaced: Boolean? = null
        ): ChatMessageEntity {
            return entity.copy(
                metadataJson = metadataJson ?: entity.metadataJson,
                metadataType = metadataType ?: entity.metadataType,
                isReplaced = isReplaced ?: entity.isReplaced,
                updatedAt = System.currentTimeMillis()  // UPDATE TIMESTAMP
            )
        }
    }

    fun toDomain(context: Context): ChatMessage {
        return ChatMessage(
            id = this.id,
            text = DecryptionValidator.safeDecryptNonNull(
                text, "text", context, "ChatMessage",
                conversationId = this.conversationId,
                messageId = this.id
            ),
            sender = DecryptionValidator.safeDecryptNonNull(
                sender, "sender", context, "ChatMessage",
                conversationId = this.conversationId,
                messageId = this.id
            ),
            senderName = DecryptionValidator.safeDecryptOptional(
                senderName, "senderName", context, "ChatMessage",
                conversationId = this.conversationId,
                messageId = this.id
            ),
            timestamp = timestamp,
            trans_timestamp = trans_timestamp,
            isDecoded = isDecoded,
            isOutgoing = isOutgoing,
            isSent = isSent,
            isYMessage = isYMessage,
            isSystemMessage = isSystemMessage,
            isReplaced = isReplaced,
            metadata = DecryptionValidator.safeDecryptMap(
                metadataJson, context, "ChatMessage",
                conversationId = this.conversationId,
                messageId = this.id
            )
        )
    }

}