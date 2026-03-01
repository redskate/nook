import com.android.build.api.dsl.SigningConfig

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    kotlin("kapt")
}

// Function to read version constants from Constants.kt file
fun loadVersionCodeFromConstants(): Int {
    val constantsFile = project.projectDir.resolve("src/main/java/solutions/semweb/nook/Constants.kt")
    if (!constantsFile.exists()) {
        throw GradleException("Constants.kt not found at: ${constantsFile.absolutePath}")
    }

    val constantsContent = constantsFile.readText()

    // Extract VERSION_CODE
    val versionCodePattern = """const\s+val\s+VERSION_CODE\s*=\s*(\d+)""".toRegex()
    val versionCodeMatch = versionCodePattern.find(constantsContent)
    val versionCode = versionCodeMatch?.groupValues?.get(1)?.toInt()
        ?: throw GradleException("VERSION_CODE not found in Constants.kt")

    // Extract version components
    val majorPattern = """const\s+val\s+VERSION_MAJOR\s*=\s*(\d+)""".toRegex()
    val minorPattern = """const\s+val\s+VERSION_MINOR\s*=\s*(\d+)""".toRegex()
    val patchPattern = """const\s+val\s+VERSION_PATCH\s*=\s*(\d+)""".toRegex()

    val major = majorPattern.find(constantsContent)?.groupValues?.get(1)?.toInt() ?: 1
    val minor = minorPattern.find(constantsContent)?.groupValues?.get(1)?.toInt() ?: 0
    val patch = patchPattern.find(constantsContent)?.groupValues?.get(1)?.toInt() ?: 0

    // Store in project properties for later use
    project.ext.set("versionMajor", major)
    project.ext.set("versionMinor", minor)
    project.ext.set("versionPatch", patch)
    project.ext.set("versionCode", versionCode)

    return versionCode
}

// Get version values from Constants.kt
val versionCodeFromConstants = try {
    loadVersionCodeFromConstants()
} catch (e: Exception) {
    println("⚠️ Warning: Error reading version from Constants.kt: ${e.message}")
    255 // Fallback value
}

// Use property delegates instead of findProperty()
val versionMajor: Int = (project.ext.get("versionMajor") as? Int) ?: 1
val versionMinor: Int = (project.ext.get("versionMinor") as? Int) ?: 1
val versionPatch: Int = (project.ext.get("versionPatch") as? Int) ?: 1
val versionNameString = "$versionMajor.$versionMinor.$versionPatch.$versionCodeFromConstants"

println("📱 Building version: $versionNameString (code: $versionCodeFromConstants)")

android {
    lint {
        baseline = file("lint-baseline.xml")
    }

    namespace = "solutions.semweb.nook"
    compileSdk = 36

    defaultConfig {
        applicationId = "solutions.semweb.nook"
        minSdk = 24 // Android 7 (Nougat)
        targetSdk = 34 // Compat
        versionCode = versionCodeFromConstants
        versionName = versionNameString
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        javaCompileOptions {
            annotationProcessorOptions {
                arguments += mapOf(
                    "room.schemaLocation" to "$projectDir/schemas",
                    "room.incremental" to "true"
                )
            }
        }
    }

    // ============================================================
    // SINGLE KEYSTORE FOR ALL BUILDS (Debug + Release)
    // ============================================================
    signingConfigs {
        // 🔐 MASTER KEYSTORE - Used for ALL builds (debug, release, testing)
        // This file is located OUTSIDE version control for security
        create("master") {
            // Look for keystore in secure external location
            // You can set this path via environment variable or local properties
            val keystorePath = System.getenv("NOOK_KEYSTORE_PATH") ?:
            project.findProperty("nook.keystore.path") as? String ?:
            "/secure/keystore/nook-master.keystore" // Fallback - CHANGE THIS!

            storeFile = file(keystorePath)

            // Verify keystore exists
            storeFile?.exists()?.let {
                if (!it) {
                    val error = """
                            ⚠️⚠️⚠️ KEYSTORE NOT FOUND ⚠️⚠️⚠️
                            Path: ${storeFile?.absolutePath}
                            
                            Please set NOOK_KEYSTORE_PATH environment variable or
                            create a local.properties file with:
                            nook.keystore.path=/path/to/your/keystore
                            nook.keystore.password=your-password
                            nook.key.alias=your-alias
                            nook.key.password=your-key-password
                        """.trimIndent()

                    if (gradle.startParameter.taskRequests.any { it.args.any { arg ->
                            arg.contains("assemble") || arg.contains("build")
                        }}) {
                        throw GradleException(error)
                    } else {
                        println(error)
                    }
                }
            }

            // Get passwords from environment variables (secure for CI/CD)
            storePassword = System.getenv("NOOK_KEYSTORE_PASSWORD") ?:
                    project.findProperty("nook.keystore.password") as? String ?: ""

            keyAlias = System.getenv("NOOK_KEY_ALIAS") ?:
                    project.findProperty("nook.key.alias") as? String ?: ""

            keyPassword = System.getenv("NOOK_KEY_PASSWORD") ?:
                    project.findProperty("nook.key.password") as? String ?: ""

            // Enable debug signing if passwords are missing (for local development)
            if (storePassword?.isEmpty() == true || keyAlias?.isEmpty() == true || keyPassword?.isEmpty() == true) {
                println("⚠️ Using debug signing (unsigned) - only for local testing!")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
                storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            isDebuggable = true
            versionNameSuffix = "-debug"
            isMinifyEnabled = false

            // Use the master keystore for ALL builds
            signingConfig = signingConfigs.getByName("master")

            // 🔐 DEBUG URLs from environment variables
            val debugSha256Url = System.getenv("NOOK_DEBUG_SHA256_URL") ?:
            project.findProperty("nook.debug.sha256.url") as? String ?:
            "http://localhost/debug/sha256"  // Safe fallback

            val debugReleasesUrl = System.getenv("NOOK_DEBUG_RELEASES_URL") ?:
            project.findProperty("nook.debug.releases.url") as? String ?:
            "http://localhost/debug/releases/"  // Safe fallback

            buildConfigField("String", "GITHUB_SHA256_URL", "\"$debugSha256Url\"")
            buildConfigField("String", "GITHUB_RELEASES_URL", "\"$debugReleasesUrl\"")

            buildConfigField("String", "BUILD_TYPE", "\"debug\"")
            buildConfigField("boolean", "DEBUG", "true")
            buildConfigField("String", "VERSION_NAME", "\"${defaultConfig.versionName}\"")
            buildConfigField("int", "VERSION_CODE", "${defaultConfig.versionCode}")
            buildConfigField("String", "FULL_VERSION", "\"${defaultConfig.versionName}-debug\"")

            // Add signing info to BuildConfig for debugging
            buildConfigField("String", "SIGNING_CERT", "\"${getSigningCertificateSha256(signingConfig)}\"")

            // Print warning if using fallback URLs
            if (debugSha256Url.contains("localhost") || debugReleasesUrl.contains("localhost")) {
                println("⚠️  WARNING: Using fallback debug URLs. Set NOOK_DEBUG_SHA256_URL and NOOK_DEBUG_RELEASES_URL environment variables")
            }
        }

        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            // Use the SAME master keystore for release builds
            signingConfig = signingConfigs.getByName("master")

            // 🔐 RELEASE URLs (these will be injected at build time, not hardcoded in Constants.kt)
            buildConfigField("String", "GITHUB_SHA256_URL", "\"https://raw.githubusercontent.com/redskate/nook/refs/heads/master/app/sha256\"")
            buildConfigField("String", "GITHUB_RELEASES_URL", "\"https://github.com/redskate/nook/raw/refs/heads/master/app/releases/\"")

            buildConfigField("String", "BUILD_TYPE", "\"release\"")
            buildConfigField("boolean", "DEBUG", "false")
            buildConfigField("String", "VERSION_NAME", "\"${defaultConfig.versionName}\"")
            buildConfigField("int", "VERSION_CODE", "${defaultConfig.versionCode}")
            buildConfigField("String", "FULL_VERSION", "\"${defaultConfig.versionName}\"")

            // Add signing info to BuildConfig for debugging
            buildConfigField("String", "SIGNING_CERT", "\"${getSigningCertificateSha256(signingConfig)}\"")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }
}

// Helper function to get certificate SHA-256 for debugging
fun getSigningCertificateSha256(config: SigningConfig?): String {
    if (config?.storeFile == null || !config.storeFile!!.exists()) {
        return "unknown"
    }

    return try {
        val process = ProcessBuilder(
            "keytool", "-list", "-v",
            "-keystore", config.storeFile!!.absolutePath,
            "-storepass", config.storePassword ?: "",
            "-alias", config.keyAlias ?: ""
        ).start()

        val output = process.inputStream.bufferedReader().readText()
        val pattern = "SHA256: ([A-F0-9:]+)".toRegex()
        pattern.find(output)?.groupValues?.get(1)?.replace(":", "")?.lowercase() ?: "unknown"
    } catch (e: Exception) {
        "unknown"
    }
}

configurations.all {
    resolutionStrategy {
        force("com.google.guava:guava:33.3.1-android")
        exclude(group = "com.google.guava", module = "listenablefuture")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.1")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation(libs.protolite.well.known.types)
    implementation(libs.androidx.datastore.core)
    implementation(libs.litert)

    val room_version = "2.8.4"
    implementation("androidx.room:room-runtime:$room_version")
    implementation("androidx.room:room-ktx:$room_version")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("commons-codec:commons-codec:1.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // SHA download and APP security check:
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("com.google.guava:guava:31.1-android") {
        because("Resolves conflict ListenableFuture btw profileinstaller and workmanager")
    }

    kapt("androidx.room:room-compiler:$room_version")

    // ✅ Test
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

kapt {
    correctErrorTypes = true
    javacOptions {
        option("-Xmaxerrs", "1000")
    }
}