package solutions.semweb.nook

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/*
   * Since GOOGLE (Play Protect) wants to understand that this APP be malware
   * show a persistent notification to contrast this untruth at every instant
   *
   * On Android 16+ a persistent notification seems to be sweapable away
   * but it need to remain well on sight to reminder the user
   * against the malware false positive warning of Play Protect.
   *
   * So we check here for missing "persistent" notification and
   * reissue the same notification again and again after a time.
   *
   * This service will be made switchable OFF through one toggle on the APP settings
   */


class ForegroundService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 2
        private const val TAG = "ForegroundService"
        private const val CHECK_INTERVAL_MS = 10 * 60000L // Check every 10 minutes for missing swiped notification on Android 16+

        // MAKE THIS PUBLIC SO NOTIFICATIONHELPER CAN CALL IT
        fun startService(context: Context) {
            try {
                val intent = Intent(context, ForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                LogUtils.d(TAG, "🔔 Start service intent sent")
            } catch (e: Exception) {
                LogUtils.e(TAG, "Failed to start service", e)
            }
        }
    }

    private lateinit var notificationManager: NotificationManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val notificationCheckRunnable = object : Runnable {
        override fun run() {
            if (!isNotificationActive()) {
                LogUtils.d(TAG, "🔔 Notification missing! Restoring...")
                restoreNotification()
            }
            mainHandler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        LogUtils.d(TAG, "🔔 Service starting on Android API: ${Build.VERSION.SDK_INT}")

        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()

        try {
            val notification = createPersistentNotification()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val foregroundServiceType = if (Build.VERSION.SDK_INT >= 34) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    } else {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    }
                    startForeground(NOTIFICATION_ID, notification, foregroundServiceType)
                } catch (e: Exception) {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            mainHandler.post(notificationCheckRunnable)
            LogUtils.d(TAG, "✅ Service started successfully")

        } catch (e: Exception) {
            LogUtils.e(TAG, "❌ Critical error in service creation", e)
        }
    }

    private fun isNotificationActive(): Boolean {
        return try {
            val notificationManagerCompat = NotificationManagerCompat.from(this)
            val activeNotifications = notificationManagerCompat.activeNotifications
            activeNotifications.any { it.id == NOTIFICATION_ID }
        } catch (e: Exception) {
            LogUtils.e(TAG, "Failed to check notification status", e)
            false
        }
    }

    private fun restoreNotification() {
        try {
            val notification = createPersistentNotification()
            notificationManager.notify(NOTIFICATION_ID, notification)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val foregroundServiceType = if (Build.VERSION.SDK_INT >= 34) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    } else {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    }
                    startForeground(NOTIFICATION_ID, notification, foregroundServiceType)
                } catch (e: Exception) {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            LogUtils.d(TAG, "🔔 Notification restored")
        } catch (e: Exception) {
            LogUtils.e(TAG, "Failed to restore notification", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LogUtils.d(TAG, "🔔 onStartCommand() called")
        restoreNotification()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        LogUtils.d(TAG, "🔔 Task removed - service continues")
        restoreNotification()
    }

    override fun onDestroy() {
        LogUtils.d(TAG, "🔔 Service destroyed - restarting!")
        mainHandler.removeCallbacks(notificationCheckRunnable)

        val restartIntent = Intent(this, ForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(restartIntent)
        } else {
            startService(restartIntent)
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createPersistentNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val iconId = R.mipmap.ic_launcher

        if (Build.VERSION.SDK_INT >= 36) {
            val bigTextStyle = Notification.BigTextStyle()
                .bigText(this.getString(R.string.notification_pamphlet))

            val platformBuilder = Notification.Builder(this, getChannelId())
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_content))
                .setSmallIcon(iconId)
                .setStyle(bigTextStyle)
                .setOngoing(true)
                .setContentIntent(openPendingIntent)
                .setAutoCancel(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)

            val notification = platformBuilder.build()
            notification.flags = notification.flags or Notification.FLAG_NO_CLEAR
            notification.flags = notification.flags or Notification.FLAG_ONGOING_EVENT
            notification.flags = notification.flags or Notification.FLAG_FOREGROUND_SERVICE
            notification.flags = notification.flags or Notification.FLAG_INSISTENT
            return notification
        }

        val bigTextStyle = NotificationCompat.BigTextStyle()
            .setBigContentTitle(getString(R.string.notification_title))
            .bigText(this.getString(R.string.notification_pamphlet))

        val builder = NotificationCompat.Builder(this, getChannelId())
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_content))
            .setSmallIcon(iconId)
            .setStyle(bigTextStyle)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(openPendingIntent)
            .setAutoCancel(false)
            .addAction(android.R.drawable.ic_dialog_info, "📖 APRI NOOK", openPendingIntent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }

        val notification = builder.build()
        notification.flags = notification.flags or Notification.FLAG_NO_CLEAR
        notification.flags = notification.flags or Notification.FLAG_ONGOING_EVENT
        notification.flags = notification.flags or Notification.FLAG_FOREGROUND_SERVICE
        return notification
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = getChannelId()
            val channelName = getString(R.string.notification_channel_name)
            val channelDescription = getString(R.string.notification_channel_description)

            val importance = NotificationManager.IMPORTANCE_LOW

            val channel = NotificationChannel(channelId, channelName, importance).apply {
                description = channelDescription
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
                setBypassDnd(true)
                setLockscreenVisibility(Notification.VISIBILITY_PUBLIC)
            }

            notificationManager.createNotificationChannel(channel)
            LogUtils.d(TAG, "Notification channel created")
        }
    }

    private fun getChannelId(): String {
        return Constants.NOTIFICATION_CHANNEL_ID + "_foreground"
    }
}