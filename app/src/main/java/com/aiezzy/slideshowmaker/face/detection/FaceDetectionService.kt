package com.aiezzy.slideshowmaker.face.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Service for detecting faces in images using ML Kit
 */
class FaceDetectionService(private val context: Context) {

    companion object {
        private const val TAG = "FaceDetectionService"
        private const val MAX_IMAGE_DIMENSION = 1024
        private const val MIN_FACE_SIZE = 0.05f  // Minimum face size relative to image
    }

    private val detector: FaceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(MIN_FACE_SIZE)
            .enableTracking()
            .build()
        FaceDetection.getClient(options)
    }

    /**
     * Detected face result with bounding box and cropped bitmap
     */
    data class DetectedFace(
        val faceId: String,
        val boundingBox: RectF,
        val confidence: Float,
        val faceBitmap: Bitmap?,
        val imageWidth: Int,
        val imageHeight: Int
    )

    /**
     * Detect faces in an image from URI
     */
    suspend fun detectFaces(imageUri: Uri): Result<List<DetectedFace>> = withContext(Dispatchers.IO) {
        try {
            val bitmap = loadAndResizeBitmap(imageUri)
            if (bitmap == null) {
                return@withContext Result.failure(Exception("Failed to load image: $imageUri"))
            }

            val faces = detectFacesInBitmap(bitmap)
            val detectedFaces = faces.map { face ->
                val boundingBox = RectF(face.boundingBox)
                // Normalize bounding box to 0-1 range
                val normalizedBox = RectF(
                    boundingBox.left / bitmap.width,
                    boundingBox.top / bitmap.height,
                    boundingBox.right / bitmap.width,
                    boundingBox.bottom / bitmap.height
                )

                // Crop face bitmap with padding
                val faceBitmap = cropFaceWithPadding(bitmap, boundingBox, 0.3f)

                DetectedFace(
                    faceId = UUID.randomUUID().toString(),
                    boundingBox = normalizedBox,
                    confidence = calculateFaceQuality(face, boundingBox, bitmap.width, bitmap.height),
                    faceBitmap = faceBitmap,
                    imageWidth = bitmap.width,
                    imageHeight = bitmap.height
                )
            }

            // Recycle bitmap if we created face bitmaps
            if (detectedFaces.isNotEmpty()) {
                // Don't recycle yet - caller may need it
            }

            Result.success(detectedFaces)
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting faces in $imageUri", e)
            Result.failure(e)
        }
    }

    /**
     * Detect faces in a bitmap
     */
    private suspend fun detectFacesInBitmap(bitmap: Bitmap): List<Face> =
        suspendCancellableCoroutine { continuation ->
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            detector.process(inputImage)
                .addOnSuccessListener { faces ->
                    continuation.resume(faces)
                }
                .addOnFailureListener { e ->
                    continuation.resumeWithException(e)
                }
        }

    /**
     * Load and resize bitmap from URI with EXIF orientation handling
     */
    private fun loadAndResizeBitmap(uri: Uri): Bitmap? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null

            // First decode bounds only
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeStream(inputStream, null, options)
            inputStream.close()

            // Calculate sample size
            val sampleSize = calculateSampleSize(
                options.outWidth,
                options.outHeight,
                MAX_IMAGE_DIMENSION,
                MAX_IMAGE_DIMENSION
            )

            // Decode with sample size
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            val newInputStream = context.contentResolver.openInputStream(uri) ?: return null
            val bitmap = BitmapFactory.decodeStream(newInputStream, null, decodeOptions)
            newInputStream.close()

            if (bitmap == null) return null

            // Handle EXIF orientation
            val rotatedBitmap = handleExifOrientation(uri, bitmap)
            if (rotatedBitmap != bitmap) {
                bitmap.recycle()
            }

            rotatedBitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error loading bitmap from $uri", e)
            null
        }
    }

    /**
     * Handle EXIF orientation for proper face detection
     */
    private fun handleExifOrientation(uri: Uri, bitmap: Bitmap): Bitmap {
        return try {
            val inputStream: InputStream = context.contentResolver.openInputStream(uri) ?: return bitmap
            val exif = ExifInterface(inputStream)
            inputStream.close()

            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )

            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
                else -> return bitmap
            }

            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling EXIF orientation", e)
            bitmap
        }
    }

    /**
     * Calculate sample size for efficient bitmap loading
     */
    private fun calculateSampleSize(
        width: Int,
        height: Int,
        maxWidth: Int,
        maxHeight: Int
    ): Int {
        var sampleSize = 1
        if (width > maxWidth || height > maxHeight) {
            val halfWidth = width / 2
            val halfHeight = height / 2
            while ((halfWidth / sampleSize) >= maxWidth && (halfHeight / sampleSize) >= maxHeight) {
                sampleSize *= 2
            }
        }
        return sampleSize
    }

    /**
     * Crop face from bitmap with padding
     */
    private fun cropFaceWithPadding(
        bitmap: Bitmap,
        boundingBox: RectF,
        paddingRatio: Float
    ): Bitmap? {
        return try {
            val width = boundingBox.width()
            val height = boundingBox.height()
            val paddingX = width * paddingRatio
            val paddingY = height * paddingRatio

            val left = (boundingBox.left - paddingX).coerceAtLeast(0f).toInt()
            val top = (boundingBox.top - paddingY).coerceAtLeast(0f).toInt()
            val right = (boundingBox.right + paddingX).coerceAtMost(bitmap.width.toFloat()).toInt()
            val bottom = (boundingBox.bottom + paddingY).coerceAtMost(bitmap.height.toFloat()).toInt()

            val cropWidth = right - left
            val cropHeight = bottom - top

            if (cropWidth <= 0 || cropHeight <= 0) return null

            Bitmap.createBitmap(bitmap, left, top, cropWidth, cropHeight)
        } catch (e: Exception) {
            Log.e(TAG, "Error cropping face", e)
            null
        }
    }

    /**
     * Calculate face quality score based on size and position
     */
    private fun calculateFaceQuality(
        face: Face,
        boundingBox: RectF,
        imageWidth: Int,
        imageHeight: Int
    ): Float {
        // Base score from face size relative to image
        val faceArea = boundingBox.width() * boundingBox.height()
        val imageArea = imageWidth.toFloat() * imageHeight
        val sizeScore = (faceArea / imageArea).coerceIn(0f, 1f) * 100

        // Penalize faces at edges (might be partially cut off)
        val centerX = boundingBox.centerX() / imageWidth
        val centerY = boundingBox.centerY() / imageHeight
        val centerScore = 1f - (kotlin.math.abs(centerX - 0.5f) + kotlin.math.abs(centerY - 0.5f))

        // Combine scores
        return (sizeScore * 0.7f + centerScore * 30f).coerceIn(0f, 100f)
    }

    /**
     * Close the detector when done
     */
    fun close() {
        detector.close()
    }
}
