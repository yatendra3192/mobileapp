package com.aiezzy.slideshowmaker.face.scanner

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.work.*
import com.aiezzy.slideshowmaker.data.face.entities.ScannedPhotoEntity
import com.aiezzy.slideshowmaker.data.face.entities.ScanStatus
import com.aiezzy.slideshowmaker.face.FaceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker for scanning gallery photos for faces in the background.
 */
class GalleryScanWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "GalleryScanWorker"
        const val WORK_NAME = "gallery_face_scan"

        // Input data keys
        const val KEY_FORCE_RESCAN = "force_rescan"

        // Progress data keys
        const val KEY_PROGRESS_TOTAL = "progress_total"
        const val KEY_PROGRESS_SCANNED = "progress_scanned"
        const val KEY_PROGRESS_FACES = "progress_faces"

        /**
         * Schedule the gallery scan work
         */
        fun schedule(context: Context, forceRescan: Boolean = false) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()

            val inputData = Data.Builder()
                .putBoolean(KEY_FORCE_RESCAN, forceRescan)
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

            Log.i(TAG, "Gallery scan work scheduled")
        }

        /**
         * Cancel any running scan
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "Gallery scan work cancelled")
        }

        /**
         * Get work info for observing progress
         */
        fun getWorkInfoLiveData(context: Context) =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkLiveData(WORK_NAME)
    }

    private val repository = FaceRepository.getInstance(applicationContext)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val forceRescan = inputData.getBoolean(KEY_FORCE_RESCAN, false)

            Log.i(TAG, "Starting gallery scan, forceRescan: $forceRescan")

            if (forceRescan) {
                repository.clearAllData()
            }

            // Query all photos from MediaStore
            val photos = queryGalleryPhotos()
            if (photos.isEmpty()) {
                Log.i(TAG, "No photos found in gallery")
                return@withContext Result.success()
            }

            Log.i(TAG, "Found ${photos.size} photos in gallery")

            // Add photos to database
            repository.addPhotosToScan(photos)

            // Start scan progress tracking
            repository.startNewScan(photos.size)

            // Process photos in batches
            var scannedCount = 0
            var totalFaces = 0

            while (true) {
                // Check if cancelled
                if (isStopped) {
                    Log.i(TAG, "Worker stopped, scan incomplete")
                    return@withContext Result.failure()
                }

                // Get next batch of pending photos
                val pendingPhotos = repository.getPendingPhotos(20)
                if (pendingPhotos.isEmpty()) {
                    break
                }

                for (photo in pendingPhotos) {
                    if (isStopped) break

                    val result = repository.processPhoto(photo.photoUri)
                    scannedCount++

                    when (result) {
                        is FaceRepository.ProcessingResult.Success -> {
                            totalFaces += result.facesDetected
                        }
                        is FaceRepository.ProcessingResult.NoFaces -> {
                            // No faces found, continue
                        }
                        is FaceRepository.ProcessingResult.Error -> {
                            Log.w(TAG, "Error processing ${photo.photoUri}: ${result.message}")
                        }
                    }

                    // Update progress
                    setProgress(
                        Data.Builder()
                            .putInt(KEY_PROGRESS_TOTAL, photos.size)
                            .putInt(KEY_PROGRESS_SCANNED, scannedCount)
                            .putInt(KEY_PROGRESS_FACES, totalFaces)
                            .build()
                    )

                    // Log progress periodically
                    if (scannedCount % 50 == 0) {
                        Log.i(TAG, "Scan progress: $scannedCount/${photos.size}, faces found: $totalFaces")
                    }
                }
            }

            // Mark scan complete
            repository.markScanComplete()

            Log.i(TAG, "Gallery scan complete. Scanned: $scannedCount, Faces: $totalFaces")
            Result.success(
                Data.Builder()
                    .putInt(KEY_PROGRESS_TOTAL, photos.size)
                    .putInt(KEY_PROGRESS_SCANNED, scannedCount)
                    .putInt(KEY_PROGRESS_FACES, totalFaces)
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
