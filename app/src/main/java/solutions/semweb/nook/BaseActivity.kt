package solutions.semweb.nook

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity

open class BaseActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyScreenshotSecurity()
    }

    override fun onResume() {
        super.onResume()
        applyScreenshotSecurity()
    }

    private fun applyScreenshotSecurity() {
        val prefs = SharedPreferencesManager.getInstance(this )

        val isLineageOS23 = Build.VERSION.SDK_INT == 36 &&
                Build.VERSION.RELEASE == "16" &&
                Build.FINGERPRINT.contains("google")

        if (!isLineageOS23) {
            if (!prefs.allowScreenshots) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                LogUtils.d(this,"BASE_ACTIVITY", "✅ FLAG_SECURE applied")
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                LogUtils.d(this,"BASE_ACTIVITY", "❌ FLAG_SECURE removed")
            }
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            LogUtils.d(this,"BASE_ACTIVITY", "⚠️ LineageOS - FLAG_SECURE ignored")
        }
    }
}