package solutions.semweb.nook

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat

object NotificationHelper {
    private const val TAG = "NotificationHelper"

    fun startForegroundNotification(context: Context) {
        try {
            Log.d(TAG, "Starting foreground service...")

            // Check notification permission for Android 13+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                    Log.d(TAG, "Notification permission not granted")
                    return
                }
            }

            // Simply start the service - let the service handle the rest
            ForegroundService.startService(context)
            Log.d(TAG, "Service start intent sent")

        } catch (e: Exception) {
            LogUtils.e(TAG, "Error starting service", e)
        }
    }
}