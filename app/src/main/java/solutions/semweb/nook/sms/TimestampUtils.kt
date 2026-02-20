package solutions.semweb.nook.sms

import solutions.semweb.nook.Constants
import solutions.semweb.nook.LogUtils
import solutions.semweb.nook.crypto.BaseXXXUtils
import solutions.semweb.nook.crypto.EncryptionMapper

object TimestampUtils {
    fun extractTimestampFromPrefix(encodedMessage: String, usedEncoding: String): Pair<String, Long?> {
        if (!encodedMessage.startsWith(Constants.SMS_OBF_PREFIX)) {
            return Pair(encodedMessage, null)
        }

        val encodingBase = EncryptionMapper.extractEncodingBase(usedEncoding)
        val timestampWidth = BaseXXXUtils.SecondTimestamp.getTimestampWidthForSeconds(encodingBase)

        val isEncrypted = encodedMessage.length > 2 &&
                encodedMessage.substring(2).startsWith(EncryptionMapper.SISA_ENCR_PREFIX)
        val prefixLength = if (isEncrypted) {
            Constants.SMS_OBF_PREFIX.length + EncryptionMapper.SISA_ENCR_PREFIX.length
        } else {
            Constants.SMS_OBF_PREFIX.length
        }

        if (encodedMessage.length < prefixLength + timestampWidth + 1) {
            return Pair(encodedMessage, null)
        }

        val afterPrefix = encodedMessage.substring(prefixLength)
        val timestampPart = afterPrefix.take(timestampWidth)

        return try {
            val timestamp = BaseXXXUtils.SecondTimestamp.decodeToLong(timestampPart, timestampWidth, encodingBase)
            val remainingMessage = encodedMessage.substring(0, prefixLength) +
                    afterPrefix.substring(timestampWidth + 1)
            Pair(remainingMessage, timestamp)
        } catch (e: Exception) {
            LogUtils.d(null, Constants.SMSTAG, "⚠️ Timestamp extraction failed: ${e.message}")
            Pair(encodedMessage, null)
        }
    }
}