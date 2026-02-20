plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    kotlin("kapt")  // Plugin kapt per Kotlin Annotation Processing
}

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
        versionCode = 1
        versionName = "1.0"
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
        }

        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("String", "BUILD_TYPE", "\"release\"")
            buildConfigField("boolean", "DEBUG", "false")
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
    // TUE DIPENDENZE ESISTENTI
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.1")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("commons-codec:commons-codec:1.16.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation(libs.play.services.vision.common)
    implementation(libs.androidx.compiler)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation(libs.common)
    implementation(libs.androidx.compose.ui.geometry)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.media3.common.ktx)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.firebase.crashlytics.buildtools)
    implementation(libs.androidx.games.text.input)
    implementation(libs.androidx.adapters)
    implementation(libs.androidx.sqlite.ktx)
    implementation(libs.protolite.well.known.types)
    implementation(libs.firebase.firestore.ktx)
    implementation(libs.firebase.ai)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.ui)

    val room_version = "2.8.4"
    implementation("androidx.room:room-runtime:$room_version")
    implementation("androidx.room:room-ktx:$room_version")  // Per coroutine support
    kapt("androidx.room:room-compiler:$room_version") // <--- CORRETTO

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

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