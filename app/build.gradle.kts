plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.hardwareone.console"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hardwareone.console"
        minSdk = 26
        targetSdk = 35
        versionCode = 35
        versionName = "2.7.0"
    }

    buildTypes {
        debug {
            // Side-by-side qualification build: `./gradlew assembleDebug -PpipeTest` installs as a
            // separate app, so the everyday install and its data are untouched.
            if (project.hasProperty("pipeTest")) {
                applicationIdSuffix = ".pipetest"
                versionNameSuffix = "-pipetest"
            }
        }
        release {
            // No shrinking for a tiny app — keeps the build reproducible and easy to audit.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.window.size)

    // Foldable posture awareness (Jetpack WindowManager).
    implementation(libs.androidx.window)

    // Biometric / device-credential prompt for the Keystore-backed credential store.
    implementation(libs.androidx.biometric)
    // Force a current androidx.fragment (biometric 1.1.0 drags in 1.2.5, whose
    // FragmentActivity rejects the Activity Result API's >16-bit request codes → crash).
    implementation(libs.androidx.fragment)

    // FOSS crypto (X25519, HKDF, ChaCha20-Poly1305, PBKDF2) for the app-layer secure
    // channel — works on all API levels and matches the firmware's libsodium/mbedTLS bytes.
    implementation(libs.bouncycastle)

    testImplementation(libs.junit)
    // android.jar's org.json is a stub that throws; unit tests need the real one.
    testImplementation(libs.json)

    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.ui.tooling.preview)
}
