plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.smstotelegram"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.smstotelegram"
        minSdk = 26
        targetSdk = 34
        versionCode = 9
        versionName = "2.3.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
}

// Copy debug APK to project root after every build
tasks.whenTaskAdded {
    if (name == "packageDebug") {
        doLast {
            val apk = file("build/outputs/apk/debug/app-debug.apk")
            if (apk.exists()) {
                val dest = rootProject.projectDir.resolve("SmsSync-v${android.defaultConfig.versionName}.apk")
                apk.copyTo(dest, overwrite = true)
                println("APK copied to: ${dest.absolutePath}")
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.activity:activity-ktx:1.8.2")

    // OkHttp for Telegram API calls
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON parsing
    implementation("org.json:json:20231013")

    // EncryptedSharedPreferences (AndroidX Security)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // WorkManager for OEM-resistant keep-alive
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}