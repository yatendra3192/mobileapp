package com.aiezzy.slideshowmaker.face.scanner

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.aiezzy.slideshowmaker.data.face.entities.ScannedPhotoEntity
import com.aiezzy.slideshowmaker.data.face.entities.ScanStatus
import com.aiezzy.slideshowmaker.face.FaceRepository
import com.aiezzy.slideshowmaker.face.clustering.BatchClusteringService
import com.aiezzy.slideshowmaker.util.MemoryMonitor
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Production-grade WorkManager worker for scanning gallery photos for faces.
 *
 * Key features:
 * - Battery-aware throttling: Adjusts batch size based on battery level
 * - Per-photo timeout: Skips photos that take too long (30 seconds)
 * - Retry queue: Failed photos are retried with exponential backoff
 * - Pause/resume: Supports pausing and resuming scan via SharedPreferences
 * - Memory-aware: Yields when memory pressure is detected
 * - Progress persistence: Tracks progress in database for resume after crash
 *
 * Uses Hilt for dependency injection via @HiltWorker annotation.
 */
@HiltWorker
class GalleryScanWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val repository: FaceRepository,
    private val batchClusteringService: BatchClusteringService
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "GalleryScanWorker"
        const val WORK_NAME = "gallery_face_scan"

        // Input data keys
        const val KEY_FORCE_RESCAN = "force_rescan"
        const val KEY_RESUME_SCAN = "resume_scan"

        // Progress data keys
        const val KEY_PROGRESS_TOTAL = "progress_total"
        const val KEY_PROGRESS_SCANNED = "progress_scanned"
        const val KEY_PROGRESS_FACES = "progress_faces"
        const val KEY_PROGRESS_FAILED = "progress_failed"
        const val KEY_PROGRESS_SKIPPED = "progress_skipped"
        const val KEY_PROGRESS_ETA_SECONDS = "progress_eta_seconds"
        const val KEY_PROGRESS_PHOTOS_PER_SECOND = "progress_photos_per_second"
        const val KEY_PROGRESS_MEMORY_PRESSURE = "progress_memory_pressure"
        const val KEY_PROGRESS_BATCH_SIZE = "progress_batch_size"

        // Batch size limits (adjusted by memory and battery)
        // OPTIMIZED FOR 50K+ GALLERIES - Increased limits for faster processing
        private const val MAX_BATCH_SIZE = 100      // Increased from 50 for large galleries
        private const val MIN_BATCH_SIZE = 8        // Increased from 4 for efficiency
        private const val DEFAULT_BATCH_SIZE = 40   // Increased from 20 for faster throughput

        // Parallel processing configuration
        // OPTIMIZED: Modern phones have 8+ cores, can safely process more in parallel
        private const val MAX_PARALLEL_PHOTOS = 12   // Increased from 8 for faster processing
        private const val MIN_PARALLEL_PHOTOS = 4    // Increased from 2 for better baseline
        private const val DEFAULT_PARALLEL_PHOTOS = 6 // Increased from 4

        // Battery multipliers for batch size
        private const val BATTERY_CHARGING_MULTIPLIER = 2.0f   // More aggressive when charging
        private const val BATTERY_HIGH_MULTIPLIER = 1.5f
        private const val BATTERY_MEDIUM_MULTIPLIER = 1.0f
        private const val BATTERY_LOW_MULTIPLIER = 0.5f
        private const val BATTERY_CRITICAL_MULTIPLIER = 0.3f

        // Timeouts and limits
        private const val PHOTO_TIMEOUT_MS = 30_000L  // Increased to 30 seconds for embedding generation
        private const val YIELD_INTERVAL = 10         // Yield less frequently
        private const val MAX_RETRY_ATTEMPTS = 2      // Fewer retries for speed
        private const val RETRY_BASE_DELAY_MS = 500L  // Faster retries

        // Staging buffer flush configuration
        private const val STAGING_FLUSH_INTERVAL_PHOTOS = 20  // Flush staging buffer every 20 photos
        private const val STAGING_FLUSH_INTERVAL_MS = 10_000L // Or every 10 seconds

        // Checkpoint settings
        private const val CHECKPOINT_INTERVAL = 100   // Save checkpoint every 100 photos
        private const val ETA_SAMPLE_SIZE = 50        // More samples for smoother ETA

        // Pause state preferences
        private const val PREFS_NAME = "scan_worker_prefs"
        private const val KEY_PAUSED = "scan_paused"
        private const val KEY_CHECKPOINT_SCANNED = "checkpoint_scanned"
        private const val KEY_CHECKPOINT_FACES = "checkpoint_faces"
        private const val KEY_CHECKPOINT_FAILED = "checkpoint_failed"
        private const val KEY_CHECKPOINT_TIMESTAMP = "checkpoint_timestamp"

        /**
         * Schedule the gallery scan work
         */
        fun schedule(context: Context, forceRescan: Boolean = false, resumeScan: Boolean = false) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(false)  // We handle battery levels internally
                .build()

            val inputData = Data.Builder()
                .putBoolean(KEY_FORCE_RESCAN, forceRescan)
                .putBoolean(KEY_RESUME_SCAN, resumeScan)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<GalleryScanWorker>()
                .setConstraints(constraints)
                .setInputData(inputData)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    workRequest
                )

            Log.i(TAG, "Gallery scan work scheduled (force=$forceRescan, resume=$resumeScan)")
        }

        /**
         * Cancel any running scan
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "Gallery scan work cancelled")
        }

        /**
         * Pause the current scan
         */
        fun pause(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_PAUSED, true)
                .apply()
            Log.i(TAG, "Gallery scan paused")
        }

        /**
         * Resume a paused scan
         */
        fun resume(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_PAUSED, false)
                .apply()
            // Re-schedule to resume
            schedule(context, forceRescan = false, resumeScan = true)
            Log.i(TAG, "Gallery scan resumed")
        }

        /**
         * Check if scan is paused
         */
        fun isPaused(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_PAUSED, false)
        }

        /**
         * Get work info for observing progress
         */
        fun getWorkInfoLiveData(context: Context) =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkLiveData(WORK_NAME)
    }

    // Track retry attempts per photo
    private val retryAttempts = mutableMapOf<String, Int>()
    private val isPausedFlag = AtomicBoolean(false)

    // ETA calculation - rolling window of processing times
    private val processingTimes = mutableListOf<Long>()  // ms per photo
    private var scanStartTime = 0L

    // Staging buffer flush tracking
    private var photosSinceLastStagingFlush = 0
    private var lastStagingFlushTime = 0L

    /**
     * Get battery level (0-100) and charging status.
     */
    private fun getBatteryInfo(): Pair<Int, Boolean> {
        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
            applicationContext.registerReceiver(null, filter)
        }

        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else 50

        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        return Pair(batteryPct, isCharging)
    }

    /**
     * Calculate batch size based on memory pressure and battery level.
     * Uses MemoryMonitor for memory-aware sizing, then adjusts for battery.
     */
    private fun calculateAdaptiveBatchSize(): Int {
        // Get memory-based batch size
        val memoryBasedSize = MemoryMonitor.getRecommendedBatchSize()

        // Get battery multiplier
        val (batteryLevel, isCharging) = getBatteryInfo()
        val batteryMultiplier = when {
            isCharging -> BATTERY_CHARGING_MULTIPLIER
            batteryLevel > 50 -> BATTERY_HIGH_MULTIPLIER
            batteryLevel > 30 -> BATTERY_MEDIUM_MULTIPLIER
            batteryLevel > 15 -> BATTERY_LOW_MULTIPLIER
            else -> BATTERY_CRITICAL_MULTIPLIER
        }

        // Combine memory and battery factors
        val adjustedSize = (memoryBasedSize * batteryMultiplier).toInt()
        val finalSize = adjustedSize.coerceIn(MIN_BATCH_SIZE, MAX_BATCH_SIZE)

        val memoryInfo = MemoryMonitor.getMemoryInfo()
        Log.v(TAG, "Adaptive batch: memory=${memoryInfo.pressure}, " +
                "battery=$batteryLevel%${if (isCharging) "(charging)" else ""}, " +
                "memoryBased=$memoryBasedSize, adjusted=$finalSize")

        return finalSize
    }

    /**
     * Check if memory is critically low and we should pause.
     */
    private fun isMemoryCritical(): Boolean {
        return MemoryMonitor.isCriticallyLow()
    }

    /**
     * Record photo processing time for ETA calculation.
     */
    private fun recordProcessingTime(timeMs: Long) {
        processingTimes.add(timeMs)
        // Keep only recent samples
        while (processingTimes.size > ETA_SAMPLE_SIZE) {
            processingTimes.removeAt(0)
        }
    }

    /**
     * Calculate estimated time remaining in seconds.
     */
    private fun calculateETASeconds(remaining: Int): Long {
        if (processingTimes.isEmpty() || remaining <= 0) return 0

        // Average processing time per photo
        val avgTimeMs = processingTimes.average()

        // Estimated remaining time
        val etaMs = (avgTimeMs * remaining).toLong()
        return etaMs / 1000
    }

    /**
     * Calculate photos per second processing rate.
     */
    private fun calculatePhotosPerSecond(): Float {
        if (processingTimes.isEmpty()) return 0f
        val avgTimeMs = processingTimes.average()
        return if (avgTimeMs > 0) (1000f / avgTimeMs.toFloat()) else 0f
    }

    /**
     * Save checkpoint to SharedPreferences.
     */
    private fun saveCheckpoint(scanned: Int, faces: Int, failed: Int) {
        applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_CHECKPOINT_SCANNED, scanned)
            .putInt(KEY_CHECKPOINT_FACES, faces)
            .putInt(KEY_CHECKPOINT_FAILED, failed)
            .putLong(KEY_CHECKPOINT_TIMESTAMP, System.currentTimeMillis())
            .apply()
        Log.d(TAG, "Checkpoint saved: scanned=$scanned, faces=$faces, failed=$failed")
    }

    /**
     * Load checkpoint from SharedPreferences.
     * Returns (scanned, faces, failed) or null if no checkpoint.
     */
    private fun loadCheckpoint(): Triple<Int, Int, Int>? {
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val timestamp = prefs.getLong(KEY_CHECKPOINT_TIMESTAMP, 0)

        // Only use checkpoint if less than 1 hour old
        if (System.currentTimeMillis() - timestamp > 60 * 60 * 1000) {
            clearCheckpoint()
            return null
        }

        val scanned = prefs.getInt(KEY_CHECKPOINT_SCANNED, -1)
        val faces = prefs.getInt(KEY_CHECKPOINT_FACES, -1)
        val failed = prefs.getInt(KEY_CHECKPOINT_FAILED, -1)

        return if (scanned >= 0) {
            Log.d(TAG, "Checkpoint loaded: scanned=$scanned, faces=$faces, failed=$failed")
            Triple(scanned, faces, failed)
        } else {
            null
        }
    }

    /**
     * Clear checkpoint data.
     */
    private fun clearCheckpoint() {
        applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_CHECKPOINT_SCANNED)
            .remove(KEY_CHECKPOINT_FACES)
            .remove(KEY_CHECKPOINT_FAILED)
            .remove(KEY_CHECKPOINT_TIMESTAMP)
            .apply()
    }

    /**
     * Check if scan is paused by user.
     */
    private fun checkPaused(): Boolean {
        val paused = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PAUSED, false)
        isPausedFlag.set(paused)
        return paused
    }

    /**
     * Process a single photo with timeout using anchor-based clustering.
     * Returns the result or null if timed out.
     */
    private suspend fun processPhotoWithTimeout(
        photoUri: String,
        photoTimestamp: Long? = null
    ): FaceRepository.ProcessingResult? {
        return try {
            withTimeout(PHOTO_TIMEOUT_MS) {
                repository.processPhotoDetectionOnly(photoUri)
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Photo processing timed out: $photoUri")
            null
        }
    }

    /**
     * Calculate optimal parallelism based on device capabilities and current load.
     */
    private fun calculateParallelism(): Int {
        val availableProcessors = Runtime.getRuntime().availableProcessors()
        val memoryPressure = MemoryMonitor.getMemoryPressure()
        val (batteryLevel, isCharging) = getBatteryInfo()

        // Base parallelism on CPU cores (use half to leave room for UI)
        val cpuBasedParallelism = maxOf(2, availableProcessors / 2)

        // Adjust for memory pressure
        val memoryMultiplier = when (memoryPressure) {
            MemoryMonitor.MemoryPressure.LOW -> 1.0f
            MemoryMonitor.MemoryPressure.MEDIUM -> 0.75f
            MemoryMonitor.MemoryPressure.HIGH -> 0.5f
            MemoryMonitor.MemoryPressure.CRITICAL -> 0.25f
        }

        // Adjust for battery
        val batteryMultiplier = when {
            isCharging -> 1.5f
            batteryLevel > 50 -> 1.0f
            batteryLevel > 20 -> 0.75f
            else -> 0.5f
        }

        val adjustedParallelism = (cpuBasedParallelism * memoryMultiplier * batteryMultiplier).toInt()
        return adjustedParallelism.coerceIn(MIN_PARALLEL_PHOTOS, MAX_PARALLEL_PHOTOS)
    }

    /**
     * Process multiple photos in parallel using anchor-based clustering.
     * Returns aggregated results.
     */
    private suspend fun processPhotosInParallel(
        photos: List<ScannedPhotoEntity>
    ): List<Pair<String, FaceRepository.ProcessingResult?>> = coroutineScope {
        val parallelism = calculateParallelism()
        Log.d(TAG, "Processing ${photos.size} photos with parallelism=$parallelism (anchor clustering)")

        // Process photos in chunks based on parallelism
        photos.chunked(parallelism).flatMap { chunk ->
            chunk.map { photo ->
                async(Dispatchers.Default) {
                    // Use dateTaken for temporal context, fallback to dateAdded
                    val photoTimestamp = photo.dateTaken ?: photo.dateAdded
                    val result = try {
                        withTimeout(PHOTO_TIMEOUT_MS) {
                            repository.processPhotoDetectionOnly(photo.photoUri)
                        }
                    } catch (e: TimeoutCancellationException) {
                        Log.w(TAG, "Photo timed out: ${photo.photoUri}")
                        null
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing ${photo.photoUri}: ${e.message}")
                        FaceRepository.ProcessingResult.Error(e.message ?: "Unknown error")
                    }
                    photo.photoUri to result
                }
            }.awaitAll()
        }
    }

    /**
     * Calculate retry delay with exponential backoff.
     */
    private fun calculateRetryDelay(attempt: Int): Long {
        return RETRY_BASE_DELAY_MS * (1 shl minOf(attempt, 5))  // Cap at 32 seconds
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val forceRescan = inputData.getBoolean(KEY_FORCE_RESCAN, false)
            val resumeScan = inputData.getBoolean(KEY_RESUME_SCAN, false)

            Log.i(TAG, "Starting gallery scan (force=$forceRescan, resume=$resumeScan)")
            MemoryMonitor.logMemoryStatus(TAG)

            scanStartTime = System.currentTimeMillis()
            processingTimes.clear()

            // Clear paused flag on fresh start
            if (!resumeScan) {
                applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_PAUSED, false)
                    .apply()
                clearCheckpoint()
            }

            if (forceRescan) {
                repository.clearAllData()
                clearCheckpoint()
            }

            // Query all photos from MediaStore
            val photos = queryGalleryPhotos()
            if (photos.isEmpty()) {
                Log.i(TAG, "No photos found in gallery")
                clearCheckpoint()
                return@withContext Result.success()
            }

            Log.i(TAG, "Found ${photos.size} photos in gallery")

            // Add photos to database (will skip already-added ones)
            repository.addPhotosToScan(photos)

            // Start or resume scan progress tracking
            if (!resumeScan) {
                repository.startNewScan(photos.size)
            }

            // Load checkpoint if resuming
            var scannedCount = 0
            var totalFaces = 0
            var failedCount = 0
            var skippedCount = 0

            if (resumeScan) {
                loadCheckpoint()?.let { (saved, faces, failed) ->
                    scannedCount = saved
                    totalFaces = faces
                    failedCount = failed
                    Log.i(TAG, "Resuming from checkpoint: scanned=$scannedCount, faces=$totalFaces")
                }
            }

            var photosInBatch = 0
            var lastCheckpointScanned = scannedCount

            // Main processing loop - PARALLEL PROCESSING for maximum speed
            val batchStartTime = System.currentTimeMillis()

            while (true) {
                // Check if cancelled by WorkManager
                if (isStopped) {
                    Log.i(TAG, "Worker stopped by system, saving checkpoint at $scannedCount/${photos.size}")
                    saveCheckpoint(scannedCount, totalFaces, failedCount)
                    return@withContext Result.failure()
                }

                // Check if paused by user
                if (checkPaused()) {
                    Log.i(TAG, "Scan paused by user at $scannedCount/${photos.size}")
                    saveCheckpoint(scannedCount, totalFaces, failedCount)
                    return@withContext Result.success(
                        Data.Builder()
                            .putInt(KEY_PROGRESS_TOTAL, photos.size)
                            .putInt(KEY_PROGRESS_SCANNED, scannedCount)
                            .putInt(KEY_PROGRESS_FACES, totalFaces)
                            .putInt(KEY_PROGRESS_FAILED, failedCount)
                            .putInt(KEY_PROGRESS_SKIPPED, skippedCount)
                            .build()
                    )
                }

                // Check memory pressure before batch
                if (isMemoryCritical()) {
                    Log.w(TAG, "Critical memory pressure detected, requesting GC...")
                    MemoryMonitor.requestGC()
                    delay(200)

                    if (isMemoryCritical()) {
                        Log.e(TAG, "Memory still critical after GC, saving checkpoint and stopping")
                        saveCheckpoint(scannedCount, totalFaces, failedCount)
                        return@withContext Result.retry()
                    }
                }

                // Calculate adaptive batch size based on memory and battery
                val batchSize = calculateAdaptiveBatchSize()

                // Get next batch of pending photos
                val pendingPhotos = repository.getPendingPhotos(batchSize)
                if (pendingPhotos.isEmpty()) {
                    break
                }

                val parallelBatchStartTime = System.currentTimeMillis()

                // PARALLEL PROCESSING - Process entire batch concurrently
                val results = processPhotosInParallel(pendingPhotos)

                // Process results
                for ((photoUri, result) in results) {
                    scannedCount++
                    photosInBatch++

                    when (result) {
                        is FaceRepository.ProcessingResult.Success -> {
                            totalFaces += result.facesDetected
                            retryAttempts.remove(photoUri)
                        }
                        is FaceRepository.ProcessingResult.NoFaces -> {
                            retryAttempts.remove(photoUri)
                        }
                        is FaceRepository.ProcessingResult.Error -> {
                            failedCount++
                            val attempts = (retryAttempts[photoUri] ?: 0) + 1
                            retryAttempts[photoUri] = attempts

                            if (attempts < MAX_RETRY_ATTEMPTS) {
                                repository.markPhotoForRetry(photoUri)
                            } else {
                                repository.markPhotoFailed(photoUri, "Max retries exceeded")
                                skippedCount++
                            }
                        }
                        null -> {
                            failedCount++
                            val attempts = (retryAttempts[photoUri] ?: 0) + 1
                            retryAttempts[photoUri] = attempts

                            if (attempts >= MAX_RETRY_ATTEMPTS) {
                                repository.markPhotoFailed(photoUri, "Timeout")
                                skippedCount++
                            } else {
                                repository.markPhotoForRetry(photoUri)
                            }
                        }
                    }
                }

                // Record batch processing time for ETA (average per photo in batch)
                val batchTime = System.currentTimeMillis() - parallelBatchStartTime
                val avgTimePerPhoto = if (pendingPhotos.isNotEmpty()) batchTime / pendingPhotos.size else batchTime
                recordProcessingTime(avgTimePerPhoto)

                // Flush staging buffer periodically for multi-stage clustering
                photosSinceLastStagingFlush += pendingPhotos.size
                val timeSinceFlush = System.currentTimeMillis() - lastStagingFlushTime
                if (photosSinceLastStagingFlush >= STAGING_FLUSH_INTERVAL_PHOTOS ||
                    timeSinceFlush >= STAGING_FLUSH_INTERVAL_MS) {
                    try {
                        val stagingSize = repository.getStagingBufferSize()
                        if (stagingSize > 0) {
                            Log.d(TAG, "Flushing staging buffer ($stagingSize faces)")
                            repository.flushStagingBuffer()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to flush staging buffer", e)
                    }
                    photosSinceLastStagingFlush = 0
                    lastStagingFlushTime = System.currentTimeMillis()
                }

                // Calculate ETA and update progress
                val remaining = photos.size - scannedCount
                val etaSeconds = calculateETASeconds(remaining)
                val photosPerSecond = calculatePhotosPerSecond()
                val memoryPressure = MemoryMonitor.getMemoryPressure().name
                val parallelism = calculateParallelism()

                // Update progress
                setProgress(
                    Data.Builder()
                        .putInt(KEY_PROGRESS_TOTAL, photos.size)
                        .putInt(KEY_PROGRESS_SCANNED, scannedCount)
                        .putInt(KEY_PROGRESS_FACES, totalFaces)
                        .putInt(KEY_PROGRESS_FAILED, failedCount)
                        .putInt(KEY_PROGRESS_SKIPPED, skippedCount)
                        .putLong(KEY_PROGRESS_ETA_SECONDS, etaSeconds)
                        .putFloat(KEY_PROGRESS_PHOTOS_PER_SECOND, photosPerSecond)
                        .putString(KEY_PROGRESS_MEMORY_PRESSURE, memoryPressure)
                        .putInt(KEY_PROGRESS_BATCH_SIZE, batchSize)
                        .build()
                )

                // Save checkpoint periodically
                if (scannedCount - lastCheckpointScanned >= CHECKPOINT_INTERVAL) {
                    saveCheckpoint(scannedCount, totalFaces, failedCount)
                    lastCheckpointScanned = scannedCount
                }

                // Log progress periodically
                if (scannedCount % 100 == 0 || scannedCount == photos.size) {
                    val (batteryLevel, isCharging) = getBatteryInfo()
                    val memInfo = MemoryMonitor.getMemoryInfo()
                    val etaMinutes = etaSeconds / 60
                    val etaSecs = etaSeconds % 60
                    val elapsedSeconds = (System.currentTimeMillis() - batchStartTime) / 1000

                    Log.i(TAG, "Progress: $scannedCount/${photos.size} (${100 * scannedCount / photos.size}%), " +
                            "faces: $totalFaces, failed: $failedCount, " +
                            "ETA: ${etaMinutes}m ${etaSecs}s, " +
                            "rate: ${"%.1f".format(photosPerSecond)} photos/s, " +
                            "parallelism: $parallelism, batch: $batchSize, " +
                            "elapsed: ${elapsedSeconds}s, " +
                            "battery: $batteryLevel%${if (isCharging) " (charging)" else ""}, " +
                            "memory: ${memInfo.usedHeapMB}/${memInfo.maxHeapMB}MB (${memInfo.pressure})")
                }

                // Yield between batches
                yield()
                photosInBatch = 0
            }

            // Clear checkpoint on successful completion
            clearCheckpoint()

            // Final flush of staging buffer before completing
            try {
                val finalStagingSize = repository.getStagingBufferSize()
                if (finalStagingSize > 0) {
                    Log.i(TAG, "Final staging buffer flush ($finalStagingSize faces)")
                    repository.flushStagingBuffer()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to flush staging buffer at completion", e)
            }

            // Mark scan complete
            repository.markScanComplete()

            Log.i(TAG, "Gallery scan complete. Scanned: $scannedCount, Faces: $totalFaces, " +
                    "Failed: $failedCount, Skipped: $skippedCount")

            // Run batch clustering on ALL detected faces
            Log.i(TAG, "Starting batch clustering on all detected faces...")
            try {
                val clusteringResult = batchClusteringService.runBatchClustering()
                Log.i(TAG, "Batch clustering: ${clusteringResult.clusteredFaces} faces in ${clusteringResult.clustersCreated} clusters (${clusteringResult.durationMs}ms)")
            } catch (e: Exception) {
                Log.e(TAG, "Error during batch clustering", e)
            }

            // Run the full refinement pipeline:
            // - Auto-merge high-confidence similar clusters
            // - Detect potential splits (for user review)
            // - Sync HNSW index with database
            // - Recalculate photo counts
            // - Fix orphaned clusters
            Log.i(TAG, "Starting cluster refinement pipeline...")
            val refinementResult = repository.runRefinementPipeline()

            Log.i(TAG, "Refinement pipeline complete: " +
                    "autoMerges=${refinementResult.autoMergesApplied}, " +
                    "splitCandidates=${refinementResult.splitCandidatesFound}, " +
                    "indexFixes=${refinementResult.indexDiscrepanciesFixed}, " +
                    "orphanedFixed=${refinementResult.orphanedClustersFixed}")

            if (refinementResult.error != null) {
                Log.w(TAG, "Refinement pipeline had error: ${refinementResult.error}")
            }

            // Log candidates for user review
            if (refinementResult.mergeCandidatesForReview.isNotEmpty()) {
                Log.i(TAG, "Merge suggestions for user review: ${refinementResult.mergeCandidatesForReview.size}")
            }
            if (refinementResult.splitCandidatesForReview.isNotEmpty()) {
                Log.i(TAG, "Split suggestions for user review: ${refinementResult.splitCandidatesForReview.size}")
            }

            // Reassign thumbnails to best faces (prioritizing visible eyes and quality)
            // This ensures thumbnails show actual faces, not hair/back of head
            Log.i(TAG, "Reassigning thumbnails to best faces...")
            val thumbnailUpdates = repository.reassignAllThumbnails()
            Log.i(TAG, "Reassigned $thumbnailUpdates thumbnails to best faces")

            // Cleanup expired history
            repository.cleanupExpiredHistory()

            Result.success(
                Data.Builder()
                    .putInt(KEY_PROGRESS_TOTAL, photos.size)
                    .putInt(KEY_PROGRESS_SCANNED, scannedCount)
                    .putInt(KEY_PROGRESS_FACES, totalFaces)
                    .putInt(KEY_PROGRESS_FAILED, failedCount)
                    .putInt(KEY_PROGRESS_SKIPPED, skippedCount)
                    .build()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Gallery scan failed", e)
            Result.failure()
        }
    }

    /**
     * Query all photos from MediaStore
     */
    private fun queryGalleryPhotos(): List<ScannedPhotoEntity> {
        val photos = mutableListOf<ScannedPhotoEntity>()

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.SIZE
        )

        val selection = "${MediaStore.Images.Media.SIZE} > 0"  // Only files with size
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        applicationContext.contentResolver.query(
            collection,
            projection,
            selection,
            null,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val dateTakenColumn = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
            val bucketColumn = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn)
                val dateAdded = cursor.getLong(dateAddedColumn) * 1000  // Convert to millis
                val dateTaken = if (dateTakenColumn >= 0) cursor.getLong(dateTakenColumn) else null
                val bucket = if (bucketColumn >= 0) cursor.getString(bucketColumn) else null

                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id
                )

                photos.add(
                    ScannedPhotoEntity(
                        photoUri = contentUri.toString(),
                        dateAdded = dateAdded,
                        dateTaken = dateTaken,
                        scanStatus = ScanStatus.PENDING,
                        lastScanned = null,
                        displayName = name,
                        bucketName = bucket
                    )
                )
            }
        }

        return photos
    }
}
