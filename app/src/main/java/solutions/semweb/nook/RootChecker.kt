// [file name]: RootChecker.kt
package solutions.semweb.nook

import android.content.Context
import android.os.Build

object RootChecker {


    fun isDeviceRooted(context: Context): Boolean {
        return try {
            // Method 1: Check per build tags di ROM rootate
            val buildTags = Build.TAGS
            if (buildTags != null && (buildTags.contains("test-keys") ||
                        buildTags.contains("debug"))) {
                LogUtils.d(context, "RootChecker", "🔍 Root rilevato via build tags: $buildTags")
                return true
            }

            // Method 2: Check per binaries SU
            val paths = arrayOf(
                "/system/app/Superuser.apk",
                "/system/xbin/su",
                "/sbin/su",
                "/system/bin/su",
                "/system/bin/.ext/.su",
                "/system/usr/we-need-root/su-backup",
                "/system/xbin/mu",
                "/su/bin/su",
                "/magisk/.magisk",
                "/data/magisk/magisk",
                "/data/magisk/magiskinit",
                "/data/adb/magisk",
                "/system/bin/failsafe/su",
                "/system/bin/cpx"
            )

            for (path in paths) {
                if (fileExists(path)) {
                    LogUtils.d(context, "RootChecker", "🔍 Root rilevato via path: $path")
                    return true
                }
            }

            val suPaths = arrayOf(
                "which su",
                "command -v su"
            )

            for (suPath in suPaths) {
                try {
                    val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", suPath))
                    val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
                    val output = reader.readLine()
                    process.waitFor()

                    if (!output.isNullOrEmpty() && output.contains("su")) {
                        LogUtils.d(context, "RootChecker", "🔍 SU trovato via which: $output")
                        return true
                    }
                } catch (e: Exception) {
                    // Ignore
                }
            }

            // Method 4: Check per Magisk Manager (per LineageOS con Magisk)
            val packageManager = context.packageManager
            try {
                packageManager.getPackageInfo("com.topjohnwu.magisk", 0)
                LogUtils.d(context, "RootChecker", "🔍 Magisk Manager rilevato")
                return true
            } catch (e: Exception) {
                // Package not found
            }

            try {
                packageManager.getPackageInfo("eu.chainfire.supersu", 0)
                LogUtils.d(context, "RootChecker", "🔍 SuperSU rilevato")
                return true
            } catch (e: Exception) {
                // Package not found
            }

            // Method 5: Check per debug system
            try {
                val debugProp = try {
                    Class.forName("android.os.SystemProperties")
                        .getMethod("get", String::class.java)
                        .invoke(null, "ro.debuggable") as String
                } catch (e: Exception) {
                    null
                }

                if (debugProp == "1") {
                    LogUtils.d(context, "RootChecker", "🔍 Sistema debuggable rilevato (ro.debuggable=1)")
                    return true
                }
            } catch (e: Exception) {
                // Ignora errore
            }

            // Method 6: Check security property
            try {
                val secureProp = try {
                    Class.forName("android.os.SystemProperties")
                        .getMethod("get", String::class.java)
                        .invoke(null, "ro.secure") as String
                } catch (e: Exception) {
                    "1"
                }

                if (secureProp == "0") {
                    LogUtils.d(context, "RootChecker", "🔍 Sistema non sicuro (ro.secure=0)")
                    return true
                }
            } catch (e: Exception) {
                // Ignor error
            }

            false
        } catch (e: Exception) {
            LogUtils.e(context, "RootChecker", "Errore durante il check root", e)
            false
        }
    }


    fun isBootloaderUnlocked(): Boolean {
        return try {
            // Usa SystemProperties per maggiore affidabilità
            val bootState = try {
                Class.forName("android.os.SystemProperties")
                    .getMethod("get", String::class.java)
                    .invoke(null, "ro.boot.verifiedbootstate") as String?
            } catch (e: Exception) {
                null
            }

            val bootFlavor = try {
                Class.forName("android.os.SystemProperties")
                    .getMethod("get", String::class.java)
                    .invoke(null, "ro.boot.flash.locked") as String?
            } catch (e: Exception) {
                null
            }

            val bootVerified = try {
                Class.forName("android.os.SystemProperties")
                    .getMethod("get", String::class.java)
                    .invoke(null, "ro.boot.veritymode") as String?
            } catch (e: Exception) {
                null
            }

            // Log per debug
            LogUtils.d(null, "RootChecker", "Boot state: $bootState, Flash locked: $bootFlavor, Verity: $bootVerified")

            // Controlla vari indicatori
            val state = bootState?.lowercase()
            when {
                state == "orange" || state == "yellow" -> {
                    LogUtils.d(null, "RootChecker", "⚠️ Bootloader probably unlocked (state: $state)")
                    true
                }
                bootFlavor == "0" -> {
                    LogUtils.d(null, "RootChecker", "⚠️ Bootloader unlocked (flash.locked=0)")
                    true
                }
                bootVerified == "disabled" -> {
                    LogUtils.d(null, "RootChecker", "⚠️ Verity disabled")
                    true
                }
                else -> false
            }
        } catch (e: Exception) {
            LogUtils.e(null, "RootChecker", "Error checking bootloader", e)
            false
        }
    }


    fun isUsbDebuggingEnabled(context: Context): Boolean {
        return try {
            val adbEnabled = android.provider.Settings.Global.getInt(
                context.contentResolver,
                android.provider.Settings.Global.ADB_ENABLED,
                0
            ) == 1

            if (adbEnabled) {
                LogUtils.d(context, "RootChecker", "⚠️ USB debugging active")
            }
            adbEnabled
        } catch (e: Exception) {
            false
        }
    }

    private fun fileExists(path: String): Boolean {
        return try {
            java.io.File(path).exists()
        } catch (e: Exception) {
            false
        }
    }


    fun evaluateSecurityRisk(context: Context): SecurityRisk {
        val isRooted = isDeviceRooted(context)
        val isBootloaderUnlocked = isBootloaderUnlocked()
        val isAdbEnabled = isUsbDebuggingEnabled(context)

        // Log dettagliato per debug
        LogUtils.d(context, "RootChecker",
            "Security evaluation: Rooted=$isRooted, BootloaderUnlocked=$isBootloaderUnlocked, ADB=$isAdbEnabled")

        return when {
            isRooted && isBootloaderUnlocked -> {
                LogUtils.d(context, "RootChecker", "⚠️ HIGH RISK: Root + Bootloader unlocked")
                SecurityRisk.HIGH
            }
            isRooted -> {
                LogUtils.d(context, "RootChecker", "⚠️ MIDDLE RISK: Root detected")
                SecurityRisk.MEDIUM
            }
            isBootloaderUnlocked -> {
                LogUtils.d(context, "RootChecker", "⚠️ MIDDLE RISK: Bootloader unlocked")
                SecurityRisk.MEDIUM
            }
            isAdbEnabled -> {
                LogUtils.d(context, "RootChecker", "⚠️ LOW RISK: ADB enabled")
                SecurityRisk.LOW
            }
            else -> {
                LogUtils.d(context, "RootChecker", "✅ No risk detected")
                SecurityRisk.NONE
            }
        }
    }

    enum class SecurityRisk {
        NONE, LOW, MEDIUM, HIGH
    }
}