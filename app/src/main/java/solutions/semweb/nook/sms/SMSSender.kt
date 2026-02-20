package solutions.semweb.nook.sms

import android.content.Context
import android.telephony.SmsManager
import solutions.semweb.nook.LogUtils
import solutions.semweb.nook.MainActivity
import solutions.semweb.nook.R
import solutions.semweb.nook.SharedPreferencesManager

object SMSSender {

    fun sendSms(context: Context, phoneNumber: String, message: String) {
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