package solutions.semweb.nook

import android.app.Application
import android.util.Log

class NookApplication : Application() {

    companion object {
        const val TAG = "NOOK_DEBUG"
    }

    //Mini APP per DEBUG (non usata normalmente)
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "════════════════════════════════════════")
        Log.d(TAG, "NookApplication.onCreate()")
        Log.d(TAG, "Package: $packageName")
        Log.d(TAG, "Process ID: ${android.os.Process.myPid()}")
        Log.d(TAG, "════════════════════════════════════════")
    }

    override fun onTerminate() {
        Log.d(TAG, "NookApplication.onTerminate()")
        super.onTerminate()
    }
}