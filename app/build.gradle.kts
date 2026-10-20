plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.torrenttoddl"
    compileSdk = 34

    // Use the exact file restored by CI instead of AGP's environment-dependent
    // default debug-keystore location. Local and PR builds keep their default.
    providers.environmentVariable("TORRENTTODDL_DEBUG_KEYSTORE").orNull?.let { keystorePath ->
        signingConfigs.getByName("debug") {
            storeFile = file(keystorePath)
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "com.example.torrenttoddl"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "1.0.4"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    val libtorrent4jVersion = "2.1.0-31"
    implementation("org.libtorrent4j:libtorrent4j:$libtorrent4jVersion")
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:$libtorrent4jVersion")
    implementation("org.libtorrent4j:libtorrent4j-android-arm:$libtorrent4jVersion")
    implementation("org.libtorrent4j:libtorrent4j-android-x86_64:$libtorrent4jVersion")

    implementation("org.nanohttpd:nanohttpd:2.3.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    implementation(platform("androidx.compose:compose-bom:2024.02.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")

    testImplementation("junit:junit:4.13.2")
    // Exercise real JNI handle lifetimes on Linux CI; not packaged in the APK.
    testRuntimeOnly("org.libtorrent4j:libtorrent4j-linux:$libtorrent4jVersion")
}
