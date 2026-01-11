package com.aiezzy.slideshowmaker.util

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.util.Log

/**
 * Utility class for monitoring memory usage and providing adaptive recommendations.
 *
 * Used by GalleryScanWorker to adjust batch sizes based on available memory,
 * preventing OutOfMemoryError during face detection and embedding generation.
 *
 * Memory thresholds are calibrated for typical face processing requirements:
 * - Each face bitmap: ~500KB-2MB (depending on resolution)
 * - FaceNet embedding: ~2KB per face
 * - HNSW index overhead: ~100 bytes per cluster
 */
object MemoryMonitor {
    private const val TAG = "MemoryMonitor"

    /**
     * Memory pressure levels for adaptive processing.
     */
    enum class MemoryPressure {
        LOW,        // > 256MB free - aggressive processing
        MEDIUM,     // 128-256MB free - normal processing
        HIGH,       // 64-128MB free - conservative processing
        CRITICAL    // < 64MB free - minimal processing
    }

    /**
     * Memory thresholds in MB.
     */
    private const val THRESHOLD_LOW_MB = 256L
    private const val THRESHOLD_MEDIUM_MB = 128L
    private const val THRESHOLD_HIGH_MB = 64L

    /**
     * Recommended batch sizes for each memory pressure level.
     */
    private val BATCH_SIZES = mapOf(
        MemoryPressure.LOW to 20,
        MemoryPressure.MEDIUM to 10,
        MemoryPressure.HIGH to 5,
        MemoryPressure.CRITICAL to 2
    )

    /**
     * Get the current memory pressure level.
     */
    fun getMemoryPressure(): MemoryPressure {
        val freeMemoryMB = getAvailableMemoryMB()

        return when {
            freeMemoryMB > THRESHOLD_LOW_MB -> MemoryPressure.LOW
            freeMemoryMB > THRESHOLD_MEDIUM_MB -> MemoryPressure.MEDIUM
            freeMemoryMB > THRESHOLD_HIGH_MB -> MemoryPressure.HIGH
            else -> MemoryPressure.CRITICAL
        }
    }

    /**
     * Get the recommended batch size based on current memory pressure.
     */
    fun getRecommendedBatchSize(): Int {
        val pressure = getMemoryPressure()
        return BATCH_SIZES[pressure] ?: 5
    }

    /**
     * Get available memory in MB using Runtime.
     * This is the heap memory available to the app.
     */
    fun getAvailableMemoryMB(): Long {
        val runtime = Runtime.getRuntime()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        val maxMemory = runtime.maxMemory()
        val freeMemory = maxMemory - usedMemory
        return freeMemory / (1024 * 1024)
    }

    /**
     * Get total memory used by the app in MB.
     */
    fun getUsedMemoryMB(): Long {
        val runtime = Runtime.getRuntime()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        return usedMemory / (1024 * 1024)
    }

    /**
     * Get the maximum heap size in MB.
     */
    fun getMaxMemoryMB(): Long {
        return Runtime.getRuntime().maxMemory() / (1024 * 1024)
    }

    /**
     * Get memory usage percentage (0-100).
     */
    fun getMemoryUsagePercent(): Int {
        val runtime = Runtime.getRuntime()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        val maxMemory = runtime.maxMemory()
        return ((usedMemory.toDouble() / maxMemory.toDouble()) * 100).toInt()
    }

    /**
     * Check if memory is critically low and processing should pause.
     */
    fun isCriticallyLow(): Boolean {
        return getMemoryPressure() == MemoryPressure.CRITICAL
    }

    /**
     * Check if a garbage collection might help before processing.
     * Returns true if memory usage is above 80%.
     */
    fun shouldRequestGC(): Boolean {
        return getMemoryUsagePercent() > 80
    }

    /**
     * Request a garbage collection hint.
     * Note: This is just a hint to the JVM, not guaranteed.
     */
    fun requestGC() {
        System.gc()
        Log.d(TAG, "GC requested. Memory after: ${getUsedMemoryMB()}MB used, ${getAvailableMemoryMB()}MB free")
    }

    /**
     * Get native memory info using Debug class.
     * Useful for tracking ML model memory usage.
     */
    fun getNativeMemoryMB(): Long {
        return Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
    }

    /**
     * Get detailed memory info for logging.
     */
    fun getMemoryInfo(): MemoryInfo {
        val runtime = Runtime.getRuntime()
        val usedHeap = runtime.totalMemory() - runtime.freeMemory()
        val maxHeap = runtime.maxMemory()
        val nativeHeap = Debug.getNativeHeapAllocatedSize()

        return MemoryInfo(
            usedHeapMB = usedHeap / (1024 * 1024),
            maxHeapMB = maxHeap / (1024 * 1024),
            freeHeapMB = (maxHeap - usedHeap) / (1024 * 1024),
            nativeHeapMB = nativeHeap / (1024 * 1024),
            usagePercent = ((usedHeap.toDouble() / maxHeap.toDouble()) * 100).toInt(),
            pressure = getMemoryPressure()
        )
    }

    /**
     * Log current memory status.
     */
    fun logMemoryStatus(tag: String = TAG) {
        val info = getMemoryInfo()
        Log.d(tag, "Memory: ${info.usedHeapMB}/${info.maxHeapMB}MB heap (${info.usagePercent}%), " +
                "${info.nativeHeapMB}MB native, pressure=${info.pressure}")
    }

    /**
     * Get system-level memory info using ActivityManager.
     * Includes memory used by other apps.
     */
    fun getSystemMemoryInfo(context: Context): SystemMemoryInfo {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        return SystemMemoryInfo(
            availableSystemMB = memoryInfo.availMem / (1024 * 1024),
            totalSystemMB = memoryInfo.totalMem / (1024 * 1024),
            lowMemory = memoryInfo.lowMemory,
            lowMemoryThresholdMB = memoryInfo.threshold / (1024 * 1024)
        )
    }

    /**
     * Detailed memory information.
     */
    data class MemoryInfo(
        val usedHeapMB: Long,
        val maxHeapMB: Long,
        val freeHeapMB: Long,
        val nativeHeapMB: Long,
        val usagePercent: Int,
        val pressure: MemoryPressure
    )

    /**
     * System-level memory information.
     */
    data class SystemMemoryInfo(
        val availableSystemMB: Long,
        val totalSystemMB: Long,
        val lowMemory: Boolean,
        val lowMemoryThresholdMB: Long
    )

    /**
     * Estimate if there's enough memory to process a batch of photos.
     *
     * @param batchSize Number of photos to process
     * @param avgBitmapSizeMB Estimated average bitmap size in MB
     * @return True if there's likely enough memory
     */
    fun canProcessBatch(batchSize: Int, avgBitmapSizeMB: Float = 1.5f): Boolean {
        val estimatedRequiredMB = (batchSize * avgBitmapSizeMB).toLong()
        val availableMB = getAvailableMemoryMB()

        // Keep at least 100MB buffer
        val safeAvailableMB = availableMB - 100

        return safeAvailableMB >= estimatedRequiredMB
    }

    /**
     * Calculate the maximum safe batch size given current memory.
     *
     * @param avgBitmapSizeMB Estimated average bitmap size in MB
     * @param minBatchSize Minimum batch size to return
     * @param maxBatchSize Maximum batch size to return
     * @return Safe batch size
     */
    fun calculateSafeBatchSize(
        avgBitmapSizeMB: Float = 1.5f,
        minBatchSize: Int = 1,
        maxBatchSize: Int = 25
    ): Int {
        val availableMB = getAvailableMemoryMB()

        // Keep 100MB buffer
        val safeAvailableMB = (availableMB - 100).coerceAtLeast(0)

        // Calculate how many photos we can safely process
        val calculatedSize = (safeAvailableMB / avgBitmapSizeMB).toInt()

        // Clamp to min/max range
        return calculatedSize.coerceIn(minBatchSize, maxBatchSize)
    }
}
