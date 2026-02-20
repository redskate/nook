package solutions.semweb.nook.data.database

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import solutions.semweb.nook.BuildConfig
import solutions.semweb.nook.LogUtils
import solutions.semweb.nook.crypto.AppCryptoManager
import java.io.File

@Database(
    entities = [
        TestEntity::class,
        TrustedContactEntity::class,
        ChatConversationEntity::class,
        ChatMessageEntity::class,
        AppSettingEntity::class,
        DecodedMessageEntity::class
    ],
    version = 8, // Increment at every schema change
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun testDao(): TestDao
    abstract fun trustedContactDao(): TrustedContactDao
    abstract fun chatConversationDao(): ChatConversationDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun appSettingDao(): AppSettingDao
    abstract fun decodedMessageDao(): DecodedMessageDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val dbName = getEncryptedDatabaseName(context)
                val dbFile = File(context.filesDir, dbName)

                LogUtils.d(null,"AppDatabase", "🔐 Database path: ${dbFile.absolutePath}")
                LogUtils.d(null,"AppDatabase", "🔐 File exists: ${dbFile.exists()}")

                val builder = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    dbFile.absolutePath
                )
                    // MIGRATIONS
                    // Keep fallback as safety net
                    // It were so nice when it functioned....

                // ⭐⭐ SWL LOGGING: ⭐⭐
                if (BuildConfig.DEBUG) {
                    builder.setQueryCallback(
                        androidx.room.RoomDatabase.QueryCallback { sqlQuery, bindArgs ->
                            LogUtils.d(null,"ROOM_SQL", "📝 SQL: $sqlQuery")
                            if (bindArgs.isNotEmpty()) {
                                LogUtils.d(null,"ROOM_SQL", "🔢 Args: ${bindArgs.joinToString(", ")}")
                            }
                        },
                        java.util.concurrent.Executors.newSingleThreadExecutor()
                    )
                }

                // Callback per logging
                builder.addCallback(object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        LogUtils.d(null,"AppDatabase", "✅ Database CREATED from scratch")

                        // Log all created tables
                        db.query("SELECT name FROM sqlite_master WHERE type='table'").use { cursor ->
                            val tables = mutableListOf<String>()
                            while (cursor.moveToNext()) {
                                tables.add(cursor.getString(0))
                            }
                            LogUtils.d(null,"AppDatabase", "📊 ${tables.joinToString(", ")} Tables created")
                        }
                    }

                    override fun onOpen(db: SupportSQLiteDatabase) {
                        super.onOpen(db)
                        LogUtils.d(null,"AppDatabase", "✅ Database OPEN")

                        // Verify integrity
                        try {
                            db.query("PRAGMA integrity_check").use { cursor ->
                                if (cursor.moveToFirst()) {
                                    val result = cursor.getString(0)
                                    LogUtils.d(null,"AppDatabase", "🔍 Integrity check: $result")
                                }
                            }
                        } catch (e: Exception) {
                            LogUtils.e("AppDatabase", "❌ Integrity check failed", e)
                        }
                    }

                    override fun onDestructiveMigration(db: SupportSQLiteDatabase) {
                        super.onDestructiveMigration(db)
                        Log.w("AppDatabase", "⚠️ Destructive migration executed!")
                    }
                })

                val instance = builder.build()
                INSTANCE = instance

                // Immediate connection test
                try {
                    val dbConn = instance.openHelper.writableDatabase
                    LogUtils.d(null,"AppDatabase", "✅ Database connection assessed")

                    dbConn.query("SELECT sqlite_version()").use { cursor ->
                        if (cursor.moveToFirst()) {
                            val version = cursor.getString(0)
                            LogUtils.d(null,"AppDatabase", "📊 SQLite version: $version")
                        }
                    }

                    // Count tables
                    dbConn.query("SELECT COUNT(*) FROM sqlite_master WHERE type='table'").use { cursor ->
                        if (cursor.moveToFirst()) {
                            val tableCount = cursor.getInt(0)
                            LogUtils.d(null,"AppDatabase", "📊 Number of tables: $tableCount")
                        }
                    }

                } catch (e: Exception) {
                    LogUtils.e("AppDatabase", "❌ Error connection test", e)
                }

                instance
            }
        }

        fun getEncryptedDatabaseName(context: Context): String {
            // Create a database name encrypted on master key
            return if (AppCryptoManager.isEncryptionActive()) {
                val plainName = "nook_encrypted_db"
                val encrypted = AppCryptoManager.encrypt64Key(plainName)
                "$encrypted.db"
            } else {
                "nook_secure.db"
            }
        }
    }
}