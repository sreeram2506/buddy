import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    // The file you share, e.g. Buddy-debug.apk instead of app-debug.apk.
    base.archivesName.set("Buddy-1.0.0")
    lint {
        // Permissions are requested up front in MainActivity and re-checked in PhoneActions.telecom(); lint cannot see through those helpers.
        disable += "MissingPermission"
    }
    namespace = "com.sreeram.jarvis"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.sreeram.jarvis"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        ndk { abiFilters += "arm64-v8a" }
        buildConfigField("String", "GROQ_KEY", "\"${localProps.getProperty("GROQ_API_KEY", "")}\"")
        buildConfigField("String", "API_KEY", "\"${localProps.getProperty("GEMINI_API_KEY", "")}\"")
    }
    buildFeatures { buildConfig = true }
    androidResources { noCompress += listOf("mdl", "fst", "conf", "int", "mat", "stats", "txt", "dubm", "ie", "final", "mdl") }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.googlecode.libphonenumber:libphonenumber:8.13.50")
}
