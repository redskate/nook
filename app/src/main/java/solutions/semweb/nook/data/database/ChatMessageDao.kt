package solutions.semweb.nook.data.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface ChatMessageDao {
    @Query("SELECT * FROM chat_messages ORDER BY timestamp DESC")
    fun getAll(): List<ChatMessageEntity>

    @Query("SELECT * FROM chat_messages WHERE conversation_id = :conversationId ORDER BY timestamp DESC")
    fun getByConversation(conversationId: Long): List<ChatMessageEntity>

    @Query("SELECT * FROM chat_messages WHERE conversation_id = :conversationId AND timestamp >= :startTime AND timestamp <= :endTime ORDER BY timestamp ASC")
    suspend fun getMessagesInTimeRange(conversationId: Long, startTime: Long, endTime: Long): List<ChatMessageEntity>

    @Insert
    fun insert(message: ChatMessageEntity): Long

    @Insert
    fun insertAll(messages: List<ChatMessageEntity>)

    @Update
    fun update(message: ChatMessageEntity)

    @Query("UPDATE chat_messages SET metadata_json = :metadataJson, metadata_type = :metadataType, updated_at = :updatedAt WHERE id = :messageId")
    suspend fun updateMetadataDirectly(messageId: Long, metadataJson: String?, metadataType: String?, updatedAt: Long): Int

    @Delete
    fun delete(message: ChatMessageEntity)

    @Query("DELETE FROM chat_messages WHERE conversation_id = :conversationId")
    fun deleteByConversation(conversationId: Long)

    @Query("SELECT * FROM chat_messages WHERE id = :messageId LIMIT 1")
    fun findById(messageId: Long): ChatMessageEntity?

    @Query("SELECT * FROM chat_messages ORDER BY id DESC LIMIT 1")
    fun findLast(): ChatMessageEntity?
    @Query("SELECT COUNT(*) FROM chat_messages WHERE conversation_id = :conversationId")
    fun countByConversation(conversationId: Long): Int

    @Query("DELETE FROM chat_messages WHERE conversation_id = :conversationId AND timestamp < :timestamp")
    fun cleanupOldMessages(conversationId: Long, timestamp: Long)

    @Query("SELECT COUNT(*) FROM chat_messages WHERE id = :encryptedId")
    suspend fun countById(encryptedId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(message: ChatMessageEntity)

    @Query("DELETE FROM chat_messages WHERE id = :messageId")
    fun deleteById(messageId: Long): Int

    /**
     * Gets the last n messages - as soon as a chat is opened
     */
    @Query("""
        SELECT * FROM chat_messages 
        WHERE conversation_id = :conversationId 
        ORDER BY timestamp DESC 
        LIMIT :limit
    """)
    fun getLatestMessages(conversationId: Long, limit: Int): List<ChatMessageEntity>

    /**
     * Gets the first N messages using OFFSET and LIMIT
     * For continuations
     */
    @Query("""
        SELECT * FROM chat_messages 
        WHERE conversation_id = :conversationId 
        ORDER BY timestamp DESC 
        LIMIT :limit OFFSET :offset
    """)
    fun getOlderMessages(conversationId: Long, limit: Int, offset: Int): List<ChatMessageEntity>

    // ============= NEW METHODS FOR MULTIPART SMS SUPPORT =============

    /**
     * Mark a message as replaced (soft delete)
     * Used for dummy/progress messages when real message arrives
     */
    @Query("UPDATE chat_messages SET is_replaced = 1 WHERE id = :messageId")
    suspend fun markAsReplaced(messageId: Long): Int

    /**
     * Find a message by its metadata key-value pair
     * This uses JSON contains search - works with SQLite's JSON1 extension
     * Alternative implementation if JSON1 is not available
     */
    @Query("""
        SELECT * FROM chat_messages 
        WHERE metadata_json IS NOT NULL 
        AND metadata_json LIKE '%' || :key || '%' || :value || '%'
        ORDER BY timestamp DESC
    """)
    suspend fun findByMetadataLike(key: String, value: String): List<ChatMessageEntity>

    /**
     * More efficient metadata search if you have JSON1 extension enabled
     * Uncomment if your SQLite version supports it
     */
    /*
    @Query("""
        SELECT * FROM chat_messages
        WHERE json_extract(metadata_json, '$.' || :key) = :value
        ORDER BY timestamp DESC
    """)
    suspend fun findByMetadataJson(key: String, value: String): List<ChatMessageEntity>
    */

    /**
     * Get all system messages for a conversation
     */
    @Query("""
        SELECT * FROM chat_messages 
        WHERE conversation_id = :conversationId 
        AND is_system_message = 1 
        ORDER BY timestamp DESC
    """)
    suspend fun getSystemMessages(conversationId: Long): List<ChatMessageEntity>

    /**
     * Get system messages of a specific type for a conversation
     */
    @Query("""
        SELECT * FROM chat_messages 
        WHERE conversation_id = :conversationId 
        AND is_system_message = 1 
        AND metadata_type = :metadataType 
        ORDER BY timestamp DESC
    """)
    suspend fun getSystemMessagesByType(conversationId: Long, metadataType: String): List<ChatMessageEntity>

    /**
     * Get all messages that have metadata (non-null)
     */
    @Query("SELECT * FROM chat_messages WHERE metadata_json IS NOT NULL")
    suspend fun getAllMessagesWithMetadata(): List<ChatMessageEntity>

    /**
     * Get dummy/progress messages that haven't been replaced yet
     */
    @Query("""
        SELECT * FROM chat_messages 
        WHERE is_system_message = 1 
        AND metadata_type = 'multipart_progress' 
        AND is_replaced = 0 
        AND timestamp > :cutoffTime
        ORDER BY timestamp DESC
    """)
    suspend fun getUnreplacedProgressMessages(cutoffTime: Long): List<ChatMessageEntity>

    /**
     * Update message metadata
     */
    @Query("""
        UPDATE chat_messages 
        SET metadata_json = :metadataJson, 
            metadata_type = :metadataType,
            updated_at = :updatedAt 
        WHERE id = :messageId
    """)
    suspend fun updateMetadata(
        messageId: Long,
        metadataJson: String,
        metadataType: String,
        updatedAt: Long
    ): Int

    /**
     * Count system messages of a specific type for a conversation
     */
    @Query("""
        SELECT COUNT(*) FROM chat_messages 
        WHERE conversation_id = :conversationId 
        AND is_system_message = 1 
        AND metadata_type = :metadataType
    """)
    suspend fun countSystemMessagesByType(conversationId: Long, metadataType: String): Int

    /**
     * Delete all replaced messages older than cutoff time
     * For cleanup purposes
     */
    @Query("""
        DELETE FROM chat_messages 
        WHERE is_replaced = 1 
        AND timestamp < :cutoffTime
    """)
    suspend fun cleanupReplacedMessages(cutoffTime: Long): Int

    /**
     * Find a specific progress message by its dummy ID stored in metadata
     */
    @Query("""
        SELECT * FROM chat_messages 
        WHERE metadata_json LIKE '%dummy_id%' || :dummyId || '%'
        AND metadata_type = 'multipart_progress'
        LIMIT 1
    """)
    suspend fun findProgressMessageByDummyId(dummyId: String): ChatMessageEntity?

    /**
     * Get all messages for a specific sender within a time range
     * Useful for scanner to find messages that might be multipart
     */
    @Query("""
        SELECT cm.* FROM chat_messages cm
        INNER JOIN chat_conversations cc ON cm.conversation_id = cc.id
        WHERE cc.phone_number LIKE '%' || :senderHash || '%'
        AND cm.timestamp BETWEEN :startTime AND :endTime
        ORDER BY cm.timestamp ASC
    """)
    suspend fun getMessagesForSenderInTimeRange(
        senderHash: String,
        startTime: Long,
        endTime: Long
    ): List<ChatMessageEntity>

    /**
     * Get the most recent message for a conversation
     */
    @Query("""
        SELECT * FROM chat_messages 
        WHERE conversation_id = :conversationId 
        ORDER BY timestamp DESC 
        LIMIT 1
    """)
    suspend fun getLatestMessageForConversation(conversationId: Long): ChatMessageEntity?
}