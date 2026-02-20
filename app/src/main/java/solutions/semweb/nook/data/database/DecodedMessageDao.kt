package solutions.semweb.nook.data.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface DecodedMessageDao {
    @Query("SELECT * FROM decoded_messages WHERE is_archived = 0 ORDER BY timestamp DESC")
    fun getAll(): List<DecodedMessageEntity>

    @Query("SELECT * FROM decoded_messages ORDER BY timestamp DESC")
    fun getAllIncludingArchived(): List<DecodedMessageEntity>

    @Query("SELECT * FROM decoded_messages WHERE is_read = 0 AND is_archived = 0 ORDER BY timestamp DESC")
    fun getUnread(): List<DecodedMessageEntity>

    @Query("SELECT * FROM decoded_messages WHERE sender = :encryptedSender ORDER BY timestamp DESC")
    fun getBySender(encryptedSender: String): List<DecodedMessageEntity>

    @Query("SELECT * FROM decoded_messages WHERE success = :success ORDER BY timestamp DESC")
    fun getBySuccess(success: Boolean): List<DecodedMessageEntity>

    @Query("SELECT * FROM decoded_messages WHERE timestamp >= :startDate AND timestamp <= :endDate ORDER BY timestamp DESC")
    fun getByDateRange(startDate: Long, endDate: Long): List<DecodedMessageEntity>

    @Query("SELECT * FROM decoded_messages WHERE message_type = :messageType ORDER BY timestamp DESC")
    fun getByType(messageType: String): List<DecodedMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(message: DecodedMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(messages: List<DecodedMessageEntity>)

    @Update
    fun update(message: DecodedMessageEntity)

    @Delete
    fun delete(message: DecodedMessageEntity)

    @Query("DELETE FROM decoded_messages WHERE id = :id")
    fun deleteById(id: String)

    @Query("SELECT * FROM decoded_messages WHERE id = :id LIMIT 1")
    fun findById(id: String): DecodedMessageEntity?

    @Query("UPDATE decoded_messages SET is_read = 1 WHERE id = :id")
    fun markAsRead(id: String)

    @Query("UPDATE decoded_messages SET is_read = 1 WHERE sender = :encryptedSender")
    fun markAllAsReadFromSender(encryptedSender: String)

    @Query("UPDATE decoded_messages SET is_archived = :archived WHERE id = :id")
    fun setArchived(id: String, archived: Boolean)

    @Query("SELECT COUNT(*) FROM decoded_messages WHERE is_read = 0 AND is_archived = 0")
    fun countUnread(): Int

    @Query("SELECT COUNT(*) FROM decoded_messages WHERE sender = :encryptedSender AND is_read = 0 AND is_archived = 0")
    fun countUnreadFromSender(encryptedSender: String): Int

    @Query("SELECT COUNT(*) FROM decoded_messages")
    fun countAll(): Int

    @Query("SELECT COUNT(*) FROM decoded_messages WHERE success = 1")
    fun countSuccessful(): Int

    @Query("SELECT COUNT(*) FROM decoded_messages WHERE success = 0")
    fun countFailed(): Int

    @Query("DELETE FROM decoded_messages WHERE timestamp < :cutoffDate AND is_archived = 1")
    fun cleanupOldArchived(cutoffDate: Long)

    @Query("DELETE FROM decoded_messages WHERE timestamp < :cutoffDate")
    fun cleanupAllBefore(cutoffDate: Long)

    @Query("SELECT DISTINCT sender FROM decoded_messages WHERE is_archived = 0 ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentSenders(limit: Int = 20): List<String>

    @Query("SELECT * FROM decoded_messages WHERE decoded_message LIKE '%' || :searchTerm || '%' ORDER BY timestamp DESC")
    fun searchInDecoded(searchTerm: String): List<DecodedMessageEntity>

    @Query("SELECT * FROM decoded_messages WHERE original_message LIKE '%' || :searchTerm || '%' ORDER BY timestamp DESC")
    fun searchInOriginal(searchTerm: String): List<DecodedMessageEntity>
}