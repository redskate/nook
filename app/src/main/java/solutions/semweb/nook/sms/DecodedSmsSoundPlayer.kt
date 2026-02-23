// File: DecodedSmsSoundPlayer.kt (versione semplificata)
package solutions.semweb.nook.sms

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import solutions.semweb.nook.Constants
import solutions.semweb.nook.LogUtils
import solutions.semweb.nook.SharedPreferencesManager

/**
 * Utility class to play sound and vibration for successfully decoded SMS messages
 */
object DecodedSmsSoundPlayer {
    private const val TAG = "DecodedSmsSoundPlayer"

    fun playDecodedSmsSound(context: Context) {
        try {
            val prefs = SharedPreferencesManager.getInstance(context)

            // 1. Handle VIBRATION if enabled
            if (prefs.vibrationEnabled) {
                triggerVibration(context)
            }

            // 2. Handle SOUND (only if custom sound is set)
            val soundUriString = prefs.notificationSoundUri
            if (soundUriString.isNotEmpty() && soundUriString != Constants.DEFAULT_NOTIFICATION_SOUND_URI) {
                playCustomSound(context, soundUriString)
            }

        } catch (e: Exception) {
            LogUtils.e(context, TAG, "❌ Error playing decoded SMS sound/vibration", e)
        }
    }

    private fun triggerVibration(context: Context) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            if (!vibrator.hasVibrator()) return

            val pattern = longArrayOf(0, 500, 250, 500) // vibra, pausa, vibra

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }

            LogUtils.d(context, TAG, "📳 Vibration triggered")

        } catch (e: Exception) {
            LogUtils.e(context, TAG, "❌ Vibration error", e)
        }
    }

    private fun playCustomSound(context: Context, soundUriString: String) {
        try {
            val soundUri = Uri.parse(soundUriString)
            val ringtone = RingtoneManager.getRingtone(context, soundUri)

            if (ringtone != null) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                    @Suppress("DEPRECATION")
                    ringtone.streamType = android.media.AudioManager.STREAM_NOTIFICATION
                }
                ringtone.play()
                LogUtils.d(context, TAG, "🔔 Playing sound")
            }
        } catch (e: Exception) {
            LogUtils.e(context, TAG, "❌ Sound error", e)
        }
    }
}