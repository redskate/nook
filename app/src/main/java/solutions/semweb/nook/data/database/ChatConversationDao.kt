package solutions.semweb.nook.data.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface ChatConversationDao {
    @Query("SELECT * FROM chat_conversations ORDER BY last_timestamp DESC")
    fun getAll(): List<ChatConversationEntity>

    @Query("SELECT * FROM chat_conversations WHERE phone_hash = :phoneHash LIMIT 1")
    fun findByPhoneHash(phoneHash: String): ChatConversationEntity?

    @Query("SELECT * FROM chat_conversations WHERE phone_number = :phoneNumber LIMIT 1")
    fun findByPhoneNumber(phoneNumber: String): ChatConversationEntity?

    @Query("SELECT * FROM chat_conversations WHERE contact_name = :encryptedContactName LIMIT 1")
    fun findByContactName(encryptedContactName: String): ChatConversationEntity?

    @Query("DELETE FROM chat_conversations WHERE contact_name = :encryptedContactName")
    fun deleteByContactName(encryptedContactName: String): Int

    @Insert
    fun insert(conversation: ChatConversationEntity): Long

    @Update
    fun update(conversation: ChatConversationEntity)

    @Delete
    fun delete(conversation: ChatConversationEntity)

    @Query("DELETE FROM chat_conversations WHERE phone_hash = :phoneHash")
    fun deleteByPhoneHash(phoneHash: String)

    @Query("DELETE FROM chat_conversations WHERE phone_number = :phoneNumber")
    fun deleteByPhoneNumber(phoneNumber: String)

    @Query("SELECT * FROM chat_conversations WHERE id = :conversationId LIMIT 1")
    fun findById(conversationId: Long): ChatConversationEntity?

    @Query("UPDATE chat_conversations SET unread_count = 0 WHERE phone_hash = :phoneHash")
    fun markAsReadByHash(phoneHash: String)

    @Query("UPDATE chat_conversations SET unread_count = 0 WHERE phone_number = :phoneNumber")
    fun markAsRead(phoneNumber: String)

    @Query("UPDATE chat_conversations SET last_message = :lastMessage, last_timestamp = :timestamp WHERE phone_hash = :phoneHash")
    fun updateLastMessageByHash(phoneHash: String, lastMessage: String, timestamp: Long)

    @Query("UPDATE chat_conversations SET last_message = :lastMessage, last_timestamp = :timestamp WHERE phone_number = :phoneNumber")
    fun updateLastMessage(phoneNumber: String, lastMessage: String, timestamp: Long)

    @Query("UPDATE chat_conversations SET unread_count = :unreadCount WHERE phone_number = :encryptedPhone")
    suspend fun updateUnreadCount(encryptedPhone: String, unreadCount: Int): Int

    @Query("UPDATE chat_conversations SET unread_count = :unreadCount WHERE phone_hash = :phoneHash")
    suspend fun updateUnreadCountByHash(phoneHash: String, unreadCount: Int): Int

    @Query("SELECT COUNT(*) FROM chat_conversations WHERE unread_count > 0")
    fun countUnread(): Int

    @Query("SELECT * FROM chat_conversations WHERE is_y_chat = 1")
    fun getAllYChats(): List<ChatConversationEntity>

    @Query("UPDATE chat_conversations SET unread_count = 0 WHERE phone_hash = :phoneHash")
    suspend fun resetUnreadCountByHash(phoneHash: String): Int

    @Query("UPDATE chat_conversations SET unread_count = 0 WHERE phone_number = :phoneNumber")
    suspend fun resetUnreadCountByPhone(phoneNumber: String): Int

    @Query("UPDATE chat_conversations SET encoding = :encoding, updated_at = :updatedAt WHERE phone_hash = :phoneHash")
    fun updateEncoding(phoneHash: String, encoding: String, updatedAt: Long)

    @Query("UPDATE chat_conversations SET encryption_scheme = :encryptionScheme, updated_at = :updatedAt WHERE phone_hash = :phoneHash")
    fun updateEncryptionScheme(phoneHash: String, encryptionScheme: String, updatedAt: Long)

    @Query("UPDATE chat_conversations SET encryption_scheme = :encryptionScheme, encoding = :encoding WHERE phone_hash = :phoneHash")
    suspend fun updateEncryptionAndEncoding(phoneHash: String, encryptionScheme: String, encoding: String): Int

    @Query("UPDATE chat_conversations SET contact_name = :contactName, encryption_scheme = :encryptionScheme, encoding = :encoding, updated_at = :updatedAt WHERE phone_hash = :phoneHash")
    fun updateContactInfo(phoneHash: String, contactName: String?, encryptionScheme: String, encoding: String, updatedAt: Long)

    @Query("UPDATE chat_conversations SET encoding = :encoding, encoding_password = :encodingPassword, updated_at = :updatedAt WHERE phone_hash = :phoneHash")
    fun updateEncodingAndPassword(phoneHash: String, encoding: String, encodingPassword: String, updatedAt: Long)

    @Query("UPDATE chat_conversations SET encryption_scheme = :encryptionScheme, encoding = :encoding, encoding_password = :encodingPassword WHERE phone_hash = :phoneHash")
    suspend fun updateEncryptionAndEncodingWithPassword(phoneHash: String, encryptionScheme: String, encoding: String, encodingPassword: String): Int

    @Query("UPDATE chat_conversations SET contact_name = :contactName, encryption_scheme = :encryptionScheme, encoding = :encoding, encoding_password = :encodingPassword, updated_at = :updatedAt WHERE phone_hash = :phoneHash")
    fun updateContactInfoWithPassword(phoneHash: String, contactName: String?, encryptionScheme: String, encoding: String, encodingPassword: String, updatedAt: Long)


    @Query("""
        UPDATE chat_conversations 
        SET contact_name = :contactName,
            last_message = :lastMessage,
            last_timestamp = :lastTimestamp,
            unread_count = :unreadCount,
            is_y_chat = :isYChat,
            encryption_scheme = :encryptionScheme,
            encoding = :encoding,
            encoding_password = :encodingPassword,
            updated_at = :updatedAt
        WHERE phone_hash = :phoneHash
    """)
    suspend fun updateConversationByHash(
        phoneHash: String,
        contactName: String?,
        lastMessage: String,
        lastTimestamp: Long,
        unreadCount: Int,
        isYChat: Boolean,
        encryptionScheme: String,
        encoding: String,
        encodingPassword: String,
        updatedAt: Long
    ): Int

    // Debug: verify whether the encoding field exists
    @Query("SELECT phone_hash, encoding FROM chat_conversations WHERE encoding IS NULL OR encoding = ''")
    fun findConversationsWithMissingEncoding(): List<EncryptionEncodingPair>

    data class EncryptionEncodingPair(
        val phone_hash: String,
        val encoding: String?
    )
}