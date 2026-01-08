package com.aiezzy.slideshowmaker.util

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * A thread-safe bitmap pool that reuses bitmap allocations to reduce GC pressure
 * during video encoding. This significantly improves performance by avoiding
 * the creation of new bitmap objects for every frame.
 *
 * Performance impact:
 * - Without pool: ~30 bitmap allocations per second at 30fps = 100MB+/sec garbage
 * - With pool: 3-5 bitmap allocations total, reused across all frames
 *
 * Usage:
 * ```
 * val pool = BitmapPool(maxSize = 5)
 * val bitmap = pool.acquire(1920, 1080, Bitmap.Config.ARGB_8888)
 * // ... use bitmap ...
 * pool.release(bitmap)  // Returns to pool for reuse
 * pool.clear()  // Call when done to free memory
 * ```
 */
class BitmapPool(private val maxSize: Int = 5) {

    private val pool = ConcurrentLinkedDeque<PooledBitmap>()

    @Volatile
    private var acquireCount = 0

    @Volatile
    private var reuseCount = 0

    @Volatile
    private var createCount = 0

    private data class PooledBitmap(
        val bitmap: Bitmap,
        val width: Int,
        val height: Int,
        val config: Bitmap.Config
    )

    /**
     * Acquires a bitmap from the pool or creates a new one if none are available.
     * The returned bitmap is cleared to transparent.
     *
     * @param width Required width of the bitmap
     * @param height Required height of the bitmap
     * @param config Bitmap configuration (default: ARGB_8888)
     * @return A bitmap ready for use, either recycled from pool or newly created
     */
    @Synchronized
    fun acquire(width: Int, height: Int, config: Bitmap.Config = Bitmap.Config.ARGB_8888): Bitmap {
        acquireCount++

        // Try to find a matching bitmap in the pool
        val iterator = pool.iterator()
        while (iterator.hasNext()) {
            val pooled = iterator.next()
            if (pooled.width == width &&
                pooled.height == height &&
                pooled.config == config &&
                !pooled.bitmap.isRecycled) {

                iterator.remove()
                reuseCount++

                // Clear the bitmap for reuse
                pooled.bitmap.eraseColor(Color.TRANSPARENT)

                if (LOG_STATS && acquireCount % 100 == 0) {
                    logStats()
                }

                return pooled.bitmap
            }
        }

        // No matching bitmap found, create a new one
        createCount++

        if (LOG_STATS && createCount % 10 == 0) {
            Log.d(TAG, "Creating new bitmap: ${width}x${height} (total created: $createCount)")
        }

        return Bitmap.createBitmap(width, height, config)
    }

    /**
     * Returns a bitmap to the pool for future reuse.
     * If the pool is full, the bitmap is recycled immediately.
     *
     * @param bitmap The bitmap to return to the pool
     */
    @Synchronized
    fun release(bitmap: Bitmap) {
        if (bitmap.isRecycled) {
            return
        }

        // Don't pool if we're at capacity
        if (pool.size >= maxSize) {
            bitmap.recycle()
            return
        }

        // Add to pool for reuse
        pool.addFirst(
            PooledBitmap(
                bitmap = bitmap,
                width = bitmap.width,
                height = bitmap.height,
                config = bitmap.config ?: Bitmap.Config.ARGB_8888
            )
        )
    }

    /**
     * Releases a bitmap back to the pool only if it's different from a source bitmap.
     * This is useful for transition operations where the result might be the same
     * as the input (e.g., when no transition is needed).
     *
     * @param bitmap The bitmap to potentially release
     * @param source The source bitmap to compare against
     */
    fun releaseIfNotSource(bitmap: Bitmap, source: Bitmap) {
        if (bitmap !== source) {
            release(bitmap)
        }
    }

    /**
     * Releases a bitmap back to the pool only if it's different from either source.
     * Useful for blend operations with two source bitmaps.
     *
     * @param bitmap The bitmap to potentially release
     * @param source1 First source bitmap to compare against
     * @param source2 Second source bitmap to compare against
     */
    fun releaseIfNotSource(bitmap: Bitmap, source1: Bitmap, source2: Bitmap?) {
        if (bitmap !== source1 && bitmap !== source2) {
            release(bitmap)
        }
    }

    /**
     * Clears all bitmaps from the pool and recycles them.
     * Call this when video processing is complete to free memory.
     */
    @Synchronized
    fun clear() {
        if (LOG_STATS) {
            logStats()
        }

        while (pool.isNotEmpty()) {
            val pooled = pool.pollFirst()
            if (pooled != null && !pooled.bitmap.isRecycled) {
                pooled.bitmap.recycle()
            }
        }

        // Reset stats
        acquireCount = 0
        reuseCount = 0
        createCount = 0
    }

    /**
     * Returns the current number of bitmaps in the pool.
     */
    fun size(): Int = pool.size

    /**
     * Returns pool statistics for debugging and monitoring.
     */
    fun getStats(): PoolStats = PoolStats(
        poolSize = pool.size,
        acquireCount = acquireCount,
        reuseCount = reuseCount,
        createCount = createCount,
        reuseRate = if (acquireCount > 0) reuseCount.toFloat() / acquireCount else 0f
    )

    private fun logStats() {
        val stats = getStats()
        Log.d(TAG, "BitmapPool stats: " +
                "poolSize=${stats.poolSize}, " +
                "acquires=${stats.acquireCount}, " +
                "reuses=${stats.reuseCount}, " +
                "creates=${stats.createCount}, " +
                "reuseRate=${String.format("%.1f", stats.reuseRate * 100)}%")
    }

    data class PoolStats(
        val poolSize: Int,
        val acquireCount: Int,
        val reuseCount: Int,
        val createCount: Int,
        val reuseRate: Float
    )

    companion object {
        private const val TAG = "BitmapPool"
        private const val LOG_STATS = false  // Set to true for debugging
    }
}
