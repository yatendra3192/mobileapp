import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.androidx.baselineprofile)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt.android)
}

// Load local.properties for signing config
val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        load(localPropertiesFile.inputStream())
    }
}

android {
    namespace = "com.aiezzy.slideshowmaker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aiezzy.slideshowmaker"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // Signing configuration for release builds
    signingConfigs {
        create("release") {
            val storeFilePath = localProperties.getProperty("RELEASE_STORE_FILE")
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
                storePassword = localProperties.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = localProperties.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = localProperties.getProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Enable R8 code shrinking, obfuscation, and optimization
            isMinifyEnabled = true
            // Enable resource shrinking (removes unused resources)
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Use release signing config
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // Disable minification for faster debug builds
            isMinifyEnabled = false
            isShrinkResources = false
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
    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // ABI splits - build separate APKs for each architecture
    // This significantly reduces APK size by excluding unused native libraries
    splits {
        abi {
            isEnable = true
            reset()
            // Include only ARM architectures (covers 99%+ of real devices)
            // x86/x86_64 are mainly for emulators
            include("armeabi-v7a", "arm64-v8a", "x86_64")
            // Set to true to also generate a universal APK (larger, but works everywhere)
            isUniversalApk = false
        }
    }

    // Assign version codes to ABI-specific APKs
    // Higher version codes for newer architectures ensures proper updates
    android.applicationVariants.all {
        val variant = this
        variant.outputs.forEach { output ->
            if (output is com.android.build.gradle.internal.api.ApkVariantOutputImpl) {
                val abiFilter = output.getFilter(com.android.build.api.variant.FilterConfiguration.FilterType.ABI.name)
                val abiVersionCode = when (abiFilter) {
                    "armeabi-v7a" -> 1
                    "arm64-v8a" -> 2
                    "x86_64" -> 3
                    else -> 0
                }
                output.versionCodeOverride = (variant.versionCode ?: 1) * 10 + abiVersionCode
            }
        }
    }
}

// Baseline Profile configuration
baselineProfile {
    // Automatically generate baseline profiles during release builds
    automaticGenerationDuringBuild = false

    // Save baseline profiles in src/main for version control
    saveInSrc = true

    // Filter out test classes from the profile
    filter {
        include("com.aiezzy.slideshowmaker.**")
    }
}

dependencies {
    // Core Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // ViewModel
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Coil for image loading
    implementation(libs.coil.compose)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // ExoPlayer for video preview
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    // Media3 Transformer for professional audio/video processing
    // This is Google's official library for media transformation
    implementation("androidx.media3:media3-transformer:1.5.1")
    implementation("androidx.media3:media3-effect:1.5.1")
    implementation("androidx.media3:media3-common:1.5.1")

    // ExifInterface for image rotation handling
    implementation(libs.androidx.exifinterface)

    // Face Grouping Feature - ML Kit, TFLite, Room, WorkManager
    implementation(libs.mlkit.face.detection)
    implementation(libs.tensorflow.lite)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime.ktx)

    // Hilt Dependency Injection (using kapt for stability)
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    implementation(libs.hilt.work)
    kapt(libs.hilt.work.compiler)
    implementation(libs.hilt.navigation.compose)

    // Baseline Profile - enables AOT compilation of critical code paths
    implementation(libs.androidx.profileinstaller)
    "baselineProfile"(project(":benchmark"))

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
