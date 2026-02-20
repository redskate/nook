package solutions.semweb.nook.data.database

import android.content.Context
import androidx.room.Entity
import androidx.room.PrimaryKey
import solutions.semweb.nook.TrustedContact
import solutions.semweb.nook.crypto.AppCryptoManager

@Entity(tableName = "trusted_contacts")
data class TrustedContactEntity(
    @PrimaryKey
    val contactId: String,
    val phoneNumber: String, // Encrypted
    val displayName: String, // Encrypted
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toDomain(context: Context): TrustedContact {
        // Decrypt data just before you read them from DB
        val decryptedPhone = AppCryptoManager.decrypt64Value(phoneNumber)
        val decryptedName = AppCryptoManager.decrypt64Value(displayName)

        return TrustedContact(
            contactId = contactId,
            phoneNumber = decryptedPhone,
            displayName = decryptedName.takeIf { it.isNotEmpty() },
            isActive = isActive
        )
    }

    companion object {
        fun fromDomain(contact: TrustedContact, context: Context): TrustedContactEntity {
            // Encrypt sensible data before saving
            val encryptedPhone = AppCryptoManager.encrypt64Value(contact.phoneNumber)
            val encryptedName = AppCryptoManager.encrypt64Value(contact.displayName ?: "")

            return TrustedContactEntity(
                contactId = contact.contactId,
                phoneNumber = encryptedPhone,
                displayName = encryptedName,
                isActive = contact.isActive
            )
        }
    }
}