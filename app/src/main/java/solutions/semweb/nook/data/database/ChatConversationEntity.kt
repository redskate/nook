package solutions.semweb.nook.data.database

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import solutions.semweb.nook.ChatConversation
import solutions.semweb.nook.Constants
import solutions.semweb.nook.LogUtils
import solutions.semweb.nook.PhoneUtils
import solutions.semweb.nook.crypto.AppCryptoManager
import java.nio.charset.StandardCharsets

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

    @ColumnInfo(name = "encoding_password")
    var encodingPassword: String = "",

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    var updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        // Write: Domain → Entity
        fun fromDomain(conversation: ChatConversation, context: Context): ChatConversationEntity {
            // Normalize phone number first
            val normalizedPhone = PhoneUtils.normalizePhoneNumber(conversation.phoneNumber)

            // Log what we're encrypting
            LogUtils.d(null, "CHAT_ENCRYPT", "📝 Encrypting conversation for: $normalizedPhone")

            // Encrypt all fields with UTF-8 guarantee
            val encryptedPhone = encryptSafely(normalizedPhone)
            val phoneHash = AppCryptoManager.encrypt64Key(normalizedPhone)
            val encryptedContactName = conversation.contactName?.let { encryptSafely(it) }
            val encryptedLastMessage = encryptSafely(conversation.lastMessage)
            // This is correct - encrypting the password once
            val encryptedEncodingPassword = if (conversation.encodingPassword.isNotEmpty())
                encryptSafely(conversation.encodingPassword)
            else
                ""

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

        // Safe encryption with UTF-8 guarantee
        private fun encryptSafely(plainText: String): String {
            return try {
                // Force UTF-8 encoding before encryption
                val utf8Bytes = plainText.toByteArray(StandardCharsets.UTF_8)
                // Encrypt the bytes directly (if AppCryptoManager supports byte arrays)
                // Otherwise, convert back to string with UTF-8 guarantee
                AppCryptoManager.encrypt64Value(plainText)
            } catch (e: Exception) {
                LogUtils.e("CHAT_ENCRYPT", "❌ Encryption failed", e)
                throw e
            }
        }

        // Safe decryption with UTF-8 guarantee
        private fun decryptSafely(encryptedText: String): String {
            return try {
                val decrypted = AppCryptoManager.decrypt64Value(encryptedText)

                // Verify it's valid UTF-8 by round-tripping
                val utf8Bytes = decrypted.toByteArray(StandardCharsets.UTF_8)
                val roundTripped = String(utf8Bytes, StandardCharsets.UTF_8)

                if (roundTripped != decrypted) {
                    LogUtils.d( null, "DB_DECRYPT", "⚠️ Decrypted string is not valid UTF-8, attempting repair")
                    // Repair by forcing UTF-8 interpretation
                    String(decrypted.toByteArray(Charsets.ISO_8859_1), StandardCharsets.UTF_8)
                } else {
                    decrypted
                }
            } catch (e: Exception) {
                LogUtils.e("DB_DECRYPT", "❌ Decryption failed", e)
                throw e
            }
        }
    }

    // READ: Entity → Domain
    // READ: Entity → Domain
    fun toDomain(context: Context): ChatConversation {
        LogUtils.d(context, "DB_DECRYPT", "🔓 Decrypting conversation ID: $id")

        val decryptedPhone = DecryptionValidator.safeDecryptNonNull(
            phoneNumber, "phoneNumber", context, "ChatConversation",
            conversationId = this.id
        )

        // FIX: Handle empty contactName specially
        val decryptedContactName = if (contactName.isNullOrEmpty()) {
            contactName // Return as-is (null or empty)
        } else {
            DecryptionValidator.safeDecryptOptional(
                contactName, "contactName", context, "ChatConversation",
                conversationId = this.id
            )
        }

        val decryptedLastMessage = DecryptionValidator.safeDecryptNonNull(
            lastMessage, "lastMessage", context, "ChatConversation",
            conversationId = this.id
        )

        // FIX: Handle empty encodingPassword specially
        val decryptedEncodingPassword = if (encodingPassword.isEmpty()) {
            "" // Empty string, no need to decrypt
        } else {
            DecryptionValidator.safeDecryptOptional(
                encodingPassword, "encodingPassword", context, "ChatConversation",
                conversationId = this.id
            ) ?: ""
        }

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