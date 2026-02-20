package solutions.semweb.nook.sound

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import solutions.semweb.nook.Constants
import solutions.semweb.nook.LogUtils
import solutions.semweb.nook.MainActivity
import solutions.semweb.nook.R
import solutions.semweb.nook.SharedPreferencesManager

/**
 * Manages ops around sounds and notifications
 */

class SoundManagement(private val context: Context) {

    private val prefs = SharedPreferencesManager.getInstance(context)
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        private const val TAG = "SoundManagement"
        private const val TEST_NOTIFICATION_ID = 9999
    }

    // ====================================================================
    // METHODS
    // ====================================================================

    /**
     * Loads sound preferences and update the notification channel
     */
    fun loadNotificationSoundPreference(): Pair<String?, Boolean> {
        LogUtils.d(context, TAG, "🔔 Loading sound prefs")

        val vibrationEnabled = prefs.vibrationEnabled
        val soundUri = prefs.notificationSoundUri
        LogUtils.d(context, TAG, "🔔 URI saved: $soundUri")

        updateMainNotificationChannel()

        return Pair(soundUri, vibrationEnabled)
    }

    /**
     * Update display of sound name
     */
    fun updateSoundNameDisplay(soundUri: String?): String {
        return if (soundUri.isNullOrEmpty() || soundUri == Constants.DEFAULT_NOTIFICATION_SOUND_URI) {
            LogUtils.d(context, TAG, "🔔 Sound: Predefined (only SMS system sound)")
            context.getString(R.string.default_sound)
        } else {
            try {
                val ringtone = RingtoneManager.getRingtone(context, Uri.parse(soundUri))
                val displayName = ringtone?.getTitle(context) ?: extractSimpleName(soundUri)

                // Shorten name if too long
                val maxLength = 15
                val abbreviatedName = if (displayName.length > maxLength) {
                    displayName.take(maxLength) + "..."
                } else {
                    displayName
                }

                LogUtils.d(context, TAG, "🔔 Sound: $displayName (extra sound + SMS sound)")
                abbreviatedName
            } catch (e: Exception) {
                LogUtils.e(context, TAG, "❌ Error parsing sound name", e)
                extractSimpleName(soundUri)
            }
        }
    }

    /**
     * Extracts a simple name from sound URI
     */
    private fun extractSimpleName(soundUri: String): String {
        return try {
            val fileName = soundUri.substringAfterLast("/")
            val simpleName = fileName
                .replace("_", " ")
                .replace(".mp3", "")
                .replace(".ogg", "")
                .replace(".wav", "")
                .replace(".aac", "")
                .trim()

            // Shorten up if too long
            if (simpleName.length > 15) {
                simpleName.take(15) + "..."
            } else {
                simpleName
            }
        } catch (e: Exception) {
            context.getString(R.string.personalized_m)
        }
    }

    /**
     * Show sound selection dialog
     */
    fun showSoundSelectionDialog(activity: Activity) {
        LogUtils.d(context, TAG, "🎵 Opening dialog for sound selection")

        try {
            openSoundPicker(activity)
        } catch (e: Exception) {
            LogUtils.e(context, TAG, "❌ Error opening sound picker", e)
            AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.error))
                .setMessage(context.getString(R.string.imposs_opening_sound_picker)+": ${e.message}")
                .setPositiveButton(context.getString(R.string.ok), null)
                .show()
        }
    }

    /**
     * Manages result sound of sound picker
     */
    fun handleSoundSelectionResult(uri: Uri?, activity: Activity) {
        if (uri != null) {
            prefs.notificationSoundUri = uri.toString()

            // play selected sound to confirm
            try {
                val ringtone = RingtoneManager.getRingtone(context, uri)
                ringtone.play()

                Handler(Looper.getMainLooper()).postDelayed({
                    ringtone.stop()
                }, 2000)

                MainActivity.showToast(context.getString(R.string.notif_sound_modified))

                LogUtils.d(context, TAG, "🔔 Notification sound saved: $uri")
                LogUtils.d(context, TAG, "🔔 URI saved in prefs: ${prefs.notificationSoundUri}")
            } catch (e: Exception) {
                LogUtils.e(context, TAG, "Error reproducing sound", e)
                MainActivity.showToast(context.getString(R.string.error_imposs_reprod_sound))
            }

            updateMainNotificationChannel()
        } else {
            prefs.notificationSoundUri = ""
            MainActivity.showToast(context.getString(R.string.error_silent_notifications))
            LogUtils.d(context, TAG, "🔕 Suono notifica disattivato")

            updateMainNotificationChannel()
        }
    }

    /**
     * Open sound picker
     */
    private fun openSoundPicker(activity: Activity) {
        LogUtils.d(context, TAG, "🎵 Opening RingtonePicker")

        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, context.getString(R.string.sound_selection_dialog_title))

            val currentUri = prefs.notificationSoundUri
            LogUtils.d(context, TAG, "🎵 Current URI: $currentUri")

            if (currentUri.isNotEmpty() && currentUri != Constants.DEFAULT_NOTIFICATION_SOUND_URI) {
                try {
                    val uri = Uri.parse(currentUri)
                    putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, uri)
                    LogUtils.d(context, TAG, "🎵 URI set: $uri")
                } catch (e: Exception) {
                    LogUtils.e(context, TAG, "❌ Error parsing URI", e)
                }
            }

            putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, Settings.System.DEFAULT_NOTIFICATION_URI)
        }

        val packageManager = context.packageManager
        if (intent.resolveActivity(packageManager) != null) {
            LogUtils.d(context, TAG, "✅ Activity disponibile, avvio...")
            if (activity is MainActivitySoundPicker) {
                activity.launchSoundPicker(intent)
            } else {
                activity.startActivityForResult(intent, Constants.REQUEST_CODE_SOUND_PICKER)
            }
        } else {
            LogUtils.e(context, TAG, "❌ No activity available for ACTION_RINGTONE_PICKER")

            MainActivity.showToast(context.getString(R.string.imposs_opening_sound_picker))
        }
    }

    /**
     * Generates a test notification to test current settings
     */
    fun simulateTestNotification() {
        try {
            val vibrationEnabled = prefs.vibrationEnabled
            val soundUriString = prefs.notificationSoundUri

            LogUtils.d(context, TAG, "🎵 Notification test with settings:")
            LogUtils.d(context, TAG, "  - Vibration: $vibrationEnabled")
            LogUtils.d(context, TAG, "  - URI sound: $soundUriString")

            val soundUri = when {
                soundUriString.isEmpty() -> null
                soundUriString == Constants.DEFAULT_NOTIFICATION_SOUND_URI ->
                    Settings.System.DEFAULT_NOTIFICATION_URI
                else -> {
                    try {
                        soundUriString.toUri()
                    } catch (e: Exception) {
                        LogUtils.e(context, TAG, "❌ Error parsing URI sound name", e)
                        null
                    }
                }
            }

            // Use an ID of unique channel to avoid conflicts
            val channelId = "test_channel_${System.currentTimeMillis()}"
            LogUtils.d(context, TAG, "🔔 ID channel: $channelId")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Delete possible old channels of lod tests
                notificationManager.deleteNotificationChannel("test_channel")

                val channel = NotificationChannel(
                    channelId,
                    "Test NooK",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Test Channel for NooK Notifications"

                    // Configure vibration depending on current settings
                    enableVibration(vibrationEnabled)
                    if (vibrationEnabled) {
                        vibrationPattern = longArrayOf(0, 500, 250, 500)
                        LogUtils.d(context, TAG, "📳 Vibration configured")
                    } else {
                        // Disable vibration
                        enableVibration(false)
                        vibrationPattern = null
                        LogUtils.d(context, TAG, "📳 Vibration disabled")
                    }

                    enableLights(true)
                    lightColor = Color.GREEN

                    if (soundUri != null) {
                        setSound(
                            soundUri,
                            AudioAttributes.Builder()
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                                .build()
                        )
                        LogUtils.d(context, TAG, "🔔 Sound configured: $soundUri")
                    } else {
                        // No sound
                        setSound(null, null)
                        LogUtils.d(context, TAG, "🔕 Sound disabled")
                    }
                }
                notificationManager.createNotificationChannel(channel)
            }

            val notificationBuilder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, channelId)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context)
            }

            notificationBuilder
                .setContentTitle(context.getString(R.string.test_nook_notification))
                .setContentText(context.getString(R.string.test_sound_and_vibration, updateSoundNameDisplay(soundUriString), if (vibrationEnabled) "ON" else "OFF" ))
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setPriority(Notification.PRIORITY_HIGH)
                .setAutoCancel(true)

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                if (soundUri != null) {
                    @Suppress("DEPRECATION")
                    notificationBuilder.setSound(soundUri)
                }
                if (vibrationEnabled) {
                    @Suppress("DEPRECATION")
                    notificationBuilder.setVibrate(longArrayOf(0, 500, 250, 500))
                } else {
                    // Disabilita vibrazione su Android vecchio
                    @Suppress("DEPRECATION")
                    notificationBuilder.setVibrate(null)
                }
            }

            notificationManager.notify(TEST_NOTIFICATION_ID, notificationBuilder.build())

            MainActivity.showToast(context.getString(R.string.test_notification_sent)+"!")

            // Log di conferma
            LogUtils.d(context, TAG, "✅ Test notification sent")

        } catch (e: Exception) {
            LogUtils.e(context, TAG, "❌ Error notification test", e)

            MainActivity.showToast(context.getString(R.string.test_notification_error)+": ${e.message}")
        }
    }

    /**
     * Update the main notification channel using current settings
     */
    fun updateMainNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val channelId = Constants.NOTIFICATION_CHANNEL_ID
                val channelName = context.getString(R.string.notification_channel_name)

                // read settings
                val vibrationEnabled = prefs.vibrationEnabled
                val soundUriString = prefs.notificationSoundUri

                val soundUri = when {
                    soundUriString.isEmpty() -> null
                    soundUriString == Constants.DEFAULT_NOTIFICATION_SOUND_URI ->
                        Settings.System.DEFAULT_NOTIFICATION_URI
                    else -> {
                        try {
                            soundUriString.toUri()
                        } catch (e: Exception) {
                            LogUtils.e(context, TAG, "❌ Errore parsing URI suono", e)
                            null
                        }
                    }
                }

                LogUtils.d(context, TAG, "🔄 Update notification channel:")
                LogUtils.d(context, TAG, "  - ID: $channelId")
                LogUtils.d(context, TAG, "  - Vibration: $vibrationEnabled")
                LogUtils.d(context, TAG, "  - Sound: $soundUri")

                // Delete existing channel
                notificationManager.deleteNotificationChannel(channelId)

                // Create new updated channel
                val channel = NotificationChannel(
                    channelId,
                    channelName,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = context.getString(R.string.notification_channel_description)

                    // Configure vibration
                    enableVibration(vibrationEnabled)
                    if (vibrationEnabled) {
                        vibrationPattern = longArrayOf(0, 500, 250, 500)
                    }

                    // Configure sound
                    if (soundUri != null) {
                        setSound(
                            soundUri,
                            AudioAttributes.Builder()
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                                .build()
                        )
                    }

                    enableLights(true)
                    lightColor = Color.GREEN
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }

                notificationManager.createNotificationChannel(channel)
                LogUtils.d(context, TAG, "✅ Notification channel updated")

            } catch (e: Exception) {
                LogUtils.e(context, TAG, "❌ Error updating notification channel", e)
            }
        }
    }

}

/**
 * Interface sound picker
 */
interface MainActivitySoundPicker {
    fun launchSoundPicker(intent: Intent)
}