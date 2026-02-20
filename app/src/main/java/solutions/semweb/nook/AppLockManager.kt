
/**
 * Blocks APP If
 * screen OFF
 * 2 min without activity (e.g. APP in background)
 * APP killed/switched OFF
 * TODO: Button "NOW"
 */

package solutions.semweb.nook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper


class AppLockManager private constructor(private val appContext: Context) {

    companion object {
        private const val TAG = "AppLockManager"
        @Volatile
        private var instance: AppLockManager? = null

        fun getInstance(context: Context): AppLockManager {
            val appContext = context.applicationContext
            return instance ?: synchronized(this) {
                instance ?: AppLockManager(appContext).also {
                    instance = it
                }
            }
        }

        fun destroyInstance() {
            instance = null
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lockCheckRunnable: Runnable? = null
    private val prefs = SharedPreferencesManager.getInstance(appContext)

    // Receiver for system events
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    LogUtils.e(TAG, "📱 Screen OFF - immediate block")
                    lockAppImmediately()
                }
                Intent.ACTION_USER_PRESENT -> {
                    LogUtils.e(TAG, "📱 User present - update timer")
                    updateLastActiveTime()
                }
            }
        }
    }

    fun startMonitoring() {
        if (!prefs.appProtectionEnabled) {
            return
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }

        try {
            appContext.registerReceiver(screenOffReceiver, filter)
        } catch (e: Exception) {
            LogUtils.e(TAG, "❌ Error receiver registration", e)
        }

        startLockCheckTimer()
        updateLastActiveTime()
    }

    fun stopMonitoring() {
        LogUtils.e(TAG, "🛑 Stop app block monitoring")

        lockCheckRunnable?.let { handler.removeCallbacks(it) }
        lockCheckRunnable = null

        try {
            appContext.unregisterReceiver(screenOffReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver not registered -> ignore
        }
    }

    private fun startLockCheckTimer() {
        lockCheckRunnable?.let { handler.removeCallbacks(it) }

        lockCheckRunnable = object : Runnable {
            override fun run() {
                checkAndLockIfNeeded()
                // Reprogram in 30 seconds
                handler.postDelayed(this, 30000)
            }
        }

        handler.post(lockCheckRunnable!!)
    }

    private fun checkAndLockIfNeeded() {
        if (!prefs.appProtectionEnabled || prefs.isAppLocked) {
            return
        }

        val currentTime = System.currentTimeMillis()
        val lastActiveTime = prefs.lastActiveTime
        val timeoutMillis = prefs.appProtectionTimeout * 1000L

        val elapsedTime = currentTime - lastActiveTime

        if (elapsedTime >= timeoutMillis) {
            LogUtils.e(TAG, "⏰ Timeout elapsed ($elapsedTime ms > $timeoutMillis ms) - app block")
            lockAppImmediately()
        } else {
            LogUtils.d(TAG, "⏰ Timeout not yet occurred: $elapsedTime/$timeoutMillis ms")
        }
    }

    fun isLockedDueToFailedAttempts(): Boolean {
        val lockUntil = prefs.getLong(Constants.KEY_APP_PROTECTION_LOCK_UNTIL, 0)
        val currentTime = System.currentTimeMillis()

        if (lockUntil > currentTime) {
            LogUtils.e(TAG, "🔒 App blocked - too many attempts - try again in ${(lockUntil - currentTime) / 60000} minutes")
            return true
        } else if (lockUntil > 0) {
            prefs.remove(Constants.KEY_APP_PROTECTION_LOCK_UNTIL)
            prefs.putInt(Constants.KEY_APP_PROTECTION_FAILED_ATTEMPTS, 0)
        }

        return false
    }

    fun recordFailedAttempt() {
        val attempts = prefs.getInt(Constants.KEY_APP_PROTECTION_FAILED_ATTEMPTS, 0) + 1
        prefs.putInt(Constants.KEY_APP_PROTECTION_FAILED_ATTEMPTS, attempts)

        LogUtils.e(TAG, "⚠️ Failed attempt #$attempts")

        if (attempts >= Constants.MAX_PASSWORD_ATTEMPTS) {
            val lockUntil = System.currentTimeMillis() + (Constants.LOCK_DURATION_MINUTES * 60 * 1000)
            prefs.putLong(Constants.KEY_APP_PROTECTION_LOCK_UNTIL, lockUntil)
            prefs.putInt(Constants.KEY_APP_PROTECTION_FAILED_ATTEMPTS, 0)

            LogUtils.e(TAG, "🔒 App blocked for ${Constants.LOCK_DURATION_MINUTES} minutes - too many failed attempts")

            lockAppImmediately()
        }
    }

    fun resetFailedAttempts() {
        prefs.remove(Constants.KEY_APP_PROTECTION_LOCK_UNTIL)
        prefs.putInt(Constants.KEY_APP_PROTECTION_FAILED_ATTEMPTS, 0)
        LogUtils.e(TAG, "✅ Failed attempts reset")
    }

    fun lockAppImmediately() {
        if (!prefs.appProtectionEnabled) return

        val intent = Intent("${Constants.mainpackage}.APP_LOCKED")

        LogUtils.e(TAG, "🔒 Immediate app block - intent: "+intent)
        prefs.isAppLocked = true

        appContext.sendBroadcast(intent)
    }

    fun unlockApp() {
        LogUtils.e(TAG, "🔓 Unlocking app")
        prefs.isAppLocked = false
        updateLastActiveTime()

        val intent = Intent("${Constants.mainpackage}.APP_UNLOCKED")
        appContext.sendBroadcast(intent)
    }

    fun updateLastActiveTime() {
        prefs.lastActiveTime = System.currentTimeMillis()
    }

    fun isAppCurrentlyLocked(): Boolean {
        return prefs.appProtectionEnabled && prefs.isAppLocked
    }

    fun resetInactivityTimer() {
        if (prefs.appProtectionEnabled && !prefs.isAppLocked) {
            updateLastActiveTime()
        }
    }
}

