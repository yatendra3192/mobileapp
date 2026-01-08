plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
    id("androidx.baselineprofile")
}

android {
    namespace = "com.aiezzy.slideshowmaker.benchmark"
    compileSdk = 34

    defaultConfig {
        minSdk = 28 // Baseline profiles require API 28+
        targetSdk = 34

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Required for baseline profile generation
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Target the app module for testing
    targetProjectPath = ":app"

    // Configure test options
    testOptions.managedDevices.devices {
        create<com.android.build.api.dsl.ManagedVirtualDevice>("pixel6Api31") {
            device = "Pixel 6"
            apiLevel = 31
            systemImageSource = "aosp"
        }
    }
}

// Baseline profile configuration
baselineProfile {
    // Use connected device or managed device for profile generation
    useConnectedDevices = true

    // Managed devices configuration (optional, for CI)
    managedDevices += "pixel6Api31"
}

dependencies {
    implementation(libs.androidx.junit)
    implementation(libs.androidx.espresso.core)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
