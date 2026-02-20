package solutions.semweb.nook

import android.content.Context
import android.util.Log
import kotlinx.coroutines.runBlocking
import solutions.semweb.nook.data.database.DatabaseActor

object LogUtils {

    private const val LEVEL_DEBUG = 0
    private const val LEVEL_INFO = 1
    private const val LEVEL_WARN = 2
    private const val LEVEL_ERROR = 3

    private var currentLogLevel = LEVEL_DEBUG

    var loggingEnabled = true

    private const val DEFAULT_TAG = "NooK"

    fun updateLoggingEnabled(enabled: Boolean, context: Context? = null) {
        loggingEnabled = enabled

        // Save in SharedPreferences
        context?.let {
            try {
                val prefs = SharedPreferencesManager.getInstance(it)
                prefs.putBoolean(Constants.KEY_LOG_ENABLED, enabled)

                // Save also in database via DatabaseActor (in background)
                Thread {
                    try {
                        runBlocking {
                            DatabaseActor.getInstance(it)
                                .saveSetting(Constants.KEY_LOG_ENABLED, enabled.toString(), "boolean")
                        }
                        Log.d(DEFAULT_TAG, "✅ Log setting saved in database: $enabled")
                    } catch (e: Exception) {
                        Log.e(DEFAULT_TAG, "❌ Errore salvataggio log setting nel database", e)
                    }
                }.start()

                d(null, "LogUtils", "Logging updated to: $enabled")
            } catch (e: Exception) {
                Log.e(DEFAULT_TAG, "Error updating logging", e)
            }
        }
    }

    private fun shouldLog(level: Int): Boolean {
        if (!BuildConfig.DEBUG) {
            return level >= LEVEL_ERROR
        }
        return loggingEnabled && level >= currentLogLevel
    }

    fun d(context: Context?, tag: String?, message: String) {
        if (!shouldLog(LEVEL_DEBUG)) return

        val finalTag = tag ?: DEFAULT_TAG
        Log.d(finalTag, formatMessage(message, context))
    }

    /** helper **/
    fun d(tag: String?, message: String) {
        return d(null, tag, message)
    }

    fun i(context: Context?, tag: String?, message: String) {
        if (!shouldLog(LEVEL_INFO)) return

        val finalTag = tag ?: DEFAULT_TAG
        Log.i(finalTag, formatMessage(message, context))
    }

    fun w(context: Context?, tag: String?, message: String, exception: Exception? = null) {
        if (!shouldLog(LEVEL_WARN)) return

        val finalTag = tag ?: DEFAULT_TAG
        val formattedMessage = formatMessage(message, context)

        if (exception != null) {
            Log.w(finalTag, formattedMessage, exception)
        } else {
            Log.w(finalTag, formattedMessage)
        }
    }

    fun e(context: Context?, tag: String?, message: String, exception: Exception? = null) {
        if (!shouldLog(LEVEL_ERROR)) return

        val finalTag = tag ?: DEFAULT_TAG
        val formattedMessage = formatMessage(message, context)

        if (exception != null) {
            Log.e(finalTag, formattedMessage, exception)
        } else {
            Log.e(finalTag, formattedMessage)
        }
    }

    /** helper **/
    fun e(tag: String?, message: String, exception: Exception? = null) {
        return e(null, tag, message, exception)
    }
    fun debug(message: String) {
        if (!shouldLog(LEVEL_DEBUG)) return

        val callerTag = getCallerClassName()
        Log.d(callerTag, message)
    }

    fun info(message: String) {
        if (!shouldLog(LEVEL_INFO)) return

        val callerTag = getCallerClassName()
        Log.i(callerTag, message)
    }

    fun error(message: String, exception: Exception? = null) {
        if (!shouldLog(LEVEL_ERROR)) return

        val callerTag = getCallerClassName()
        if (exception != null) {
            Log.e(callerTag, message, exception)
        } else {
            Log.e(callerTag, message)
        }
    }

    private fun formatMessage(message: String, context: Context?): String {
        val builder = StringBuilder()

        // Add timestamp
        if (BuildConfig.DEBUG) {
            val time = System.currentTimeMillis() % 100000
            builder.append("[$time] ")
        }

        // Add thread info in debug
        if (BuildConfig.DEBUG && currentLogLevel == LEVEL_DEBUG) {
            val threadName = Thread.currentThread().name
            if (threadName != "main") {
                builder.append("{$threadName} ")
            }
        }

        builder.append(message)
        return builder.toString()
    }

    private fun getCallerClassName(): String {
        return try {
            val stackTrace = Thread.currentThread().stackTrace
            // Search for the first class not LogUtils
            for (i in 4 until stackTrace.size.coerceAtMost(8)) {
                val className = stackTrace[i].className
                if (!className.contains("LogUtils")) {
                    return className.substringAfterLast(".")
                }
            }
            DEFAULT_TAG
        } catch (e: Exception) {
            DEFAULT_TAG
        }
    }


    fun setLogLevel(level: Int) {
        currentLogLevel = level
        d(null, "LogUtils", "Log level set to: $level")
    }

    fun crypto(tag: String, message: String) {
        if (currentLogLevel <= LEVEL_DEBUG) {
            Log.d("🔐$tag", message)
        } else if (currentLogLevel <= LEVEL_INFO) {
            Log.i("🔐$tag", message)
        }
    }

    fun database(tag: String, message: String) {
        if (currentLogLevel <= LEVEL_DEBUG) {
            Log.d("💾$tag", message)
        }
    }

    fun ui(tag: String, message: String) {
        if (currentLogLevel <= LEVEL_DEBUG) {
            Log.d("🎨$tag", message)
        }
    }

}