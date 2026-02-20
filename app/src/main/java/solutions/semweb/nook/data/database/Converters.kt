// File: Converters.kt
package solutions.semweb.nook.data.database

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Date

/**
 * USED BY ROOMS
 */

class Converters {
    @TypeConverter
    fun fromTimestamp(value: Long?): Date? {
        return value?.let { Date(it) }
    }

    @TypeConverter
    fun dateToTimestamp(date: Date?): Long? {
        return date?.time
    }

    // AGGIUNGI QUESTI:
    @TypeConverter
    fun fromBoolean(value: Boolean?): Int? {
        return value?.let { if (it) 1 else 0 }
    }

    @TypeConverter
    fun toBoolean(value: Int?): Boolean? {
        return value?.let { it == 1 }
    }

    @TypeConverter
    fun fromLong(value: Long?): String? {
        return value?.toString()
    }

    @TypeConverter
    fun toLong(value: String?): Long? {
        return value?.toLongOrNull()
    }

    @TypeConverter
    fun fromFloat(value: Float?): String? {
        return value?.toString()
    }

    @TypeConverter
    fun toFloat(value: String?): Float? {
        return value?.toFloatOrNull()
    }

    @TypeConverter
    fun fromStringList(list: List<String>?): String? {
        return list?.joinToString(",")
    }

    @TypeConverter
    fun toStringList(data: String?): List<String>? {
        return data?.split(",")?.map { it.trim() }
    }

    // Per Map<String, Any> (usato in DecodedMessageEntity)
    @TypeConverter
    fun fromMap(map: Map<String, Any>?): String? {
        return map?.let { Gson().toJson(it) }
    }

    @TypeConverter
    fun toMap(json: String?): Map<String, Any>? {
        return json?.let {
            val type = object : TypeToken<Map<String, Any>>() {}.type
            Gson().fromJson(it, type)
        }
    }
}