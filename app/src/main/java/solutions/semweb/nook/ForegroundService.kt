package solutions.semweb.nook

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

class ForegroundService : Service() {

    companion object {
        const val NOTIFICATION_ID = 2001
        private const val TAG = "ForegroundService"

        // This is the missing method!
        fun startService(context: Context) {
            try {
                val intent = Intent(context, ForegroundService::class.java)
                context.startService(intent)
                LogUtils.e(TAG, "🚀 Starting ForegroundService")
            } catch (e: Exception) {
                LogUtils.e(TAG, "❌ Error starting service", e)
            }
        }
    }

    private lateinit var notificationManager: NotificationManager
    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "${Constants.mainpackage}.STOP_FOREGROUND_SERVICE") {
                LogUtils.e(TAG, "📡 Received stop broadcast")
                stopForegroundService()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        LogUtils.e(TAG, "🟢 ForegroundService created")

        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()

        // Register receiver for stop commands
        registerStopReceiver()
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerStopReceiver() {
        try {
            val filter = IntentFilter("${Constants.mainpackage}.STOP_FOREGROUND_SERVICE")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(stopReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(stopReceiver, filter)
            }

            LogUtils.e(TAG, "✅ Stop receiver registered")
        } catch (e: Exception) {
            LogUtils.e(TAG, "❌ Error registering receiver", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LogUtils.e(TAG, "▶️ ForegroundService onStartCommand")

        // Create and show notification with ORIGINAL text
        startForeground(NOTIFICATION_ID, createPersistentNotification())

        return START_STICKY
    }

    private fun stopForegroundService() {
        LogUtils.e(TAG, "🛑 Stopping foreground service internally")

        // 1. Stop foreground with REMOVE flag
        stopForeground(STOP_FOREGROUND_REMOVE)

        // 2. Cancel notification
        val notificationManager = NotificationManagerCompat.from(this)
        notificationManager.cancel(NOTIFICATION_ID)

        // 3. Stop self
        stopSelf()
    }

    override fun onDestroy() {
        LogUtils.e(TAG, "💥 ForegroundService onDestroy")

        // Force remove notification
        stopForeground(STOP_FOREGROUND_REMOVE)
        val notificationManager = NotificationManagerCompat.from(this)
        notificationManager.cancel(NOTIFICATION_ID)

        // Unregister receiver
        try {
            unregisterReceiver(stopReceiver)
            LogUtils.e(TAG, "✅ Stop receiver unregistered")
        } catch (e: Exception) {
            LogUtils.e(TAG, "⚠️ Error unregistering receiver", e)
        }

        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        LogUtils.e(TAG, "🗑️ Task removed - app swiped away")
        stopForegroundService()
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ORIGINAL notification creation with pamphlet text
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