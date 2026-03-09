package solutions.semweb.nook

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat.getString
import okhttp3.OkHttpClient
import java.io.File
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Management of integrity verification of the APK via SHA256
 * Download SHA checksum file and check on current APK
 * Uses app VERSION value for SHA check
 */

class ShaVerificationManager private constructor(private val context: Context) {

    /**
     * Check if Pure SMS mode is enabled - if so, skip GitHub operations
     */
    private fun isPureSmsMode(): Boolean {
        return prefs.pureSmsMode
    }

    companion object {
        private const val TAG = "ShaVerification"
        private const val PREF_LAST_SHA_CHECK = "last_sha_check_timestamp"
        private const val PREF_STORED_APK_HASH = "stored_apk_hash"
        private const val PREF_STORED_APK_VERSION = "stored_apk_version"
        private const val PREF_VERIFICATION_STATUS = "verification_status"
        private const val CYCLIC_CHECK_MS = 6 * 60 * 60 * 1000L

        // NEW: Key to track first installation
        private const val PREF_FIRST_INSTALL_COMPLETE = "first_install_complete"
        // NEW: Delay for first verification after installation (10 minutes)
        private const val FIRST_VERIFICATION_DELAY_MS = 5 * 60 * 1000L // 5 minutes

        @Volatile
        private var instance: WeakReference<ShaVerificationManager>? = null

        fun getInstance(context: Context): ShaVerificationManager {
            val appContext = context.applicationContext

            // Check there is a valid instance
            instance?.get()?.let { return it }

            // Otherwise create new instance
            return synchronized(this) {
                val newInstance = ShaVerificationManager(appContext)
                instance = WeakReference(newInstance)
                newInstance
            }
        }

        // To destroy the instance (optional)
        fun clearInstance() {
            instance?.clear()
            instance = null
        }
    }

    private val prefs = SharedPreferencesManager.getInstance(context)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())

    // Receiver for background verification failures
    private var shaVerificationReceiver: BroadcastReceiver? = null
    private var onVerificationFailedListener: ((SHAVerificationResult) -> Unit)? = null

    // Flag to track if receiver is registered
    private val isReceiverRegistered = AtomicBoolean(false)

    // Flag to track if this is first installation
    private val isFirstInstallation: Boolean by lazy {
        !prefs.getBoolean(PREF_FIRST_INSTALL_COMPLETE, false)
    }

    /**
     * APK installation information
     */
    data class ApkInstallationInfo(
        val expectedHash: String,
        val apkHash: String,
        val path: String,
        val lastModified: Long
    )

    /**
     * Result of SHA verification
     */
    data class SHAVerificationResult(
        val isValid: Boolean,
        val message: String,
        val timestamp: Long = System.currentTimeMillis(),
        val version: String = BuildConfig.VERSION_NAME,
        val isOffline: Boolean = false,
        val apkInfo: ApkInstallationInfo? = null  // Added for debug info
    )

    /**
     * Register receiver for SHA verification failures
     * @param listener Callback when verification fails in background
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun registerVerificationReceiver(listener: (SHAVerificationResult) -> Unit) {
        this.onVerificationFailedListener = listener

        if (isReceiverRegistered.get()) {
            LogUtils.e(TAG, "Receiver already registered")
            return
        }

        val filter = IntentFilter("${Constants.mainpackage}.SHA_VERIFICATION_FAILED")

        shaVerificationReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent?.getParcelableExtra("result", SHAVerificationResult::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent?.getParcelableExtra("result")
                }

                if (result != null) {
                    LogUtils.e(TAG, "📡 Received SHA verification failure broadcast")
                    onVerificationFailedListener?.invoke(result)
                }
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(shaVerificationReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(shaVerificationReceiver, filter)
            }
            isReceiverRegistered.set(true)
            LogUtils.e(TAG, "✅ SHA verification receiver registered")
        } catch (e: Exception) {
            LogUtils.e(TAG, "❌ Error registering receiver", e)
        }
    }

    /**
     * Unregister the verification receiver
     */
    fun unregisterVerificationReceiver() {
        if (isReceiverRegistered.get() && shaVerificationReceiver != null) {
            try {
                context.unregisterReceiver(shaVerificationReceiver)
                isReceiverRegistered.set(false)
                shaVerificationReceiver = null
                onVerificationFailedListener = null
                LogUtils.e(TAG, "✅ SHA verification receiver unregistered")
            } catch (e: Exception) {
                LogUtils.e(TAG, "❌ Error unregistering receiver", e)
            }
        }
    }

    /**
     * Call this when app initialization is complete
     * This will schedule the first verification after 10 minutes
     */
    fun onAppInitialized() {
        if (isFirstInstallation) {
            LogUtils.e(TAG, "🆕 First installation detected - scheduling verification in 10 minutes")

            // Mark first installation as complete
            prefs.putBoolean(PREF_FIRST_INSTALL_COMPLETE, true)

            // Schedule verification after delay
            scheduleFirstVerification()
        }
    }

    /**
     * Schedule the first verification to run after delay
     */
    private fun scheduleFirstVerification() {
        mainHandler.postDelayed({
            LogUtils.e(TAG, "⏰ Running scheduled first verification")
            performVerificationWithDelay()
        }, FIRST_VERIFICATION_DELAY_MS)

        LogUtils.e(TAG, "⏰ First verification scheduled in ${FIRST_VERIFICATION_DELAY_MS / 1000 / 60} minutes")
    }

    /**
     * Perform verification with delay handling
     */
    private fun performVerificationWithDelay() {
        // Only verify if Pure SMS is OFF at verification time
        // If Pure SMS is still ON, we skip
        if (isPureSmsMode()) {
            LogUtils.e(TAG, "📡 Pure SMS still ON at verification time - skipping")
            return
        }

        // Perform the verification
        verifyApkIntegrity(forceDownload = true) { result ->
            if (!result.isValid) {
                // Send broadcast for failure
                sendVerificationBroadcast(result)
            }
        }
    }

    /**
     * - First installation: force download & verify (but only after delay)
     * - Normal ("daily") start: use saved hash, refresh in background if necessary
     */
    fun verifyApkIntegrity(
        forceDownload: Boolean = false,
        onComplete: (SHAVerificationResult) -> Unit
    ) {
        // CRITICAL: If this is first installation, return success immediately without verifying
        // The real verification will happen after the 10-minute delay via onAppInitialized()
        if (isFirstInstallation) {
            LogUtils.e(TAG, "🆕 First installation - skipping immediate verification")

            val currentVersion = BuildConfig.VERSION_NAME

            mainHandler.post {
                onComplete(
                    SHAVerificationResult(
                        isValid = true, // Assume valid to not block UI
                        message = "First installation - verification scheduled",
                        timestamp = System.currentTimeMillis(),
                        version = currentVersion,
                        isOffline = false,
                        apkInfo = null
                    )
                )
            }
            return
        }

        // If Pure SMS mode is enabled and we're trying to force download, skip
        if (isPureSmsMode() && forceDownload) {
            LogUtils.e(TAG, "📡 Pure SMS mode active - skipping forced SHA verification")

            // Return a result that looks like offline mode (but without internet)
            val currentVersion = BuildConfig.VERSION_NAME
            val storedHash = prefs.getString(PREF_STORED_APK_HASH, "")
            val storedVersion = prefs.getString(PREF_STORED_APK_VERSION, "")
            val lastCheck = prefs.getLong(PREF_LAST_SHA_CHECK, 0)

            // Check if stored hash matches current APK (without downloading)
            val currentApkInfo = calculateApkSha256(storedHash)
            val isValid = currentApkInfo?.apkHash == storedHash && storedVersion == currentVersion

            mainHandler.post {
                onComplete(
                    SHAVerificationResult(
                        isValid = isValid,
                        message = if (isValid)
                            getString(context, R.string.apk_hash_match)
                        else
                            "Pure SMS mode - no verification performed",
                        timestamp = lastCheck,
                        version = currentVersion,
                        isOffline = true, // Treat as offline to show appropriate UI
                        apkInfo = currentApkInfo
                    )
                )
            }
            return
        }

        Thread {
            try {
                val currentVersion = BuildConfig.VERSION_NAME
                var storedHash = prefs.getString(PREF_STORED_APK_HASH, "")
                val storedVersion = prefs.getString(PREF_STORED_APK_VERSION, "")
                val lastCheck = prefs.getLong(PREF_LAST_SHA_CHECK, 0)
                val currentTime = System.currentTimeMillis()

                // Case 1: No hash saved (shouldn't happen after first installation, but just in case)
                if (storedHash == "") {
                    LogUtils.e(TAG, "⚠️ No hash found - performing verification")
                    performFullVerification(onComplete)
                    return@Thread
                }

                // Case 2: Version changed (app update)
                if (storedVersion != currentVersion) {
                    LogUtils.e(TAG, "🔄 Changed Version: $storedVersion -> $currentVersion")
                    performFullVerification(onComplete)
                    return@Thread
                }

                // Case 3: if Internet, re-download and store fresh hash
                var hadInternet = false
                val shaFileContent = if (!isPureSmsMode()) downloadShaFile() else null

                if (shaFileContent != null) {
                    val actualHash = findHashForVersion(shaFileContent, currentVersion)
                    if (actualHash != null) {
                        hadInternet = true
                        prefs.putString(PREF_STORED_APK_HASH, actualHash)
                        LogUtils.e(TAG, "🆕 Cyclic SHA refresh done")
                        storedHash = actualHash
                    }
                }
                val currentApkInfo = calculateApkSha256(storedHash)

                if (BuildConfig.DEBUG || currentApkInfo?.apkHash == storedHash) {
                    LogUtils.e(TAG, "✅ Rapid verification OK - Hash matches")
                    prefs.putLong(PREF_LAST_SHA_CHECK, currentTime)

                    mainHandler.post {
                        onComplete(
                            SHAVerificationResult(
                                isValid = true,
                                message = getString(context, R.string.apk_hash_match),
                                timestamp = currentTime,
                                isOffline = !hadInternet || isPureSmsMode(),
                                version = currentVersion,
                                apkInfo = currentApkInfo
                            )
                        )
                    }

                    // Background refresh if needed
                    if (!isPureSmsMode() && currentTime - lastCheck > CYCLIC_CHECK_MS) {
                        LogUtils.e(TAG, "🔄 Background refresh")
                        performBackgroundRefresh()
                    }
                } else {
                    // Hash mismatch - PROBLEM!
                    LogUtils.e(TAG, "❌❌❌ HASH MISMATCH! App corrupted!")
                    mainHandler.post {
                        onComplete(
                            SHAVerificationResult(
                                isValid = false,
                                message = getString(context, R.string.apk_hash_mismatch),
                                timestamp = currentTime,
                                version = currentVersion,
                                apkInfo = currentApkInfo
                            )
                        )
                    }
                }

            } catch (e: Exception) {
                LogUtils.e(TAG, "❌ Error during apk hash verification", e)
                mainHandler.post {
                    onComplete(
                        SHAVerificationResult(
                            isValid = false,
                            message = getString(context, R.string.error) + ": ${e.message}",
                            isOffline = true,
                            apkInfo = null
                        )
                    )
                }
            }
        }.start()
    }

    /**
     * SHA file download and verification
     */
    private fun performFullVerification(onComplete: (SHAVerificationResult) -> Unit) {
        try {
            val currentVersion = BuildConfig.VERSION_NAME

            // Download SHA file
            val shaFileContent = downloadShaFile()

            if (shaFileContent == null) {
                mainHandler.post {
                    onComplete(
                        SHAVerificationResult(
                            isValid = false,
                            message = getString(context,R.string.could_not_download_sha_file),
                            isOffline = true,
                            apkInfo = null
                        )
                    )
                }
                return
            }

            // Find right line containing version
            val expectedHash = findHashForVersion(shaFileContent, currentVersion)

            if (expectedHash == null) {
                if (BuildConfig.DEBUG)
                    mainHandler.post {
                        onComplete(
                            SHAVerificationResult(
                                isValid = true,
                                message = getString(context,R.string.apk_hash_match), // lied but DEBUG
                                version = currentVersion,
                                apkInfo = null
                            )
                        )
                    }
                else
                    mainHandler.post {
                        onComplete(
                            SHAVerificationResult(
                                isValid = false,
                                message = getString(context,R.string.sha_version_not_found_in_file),
                                version = currentVersion,
                                apkInfo = null
                            )
                        )
                    }
                return
            }


            // Compute current APK info
            val currentApkInfo = calculateApkSha256(expectedHash)

            if (currentApkInfo == null) {
                mainHandler.post {
                    onComplete(
                        SHAVerificationResult(
                            isValid = false,
                            message = getString(context,R.string.could_not_compute_apk_hash),
                            version = currentVersion,
                            apkInfo = null
                        )
                    )
                }
                return
            }

            //Skip in DEBUG this initial check!!!
            val isValid = BuildConfig.DEBUG || currentApkInfo.apkHash.equals(expectedHash, ignoreCase = true)

            if (isValid) {
                // Save hash & version for future use
                prefs.putString(PREF_STORED_APK_HASH, currentApkInfo.apkHash)
                prefs.putString(PREF_STORED_APK_VERSION, currentVersion)
                prefs.putLong(PREF_LAST_SHA_CHECK, System.currentTimeMillis())
                prefs.putBoolean(PREF_VERIFICATION_STATUS, true)

                LogUtils.e(TAG, "✅ Complete verify OK - hash saved")
            } else {
                LogUtils.e(TAG, "❌ Complete verify FAILED - hash mismatch")
                LogUtils.e(TAG, "  Expected: $expectedHash")
                LogUtils.e(TAG, "  Calculated: ${currentApkInfo.apkHash}")
                LogUtils.e(TAG, "  Path: ${currentApkInfo.path}")
                LogUtils.e(TAG, "  Modified: ${formatTimestamp(currentApkInfo.lastModified)}")
            }

            val finalIsValid = isValid
            mainHandler.post {
                onComplete(
                    SHAVerificationResult(
                        isValid = finalIsValid,
                        message = "! "+if (finalIsValid) getString(context,R.string.apk_hash_match)
                        else getString(context,R.string.apk_hash_mismatch),
                        apkInfo = currentApkInfo
                    )
                )
            }
        } catch (e: Exception) {
            LogUtils.e(TAG, "❌ Error during complete verify", e)
            mainHandler.post {
                onComplete(
                    SHAVerificationResult(
                        isValid = false,
                        message = getString(context,R.string.error)+": ${e.message}",
                        isOffline = true,
                        apkInfo = null
                    )
                )
            }
        }
    }


    fun formatShortTimestamp(timestamp: Long): String {
        if (timestamp == 0L) return ""
        val date = Date(timestamp)
        return SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault()).format(date)
    }

    private fun performBackgroundRefresh() {
        Thread {
            try {
                val currentVersion = BuildConfig.VERSION_NAME
                val shaFileContent = downloadShaFile()
                val isOffline = shaFileContent == null

                if (shaFileContent != null) {
                    val expectedHash = findHashForVersion(shaFileContent, currentVersion)

                    if (expectedHash != null) {
                        val currentApkInfo = calculateApkSha256(expectedHash)

                        if (currentApkInfo != null && currentApkInfo.apkHash.equals(expectedHash, ignoreCase = true)) {
                            prefs.putLong(PREF_LAST_SHA_CHECK, System.currentTimeMillis())
                            prefs.putBoolean(PREF_VERIFICATION_STATUS, true)
                            LogUtils.e(TAG, "✅ Refresh background OK - hash still valid")

                            // Send success result (optional - you might not want to show dialog for success)
                            val result = SHAVerificationResult(
                                isValid = true,
                                message = "App integrity verified",
                                isOffline = false,
                                apkInfo = currentApkInfo
                            )
                            sendVerificationBroadcast(result)

                        } else if (currentApkInfo != null) {
                            LogUtils.e(TAG, "❌❌❌ Refresh background: HASH CHANGED! App corrupted!")
                            prefs.putBoolean(PREF_VERIFICATION_STATUS, false)

                            // Send failure result
                            val result = SHAVerificationResult(
                                isValid = false,
                                message = "!2 "+"App integrity corrupted! Hash mismatch",
                                isOffline = false,
                                apkInfo = currentApkInfo
                            )
                            sendVerificationBroadcast(result)
                        }
                    } else {
                        // Version not found in SHA file
                        LogUtils.e(TAG, "❌ Version $currentVersion not found in SHA file")
                        val result = SHAVerificationResult(
                            isValid = false,
                            message = "Version $currentVersion not found in integrity file",
                            isOffline = false,
                            apkInfo = calculateApkSha256("")
                        )
                        sendVerificationBroadcast(result)
                    }
                } else {
                    var storedHash = prefs.getString(PREF_STORED_APK_HASH, "")

                    // Offline case - can't verify
                    LogUtils.e(TAG, "❌ Offline - can't verify integrity")
                    val result = SHAVerificationResult(
                        isValid = false,
                        message = "Cannot verify integrity while offline",
                        isOffline = true,
                        apkInfo = calculateApkSha256(storedHash)
                    )
                    sendVerificationBroadcast(result)
                }
            } catch (e: Exception) {
                LogUtils.e(TAG, "❌ Errore refresh background", e)
                val result = SHAVerificationResult(
                    isValid = false,
                    message = "Error during verification: ${e.message}",
                    isOffline = true,
                    apkInfo = null
                )
                sendVerificationBroadcast(result)
            }
        }.start()
    }

    /**
     * Send verification broadcast
     */
    private fun sendVerificationBroadcast(result: SHAVerificationResult) {
        try {
            val intent = Intent("${Constants.mainpackage}.SHA_VERIFICATION_FAILED")
            intent.putExtra("result", result as java.io.Serializable)
            context.sendBroadcast(intent)
            LogUtils.e(TAG, "📡 Verification broadcast sent")
        } catch (e: Exception) {
            LogUtils.e(TAG, "❌ Error sending broadcast", e)
        }
    }

    private fun downloadShaFile(): String? {

        if (isPureSmsMode()) {
            LogUtils.e(TAG, "📡 Pure SMS mode active - skipping SHA download")
            return null
        }

        return try {
            LogUtils.e(TAG, "📡 Download SHA con OkHttp...")

            val client = OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()

            val request = okhttp3.Request.Builder()
                .url(Constants.GITHUB_SHA256_URL)
                .header("User-Agent", "NooK-Android/${BuildConfig.VERSION_NAME}")
                .header("Accept", "text/plain")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val content = response.body?.string()
                    LogUtils.e(TAG, "✅ Download OK (${content?.length ?: 0} bytes)")
                    LogUtils.e(TAG, "📄 Contenuto:\n$content")
                    return content
                } else {
                    LogUtils.e(TAG, "❌ HTTP error: ${response.code}")
                    return null
                }
            }
        } catch (e: Exception) {
            LogUtils.e(TAG, "❌ OkHttp error: ${e.message}")
            e.printStackTrace()
            null
        }
    }


    /**
     * Find hash for version
     * in file content
     */
    private fun findHashForVersion(fileContent: String, version: String): String? {
        // select line according to version
        val patterns = listOf(
            "v$version:",     // versione release (es. v1.1.1.255:)
            "t$version:",     // versione test   (es. t1.2.1.257:)
            "$version:",      // senza prefisso
            "v$version ",     // con spazio invece di :
            "t$version ",
            "$version "
        )

        return fileContent.lines()
            .firstOrNull { line ->
                patterns.any { pattern ->
                    line.trim().startsWith(pattern)
                }
            }
            ?.substringAfter(":")
            ?.trim()
    }

    /**
     * Calcola SHA256 dell'APK corrente
     * Returns triple (hash, installation_path, installation_timestamp)
     */
    private fun calculateApkSha256(downloadedHash: String): ApkInstallationInfo? {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }

            val apkPath = packageInfo.applicationInfo?.sourceDir
                ?: return null

            val apkFile = File(apkPath)
            if (!apkFile.exists()) return null

            val digest = MessageDigest.getInstance("SHA-256")
            apkFile.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }

            val hash = digest.digest().joinToString("") { "%02x".format(it) }

            // For debug: return also path and last modified timestamp
            ApkInstallationInfo(
                apkHash = hash,
                expectedHash = downloadedHash,
                path = apkPath,
                lastModified = apkFile.lastModified()
            )

        } catch (e: Exception) {
            LogUtils.e(TAG, "❌ Errore calcolo hash APK", e)
            null
        }
    }

    /**
     * Ottiene lo stato di verifica corrente
     */
    fun getCurrentVerificationStatus(): Boolean {
        return prefs.getBoolean(PREF_VERIFICATION_STATUS, false)
    }

    /**
     * Ottiene il timestamp dell'ultima verifica
     */
    fun getLastVerificationTimestamp(): Long {
        return prefs.getLong(PREF_LAST_SHA_CHECK, 0)
    }

    /**
     * Formatta timestamp per display
     */
    fun formatTimestamp(timestamp: Long): String {
        if (timestamp == 0L) return "Mai"
        val date = Date(timestamp)
        return SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(date)
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        unregisterVerificationReceiver()
    }
}