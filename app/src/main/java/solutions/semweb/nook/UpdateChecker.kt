
package solutions.semweb.nook

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import okhttp3.OkHttpClient
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Manages app update checking and downloading
 * Supports Android 7 (API 24) through Android 16 (API 36)
 */
class UpdateChecker private constructor(private val context: Context) {

    companion object {
        private const val TAG = "UpdateChecker"
        private const val PREF_LAST_UPDATE_CHECK = "last_update_check_timestamp"
        private const val PREF_LATEST_VERSION = "latest_version"
        private const val PREF_UPDATE_AVAILABLE = "update_available"
        private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L // 24 hours

        @Volatile
        private var instance: UpdateChecker? = null

        fun getInstance(context: Context): UpdateChecker {
            return instance ?: synchronized(this) {
                instance ?: UpdateChecker(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs = SharedPreferencesManager.getInstance(context)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Data class for update information
     */
    data class UpdateInfo(
        val latestVersion: String,
        val currentVersion: String,
        val isUpdateAvailable: Boolean,
        val downloadUrl: String? = null,
        val releaseNotes: String? = null,
        val hasNewerVersion: Boolean = false // New flag to indicate if there's actually a newer version
    )

    /**
     * Check for updates - can be called periodically or manually
     */
    fun checkForUpdates(
        forceCheck: Boolean = false,
        onComplete: (UpdateInfo) -> Unit
    ) {
        // If Pure SMS mode is enabled, never check for updates
        if (isPureSmsMode()) {
            LogUtils.e(TAG, "📡 Pure SMS mode active - skipping update check")

            val currentVersion = BuildConfig.VERSION_NAME
            mainHandler.post {
                onComplete(
                    UpdateInfo(
                        latestVersion = currentVersion,
                        currentVersion = currentVersion,
                        isUpdateAvailable = false,
                        downloadUrl = null,
                        hasNewerVersion = false
                    )
                )
            }
            return
        }

        Thread {
            try {
                val currentVersion = BuildConfig.VERSION_NAME
                val lastCheck = prefs.getLong(PREF_LAST_UPDATE_CHECK, 0)
                val currentTime = System.currentTimeMillis()

                // If not forced and checked recently, use cached value
                if (!forceCheck && currentTime - lastCheck < CHECK_INTERVAL_MS) {
                    val cachedLatest = prefs.getString(PREF_LATEST_VERSION, currentVersion)
                    val updateAvailable = prefs.getBoolean(PREF_UPDATE_AVAILABLE, false)

                    // Check if the cached latest version is actually newer than current
                    val hasNewerVersion = isNewerVersion(cachedLatest, currentVersion)

                    mainHandler.post {
                        onComplete(
                            UpdateInfo(
                                latestVersion = cachedLatest,
                                currentVersion = currentVersion,
                                isUpdateAvailable = updateAvailable,
                                downloadUrl = generateDownloadUrl(cachedLatest),
                                hasNewerVersion = hasNewerVersion
                            )
                        )
                    }
                    return@Thread
                }

                // Perform actual check
                val versionCheckResult = fetchLatestVersionFromGithub(currentVersion)

                if (versionCheckResult != null) {
                    val (latestVersion, hasNewerVersion) = versionCheckResult
                    val isUpdateAvailable = hasNewerVersion

                    // Save to prefs
                    prefs.putString(PREF_LATEST_VERSION, latestVersion)
                    prefs.putBoolean(PREF_UPDATE_AVAILABLE, isUpdateAvailable)
                    prefs.putLong(PREF_LAST_UPDATE_CHECK, currentTime)

                    mainHandler.post {
                        onComplete(
                            UpdateInfo(
                                latestVersion = latestVersion,
                                currentVersion = currentVersion,
                                isUpdateAvailable = isUpdateAvailable,
                                downloadUrl = generateDownloadUrl(latestVersion),
                                hasNewerVersion = hasNewerVersion
                            )
                        )
                    }
                } else {
                    // Failed to check - use current version as latest (no update)
                    mainHandler.post {
                        onComplete(
                            UpdateInfo(
                                latestVersion = currentVersion,
                                currentVersion = currentVersion,
                                isUpdateAvailable = false,
                                downloadUrl = null,
                                hasNewerVersion = false
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                LogUtils.e(TAG, "❌ Error checking for updates", e)
                mainHandler.post {
                    onComplete(
                        UpdateInfo(
                            latestVersion = BuildConfig.VERSION_NAME,
                            currentVersion = BuildConfig.VERSION_NAME,
                            isUpdateAvailable = false,
                            downloadUrl = null,
                            hasNewerVersion = false
                        )
                    )
                }
            }
        }.start()
    }

    /**
     * Fetch latest version from GitHub releases by parsing the SHA file
     * Returns a Pair containing the latest version found and a boolean indicating
     * if there's actually a newer version available
     */
    private fun fetchLatestVersionFromGithub(currentVersion: String): Pair<String, Boolean>? {
        // If Pure SMS mode is enabled, don't contact GitHub
        if (isPureSmsMode()) {
            LogUtils.e(TAG, "📡 Pure SMS mode active - skipping GitHub fetch")
            return null
        }

        return try {
            LogUtils.e(TAG, "📡 Checking for updates...")

            val request = okhttp3.Request.Builder()
                .url(Constants.GITHUB_SHA256_URL)
                .header("User-Agent", "NooK-Android/${BuildConfig.VERSION_NAME}")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val content = response.body?.string() ?: return null

                    // Parse versions from SHA file (format: v1.1.1.255: hash)
                    val versionPattern = Pattern.compile("v(\\d+\\.\\d+\\.\\d+\\.\\d+)")
                    val matcher = versionPattern.matcher(content)

                    val versions = mutableListOf<String>()
                    while (matcher.find()) {
                        versions.add(matcher.group(1))
                    }

                    if (versions.isEmpty()) {
                        return null
                    }

                    // Sort versions and return the latest
                    versions.sortWith(VersionComparator())
                    val latestVersion = versions.last()

                    // Check if the latest version is actually newer than current
                    val hasNewerVersion = isNewerVersion(latestVersion, currentVersion)

                    LogUtils.e(TAG, "📡 Latest version found: $latestVersion, hasNewerVersion: $hasNewerVersion")

                    return Pair(latestVersion, hasNewerVersion)
                } else {
                    LogUtils.e(TAG, "❌ HTTP error: ${response.code}")
                    null
                }
            }
        } catch (e: Exception) {
            LogUtils.e(TAG, "❌ Error fetching versions", e)
            null
        }
    }

    /**
     * Comparator for version strings (e.g., 1.1.1.255)
     */
    class VersionComparator : Comparator<String> {
        override fun compare(v1: String, v2: String): Int {
            val parts1 = v1.split(".").map { it.toIntOrNull() ?: 0 }
            val parts2 = v2.split(".").map { it.toIntOrNull() ?: 0 }

            for (i in 0 until minOf(parts1.size, parts2.size)) {
                if (parts1[i] != parts2[i]) {
                    return parts1[i] - parts2[i]
                }
            }
            return parts1.size - parts2.size
        }
    }

    /**
     * Check if version2 is newer than version1
     */
    private fun isNewerVersion(latest: String, current: String): Boolean {
        return VersionComparator().compare(latest, current) > 0
    }

    /**
     * Generate download URL for a specific version
     */
    private fun generateDownloadUrl(version: String): String {
        val url = "${Constants.GITHUB_RELEASES_URL}nook-v$version.apk"
        LogUtils.d("releaseDownload", "CALLING $url")
        return url
    }

    /**
     * Main entry point for downloading APK - automatically selects the right method
     * based on Android version
     */
    fun downloadAPKAndStopAPP(
        version: String,
        onProgress: (Int) -> Unit = {},
        onComplete: (Boolean, String?) -> Unit
    ) {

        // If Pure SMS mode is enabled, don't download
        if (isPureSmsMode()) {
            LogUtils.e(TAG, "📡 Pure SMS mode active - skipping APK download")
            onComplete(false, "Pure SMS mode active - downloads disabled")
            return
        }

        Thread {
            try {
                val downloadUrl = generateDownloadUrl(version)
                val fileName = "nook-v$version.apk"

                // Select the appropriate download method based on Android version
                val outputFile = when {
                    // Android 10+ (API 29+) - Use MediaStore
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                        try {
                            downloadToDownloadsFolderUsingMediaStore(context, fileName, downloadUrl, onProgress)
                        } catch (e: SecurityException) {
                            LogUtils.e(TAG, "⚠️ MediaStore failed, falling back to cache: ${e.message}")
                            downloadToCache(context, fileName, downloadUrl, onProgress)
                        } catch (e: IOException) {
                            LogUtils.e(TAG, "⚠️ MediaStore failed, falling back to cache: ${e.message}")
                            downloadToCache(context, fileName, downloadUrl, onProgress)
                        }
                    }

                    // Android 7-9 (API 24-28) - Use traditional file access with permission check
                    else -> {
                        try {
                            downloadToDownloadsFolderLegacy(context, fileName, downloadUrl, onProgress)
                        } catch (e: SecurityException) {
                            LogUtils.e(TAG, "⚠️ Legacy download failed, falling back to cache: ${e.message}")
                            downloadToCache(context, fileName, downloadUrl, onProgress)
                        } catch (e: IOException) {
                            LogUtils.e(TAG, "⚠️ Legacy download failed, falling back to cache: ${e.message}")
                            downloadToCache(context, fileName, downloadUrl, onProgress)
                        }
                    }
                }

                if (outputFile != null && outputFile.exists() && outputFile.length() > 0) {
                    onProgress(100)
                    Thread.sleep(500)
                    onComplete(true, outputFile.absolutePath)
                } else {
                    onComplete(false, context.getString(R.string.download_failed_empty_file))
                }

            } catch (e: Exception) {
                LogUtils.e(TAG, "❌ Download failed", e)
                onComplete(false, e.message ?: context.getString(R.string.unknown_error))
            }
        }.start()
    }

    /**
     * Download to Downloads folder using MediaStore (Android 10+ / API 29+)
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun downloadToDownloadsFolderUsingMediaStore(
        context: Context,
        fileName: String,
        downloadUrl: String,
        onProgress: (Int) -> Unit
    ): File? {
        try {
            LogUtils.e(TAG, "📥 Downloading using MediaStore (Android 10+)")

            // Create content values for the new file
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }

            // Insert into MediaStore
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                ?: throw IOException("Failed to create MediaStore entry")

            // Open output stream through MediaStore
            resolver.openOutputStream(uri)?.use { outputStream ->
                downloadFileToOutputStream(downloadUrl, outputStream, onProgress)
            } ?: throw IOException("Failed to open output stream")

            // Try to get the actual file path for the return value
            return getFileFromMediaStoreUri(uri, fileName)

        } catch (e: Exception) {
            LogUtils.e(TAG, "❌ MediaStore download failed", e)
            throw e
        }
    }

    /**
    * Check if Pure SMS mode is enabled - if so, skip GitHub operations
    */
    private fun isPureSmsMode(): Boolean {
        return prefs.pureSmsMode
    }
    /**
     * Helper to get File object from MediaStore URI
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun getFileFromMediaStoreUri(uri: Uri, fileName: String): File? {
        val resolver = context.contentResolver
        val cursor = resolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.DATA),
            null,
            null,
            null
        )

        cursor?.use {
            if (it.moveToFirst()) {
                val dataIndex = it.getColumnIndex(MediaStore.MediaColumns.DATA)
                if (dataIndex != -1) {
                    val filePath = it.getString(dataIndex)
                    return File(filePath)
                }
            }
        }

        // Fallback: return File with Downloads directory + filename
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(downloadsDir, fileName)
    }

    /**
     * Download to Downloads folder using legacy file access (Android 7-9 / API 24-28)
     */
    private fun downloadToDownloadsFolderLegacy(
        context: Context,
        fileName: String,
        downloadUrl: String,
        onProgress: (Int) -> Unit
    ): File? {

        LogUtils.e(TAG, "📥 Downloading using legacy file access (Android 7-9)")

        // Check permission for Android 9 and below
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                throw SecurityException("WRITE_EXTERNAL_STORAGE permission not granted")
            }
        }

        // Get Downloads folder
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadsDir.exists()) {
            downloadsDir.mkdirs()
        }

        val outputFile = File(downloadsDir, fileName)

        // Delete existing file
        if (outputFile.exists()) {
            outputFile.delete()
        }

        LogUtils.e(TAG, "📥 Downloading to: ${outputFile.absolutePath}")

        // Download file
        downloadFileToOutputStream(downloadUrl, FileOutputStream(outputFile), onProgress)
        return outputFile
    }

    /**
     * Fallback: Download to app's cache directory (always works on all Android versions)
     */
    private fun downloadToCache(
        context: Context,
        fileName: String,
        downloadUrl: String,
        onProgress: (Int) -> Unit
    ): File {

        LogUtils.e(TAG, "📥 Downloading to cache (fallback)")

        val cacheFile = File(context.cacheDir, fileName)

        // Delete existing file
        if (cacheFile.exists()) {
            cacheFile.delete()
        }

        LogUtils.e(TAG, "📥 Downloading to cache: ${cacheFile.absolutePath}")

        downloadFileToOutputStream(downloadUrl, FileOutputStream(cacheFile), onProgress)
        return cacheFile
    }

    /**
     * Common download function that writes to an OutputStream
     */
    private fun downloadFileToOutputStream(
        downloadUrl: String,
        outputStream: OutputStream,
        onProgress: (Int) -> Unit
    ) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(downloadUrl)

            // For Android 16, try with a custom DNS resolver
            val config = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build()

            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            // Use the default network with explicit DNS
            val network = connectivityManager.activeNetwork
            if (network != null) {
                connectivityManager.bindProcessToNetwork(network)
            }

            connection = url.openConnection() as HttpURLConnection
            connection.setRequestProperty("Connection", "close") // Prevent keep-alive issues
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.instanceFollowRedirects = true

            connection.connect()

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Server returned HTTP $responseCode")
            }

            val fileLength = connection.contentLength
            val inputStream = connection.inputStream

            val buffer = ByteArray(8192)
            var bytesRead: Int
            var totalBytesRead = 0

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalBytesRead += bytesRead

                if (fileLength > 0) {
                    val progress = (totalBytesRead * 100 / fileLength)
                    onProgress(progress)
                }
            }

            outputStream.flush()
        } finally {
            // Unbind from network
            ConnectivityManager.setProcessDefaultNetwork(null)
            connection?.disconnect()
            outputStream.close()
        }
    }

    /**
     * Get latest version (cached)
     */
    fun getLatestVersion(): String {
        return prefs.getString(PREF_LATEST_VERSION, BuildConfig.VERSION_NAME)
    }
}