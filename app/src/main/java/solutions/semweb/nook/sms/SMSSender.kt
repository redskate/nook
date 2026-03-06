package solutions.semweb.nook.sms

import android.content.Context
import android.telephony.SmsManager
import solutions.semweb.nook.LogUtils
import solutions.semweb.nook.MainActivity
import solutions.semweb.nook.R
import solutions.semweb.nook.SharedPreferencesManager
import solutions.semweb.nook.chat.SMSQueueManager

object SMSSender {

    /**
     * Public method - queues SMS for sending with duplicate detection
     */
    fun sendSms(context: Context, phoneNumber: String, message: String) {
        // Queue the SMS - returns false if duplicate and suppressed
        val queued = SMSQueueManager.queueSms(context, phoneNumber, message)

        if (queued) {
            // Show toast for queued message (if not in silent mode)
            if (SharedPreferencesManager.getInstance(context).shouldShowToast()) {
                MainActivity.showToast(context.getString(R.string.sms_queued))
            }
        }
    }

    /**
     * Internal method - actually sends the SMS
     * This should only be called from SMSQueueManager
     */
    fun sendSmsDirect(context: Context, phoneNumber: String, message: String) {
        try {
            val smsManager: SmsManager = SmsManager.getDefault()

            // send always multipart
            smsManager.sendMultipartTextMessage(
                phoneNumber,
                null,
                smsManager.divideMessage(message),
                null,
                null
            )

            // Log
            LogUtils.d(context, "SmsSender", "SMS sent to: $phoneNumber")
            LogUtils.d(context, "SmsSender", "SMS content: $message")

            if (SharedPreferencesManager.getInstance(context).shouldShowToast()) {
                MainActivity.showToast(context.getString(R.string.sms_sent))
            }

        } catch (e: Exception) {
            LogUtils.e(context, "SmsSender", "Error SMS sending", e)
            MainActivity.showToast(context.getString(R.string.sms_send_error, e.message ?: ""))
        }
    }
}