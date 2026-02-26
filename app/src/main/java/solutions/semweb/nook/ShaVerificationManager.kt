package solutions.semweb.nook

import android.content.Context
import android.content.Intent
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

/**
 * Management of integrity verification of the APK via SHA256
 * Download SHA checksum file and check on current APK
 * Uses app VERSION value for SHA check
 */

class ShaVerificationManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "ShaVerification"
        private const val SHA_FILE_URL = "https://raw.githubusercontent.com/redskate/nook/refs/heads/master/app/sha256"
        private const val PREF_LAST_SHA_CHECK = "last_sha_check_timestamp"
        private const val PREF_STORED_APK_HASH = "stored_apk_hash"
        private const val PREF_STORED_APK_VERSION = "stored_apk_version"
        private const val PREF_VERIFICATION_STATUS = "verification_status"
        private const val CYCLIC_CHECK_MS = 6 * 60 * 60 * 1000L

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
     * - First installation: force download & verify
     * - Normal ("daily") start: use saved hash, refresh in background if necessary
     */
    fun verifyApkIntegrity(
        forceDownload: Boolean = false,
        onComplete: (SHAVerificationResult) -> Unit
    ) {
        Thread {
            try {
                val currentVersion = BuildConfig.VERSION_NAME
                var storedHash = prefs.getString(PREF_STORED_APK_HASH, "")
                val storedVersion = prefs.getString(PREF_STORED_APK_VERSION, "")
                val lastCheck = prefs.getLong(PREF_LAST_SHA_CHECK, 0)
                val currentTime = System.currentTimeMillis()

                // Case 1: First installation (no Hash saved)
                if (storedHash == "") {
                    LogUtils.e(TAG, "🆕 First installation - SHA download mandatory")
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
                // try to read fresh if internet connected
                var hadInternet = false
                val shaFileContent = downloadShaFile()
                if (shaFileContent!=null) {

                    val actualHash = findHashForVersion(shaFileContent, currentVersion)
                    if (actualHash != null) {
                        hadInternet = true
                        prefs.putString(PREF_STORED_APK_HASH, actualHash)
                        LogUtils.e(TAG, "🆕 Cyclic SHA refresh done")
                        storedHash = actualHash
                    }
                }
                val currentApkInfo = calculateApkSha256(storedHash)


                // pass through for DEBUG
                if (BuildConfig.DEBUG ||
                     currentApkInfo?.apkHash == storedHash ) {

                    LogUtils.e(TAG, "✅ Rapid verification OK - Hash matches")
                    prefs.putLong(PREF_LAST_SHA_CHECK, currentTime)

                    mainHandler.post {
                        onComplete(
                            SHAVerificationResult(
                                isValid = true,
                                message = getString(context,R.string.apk_hash_match),
                                timestamp = currentTime,
                                isOffline = !hadInternet,
                                version = currentVersion,
                                apkInfo = currentApkInfo  // Added for debug
                            )
                        )
                    }

                    // In case more than a day is elapsed, refresh APK HASH check
                    if (currentTime - lastCheck > CYCLIC_CHECK_MS) {
                        LogUtils.e(TAG, "🔄 Refresh giornaliero in background")
                        performBackgroundRefresh()
                    }
                } else {
                    // Hash mismatch - PROBLEM!
                    LogUtils.e(TAG, "❌❌❌ HASH MISMATCH! App corrupted!")
                    mainHandler.post {
                        onComplete(
                            SHAVerificationResult(
                                isValid = false,
                                message = getString(context,R.string.apk_hash_mismatch),
                                timestamp = currentTime,
                                version = currentVersion,
                                apkInfo = currentApkInfo  // Added for debug
                            )
                        )
                    }
                }

            } catch (e: Exception) {
                LogUtils.e(TAG, "❌ Error during apk hast verification", e)
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
                        apkInfo = currentApkInfo  // Added for debug
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
                            val intent = Intent("${Constants.mainpackage}.SHA_VERIFICATION_FAILED")
                            intent.putExtra("result", result as java.io.Serializable)
                            context.sendBroadcast(intent)

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
                            val intent = Intent("${Constants.mainpackage}.SHA_VERIFICATION_FAILED")
                            intent.putExtra("result", result as java.io.Serializable)
                            context.sendBroadcast(intent)
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
                        val intent = Intent("${Constants.mainpackage}.SHA_VERIFICATION_FAILED")
                        intent.putExtra("result", result as java.io.Serializable)
                        context.sendBroadcast(intent)
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
                    val intent = Intent("${Constants.mainpackage}.SHA_VERIFICATION_FAILED")
                    intent.putExtra("result", result as java.io.Serializable)
                    context.sendBroadcast(intent)
                }
            } catch (e: Exception) {
                LogUtils.e(TAG, "❌ Errore refresh background", e)
                val result = SHAVerificationResult(
                    isValid = false,
                    message = "Error during verification: ${e.message}",
                    isOffline = true,
                    apkInfo = null
                )
                val intent = Intent("${Constants.mainpackage}.SHA_VERIFICATION_FAILED")
                intent.putExtra("result", result as java.io.Serializable)
                context.sendBroadcast(intent)
            }
        }.start()
    }




    private fun downloadShaFile(): String? {
        return try {
            LogUtils.e(TAG, "📡 Download SHA con OkHttp...")

            val client = OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()

            val request = okhttp3.Request.Builder()
                .url(SHA_FILE_URL)
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
}