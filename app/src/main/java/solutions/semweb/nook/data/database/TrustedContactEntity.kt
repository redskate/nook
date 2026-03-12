package solutions.semweb.nook.data.database

import android.content.Context
import androidx.room.Entity
import androidx.room.PrimaryKey
import solutions.semweb.nook.LogUtils
import solutions.semweb.nook.TrustedContact
import solutions.semweb.nook.crypto.EncryptionVerifier
import java.nio.charset.StandardCharsets

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
    // READ
    fun toDomain(context: Context): TrustedContact {
        LogUtils.d(null, "DB_CRYPT", "=== DECRYPTION DEBUG ===")
        LogUtils.d(null, "DB_CRYPT", "Contact ID: $contactId")

        val decryptedPhone = DecryptionValidator.safeDecryptNonNull(
            phoneNumber, "phoneNumber", context, "TrustedContact"
        )

        val decryptedName = DecryptionValidator.safeDecryptNonNull(
            displayName, "displayName", context, "TrustedContact"
        )

        return TrustedContact(
            contactId = contactId,
            phoneNumber = decryptedPhone,
            displayName = decryptedName.takeIf { it.isNotEmpty() },
            isActive = isActive
        )
    }

    companion object {
        // WRITE
        fun fromDomain(contact: TrustedContact, context: Context): TrustedContactEntity {
            LogUtils.d(null, "DB_CRYPT", "=== ENCRYPTION DEBUG ===")
            LogUtils.d(null, "DB_CRYPT", "Original - Name: '${contact.displayName}'")
            LogUtils.d(null, "DB_CRYPT", "Original - Phone: '${contact.phoneNumber}'")

            val encryptedName = EncryptionVerifier.encryptAndVerify(
                contact.displayName ?: "", "displayName", "TrustedContact", context
            )
            val encryptedPhone = EncryptionVerifier.encryptAndVerify(
                contact.phoneNumber, "phoneNumber", "TrustedContact", context
            )

            return TrustedContactEntity(
                contactId = contact.contactId,
                phoneNumber = encryptedPhone,
                displayName = encryptedName,
                isActive = contact.isActive
            )
        }

        private fun ensureUtf8(input: String): String {
            return try {
                // Ensure the string is properly UTF-8 encoded
                val bytes = input.toByteArray(StandardCharsets.UTF_8)
                String(bytes, StandardCharsets.UTF_8)
            } catch (e: Exception) {
                input
            }
        }
    }
}