plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    kotlin("kapt")  // Plugin kapt per Kotlin Annotation Processing
}

// Function to read version constants from Constants.kt file
// Renamed to avoid conflict with implicit getter
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
    loadVersionCodeFromConstants()  // Using renamed function
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
        minSdk = 24
        targetSdk = 34
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

    buildTypes {
        getByName("debug") {
            isDebuggable = true
            versionNameSuffix = "-debug"
            isMinifyEnabled = false

            buildConfigField("String", "BUILD_TYPE", "\"debug\"")
            buildConfigField("boolean", "DEBUG", "true")

            // Version fields in DEBUG / use in Constants.kt
            buildConfigField("String", "VERSION_NAME", "\"${defaultConfig.versionName}\"")
            buildConfigField("int", "VERSION_CODE", "${defaultConfig.versionCode}")
            buildConfigField("String", "FULL_VERSION", "\"${defaultConfig.versionName}-debug\"")
        }

        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("String", "BUILD_TYPE", "\"release\"")
            buildConfigField("boolean", "DEBUG", "false")

            // Version fields in release / use in Constants.kt
            buildConfigField("String", "VERSION_NAME", "\"${defaultConfig.versionName}\"")
            buildConfigField("int", "VERSION_CODE", "${defaultConfig.versionCode}")
            buildConfigField("String", "FULL_VERSION", "\"${defaultConfig.versionName}\"")
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

    val room_version = "2.8.4"
    implementation("androidx.room:room-runtime:$room_version")
    implementation("androidx.room:room-ktx:$room_version")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("commons-codec:commons-codec:1.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    //SHA download and APP security check:
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
        option("-Xmaxerrs", 1000)
    }
}