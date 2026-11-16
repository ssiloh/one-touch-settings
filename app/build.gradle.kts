plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.ssiloh.onetouchsettings"
    // CI compiles against 36. The aapt2 on some local (ARM) setups cannot read the android-36 jar,
    // so it can be lowered with -PotsCompileSdk=34 without changing targetSdk.
    compileSdk = (findProperty("otsCompileSdk") as String? ?: "36").toInt()

    defaultConfig {
        applicationId = "io.github.ssiloh.onetouchsettings"
        minSdk = 30 // Wireless debugging (pairing + TLS connect) exists from Android 11 (API 30)
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            // Not minified: the ADB/TLS/SPAKE2 stack is reflection- and JNI-heavy and unverified under R8.
            isMinifyEnabled = false
            // Debug-signed so the CI/local release APK is installable. Replace with your own key for distribution.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        jniLibs { useLegacyPackaging = true }
    }
    lint { abortOnError = false }
}

dependencies {
    // libadb-android declares this as runtime-only, but its types appear in the API we call.
    implementation("androidx.annotation:annotation:1.9.1")
    // Pure-Java ADB client (pairing + TLS connect) so the phone can talk to its own adbd.
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    // Needed for TLS 1.3 key-exporting used by the ADB pairing protocol.
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    // Generates the self-signed certificate ADB uses for TLS client auth.
    implementation("com.github.MuntashirAkon:sun-security-android:1.1")
}
