package solutions.semweb.nook

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
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
        private const val GITHUB_RELEASES_URL = "https://github.com/redskate/nook/raw/refs/heads/master/app/releases/"
        private const val SHA_FILE_URL = "https://github.com/redskate/nook/raw/refs/heads/master/app/sha256"
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
        val releaseNotes: String? = null
    )

    /**
     * Check for updates - can be called periodically or manually
     */
    fun checkForUpdates(
        forceCheck: Boolean = false,
        onComplete: (UpdateInfo) -> Unit
    ) {
        Thread {
            try {
                val currentVersion = BuildConfig.VERSION_NAME
                val lastCheck = prefs.getLong(PREF_LAST_UPDATE_CHECK, 0)
                val currentTime = System.currentTimeMillis()

                // If not forced and checked recently, use cached value
                if (!forceCheck && currentTime - lastCheck < CHECK_INTERVAL_MS) {
                    val cachedLatest = prefs.getString(PREF_LATEST_VERSION, currentVersion)
                    val updateAvailable = prefs.getBoolean(PREF_UPDATE_AVAILABLE, false)

                    mainHandler.post {
                        onComplete(
                            UpdateInfo(
                                latestVersion = cachedLatest,
                                currentVersion = currentVersion,
                                isUpdateAvailable = updateAvailable,
                                downloadUrl = generateDownloadUrl(cachedLatest)
                            )
                        )
                    }
                    return@Thread
                }

                // Perform actual check
                val latestVersion = fetchLatestVersionFromGithub()

                if (latestVersion != null) {
                    val isUpdateAvailable = isNewerVersion(latestVersion, currentVersion)

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
                                downloadUrl = generateDownloadUrl(latestVersion)
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
                                downloadUrl = null
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
                            downloadUrl = null
                        )
                    )
                }
            }
        }.start()
    }

    /**
     * Fetch latest version from GitHub releases by parsing the SHA file
     */
    private fun fetchLatestVersionFromGithub(): String? {
        return try {
            LogUtils.e(TAG, "📡 Checking for updates...")

            val request = okhttp3.Request.Builder()
                .url(SHA_FILE_URL)
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

                    // Sort versions and return the latest
                    versions.sortWith(VersionComparator())
                    return versions.lastOrNull()
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
        return "${GITHUB_RELEASES_URL}nook-v$version.apk"
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
        val url = URL(downloadUrl)
        url.openConnection().let { connection ->
            connection.connect()
            val fileLength = connection.contentLength

            url.openStream().use { inputStream ->
                val buffer = ByteArray(8192)
                var total: Long = 0
                var count: Int
                var lastProgress = 0

                while (inputStream.read(buffer).also { count = it } != -1) {
                    total += count
                    if (fileLength > 0) {
                        val progress = (total * 100 / fileLength).toInt()
                        if (progress != lastProgress) {
                            onProgress(progress)
                            lastProgress = progress
                        }
                    }
                    outputStream.write(buffer, 0, count)
                }
                outputStream.flush()
            }
        }
    }

    /**
     * Get latest version (cached)
     */
    fun getLatestVersion(): String {
        return prefs.getString(PREF_LATEST_VERSION, BuildConfig.VERSION_NAME)
    }
}