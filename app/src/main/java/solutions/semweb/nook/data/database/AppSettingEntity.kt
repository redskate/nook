package solutions.semweb.nook.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import solutions.semweb.nook.crypto.AppCryptoManager

@Entity(
    tableName = "app_settings",
    indices = [Index(value = ["key"], unique = true)]
)
data class AppSettingEntity(
    @PrimaryKey
    val key: String, // Encrypted with deterministic encryptKey
    val value: String, // Encrypted with encryptValue (non deterministic)
    @ColumnInfo(name = "value_type")
    val valueType: String = "string", // string, boolean, int, long, float
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        fun create(key: String, value: String, valueType: String = "string", context: android.content.Context): AppSettingEntity {
            // Encrypt key with encryptKey (deterministic)
            val encryptedKey = AppCryptoManager.encrypt64Key(key)
            // Encrypt value with encryptValue (non deterministic)
            val encryptedValue = AppCryptoManager.encrypt64Value(value)

            return AppSettingEntity(
                key = encryptedKey,
                value = encryptedValue,
                valueType = valueType
            )
        }
    }

    fun getDecryptedValue(context: android.content.Context): String {
        return AppCryptoManager.decrypt64Value(value)
    }

    fun getValueAsBoolean(context: android.content.Context): Boolean {
        return getDecryptedValue(context).toBooleanStrictOrNull() ?: false
    }

    fun getValueAsInt(context: android.content.Context): Int {
        return getDecryptedValue(context).toIntOrNull() ?: 0
    }

    fun getValueAsLong(context: android.content.Context): Long {
        return getDecryptedValue(context).toLongOrNull() ?: 0L
    }

    fun getValueAsFloat(context: android.content.Context): Float {
        return getDecryptedValue(context).toFloatOrNull() ?: 0f
    }
}