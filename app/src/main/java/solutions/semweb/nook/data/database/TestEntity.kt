package solutions.semweb.nook.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "test_messages")
data class TestEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val text: String = "",
    val timestamp: Long = System.currentTimeMillis()
)