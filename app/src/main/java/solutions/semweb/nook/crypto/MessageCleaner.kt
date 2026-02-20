package solutions.semweb.nook.crypto

import solutions.semweb.nook.LogUtils


object MessageCleaner {

    fun cleanForDecoding(message: String): String {
        return try {
            var cleaned = message.trim()

            LogUtils.d(null, "MessageCleaner", "🧹 Cleaning message...")

            // 2. Controlla se è SiSa
            if (cleaned.startsWith(EncryptionMapper.SISA_ENCR_PREFIX)) {
                LogUtils.d(null, "MessageCleaner", "  🔐 SiSa prefix detected")
                return cleaned.substring(EncryptionMapper.SISA_ENCR_PREFIX.length).trim()
            }

            // 4. Handle newline or special chars
            cleaned = cleaned
                .replace("\r\n", " ")
                .replace("\n", " ")
                .replace("\r", " ")
                .replace("\t", " ")

            // 5. verify emptiness
            if (cleaned.isEmpty()) {
                LogUtils.w(null, "MessageCleaner", "  ⚠️ Empty message after cleaning")
                return message.trim() // Return cleaned message
            }

            LogUtils.d(null, "MessageCleaner", "  ✅ Cleaned: '${cleaned.take(50)}...'")
            LogUtils.d(null, "MessageCleaner", "  Cleaned length: ${cleaned.length}")

            cleaned

        } catch (e: Exception) {
            LogUtils.e(null, "MessageCleaner", "❌ Error during message cleaning", e)
            message.trim() // Fallback: return original
        }
    }

}