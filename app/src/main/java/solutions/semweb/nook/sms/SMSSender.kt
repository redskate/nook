package solutions.semweb.nook.sms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import solutions.semweb.nook.LogUtils
import solutions.semweb.nook.MainActivity
import solutions.semweb.nook.MainActivity.Companion.showToast
import solutions.semweb.nook.PhoneUtils
import solutions.semweb.nook.R
import solutions.semweb.nook.SharedPreferencesManager
import solutions.semweb.nook.chat.SMSQueueManager

object SMSSender {

    // Broadcast receiver action for SMS sent status
    private const val SMS_SENT_ACTION = "solutions.semweb.nook.SMS_SENT"
    private const val SMS_DELIVERED_ACTION = "solutions.semweb.nook.SMS_DELIVERED"
    private const val EXTRA_PHONE_NUMBER = "extra_phone_number"
    private const val EXTRA_MESSAGE_ID = "extra_message_id"

    /**
     * Public method - queues SMS for sending with duplicate detection
     */
    fun sendSms(context: Context, phoneNumber: String, message: String) {
        //check phoneNumber is a real one - or forget request
        if (PhoneUtils.isPhoneNumber(phoneNumber))
        {
            // Queue the SMS - returns false if duplicate and suppressed
            val queued = SMSQueueManager.queueSms(context, phoneNumber, message)

            if (queued) {
                // Show toast for queued message (if not in silent mode)
                if (SharedPreferencesManager.getInstance(context).shouldShowToast()) {
                    MainActivity.showToast(context.getString(R.string.sms_queued))
                }
            }
        }
        else
            showToast(context.getString(R.string.sms_not_sent_bad_phonenumber), true)
    }

    /**
     * Internal method - actually sends the SMS
     * This should only be called from SMSQueueManager
     */
    fun sendSmsDirect(context: Context, phoneNumber: String, message: String) {
        try {
            val smsManager: SmsManager = SmsManager.getDefault()
            val parts = smsManager.divideMessage(message)

            // Create PendingIntents for each part
            val sentIntents = ArrayList<PendingIntent>()
            val deliveryIntents = ArrayList<PendingIntent>()

            val messageId = System.currentTimeMillis().toString()

            for (i in parts.indices) {
                // Create sent intent for this part
                val sentIntent = PendingIntent.getBroadcast(
                    context,
                    (messageId + i).hashCode(), // Unique request code for each part
                    Intent(SMS_SENT_ACTION).apply {
                        putExtra(EXTRA_PHONE_NUMBER, phoneNumber)
                        putExtra(EXTRA_MESSAGE_ID, messageId)
                        putExtra("part_index", i)
                        putExtra("total_parts", parts.size)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                sentIntents.add(sentIntent)

                // Create delivery intent (optional)
                val deliveryIntent = PendingIntent.getBroadcast(
                    context,
                    (messageId + i).hashCode() + parts.size,
                    Intent(SMS_DELIVERED_ACTION).apply {
                        putExtra(EXTRA_PHONE_NUMBER, phoneNumber)
                        putExtra(EXTRA_MESSAGE_ID, messageId)
                        putExtra("part_index", i)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                deliveryIntents.add(deliveryIntent)
            }

            // Send multipart message with pending intents
            smsManager.sendMultipartTextMessage(
                phoneNumber,
                null,
                parts,
                sentIntents,
                deliveryIntents
            )

            showToast(context.getString(R.string.sending_sms), false)
            // Log
            LogUtils.d(context, "SmsSender", "SMS sending initiated to: $phoneNumber (ID: $messageId, parts: ${parts.size})")
            LogUtils.d(context, "SmsSender", "SMS content: $message")

        } catch (e: Exception) {
            LogUtils.e(context, "SmsSender", "Error initiating SMS sending", e)
            MainActivity.showToast(context.getString(R.string.sms_send_error, e.message ?: ""))
        }
    }
}