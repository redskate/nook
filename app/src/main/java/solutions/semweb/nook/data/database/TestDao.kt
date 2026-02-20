package solutions.semweb.nook.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface TestDao {
    @Insert
    fun insert(entity: TestEntity): Long

    @Query("SELECT * FROM test_messages ORDER BY timestamp DESC")
    fun getAll(): List<TestEntity>

    @Query("DELETE FROM test_messages")
    fun clearAll()
}