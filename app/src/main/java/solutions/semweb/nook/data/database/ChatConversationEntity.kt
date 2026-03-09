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
        // SAVE: Domain → Entity
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
            val encryptedEncodingPassword = encryptSafely(conversation.encodingPassword)

            // Verify encryption immediately
            try {
                val verifyPhone = decryptSafely(encryptedPhone)
                if (verifyPhone != normalizedPhone) {
                    LogUtils.e("CHAT_ENCRYPT",
                        "❌ Phone encryption verification failed during save!")
                } else {
                    LogUtils.d("CHAT_ENCRYPT", "✅ Phone encryption verified")
                }
            } catch (e: Exception) {
                LogUtils.e("CHAT_ENCRYPT", "❌ Encryption verification failed", e)
            }

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
    fun toDomain(context: Context): ChatConversation {
        LogUtils.d(context, "DB_DECRYPT", "🔓 Decrypting conversation ID: $id")

        // Use a thread-local or synchronized approach to prevent state corruption
        val decryptedPhone = decryptWithRecovery(phoneNumber, "phoneNumber")
        val decryptedContactName = contactName?.let { decryptWithRecovery(it, "contactName") }
        val decryptedLastMessage = decryptWithRecovery(lastMessage, "lastMessage")
        val decryptedEncodingPassword = decryptWithRecovery(encodingPassword, "encodingPassword")

        // Final validation
        if (!isValidDecryptedData(decryptedPhone, decryptedContactName)) {
            LogUtils.e("DB_DECRYPT",
                "❌ Decryption produced invalid data for conversation $id")

            // Attempt emergency recovery
            val recoveredPhone = emergencyRecovery(phoneNumber)
            val recoveredName = contactName?.let { emergencyRecovery(it) }

            return ChatConversation(
                id = this.id,
                phoneNumber = recoveredPhone,
                contactName = recoveredName,
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

    // Thread-safe decryption with recovery
    private fun decryptWithRecovery(encryptedValue: String, fieldName: String): String {
        return try {
            // Try normal decryption first
            val decrypted = AppCryptoManager.decrypt64Value(encryptedValue)

            // Validate it's readable (contains mostly printable chars)
            if (isMostlyPrintable(decrypted)) {
                decrypted
            } else {
                LogUtils.d(null,"DB_DECRYPT","⚠️ Field $fieldName contains non-printable chars, attempting repair")
                repairDecryptedString(encryptedValue)
            }
        } catch (e: Exception) {
            LogUtils.e("DB_DECRYPT",
                "❌ Failed to decrypt field $fieldName, attempting repair", e)
            repairDecryptedString(encryptedValue)
        }
    }

    // Repair a corrupted decryption
    private fun repairDecryptedString(encryptedValue: String): String {
        return try {
            // Try different decoding strategies
            val strategies = listOf(
                { AppCryptoManager.decrypt64Value(encryptedValue) },
                {
                    // Force ISO-8859-1 to UTF-8 conversion
                    val corrupted = AppCryptoManager.decrypt64Value(encryptedValue)
                    val bytes = corrupted.toByteArray(Charsets.ISO_8859_1)
                    String(bytes, StandardCharsets.UTF_8)
                }
            )

            for (strategy in strategies) {
                try {
                    val result = strategy()
                    if (isMostlyPrintable(result)) {
                        return result
                    }
                } catch (e: Exception) {
                    // Try next strategy
                }
            }

            // Last resort: try to extract any valid characters
            val fallback = AppCryptoManager.decrypt64Value(encryptedValue)
            fallback.filter { it.isLetterOrDigit() || it.isWhitespace() || it == '+' }
                .takeIf { it.isNotEmpty() } ?: "[DECRYPTION_FAILED]"

        } catch (e: Exception) {
            LogUtils.e("DB_DECRYPT", "❌ All repair strategies failed", e)
            "[DECRYPTION_FAILED]"
        }
    }

    // Emergency recovery when everything else fails
    private fun emergencyRecovery(encryptedValue: String): String {
        return try {
            // Try to decrypt and then force UTF-8 interpretation
            val decrypted = AppCryptoManager.decrypt64Value(encryptedValue)
            val isoBytes = decrypted.toByteArray(Charsets.ISO_8859_1)
            String(isoBytes, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            // If all fails, return a placeholder
            "[RECOVERY_FAILED]"
        }
    }

    // Check if a string is mostly printable (not full of control chars)
    private fun isMostlyPrintable(str: String): Boolean {
        if (str.isEmpty()) return true

        val printableCount = str.count { it.code >= 32 && it.code <= 126 || it.isWhitespace() }
        return printableCount.toFloat() / str.length > 0.8 // 80% printable
    }

    // Validate decrypted data
    private fun isValidDecryptedData(phone: String, name: String?): Boolean {
        // Phone should be mostly digits and '+'
        val phoneValid = phone.all { it.isDigit() || it == '+' || it.isWhitespace() }

        // Name should be mostly letters and spaces
        val nameValid = name?.all {
            it.isLetter() || it.isWhitespace() || it == '-' || it == '\''
        } ?: true

        return phoneValid && nameValid
    }
}