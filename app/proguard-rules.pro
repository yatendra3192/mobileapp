# ============================================================================
# ProGuard/R8 Rules for Aiezzy Slideshow Maker
# ============================================================================
# These rules ensure proper minification while preserving necessary classes
# for runtime reflection and serialization.
#
# Expected APK size reduction: 30-50%
# Expected performance improvement: 10-20% (due to optimization passes)
# ============================================================================

# ----------------------------------------------------------------------------
# General Android Rules
# ----------------------------------------------------------------------------

# Keep annotations
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# Keep source file names and line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ----------------------------------------------------------------------------
# Kotlin Rules
# ----------------------------------------------------------------------------

# Keep Kotlin metadata for reflection
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# Kotlin serialization (if used in future)
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# ----------------------------------------------------------------------------
# Jetpack Compose Rules
# ----------------------------------------------------------------------------

# Keep Compose runtime classes
-keep class androidx.compose.** { *; }
-keepclassmembers class androidx.compose.** { *; }

# Keep Composable functions metadata
-keepclasseswithmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}

# Keep classes used with @Preview
-keepclasseswithmembers class * {
    @androidx.compose.ui.tooling.preview.Preview <methods>;
}

# Material 3 components
-keep class androidx.compose.material3.** { *; }
-keep class androidx.compose.material.icons.** { *; }

# ----------------------------------------------------------------------------
# Navigation Compose
# ----------------------------------------------------------------------------

-keep class androidx.navigation.** { *; }
-keepclassmembers class * {
    @androidx.navigation.compose.NavHost <methods>;
}

# ----------------------------------------------------------------------------
# Coil Image Loading
# ----------------------------------------------------------------------------

-keep class coil.** { *; }
-keep interface coil.** { *; }
-keepclassmembers class coil.** { *; }

# Coil uses Kotlin coroutines
-dontwarn coil.**

# Keep Coil's image decoders
-keep class coil.decode.** { *; }
-keep class coil.fetch.** { *; }
-keep class coil.transform.** { *; }

# ----------------------------------------------------------------------------
# Media3 / ExoPlayer
# ----------------------------------------------------------------------------

# Keep all Media3 classes (video playback)
-keep class androidx.media3.** { *; }
-keep interface androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Keep ExoPlayer extension classes
-keep class com.google.android.exoplayer2.** { *; }
-dontwarn com.google.android.exoplayer2.**

# ----------------------------------------------------------------------------
# MediaCodec / Video Processing
# ----------------------------------------------------------------------------

# Keep MediaCodec related classes
-keep class android.media.** { *; }
-keepclassmembers class android.media.** { *; }

# Keep our VideoProcessor internals
-keep class com.aiezzy.slideshowmaker.util.VideoProcessor { *; }
-keep class com.aiezzy.slideshowmaker.util.BitmapPool { *; }
-keep class com.aiezzy.slideshowmaker.util.BeatDetector { *; }

# ----------------------------------------------------------------------------
# ExifInterface
# ----------------------------------------------------------------------------

-keep class androidx.exifinterface.media.ExifInterface { *; }
-keepclassmembers class androidx.exifinterface.media.ExifInterface { *; }

# ----------------------------------------------------------------------------
# Data Models (prevent obfuscation of data classes)
# ----------------------------------------------------------------------------

# Keep all data models - these may be serialized or used with reflection
-keep class com.aiezzy.slideshowmaker.data.models.** { *; }
-keepclassmembers class com.aiezzy.slideshowmaker.data.models.** { *; }

# Keep enum values
-keepclassmembers enum com.aiezzy.slideshowmaker.data.models.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep templates and music library data
-keep class com.aiezzy.slideshowmaker.data.templates.** { *; }
-keep class com.aiezzy.slideshowmaker.data.music.** { *; }

# ----------------------------------------------------------------------------
# ViewModel
# ----------------------------------------------------------------------------

-keep class * extends androidx.lifecycle.ViewModel { *; }
-keep class com.aiezzy.slideshowmaker.viewmodel.** { *; }
-keepclassmembers class com.aiezzy.slideshowmaker.viewmodel.** { *; }

# Keep StateFlow and SharedFlow
-keepclassmembers class kotlinx.coroutines.flow.** { *; }

# ----------------------------------------------------------------------------
# Services
# ----------------------------------------------------------------------------

# Keep foreground service
-keep class com.aiezzy.slideshowmaker.service.** { *; }

# ----------------------------------------------------------------------------
# Application Class
# ----------------------------------------------------------------------------

-keep class com.aiezzy.slideshowmaker.SlideshowApp { *; }

# ----------------------------------------------------------------------------
# R8 Specific Optimizations
# ----------------------------------------------------------------------------

# Enable aggressive optimizations
-optimizationpasses 5
-allowaccessmodification
-repackageclasses ''

# Remove logging in release builds (optional - uncomment if desired)
# -assumenosideeffects class android.util.Log {
#     public static int v(...);
#     public static int d(...);
#     public static int i(...);
# }

# ----------------------------------------------------------------------------
# Suppressed Warnings
# ----------------------------------------------------------------------------

# Suppress warnings for missing classes that are optional
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn java.lang.invoke.**
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit

# RenderScript deprecation warnings
-dontwarn android.renderscript.**

# ----------------------------------------------------------------------------
# Debug Helpers (remove in production if APK size is critical)
# ----------------------------------------------------------------------------

# Keep method names for better stack traces
-keepnames class com.aiezzy.slideshowmaker.** { *; }
