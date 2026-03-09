package solutions.semweb.nook.data.database

import android.content.Context
import androidx.room.Entity
import androidx.room.PrimaryKey
import solutions.semweb.nook.LogUtils
import solutions.semweb.nook.TrustedContact
import solutions.semweb.nook.crypto.AppCryptoManager
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
        LogUtils.d(null, "DB_CRYPT", "Encrypted Phone (first 30): ${phoneNumber.take(30)}...")
        LogUtils.d(null, "DB_CRYPT", "Encrypted Name (first 30): ${displayName.take(30)}...")

        val decryptedPhone: String
        val decryptedName: String

        try {
            // Try to decrypt phone with detailed error handling
            decryptedPhone = try {
                val decrypted = AppCryptoManager.decrypt64Value(phoneNumber)
                LogUtils.d(null, "DB_CRYPT", "✅ Phone decrypted successfully")
                LogUtils.d(null, "DB_CRYPT", "   Decrypted phone: '$decrypted'")

                // Log hex of decrypted data
                val decryptedBytes = decrypted.toByteArray(Charsets.UTF_8)
                LogUtils.d(null, "DB_CRYPT",
                    "   Phone UTF-8 bytes: ${decryptedBytes.joinToString(" ") { "0x%02X".format(it) }}")

                decrypted
            } catch (e: Exception) {
                LogUtils.e("DB_CRYPT", "❌ Phone decryption failed", e)
                throw e
            }

            // Try to decrypt name with detailed error handling
            decryptedName = try {
                val decrypted = AppCryptoManager.decrypt64Value(displayName)
                LogUtils.d(null, "DB_CRYPT", "✅ Name decrypted successfully")
                LogUtils.d(null, "DB_CRYPT", "   Decrypted name: '$decrypted'")

                // Log hex of decrypted data
                val decryptedBytes = decrypted.toByteArray(Charsets.UTF_8)
                LogUtils.d(null, "DB_CRYPT",
                    "   Name UTF-8 bytes: ${decryptedBytes.joinToString(" ") { "0x%02X".format(it) }}")

                decrypted
            } catch (e: Exception) {
                LogUtils.e("DB_CRYPT", "❌ Name decryption failed", e)
                throw e
            }

        } catch (e: Exception) {
            LogUtils.e("DB_CRYPT", "❌ Decryption failed completely", e)

            // Attempt to see the raw decrypted bytes even if string conversion fails
            try {
                val encryptedBytes = android.util.Base64.decode(phoneNumber, android.util.Base64.DEFAULT)
                LogUtils.e("DB_CRYPT",
                    "Raw encrypted phone bytes: ${encryptedBytes.joinToString(" ") { "0x%02X".format(it) }}")
            } catch (be: Exception) {
                // Ignore
            }

            throw e
        }

        // Validate phone number format
        if (!isValidPhoneNumber(decryptedPhone)) {
            LogUtils.e("DB_CRYPT",
                "❌ Decrypted phone number is invalid: '$decryptedPhone'")

            // Show what we got
            LogUtils.e("DB_CRYPT",
                "Characters: ${decryptedPhone.map { it.code }}")

            throw IllegalStateException("Invalid phone number after decryption")
        }

        return TrustedContact(
            contactId = contactId,
            phoneNumber = decryptedPhone,
            displayName = decryptedName.takeIf { it.isNotEmpty() },
            isActive = isActive
        )
    }

    private fun safeDecryptWithValidation(encryptedValue: String, fieldName: String, context: Context): String {
        return try {
            val decrypted = AppCryptoManager.decrypt64Value(encryptedValue)

            // Verify it's valid UTF-8
            if (isValidUtf8(decrypted)) {
                decrypted
            } else {
                LogUtils.d(null, "TrustedContactEntity", "⚠️ Decrypted $fieldName is not valid UTF-8, attempting repair")
                repairUtf8String(decrypted)
            }
        } catch (e: Exception) {
            LogUtils.e(null, "TrustedContactEntity",
                "❌ Failed to decrypt $fieldName", e)
            throw e
        }
    }

    private fun isValidUtf8(str: String): Boolean {
        return try {
            // Try to encode and decode back to check for corruption
            val bytes = str.toByteArray(StandardCharsets.UTF_8)
            val reconstructed = String(bytes, StandardCharsets.UTF_8)
            str == reconstructed
        } catch (e: Exception) {
            false
        }
    }

    private fun repairUtf8String(corrupted: String): String {
        return try {
            // Try to recover by forcing UTF-8 interpretation
            val bytes = corrupted.toByteArray(Charsets.ISO_8859_1)
            String(bytes, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            corrupted // Return original if repair fails
        }
    }

    private fun forceUtf8Recovery(str: String): String {
        return try {
            // More aggressive recovery for phone numbers
            val bytes = str.toByteArray(Charsets.ISO_8859_1)
            val utf8String = String(bytes, StandardCharsets.UTF_8)

            // Extract only valid phone number characters
            utf8String.filter { it.isDigit() || it == '+' }
        } catch (e: Exception) {
            str
        }
    }

    private fun isValidPhoneNumber(phone: String): Boolean {
        return phone.isNotBlank() &&
                phone.all { it.isDigit() || it == '+' || it == ' ' || it == '-' || it == '(' || it == ')' }
    }

    companion object {
        // WRITE
        fun fromDomain(contact: TrustedContact, context: Context): TrustedContactEntity {
            // Log the original data with hex dump for special characters
            LogUtils.d(null, "DB_CRYPT", "=== ENCRYPTION DEBUG ===")
            LogUtils.d(null, "DB_CRYPT", "Original - Name: '${contact.displayName}'")
            LogUtils.d(null, "DB_CRYPT", "Original - Phone: '${contact.phoneNumber}'")

            // Log character by character for debugging
            contact.displayName?.forEachIndexed { index, char ->
                LogUtils.d(null, "DB_CRYPT",
                    "  Name char[$index]: '$char' (code: ${char.code}, hex: 0x${char.code.toString(16)})")
            }

            // Log the UTF-8 bytes
            val nameBytes = (contact.displayName ?: "").toByteArray(Charsets.UTF_8)
            LogUtils.d(null, "DB_CRYPT",
                "Name UTF-8 bytes (${nameBytes.size}): ${nameBytes.joinToString(" ") { "0x%02X".format(it) }}")

            // Encrypt
            val encryptedName = AppCryptoManager.encrypt64Value(contact.displayName ?: "")
            val encryptedPhone = AppCryptoManager.encrypt64Value(contact.phoneNumber)

            // Log encrypted result
            LogUtils.d(null, "DB_CRYPT",
                "Encrypted Name (Base64, length ${encryptedName.length}): ${encryptedName.take(30)}...")

            // Test decryption immediately to verify
            try {
                val testDecryptName = AppCryptoManager.decrypt64Value(encryptedName)
                LogUtils.d(null, "DB_CRYPT", "Test decrypt Name: '$testDecryptName'")

                if (testDecryptName != contact.displayName) {
                    LogUtils.e("DB_CRYPT",
                        "❌ IMMEDIATE DECRYPTION MISMATCH!")
                    LogUtils.e("DB_CRYPT",
                        "  Original: '${contact.displayName}' (${contact.displayName?.length} chars)")
                    LogUtils.e("DB_CRYPT",
                        "  Decrypted: '$testDecryptName' (${testDecryptName.length} chars)")

                    // Hex dump both
                    val originalHex = (contact.displayName ?: "").toByteArray(Charsets.UTF_8)
                        .joinToString("") { "%02x".format(it) }
                    val decryptedHex = testDecryptName.toByteArray(Charsets.UTF_8)
                        .joinToString("") { "%02x".format(it) }
                    LogUtils.e("DB_CRYPT", "  Original hex: $originalHex")
                    LogUtils.e("DB_CRYPT", "  Decrypted hex: $decryptedHex")
                }
            } catch (e: Exception) {
                LogUtils.e("DB_CRYPT", "❌ Test decryption failed immediately!", e)
            }

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