import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("app.cash.paparazzi")
}
// Usage analytics (Firebase) only when the project's config is present: drop google-services.json into app/.
// Without it the app builds and runs with analytics off.
if (file("google-services.json").exists()) apply(plugin = "com.google.gms.google-services")

// Release signing: the upload key lives OUTSIDE the repo. Put keystore.properties next to this project's
// settings.gradle.kts (it's gitignored) with storeFile, storePassword, keyAlias, keyPassword. Without it,
// release builds are signed with the debug key (fine for sideloading, not for Play).
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.home.tvremote"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.home.tvremote"
        minSdk = 26
        targetSdk = 35
        versionCode = 64
        versionName = "1.5.3"
    }
    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) create("upload") {
            storeFile = file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            // Smaller, faster app: R8 removes unused code and resources.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    // On-phone AI (Gemini Nano via Android's on-device AI, e.g. Pixel 9). Beta API.
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta4")
    // Anonymous usage analytics, off until the user agrees (see Analytics.kt).
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-analytics")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
