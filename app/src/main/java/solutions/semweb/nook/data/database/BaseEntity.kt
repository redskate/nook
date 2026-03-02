// File: BaseEntity.kt
package solutions.semweb.nook.data.database

import android.content.Context

/**
 * Interface that all Entity classes should implement
 * to ensure consistent decryption handling
 */
interface BaseEntity<T> {
    fun toDomain(context: Context): T

    companion object {
        // Helper for entities to access the validator
        val validator get() = DecryptionValidator
    }
}