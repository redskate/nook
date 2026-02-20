package solutions.semweb.nook.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface AppSettingDao {
    @Query("SELECT * FROM app_settings WHERE `key` = :key LIMIT 1")
    fun get(key: String): AppSettingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertOrUpdate(setting: AppSettingEntity)

    @Update
    fun update(setting: AppSettingEntity)

    @Query("DELETE FROM app_settings WHERE `key` = :key")
    fun delete(key: String)

    @Query("SELECT value FROM app_settings WHERE `key` = :key")
    fun getValue(key: String): String?

    @Query("SELECT * FROM app_settings")
    fun getAll(): List<AppSettingEntity>
}