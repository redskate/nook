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
import solutions.semweb.nook.crypto.EncryptionVerifier
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
            val normalizedPhone = PhoneUtils.normalizePhoneNumber(conversation.phoneNumber)
            LogUtils.d(null, "CHAT_ENCRYPT", "📝 Encrypting conversation for: $normalizedPhone")

            val encryptedPhone = encryptSafely(
                normalizedPhone,
                fieldName = "phone",
                context = context,
                conversationId = conversation.id
            )
            val phoneHash = AppCryptoManager.encrypt64Key(normalizedPhone)
            val encryptedContactName = conversation.contactName?.let {
                encryptSafely(
                    conversation.contactName,
                    fieldName = "contactName",
                    context = context,
                    conversationId = conversation.id
                )
            }
            val encryptedLastMessage = encryptSafely(
                conversation.lastMessage,
                fieldName = "lastMessage",
                context = context,
                conversationId = conversation.id
            )
            val encryptedEncodingPassword = if (conversation.encodingPassword.isNotEmpty())
                encryptSafely(
                    conversation.encodingPassword,
                    fieldName = "encodingPassword",
                    context = context,
                    conversationId = conversation.id
                )
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
        private fun encryptSafely(plainText: String, fieldName: String, context: Context, conversationId: Long? = null): String {
            return try {
                // Log what we're about to encrypt
                LogUtils.d("ENCRYPT_WATCH", "🔐 About to encrypt $fieldName: '${plainText.take(50)}...'")

                val result = EncryptionVerifier.encryptAndVerify(
                    plainText = plainText,
                    fieldName = fieldName,
                    entityType = "ChatConversation",
                    context = context,
                    conversationId = conversationId ?: 0L
                )

                if (AppCryptoManager.looksLikeEncoded(result) && !AppCryptoManager.looksLikeEncoded(plainText)) {
                    LogUtils.e("ENCRYPT_WATCH", "❌❌❌ PRODUCED BASE64 OUTPUT for $fieldName!")
                    LogUtils.e("ENCRYPT_WATCH", "  Original (plain): '$plainText'")
                    LogUtils.e("ENCRYPT_WATCH", "  Result (looks like Base64): '$result'")
                    LogUtils.e("ENCRYPT_WATCH", "  This suggests we're encrypting already encrypted data!")
                }

                result
            } catch (e: Exception) {
                LogUtils.e("ENCRYPT_WATCH", "❌ Encryption failed for $fieldName", e)
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

    private fun encryptSafely(plainText: String, fieldName: String, context: Context, conversationId: Long? = null): String {
        return try {
            // Force UTF-8 encoding before encryption
            val utf8Bytes = plainText.toByteArray(StandardCharsets.UTF_8)
            // Use the new encryptAndVerify method
            EncryptionVerifier.encryptAndVerify(
                plainText = plainText,
                fieldName = fieldName,
                entityType = "ChatConversation",
                context = context,
                conversationId = conversationId ?: 0L
            )
        } catch (e: Exception) {
            LogUtils.e("CHAT_ENCRYPT", "❌ Encryption failed for $fieldName", e)
            throw e
        }
    }


}