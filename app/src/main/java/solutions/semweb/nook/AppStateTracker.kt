package solutions.semweb.nook

import android.app.Activity
import android.content.Context

object AppStateTracker {
    private var activityCount = 0
    var isAppInForeground = false
        private set

    fun onActivityResumed(activity: Activity) {
        activityCount++
        isAppInForeground = true
        saveAppState(activity, true)
        LogUtils.d(activity, "AppStateTracker", "Activity resumed - count: $activityCount - FOREGROUND")
    }

    fun onActivityPaused(activity: Activity) {
        // Don't change foreground state here - wait for stopped
        LogUtils.d(activity, "AppStateTracker", "Activity paused - count: $activityCount")
    }

    fun onActivityStopped(activity: Activity) {
        activityCount--
        if (activityCount < 0) activityCount = 0

        if (activityCount == 0) {
            isAppInForeground = false
            saveAppState(activity, false)
            clearCurrentActivity(activity)
            LogUtils.d(activity, "AppStateTracker", "App in BACKGROUND")
        } else {
            LogUtils.d(activity, "AppStateTracker", "Activity stopped - count: $activityCount - still foreground")
        }
    }

    fun clearCurrentActivity(activity: Activity) {
        activity.getSharedPreferences("app_state", Context.MODE_PRIVATE).edit()
            .remove("last_activity")
            .remove("last_phone_number")
            .apply()
    }

    private fun saveAppState(activity: Activity, isForeground: Boolean) {
        activity.getSharedPreferences("app_state", Context.MODE_PRIVATE).edit()
            .putBoolean("is_foreground", isForeground)
            .apply()
    }

    fun isAppInForeground(context: Context): Boolean {
        val prefs = context.getSharedPreferences("app_state", Context.MODE_PRIVATE)
        val isForeground = prefs.getBoolean("is_foreground", false)
        LogUtils.d(context, "AppStateTracker", "isAppInForeground check: $isForeground")
        return isForeground
    }
}