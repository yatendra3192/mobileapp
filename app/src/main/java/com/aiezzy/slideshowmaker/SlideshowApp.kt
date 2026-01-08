package com.aiezzy.slideshowmaker

import android.app.Application
import android.content.ComponentCallbacks2
import android.os.Build
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.util.DebugLogger
import com.aiezzy.slideshowmaker.util.VideoProcessor
import java.io.File

private const val TAG = "SlideshowApp"

/**
 * Application class with optimized Coil image caching configuration.
 *
 * Memory cache: 25% of available heap (typically 32-64MB)
 * Disk cache: 100MB for thumbnails
 *
 * This configuration provides:
 * - Fast grid scrolling (images cached in memory)
 * - Reduced network/disk I/O (disk cache for persistence)
 * - Proper memory management under pressure
 */
class SlideshowApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "SlideshowApp initialized with optimized Coil caching")
    }

    /**
     * Creates a custom ImageLoader with optimized caching for grid performance.
     *
     * Key optimizations:
     * 1. Memory cache: 25% of heap for fast reloads during scrolling
     * 2. Disk cache: 100MB for thumbnail persistence across sessions
     * 3. Hardware bitmaps: Enabled on API 26+ for GPU rendering
     * 4. Crossfade disabled for grid: Faster perceived load times
     */
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            // Memory cache configuration
            .memoryCache {
                MemoryCache.Builder(this)
                    // Use 25% of available memory for image cache
                    .maxSizePercent(0.25)
                    // Strong references for visible images, weak for off-screen
                    .strongReferencesEnabled(true)
                    .weakReferencesEnabled(true)
                    .build()
            }
            // Disk cache configuration
            .diskCache {
                DiskCache.Builder()
                    .directory(File(cacheDir, "coil_image_cache"))
                    // 100MB disk cache for thumbnails
                    .maxSizeBytes(100L * 1024L * 1024L)
                    .build()
            }
            // Cache policies
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            // Performance optimizations
            .allowHardware(true) // Use hardware bitmaps for better GPU performance
            .allowRgb565(true)   // Use RGB565 for opaque images (50% memory savings)
            .crossfade(false)    // Disable crossfade for grid (faster perceived load)
            // Respect cache headers from content providers
            .respectCacheHeaders(false)
            // Add debug logging in debug builds only
            .apply {
                if (BuildConfig.DEBUG) {
                    logger(DebugLogger())
                }
            }
            .build()
    }

    /**
     * Respond to system memory pressure by clearing caches.
     * This is called when the system is running low on memory and we should
     * release any resources that can be recreated.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        when (level) {
            // App is in foreground but system is running low
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> {
                Log.d(TAG, "Memory trim: RUNNING_MODERATE")
            }

            // App is in foreground but system is critically low
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                Log.w(TAG, "Memory trim: RUNNING_LOW/CRITICAL - clearing caches")
                VideoProcessor.clearBitmapPool()
                clearCoilMemoryCache()
            }

            // App is backgrounded
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                Log.d(TAG, "Memory trim: UI_HIDDEN - clearing caches")
                VideoProcessor.clearBitmapPool()
                clearCoilMemoryCache()
            }

            // System is running very low, release everything
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                Log.w(TAG, "Memory trim: BACKGROUND/MODERATE/COMPLETE - clearing all caches")
                VideoProcessor.clearBitmapPool()
                clearCoilMemoryCache()
            }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Log.e(TAG, "System LOW MEMORY - clearing all caches")
        VideoProcessor.clearBitmapPool()
        clearCoilMemoryCache()
    }

    /**
     * Clears the Coil memory cache to free up memory.
     * Disk cache is preserved for faster reload.
     */
    private fun clearCoilMemoryCache() {
        try {
            imageLoader.memoryCache?.clear()
            Log.d(TAG, "Coil memory cache cleared")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear Coil memory cache", e)
        }
    }

    /**
     * Provides access to the ImageLoader for prefetching operations.
     */
    val imageLoader: ImageLoader by lazy { newImageLoader() }

    companion object {
        lateinit var instance: SlideshowApp
            private set

        /**
         * Convenience accessor for the ImageLoader.
         */
        val coilImageLoader: ImageLoader
            get() = instance.imageLoader
    }
}
