package solutions.semweb.nook.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import solutions.semweb.nook.crypto.EncryptionVerifier
import java.util.UUID

@Entity(
    tableName = "decoded_messages",
    indices = [
        Index(value = ["sender"], name = "idx_decoded_sender"),
        Index(value = ["timestamp"], name = "idx_decoded_timestamp"),
        Index(value = ["success"], name = "idx_decoded_success")
    ]
)
data class DecodedMessageEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "original_message")
    val originalMessage: String, // Encrypted
    @ColumnInfo(name = "decoded_message")
    val decodedMessage: String, // Encrypted
    val sender: String, // Encrypted - Sender number
    @ColumnInfo(name = "sender_name")
    val senderName: String?, // Encrypted, nullable - Sender name
    @ColumnInfo(name = "timestamp")
    val timestamp: Long, // When received
    @ColumnInfo(name = "decoding_timestamp")
    val decodingTimestamp: Long = System.currentTimeMillis(), // When decoded
    val success: Boolean, // Whether the decodification had success
    @ColumnInfo(name = "decoding_scheme")
    val decodingScheme: String, // Scheme used to decrypt
    @ColumnInfo(name = "is_read")
    val isRead: Boolean = false, // Whether user has seen the message
    @ColumnInfo(name = "is_archived")
    val isArchived: Boolean = false, // Whether message is archived
    @ColumnInfo(name = "message_type")
    val messageType: String = "sms", // Type - currently just sms
    @ColumnInfo(name = "additional_info")
    val additionalInfo: String? = null, // Encrypted, nullable
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        fun fromDomain(
            originalMessage: String,
            decodedMessage: String,
            sender: String,
            senderName: String? = null,
            timestamp: Long,
            success: Boolean,
            decodingScheme: String,
            messageType: String = "sms",
            additionalInfo: Map<String, Any>? = null,
            context: android.content.Context
        ): DecodedMessageEntity {
            val encryptedOriginal = EncryptionVerifier.encryptAndVerify(
                originalMessage, "originalMessage", "DecodedMessage", context
            )
            val encryptedDecoded = EncryptionVerifier.encryptAndVerify(
                decodedMessage, "decodedMessage", "DecodedMessage", context
            )
            val encryptedSender = EncryptionVerifier.encryptAndVerify(
                sender, "sender", "DecodedMessage", context
            )
            val encryptedSenderName = senderName?.let {
                EncryptionVerifier.encryptAndVerify(
                    it, "senderName", "DecodedMessage", context
                )
            }
            val encryptedAdditionalInfo = additionalInfo?.let {
                com.google.gson.Gson().toJson(it).let { json ->
                    EncryptionVerifier.encryptAndVerify(
                        json, "additionalInfo", "DecodedMessage", context
                    )
                }
            }

            return DecodedMessageEntity(
                originalMessage = encryptedOriginal,
                decodedMessage = encryptedDecoded,
                sender = encryptedSender,
                senderName = encryptedSenderName,
                timestamp = timestamp,
                success = success,
                decodingScheme = decodingScheme,
                messageType = messageType,
                additionalInfo = encryptedAdditionalInfo
            )
        }
    }

    data class DecodedMessageDomain(
        val originalMessage: String,
        val decodedMessage: String,
        val sender: String,
        val senderName: String? = null,
        val timestamp: Long,
        val decodingTimestamp: Long,
        val success: Boolean,
        val decodingScheme: String,
        val isRead: Boolean = false,
        val isArchived: Boolean = false,
        val messageType: String = "sms",
        val additionalInfo: Map<String, Any>? = null
    )

    fun toDomain(context: android.content.Context): DecodedMessageDomain {
        // Decifra i dati con validazione
        val decryptedOriginal = DecryptionValidator.safeDecryptNonNull(
            originalMessage, "originalMessage", context, "DecodedMessage"
        )
        val decryptedDecoded = DecryptionValidator.safeDecryptNonNull(
            decodedMessage, "decodedMessage", context, "DecodedMessage"
        )
        val decryptedSender = DecryptionValidator.safeDecryptNonNull(
            sender, "sender", context, "DecodedMessage"
        )

        // FIX: Handle empty senderName specially
        val decryptedSenderName = if (senderName.isNullOrEmpty()) {
            senderName // Return as-is (null or empty)
        } else {
            DecryptionValidator.safeDecryptOptional(
                senderName, "senderName", context, "DecodedMessage"
            )
        }

        val decryptedAdditionalInfo = additionalInfo?.let {
            if (it.isEmpty()) {
                null
            } else {
                DecryptionValidator.safeDecryptOptional(it, "additionalInfo", context, "DecodedMessage")
            }
        }?.let { json ->
            try {
                com.google.gson.Gson().fromJson(json, Map::class.java) as Map<String, Any>
            } catch (e: Exception) {
                null
            }
        }

        return DecodedMessageDomain(
            originalMessage = decryptedOriginal,
            decodedMessage = decryptedDecoded,
            sender = decryptedSender,
            senderName = decryptedSenderName,
            timestamp = timestamp,
            decodingTimestamp = decodingTimestamp,
            success = success,
            decodingScheme = decodingScheme,
            isRead = isRead,
            isArchived = isArchived,
            messageType = messageType,
            additionalInfo = decryptedAdditionalInfo
        )
    }
}