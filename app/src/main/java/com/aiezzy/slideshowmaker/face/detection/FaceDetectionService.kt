package com.aiezzy.slideshowmaker.face.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Production-grade face detection service using ML Kit.
 *
 * Key improvements over prototype:
 * - Comprehensive face quality scoring (pose, blur, size, lighting)
 * - Proper bitmap lifecycle management to prevent memory leaks
 * - Accurate landmark coordinate translation for face alignment
 * - Configurable quality thresholds for filtering low-quality faces
 * - Thread-safe detector initialization
 * - HIGH-QUALITY FACE CROPS: Uses BitmapRegionDecoder to crop faces directly from
 *   ORIGINAL resolution images without loading the full image into memory
 */
class FaceDetectionService(private val context: Context) {

    companion object {
        private const val TAG = "FaceDetectionService"

        // ============ TURBO SCAN MODE ============
        // Set to true for maximum speed (1000+ photos/minute)
        // Set to false for maximum accuracy (better clustering)
        var TURBO_SCAN_MODE = true  // Default to fast scanning

        // Image processing configuration - ADAPTIVE based on scan mode
        // Detection uses downsampled image for speed, cropping uses ORIGINAL via BitmapRegionDecoder
        private val MAX_DETECTION_DIMENSION: Int
            get() = if (TURBO_SCAN_MODE) 640 else 1024  // 640px = 2x faster detection
        private const val MAX_CROP_DIMENSION = 3000       // Fallback max size if BitmapRegionDecoder fails
        private val MIN_FACE_SIZE_RATIO: Float
            get() = if (TURBO_SCAN_MODE) 0.03f else 0.02f  // Skip smaller faces in turbo mode

        // Face quality thresholds - RAISED for better clustering accuracy
        private const val MIN_FACE_PIXELS = 50          // Minimum face dimension in pixels (raised from 40)
        private const val MIN_QUALITY_SCORE = 25f       // Minimum quality score to keep face (raised from 15)
        private const val EMBEDDING_QUALITY_THRESHOLD = 45f  // Raised from 30f for better embeddings

        // Face crop configuration
        private const val FACE_CROP_PADDING = 0.35f     // 35% padding around face for context

        // Pose angle limits (degrees) - STRICTER for better clustering
        private const val MAX_HEAD_EULER_Y = 25f        // Yaw (left/right turn) - reduced from 35
        private const val MAX_HEAD_EULER_Z = 20f        // Roll (head tilt) - reduced from 25

        // Minimum face size in original image for high-quality embedding
        private const val MIN_FACE_SIZE_FOR_EMBEDDING = 80  // At least 80x80 pixels in original

        // ============ NEW: Quality Tier Thresholds ============
        // These thresholds determine what a face can do in the clustering pipeline
        private const val ANCHOR_QUALITY_THRESHOLD = 65f      // Can form clusters, be representative
        private const val CLUSTERING_QUALITY_THRESHOLD = 50f  // Can join clusters, affect centroid
        private const val DISPLAY_QUALITY_THRESHOLD = 35f     // Visible in UI only, no clustering
        private const val ANCHOR_SHARPNESS_THRESHOLD = 15f    // Min sharpness for anchors
        private const val CLUSTERING_SHARPNESS_THRESHOLD = 10f // Min sharpness for clustering
        private const val ANCHOR_EYE_THRESHOLD = 6f           // Min eye visibility for anchors

        // Aspect ratio limits for distorted face rejection
        private const val MAX_ASPECT_RATIO = 2.0f  // Reject if width/height > 2.0
        private const val MIN_ASPECT_RATIO = 0.5f  // Reject if width/height < 0.5

        // Edge clipping threshold
        private const val MAX_EDGE_CLIP_RATIO = 0.20f  // Reject if >20% outside frame
    }

    /**
     * Quality tier determines what a face can do in the clustering pipeline.
     *
     * ANCHOR:       High-quality face that can form new clusters and be representative
     * CLUSTERING:   Medium-quality face that can join clusters and influence centroid
     * DISPLAY_ONLY: Low-quality face visible in UI but excluded from clustering decisions
     * REJECTED:     Face too poor quality to store
     *
     * This tiered approach prevents blurry/low-quality faces from affecting clustering
     * while still showing them in the UI (users expect to see all detected faces).
     */
    enum class FaceQualityTier {
        ANCHOR,        // >= 65 score, sharp, good eyes: Can form clusters, be representative
        CLUSTERING,    // >= 50 score, reasonably sharp: Can join clusters, affect centroid
        DISPLAY_ONLY,  // >= 35 score: Visible in UI, excluded from clustering
        REJECTED;      // < 35 score: Not stored

        companion object {
            /**
             * Determine quality tier from face quality metrics.
             * Uses multiple criteria to ensure robust tier assignment.
             */
            fun fromQuality(quality: FaceQuality): FaceQualityTier {
                val score = quality.overallScore
                val sharpness = quality.sharpnessScore
                val eyeVisibility = quality.eyeVisibilityScore

                return when {
                    // ANCHOR: High overall + sharp + good eyes
                    score >= ANCHOR_QUALITY_THRESHOLD &&
                    sharpness >= ANCHOR_SHARPNESS_THRESHOLD &&
                    eyeVisibility >= ANCHOR_EYE_THRESHOLD -> ANCHOR

                    // CLUSTERING: Medium overall + reasonably sharp
                    score >= CLUSTERING_QUALITY_THRESHOLD &&
                    sharpness >= CLUSTERING_SHARPNESS_THRESHOLD -> CLUSTERING

                    // DISPLAY_ONLY: Low overall but passable
                    score >= DISPLAY_QUALITY_THRESHOLD -> DISPLAY_ONLY

                    // REJECTED: Too poor quality
                    else -> REJECTED
                }
            }
        }

        /** Whether this tier allows forming new clusters */
        val canFormCluster: Boolean
            get() = this == ANCHOR

        /** Whether this tier allows joining existing clusters */
        val canJoinCluster: Boolean
            get() = this == ANCHOR || this == CLUSTERING

        /** Whether this tier allows updating cluster centroid */
        val canUpdateCentroid: Boolean
            get() = this == ANCHOR || this == CLUSTERING

        /** Whether this face should be stored in the database */
        val shouldStore: Boolean
            get() = this != REJECTED

        /** Whether this face should be visible in the UI */
        val isUIVisible: Boolean
            get() = this != REJECTED
    }

    /**
     * Result from loading an image with scale information.
     */
    private data class LoadedImage(
        val bitmap: Bitmap,
        val originalWidth: Int,
        val originalHeight: Int,
        val scaleX: Float,  // Multiply detection coords by this to get original coords
        val scaleY: Float
    )

    private val detector: FaceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setMinFaceSize(MIN_FACE_SIZE_RATIO)
            .enableTracking()
            .build()
        FaceDetection.getClient(options)
    }

    /**
     * Detected face with quality metrics and landmarks for alignment.
     */
    data class DetectedFace(
        val faceId: String,
        val boundingBox: RectF,           // Normalized 0-1 coordinates
        val quality: FaceQuality,          // Comprehensive quality metrics
        val qualityTier: FaceQualityTier,  // NEW: Quality tier for clustering decisions
        val faceBitmap: Bitmap?,           // Cropped face for embedding
        val imageWidth: Int,
        val imageHeight: Int,
        // Facial landmarks in crop-relative coordinates
        val leftEye: PointF?,
        val rightEye: PointF?,
        val nose: PointF?,
        val leftMouth: PointF?,
        val rightMouth: PointF?,
        // Tracking ID from ML Kit (for video/burst deduplication)
        val trackingId: Int?
    ) {
        /** Whether this face meets minimum quality for embedding generation */
        val isSuitableForEmbedding: Boolean
            get() = qualityTier.canJoinCluster && faceBitmap != null

        /** Whether this face can form a new cluster */
        val canFormCluster: Boolean
            get() = qualityTier.canFormCluster

        /** Whether this face can join existing clusters */
        val canJoinCluster: Boolean
            get() = qualityTier.canJoinCluster

        /** Whether this face should be stored (even if not clustered) */
        val shouldStore: Boolean
            get() = qualityTier.shouldStore
    }

    /**
     * Point class for landmarks.
     */
    data class PointF(val x: Float, val y: Float)

    /**
     * Comprehensive face quality metrics.
     * Used to filter faces and select best representatives.
     *
     * NEW SCORING FORMULA (100 points max):
     * - Size Score:        0-25 points (larger faces are clearer)
     * - Pose Score:        0-25 points (frontal faces are better)
     * - Sharpness Score:   0-25 points (Laplacian variance for blur detection)
     * - Brightness Score:  0-15 points (optimal lighting, reject under/overexposed)
     * - Eye Visibility:    0-10 points (both eyes detected and visible)
     */
    data class FaceQuality(
        val overallScore: Float,           // 0-100 composite score
        val sizeScore: Float,              // Based on face pixel size (0-25)
        val poseScore: Float,              // Based on head rotation angles (0-25)
        val sharpnessScore: Float,         // Laplacian variance blur detection (0-25)
        val brightnessScore: Float,        // NEW: Lighting quality (0-15)
        val eyeVisibilityScore: Float,     // NEW: Both eyes detected (0-10)
        val positionScore: Float,          // Penalty for edge-cropped faces (internal use)
        val eulerY: Float?,                // Head yaw angle (left/right)
        val eulerZ: Float?,                // Head roll angle (tilt)
        val leftEyeOpenProb: Float?,       // Eye open probability
        val rightEyeOpenProb: Float?,
        val smilingProb: Float?            // Smile probability
    ) {
        /** Flags face as frontal (good for representative selection) */
        val isFrontal: Boolean
            get() = (eulerY?.let { abs(it) < 15f } ?: false) &&
                    (eulerZ?.let { abs(it) < 10f } ?: false)

        /** Flags face as having good lighting */
        val hasGoodLighting: Boolean
            get() = brightnessScore >= 10f

        /** Flags face as having visible eyes */
        val hasVisibleEyes: Boolean
            get() = eyeVisibilityScore >= 7f

        /** Comprehensive check for high-quality face suitable for embedding */
        val isHighQuality: Boolean
            get() = overallScore >= EMBEDDING_QUALITY_THRESHOLD &&
                    sharpnessScore >= 12f &&
                    hasGoodLighting &&
                    hasVisibleEyes
    }

    /**
     * Detect faces in an image from URI.
     *
     * IMPORTANT: Uses two-stage loading for quality:
     * 1. Detection: Uses downsampled image (1024px) for fast ML Kit detection
     * 2. Cropping: Uses BitmapRegionDecoder to crop faces directly from ORIGINAL image
     *    This is memory efficient and preserves full quality (no 3000px limit!)
     *
     * This gives us both speed (fast detection) AND quality (sharp embeddings).
     *
     * @param imageUri Content URI of the image to process
     * @return Result containing list of detected faces or error
     */
    suspend fun detectFaces(imageUri: Uri): Result<List<DetectedFace>> = withContext(Dispatchers.IO) {
        var detectionImage: LoadedImage? = null
        try {
            // STEP 1: Load DOWNSAMPLED image for fast face detection
            detectionImage = loadImageForDetection(imageUri)
            if (detectionImage == null) {
                return@withContext Result.failure(Exception("Failed to load image: $imageUri"))
            }

            val detectionBitmap = detectionImage.bitmap
            val faces = detectFacesInBitmap(detectionBitmap)

            if (faces.isEmpty()) {
                Log.d(TAG, "No faces detected in $imageUri")
                detectionBitmap.recycle()
                return@withContext Result.success(emptyList())
            }

            Log.d(TAG, "Detected ${faces.size} raw faces, cropping from ORIGINAL using BitmapRegionDecoder")

            // STEP 2: For each face, crop directly from ORIGINAL using BitmapRegionDecoder
            // This is memory efficient - we never load the full original image!
            val detectedFaces = faces.mapNotNull { face ->
                processFaceWithRegionDecoder(
                    face = face,
                    imageUri = imageUri,
                    detectionBitmap = detectionBitmap,
                    originalWidth = detectionImage.originalWidth,
                    originalHeight = detectionImage.originalHeight
                )
            }

            // Sort by quality score descending for consistent ordering
            val sortedFaces = detectedFaces.sortedByDescending { it.quality.overallScore }

            Log.d(TAG, "Detected ${sortedFaces.size} faces (${faces.size} raw) in $imageUri")

            // Clean up detection bitmap
            detectionBitmap.recycle()

            Result.success(sortedFaces)
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting faces in $imageUri", e)
            Result.failure(e)
        }
    }

    /**
     * Process a single detected face into our data structure.
     *
     * KEY: Face is detected on downsampled image, but CROPPED from high-res image!
     *
     * @param face ML Kit face detection result (coordinates in detection image space)
     * @param detectionBitmap The downsampled image used for detection
     * @param cropBitmap The high-resolution image used for cropping
     * @param scaleX Scale factor from detection to crop coordinates (X axis)
     * @param scaleY Scale factor from detection to crop coordinates (Y axis)
     * @param originalWidth Original image width (for normalized coordinates)
     * @param originalHeight Original image height (for normalized coordinates)
     */
    private fun processFace(
        face: Face,
        detectionBitmap: Bitmap,
        cropBitmap: Bitmap,
        scaleX: Float,
        scaleY: Float,
        originalWidth: Int,
        originalHeight: Int
    ): DetectedFace? {
        // Bounding box from detection (in detection image coordinates)
        val detectionBox = RectF(face.boundingBox)

        // Filter faces that are too small on detection image
        val faceWidth = detectionBox.width()
        val faceHeight = detectionBox.height()
        if (faceWidth < MIN_FACE_PIXELS || faceHeight < MIN_FACE_PIXELS) {
            Log.v(TAG, "Skipping small face: ${faceWidth.toInt()}x${faceHeight.toInt()} pixels")
            return null
        }

        // NEW: Reject faces with extreme aspect ratios (likely distorted or partial)
        val aspectRatio = faceWidth / faceHeight
        if (aspectRatio > MAX_ASPECT_RATIO || aspectRatio < MIN_ASPECT_RATIO) {
            Log.v(TAG, "Skipping distorted face: aspect ratio=${"%.2f".format(aspectRatio)}")
            return null
        }

        // NEW: Reject faces that are significantly clipped by image edges
        val edgeClipRatio = calculateEdgeClipRatio(detectionBox, detectionBitmap.width, detectionBitmap.height)
        if (edgeClipRatio > MAX_EDGE_CLIP_RATIO) {
            Log.v(TAG, "Skipping edge-clipped face: ${(edgeClipRatio * 100).toInt()}% outside frame")
            return null
        }

        // SCALE bounding box to crop image coordinates
        val cropBox = RectF(
            detectionBox.left * scaleX,
            detectionBox.top * scaleY,
            detectionBox.right * scaleX,
            detectionBox.bottom * scaleY
        )

        // Calculate crop boundaries with padding (on crop image)
        val cropInfo = calculateCropBounds(cropBox, cropBitmap.width, cropBitmap.height, FACE_CROP_PADDING)

        // Crop face bitmap from HIGH-RES image for quality embeddings
        val faceBitmap = cropFace(cropBitmap, cropInfo)

        // Calculate quality metrics using SCALED face size and HIGH-RES face bitmap
        val quality = calculateFaceQuality(
            face = face,
            boundingBox = cropBox,
            imageWidth = cropBitmap.width,
            imageHeight = cropBitmap.height,
            faceBitmap = faceBitmap
        )

        // Determine quality tier for clustering decisions
        val qualityTier = FaceQualityTier.fromQuality(quality)

        // Filter faces that don't meet minimum threshold to store
        if (!qualityTier.shouldStore) {
            Log.v(TAG, "Rejecting face: tier=$qualityTier, score=${quality.overallScore}")
            faceBitmap?.recycle()
            return null
        }

        // Normalize bounding box to 0-1 range (based on original image dimensions)
        val normalizedBox = RectF(
            detectionBox.left / detectionBitmap.width,
            detectionBox.top / detectionBitmap.height,
            detectionBox.right / detectionBitmap.width,
            detectionBox.bottom / detectionBitmap.height
        )

        // Extract and translate landmarks to crop-relative coordinates
        // Note: Landmarks are in detection image space, need to scale
        val leftEyePos = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val rightEyePos = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
        val nosePos = face.getLandmark(FaceLandmark.NOSE_BASE)?.position
        val leftMouthPos = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position
        val rightMouthPos = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position

        // NEW: Reject faces without any eye landmarks - likely false positives (back of head, hair, etc.)
        if (leftEyePos == null && rightEyePos == null) {
            Log.v(TAG, "Rejecting face without eye landmarks (likely false positive)")
            faceBitmap?.recycle()
            return null
        }

        // Translate landmarks to crop-relative coordinates (scaled to crop image)
        fun translateLandmark(pos: android.graphics.PointF?): PointF? {
            return pos?.let {
                PointF(
                    (it.x * scaleX) - cropInfo.left,
                    (it.y * scaleY) - cropInfo.top
                )
            }
        }

        Log.v(TAG, "Face: ${faceWidth.toInt()}x${faceHeight.toInt()}px, quality=${quality.overallScore}, tier=$qualityTier")

        return DetectedFace(
            faceId = UUID.randomUUID().toString(),
            boundingBox = normalizedBox,
            quality = quality,
            qualityTier = qualityTier,
            faceBitmap = faceBitmap,
            imageWidth = originalWidth,
            imageHeight = originalHeight,
            leftEye = translateLandmark(leftEyePos),
            rightEye = translateLandmark(rightEyePos),
            nose = translateLandmark(nosePos),
            leftMouth = translateLandmark(leftMouthPos),
            rightMouth = translateLandmark(rightMouthPos),
            trackingId = face.trackingId
        )
    }

    /**
     * Process a face using BitmapRegionDecoder to crop directly from ORIGINAL image.
     *
     * This is the new approach that:
     * 1. Takes face coordinates from detection image
     * 2. Scales them to original image coordinates
     * 3. Uses BitmapRegionDecoder to crop ONLY the face region (memory efficient!)
     * 4. Handles EXIF rotation for the cropped face
     *
     * Benefits:
     * - Never loads full 12MP+ image into memory
     * - Face crops are from ORIGINAL resolution (not downscaled)
     * - Better quality embeddings for face matching
     */
    private fun processFaceWithRegionDecoder(
        face: Face,
        imageUri: Uri,
        detectionBitmap: Bitmap,
        originalWidth: Int,
        originalHeight: Int
    ): DetectedFace? {
        // Bounding box from detection (in detection image coordinates)
        val detectionBox = RectF(face.boundingBox)

        // Filter faces that are too small on detection image
        val faceWidth = detectionBox.width()
        val faceHeight = detectionBox.height()
        if (faceWidth < MIN_FACE_PIXELS || faceHeight < MIN_FACE_PIXELS) {
            Log.v(TAG, "Skipping small face: ${faceWidth.toInt()}x${faceHeight.toInt()} pixels")
            return null
        }

        // NEW: Reject faces with extreme aspect ratios (likely distorted or partial)
        val aspectRatio = faceWidth / faceHeight
        if (aspectRatio > MAX_ASPECT_RATIO || aspectRatio < MIN_ASPECT_RATIO) {
            Log.v(TAG, "Skipping distorted face: aspect ratio=${"%.2f".format(aspectRatio)}")
            return null
        }

        // NEW: Reject faces that are significantly clipped by image edges
        val edgeClipRatio = calculateEdgeClipRatio(detectionBox, detectionBitmap.width, detectionBitmap.height)
        if (edgeClipRatio > MAX_EDGE_CLIP_RATIO) {
            Log.v(TAG, "Skipping edge-clipped face: ${(edgeClipRatio * 100).toInt()}% outside frame")
            return null
        }

        // Calculate scale factors from detection to original coordinates
        val scaleX = originalWidth.toFloat() / detectionBitmap.width.toFloat()
        val scaleY = originalHeight.toFloat() / detectionBitmap.height.toFloat()

        // Scale bounding box to ORIGINAL image coordinates
        val originalBox = RectF(
            detectionBox.left * scaleX,
            detectionBox.top * scaleY,
            detectionBox.right * scaleX,
            detectionBox.bottom * scaleY
        )

        // Add padding around face for context
        val paddingX = originalBox.width() * FACE_CROP_PADDING
        val paddingY = originalBox.height() * FACE_CROP_PADDING

        // Calculate crop region in original image coordinates
        val cropRect = Rect(
            (originalBox.left - paddingX).toInt().coerceAtLeast(0),
            (originalBox.top - paddingY).toInt().coerceAtLeast(0),
            (originalBox.right + paddingX).toInt().coerceAtMost(originalWidth),
            (originalBox.bottom + paddingY).toInt().coerceAtMost(originalHeight)
        )

        // Use BitmapRegionDecoder to crop ONLY the face region from original
        val faceBitmap = cropFaceUsingRegionDecoder(imageUri, cropRect)

        if (faceBitmap == null) {
            Log.w(TAG, "BitmapRegionDecoder failed, falling back to standard crop")
            // Fallback to standard cropping if BitmapRegionDecoder fails
            return processFaceWithFallback(face, imageUri, detectionBitmap, originalWidth, originalHeight)
        }

        Log.v(TAG, "Cropped face from ORIGINAL: ${cropRect.width()}x${cropRect.height()}px -> bitmap ${faceBitmap.width}x${faceBitmap.height}px")

        // Calculate quality metrics using the high-res face crop
        val quality = calculateFaceQuality(
            face = face,
            boundingBox = originalBox,
            imageWidth = originalWidth,
            imageHeight = originalHeight,
            faceBitmap = faceBitmap
        )

        // Determine quality tier for clustering decisions
        val qualityTier = FaceQualityTier.fromQuality(quality)

        // Filter faces that don't meet minimum threshold to store
        if (!qualityTier.shouldStore) {
            Log.v(TAG, "Rejecting face: tier=$qualityTier, score=${quality.overallScore}")
            faceBitmap.recycle()
            return null
        }

        // Normalize bounding box to 0-1 range
        val normalizedBox = RectF(
            detectionBox.left / detectionBitmap.width,
            detectionBox.top / detectionBitmap.height,
            detectionBox.right / detectionBitmap.width,
            detectionBox.bottom / detectionBitmap.height
        )

        // Extract landmarks and translate to crop-relative coordinates
        val leftEyePos = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val rightEyePos = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
        val nosePos = face.getLandmark(FaceLandmark.NOSE_BASE)?.position
        val leftMouthPos = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position
        val rightMouthPos = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position

        // NEW: Reject faces without any eye landmarks - likely false positives (back of head, hair, etc.)
        if (leftEyePos == null && rightEyePos == null) {
            Log.v(TAG, "Rejecting face without eye landmarks (likely false positive)")
            faceBitmap.recycle()
            return null
        }

        // Translate landmarks to crop-relative coordinates
        fun translateLandmark(pos: android.graphics.PointF?): PointF? {
            return pos?.let {
                PointF(
                    (it.x * scaleX) - cropRect.left,
                    (it.y * scaleY) - cropRect.top
                )
            }
        }

        Log.v(TAG, "Face: ${faceWidth.toInt()}x${faceHeight.toInt()}px, quality=${quality.overallScore}, tier=$qualityTier")

        return DetectedFace(
            faceId = UUID.randomUUID().toString(),
            boundingBox = normalizedBox,
            quality = quality,
            qualityTier = qualityTier,
            faceBitmap = faceBitmap,
            imageWidth = originalWidth,
            imageHeight = originalHeight,
            leftEye = translateLandmark(leftEyePos),
            rightEye = translateLandmark(rightEyePos),
            nose = translateLandmark(nosePos),
            leftMouth = translateLandmark(leftMouthPos),
            rightMouth = translateLandmark(rightMouthPos),
            trackingId = face.trackingId
        )
    }

    /**
     * Crop face region directly from original image using BitmapRegionDecoder.
     *
     * This is memory efficient because it only decodes the face region,
     * not the entire image. Perfect for high-resolution (12MP+) photos.
     */
    private fun cropFaceUsingRegionDecoder(uri: Uri, cropRect: Rect): Bitmap? {
        return try {
            // Read EXIF orientation first
            val orientation = context.contentResolver.openInputStream(uri)?.use { stream ->
                try {
                    val exif = ExifInterface(stream)
                    exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                } catch (e: Exception) {
                    ExifInterface.ORIENTATION_NORMAL
                }
            } ?: ExifInterface.ORIENTATION_NORMAL

            // Use BitmapRegionDecoder to decode only the face region
            val decoder = context.contentResolver.openInputStream(uri)?.use { stream ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    BitmapRegionDecoder.newInstance(stream)
                } else {
                    @Suppress("DEPRECATION")
                    BitmapRegionDecoder.newInstance(stream, false)
                }
            }

            if (decoder == null) {
                Log.w(TAG, "Failed to create BitmapRegionDecoder for $uri")
                return null
            }

            // For rotated images, we need to transform the crop rect
            val transformedRect = transformRectForExif(cropRect, decoder.width, decoder.height, orientation)

            // Decode only the face region
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            val faceBitmap = decoder.decodeRegion(transformedRect, options)
            decoder.recycle()

            if (faceBitmap == null) {
                Log.w(TAG, "Failed to decode region from $uri")
                return null
            }

            // Apply EXIF rotation to the cropped face
            applyExifRotation(faceBitmap, orientation)
        } catch (e: Exception) {
            Log.e(TAG, "Error using BitmapRegionDecoder for $uri", e)
            null
        }
    }

    /**
     * Transform crop rectangle for EXIF orientation.
     *
     * BitmapRegionDecoder operates on raw (pre-rotation) pixel data,
     * so we need to transform our coordinates if the image has EXIF rotation.
     */
    private fun transformRectForExif(rect: Rect, imageWidth: Int, imageHeight: Int, orientation: Int): Rect {
        return when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> {
                // 90° CW: (x, y) -> (y, width - x - w)
                Rect(
                    rect.top,
                    imageWidth - rect.right,
                    rect.bottom,
                    imageWidth - rect.left
                )
            }
            ExifInterface.ORIENTATION_ROTATE_180 -> {
                // 180°: (x, y) -> (width - x - w, height - y - h)
                Rect(
                    imageWidth - rect.right,
                    imageHeight - rect.bottom,
                    imageWidth - rect.left,
                    imageHeight - rect.top
                )
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> {
                // 270° CW: (x, y) -> (height - y - h, x)
                Rect(
                    imageHeight - rect.bottom,
                    rect.left,
                    imageHeight - rect.top,
                    rect.right
                )
            }
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
                Rect(
                    imageWidth - rect.right,
                    rect.top,
                    imageWidth - rect.left,
                    rect.bottom
                )
            }
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                Rect(
                    rect.left,
                    imageHeight - rect.bottom,
                    rect.right,
                    imageHeight - rect.top
                )
            }
            else -> rect // ORIENTATION_NORMAL or unknown
        }
    }

    /**
     * Fallback face processing using standard bitmap loading.
     * Used when BitmapRegionDecoder fails (e.g., for non-JPEG/PNG formats).
     */
    private fun processFaceWithFallback(
        face: Face,
        imageUri: Uri,
        detectionBitmap: Bitmap,
        originalWidth: Int,
        originalHeight: Int
    ): DetectedFace? {
        // Load crop image using standard method
        val cropImage = loadImageForCropping(imageUri) ?: return null

        val scaleX = cropImage.width.toFloat() / detectionBitmap.width.toFloat()
        val scaleY = cropImage.height.toFloat() / detectionBitmap.height.toFloat()

        val result = processFace(
            face = face,
            detectionBitmap = detectionBitmap,
            cropBitmap = cropImage,
            scaleX = scaleX,
            scaleY = scaleY,
            originalWidth = originalWidth,
            originalHeight = originalHeight
        )

        // Recycle crop image if it's not the same as face bitmap
        // Note: This is tricky because faceBitmap is created from cropImage
        // We need to be careful about memory management here
        // For now, don't recycle cropImage as it may still be referenced

        return result
    }

    /**
     * Crop information for extracting face region.
     */
    private data class CropBounds(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    )

    /**
     * Calculate crop boundaries with padding, clamped to image bounds.
     */
    private fun calculateCropBounds(
        boundingBox: RectF,
        imageWidth: Int,
        imageHeight: Int,
        paddingRatio: Float
    ): CropBounds {
        val paddingX = boundingBox.width() * paddingRatio
        val paddingY = boundingBox.height() * paddingRatio

        return CropBounds(
            left = (boundingBox.left - paddingX).coerceAtLeast(0f),
            top = (boundingBox.top - paddingY).coerceAtLeast(0f),
            right = (boundingBox.right + paddingX).coerceAtMost(imageWidth.toFloat()),
            bottom = (boundingBox.bottom + paddingY).coerceAtMost(imageHeight.toFloat())
        )
    }

    /**
     * Crop face from source bitmap.
     */
    private fun cropFace(bitmap: Bitmap, cropBounds: CropBounds): Bitmap? {
        return try {
            val left = cropBounds.left.toInt()
            val top = cropBounds.top.toInt()
            val width = (cropBounds.right - cropBounds.left).toInt().coerceAtLeast(1)
            val height = (cropBounds.bottom - cropBounds.top).toInt().coerceAtLeast(1)

            if (left < 0 || top < 0 || left + width > bitmap.width || top + height > bitmap.height) {
                Log.w(TAG, "Crop bounds out of image: crop=($left,$top,$width,$height), image=${bitmap.width}x${bitmap.height}")
                return null
            }

            Bitmap.createBitmap(bitmap, left, top, width, height)
        } catch (e: Exception) {
            Log.e(TAG, "Error cropping face", e)
            null
        }
    }

    /**
     * Calculate what fraction of a face bounding box is clipped by image edges.
     *
     * A face near the edge of an image may be partially cut off, which produces
     * poor quality embeddings. This function estimates how much of the expected
     * face area is outside the image bounds.
     *
     * @param faceBox The detected face bounding box
     * @param imageWidth Image width in pixels
     * @param imageHeight Image height in pixels
     * @return Ratio of estimated clipped area (0.0 = fully visible, 1.0 = fully clipped)
     */
    private fun calculateEdgeClipRatio(faceBox: RectF, imageWidth: Int, imageHeight: Int): Float {
        // Estimate the "full" face box if it weren't clipped
        // Assume the face should be roughly centered with typical proportions
        val faceWidth = faceBox.width()
        val faceHeight = faceBox.height()

        // Calculate how much is clipped on each edge
        val leftClip = max(0f, -faceBox.left)
        val topClip = max(0f, -faceBox.top)
        val rightClip = max(0f, faceBox.right - imageWidth)
        val bottomClip = max(0f, faceBox.bottom - imageHeight)

        // Total clipped area as fraction of face area
        val clippedWidth = leftClip + rightClip
        val clippedHeight = topClip + bottomClip

        // Approximate the clipped ratio (not exact area, but good estimate)
        val widthClipRatio = clippedWidth / faceWidth
        val heightClipRatio = clippedHeight / faceHeight

        // Return the maximum clip ratio (worst case)
        return max(widthClipRatio, heightClipRatio).coerceIn(0f, 1f)
    }

    /**
     * Calculate comprehensive face quality metrics.
     *
     * NEW SCORING FORMULA (100 points max):
     * - Size Score:        0-25 points (larger faces are clearer)
     * - Pose Score:        0-25 points (frontal faces are better for embedding)
     * - Sharpness Score:   0-25 points (Laplacian variance blur detection)
     * - Brightness Score:  0-15 points (optimal lighting, reject under/overexposed)
     * - Eye Visibility:    0-10 points (both eyes detected and visible)
     *
     * Position score is calculated but used only internally for edge penalties.
     */
    private fun calculateFaceQuality(
        face: Face,
        boundingBox: RectF,
        imageWidth: Int,
        imageHeight: Int,
        faceBitmap: Bitmap?
    ): FaceQuality {
        // ============ SIZE SCORE (0-25 points) ============
        // Larger faces provide clearer features for embedding
        val facePixelSize = min(boundingBox.width(), boundingBox.height())
        val sizeScore = when {
            facePixelSize >= 200 -> 25f   // Excellent - very clear face
            facePixelSize >= 150 -> 22f   // Very good
            facePixelSize >= 100 -> 18f   // Good
            facePixelSize >= 80 -> 14f    // Acceptable (MIN_FACE_SIZE_FOR_EMBEDDING)
            facePixelSize >= 60 -> 8f     // Marginal
            facePixelSize >= 40 -> 4f     // Poor
            else -> 0f                    // Too small
        }

        // ============ POSE SCORE (0-25 points) ============
        // Frontal faces produce more consistent embeddings
        val eulerY = face.headEulerAngleY  // Yaw (left/right turn)
        val eulerZ = face.headEulerAngleZ  // Roll (head tilt)

        // Progressive penalty: small angles have small penalty, large angles have heavy penalty
        val absYaw = abs(eulerY)
        val yawPenalty = when {
            absYaw > 45f -> 20f          // Extreme turn - heavy penalty
            absYaw > MAX_HEAD_EULER_Y -> 10f + (absYaw - MAX_HEAD_EULER_Y) * 0.4f  // Over threshold
            absYaw > 15f -> 3f + (absYaw - 15f) * 0.3f  // Moderate turn
            else -> absYaw * 0.1f         // Small turn - minimal penalty
        }

        val absRoll = abs(eulerZ)
        val rollPenalty = when {
            absRoll > 35f -> 10f          // Extreme tilt
            absRoll > MAX_HEAD_EULER_Z -> 5f + (absRoll - MAX_HEAD_EULER_Z) * 0.3f
            else -> absRoll * 0.1f         // Small tilt
        }

        val poseScore = (25f - yawPenalty - rollPenalty).coerceIn(0f, 25f)

        // ============ SHARPNESS SCORE (0-25 points) ============
        // Laplacian variance for blur detection
        val sharpnessScore = if (faceBitmap != null && !faceBitmap.isRecycled) {
            calculateSharpnessScore(faceBitmap)
        } else {
            10f  // Default if no bitmap available
        }

        // ============ BRIGHTNESS SCORE (0-15 points) - NEW! ============
        // Detect under/overexposed faces that produce poor embeddings
        val brightnessScore = if (faceBitmap != null && !faceBitmap.isRecycled) {
            calculateBrightnessScore(faceBitmap)
        } else {
            7f  // Default if no bitmap available
        }

        // ============ EYE VISIBILITY SCORE (0-10 points) - NEW! ============
        // Both eyes should be detected and open for good embeddings
        val leftEyeOpen = face.leftEyeOpenProbability
        val rightEyeOpen = face.rightEyeOpenProbability
        val smiling = face.smilingProbability

        val eyeVisibilityScore = calculateEyeVisibilityScore(
            face = face,
            leftEyeOpenProb = leftEyeOpen,
            rightEyeOpenProb = rightEyeOpen
        )

        // ============ POSITION SCORE (internal use) ============
        // Centered faces preferred, edge faces penalized
        val centerX = boundingBox.centerX() / imageWidth
        val centerY = boundingBox.centerY() / imageHeight
        val distFromCenter = (abs(centerX - 0.5f) + abs(centerY - 0.5f)) / 1.0f
        var positionScore = ((1f - distFromCenter) * 5f).coerceIn(0f, 5f)

        // Edge penalty - faces cut off at edges are lower quality
        val edgeMargin = 0.03f
        val isNearEdge = boundingBox.left / imageWidth < edgeMargin ||
                boundingBox.top / imageHeight < edgeMargin ||
                (imageWidth - boundingBox.right) / imageWidth < edgeMargin ||
                (imageHeight - boundingBox.bottom) / imageHeight < edgeMargin
        if (isNearEdge) {
            positionScore = 0f  // Zero position score for edge-cut faces
        }

        // ============ OVERALL SCORE (max 100) ============
        // Sum of all component scores
        val overallScore = sizeScore + poseScore + sharpnessScore + brightnessScore + eyeVisibilityScore
        // Note: positionScore is not added to overall but used for filtering

        return FaceQuality(
            overallScore = overallScore.coerceIn(0f, 100f),
            sizeScore = sizeScore,
            poseScore = poseScore,
            sharpnessScore = sharpnessScore,
            brightnessScore = brightnessScore,
            eyeVisibilityScore = eyeVisibilityScore,
            positionScore = positionScore,
            eulerY = eulerY,
            eulerZ = eulerZ,
            leftEyeOpenProb = leftEyeOpen,
            rightEyeOpenProb = rightEyeOpen,
            smilingProb = smiling
        )
    }

    /**
     * Calculate eye visibility score (0-10 points).
     *
     * Checks:
     * 1. Both eye landmarks were detected
     * 2. Eyes are open (not closed/squinting)
     * 3. Inter-eye distance is reasonable (not too close = profile view)
     */
    private fun calculateEyeVisibilityScore(
        face: Face,
        leftEyeOpenProb: Float?,
        rightEyeOpenProb: Float?
    ): Float {
        var score = 0f

        // Check if eye landmarks exist (3 points each)
        val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)
        val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)

        if (leftEye != null) score += 1.5f
        if (rightEye != null) score += 1.5f

        // Check if eyes are open (4 points total)
        if (leftEyeOpenProb != null) {
            score += when {
                leftEyeOpenProb > 0.8f -> 2f    // Clearly open
                leftEyeOpenProb > 0.5f -> 1.5f  // Probably open
                leftEyeOpenProb > 0.3f -> 0.5f  // Possibly squinting
                else -> 0f                       // Closed
            }
        } else {
            score += 1f  // Benefit of doubt if not detected
        }

        if (rightEyeOpenProb != null) {
            score += when {
                rightEyeOpenProb > 0.8f -> 2f
                rightEyeOpenProb > 0.5f -> 1.5f
                rightEyeOpenProb > 0.3f -> 0.5f
                else -> 0f
            }
        } else {
            score += 1f
        }

        // Check inter-eye distance for profile detection (2 points)
        if (leftEye != null && rightEye != null) {
            val eyeDistance = kotlin.math.sqrt(
                (leftEye.position.x - rightEye.position.x).let { it * it } +
                (leftEye.position.y - rightEye.position.y).let { it * it }
            )
            // If eyes are too close together, likely a profile/extreme angle
            score += when {
                eyeDistance > 30f -> 2f     // Good separation
                eyeDistance > 20f -> 1.5f   // Acceptable
                eyeDistance > 10f -> 1f     // Marginal
                else -> 0f                  // Too close - likely profile
            }
        }

        return score.coerceIn(0f, 10f)
    }

    /**
     * Detect faces in a bitmap using ML Kit.
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
     * Load DOWNSAMPLED image for fast face detection.
     *
     * Returns a LoadedImage containing:
     * - Downsampled bitmap (max 1024px) for ML Kit detection
     * - Original image dimensions for coordinate scaling
     *
     * OPTIMIZATION: Uses single stream read - buffers bytes once.
     */
    private fun loadImageForDetection(uri: Uri): LoadedImage? {
        return try {
            // SINGLE STREAM: Read all bytes once
            val imageBytes = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.readBytes()
            } ?: run {
                Log.w(TAG, "Failed to read image bytes for $uri")
                return null
            }

            // Step 1: Read EXIF orientation from buffered bytes
            val orientation = readExifOrientation(imageBytes, uri)

            // Step 2: Decode bounds only from buffered bytes
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            ByteArrayInputStream(imageBytes).use { boundsStream ->
                BitmapFactory.decodeStream(boundsStream, null, boundsOptions)
            }

            if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) {
                Log.w(TAG, "Invalid image dimensions for $uri")
                return null
            }

            val originalWidth = boundsOptions.outWidth
            val originalHeight = boundsOptions.outHeight

            // Step 3: Calculate sample size for DETECTION (small image = fast)
            val sampleSize = calculateSampleSize(
                originalWidth,
                originalHeight,
                MAX_DETECTION_DIMENSION,
                MAX_DETECTION_DIMENSION
            )

            // Step 4: Decode downsampled bitmap for detection
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            val rawBitmap = ByteArrayInputStream(imageBytes).use { bitmapStream ->
                BitmapFactory.decodeStream(bitmapStream, null, decodeOptions)
            }

            if (rawBitmap == null) {
                Log.w(TAG, "Failed to decode detection bitmap for $uri")
                return null
            }

            // Step 5: Apply EXIF rotation if needed
            val rotatedBitmap = applyExifRotation(rawBitmap, orientation)

            LoadedImage(
                bitmap = rotatedBitmap,
                originalWidth = originalWidth,
                originalHeight = originalHeight,
                scaleX = originalWidth.toFloat() / rotatedBitmap.width,
                scaleY = originalHeight.toFloat() / rotatedBitmap.height
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error loading detection image from $uri", e)
            null
        }
    }

    /**
     * Load HIGH-RESOLUTION image for sharp face cropping.
     *
     * This loads the image at much higher resolution (up to 3000px) for quality face crops.
     * Only called AFTER faces are detected, so we don't waste memory on images without faces.
     */
    private fun loadImageForCropping(uri: Uri): Bitmap? {
        return try {
            // SINGLE STREAM: Read all bytes once
            val imageBytes = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.readBytes()
            } ?: run {
                Log.w(TAG, "Failed to read image bytes for cropping: $uri")
                return null
            }

            // Read EXIF orientation
            val orientation = readExifOrientation(imageBytes, uri)

            // Decode bounds
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            ByteArrayInputStream(imageBytes).use { boundsStream ->
                BitmapFactory.decodeStream(boundsStream, null, boundsOptions)
            }

            if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) {
                Log.w(TAG, "Invalid image dimensions for cropping: $uri")
                return null
            }

            // Calculate sample size for HIGH-RES cropping (larger = better face quality)
            val sampleSize = calculateSampleSize(
                boundsOptions.outWidth,
                boundsOptions.outHeight,
                MAX_CROP_DIMENSION,
                MAX_CROP_DIMENSION
            )

            // Decode high-resolution bitmap for cropping
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            val rawBitmap = ByteArrayInputStream(imageBytes).use { bitmapStream ->
                BitmapFactory.decodeStream(bitmapStream, null, decodeOptions)
            }

            if (rawBitmap == null) {
                Log.w(TAG, "Failed to decode crop bitmap for $uri")
                return null
            }

            // Apply EXIF rotation
            applyExifRotation(rawBitmap, orientation)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading crop image from $uri", e)
            null
        }
    }

    /**
     * Read EXIF orientation from buffered image bytes.
     */
    private fun readExifOrientation(imageBytes: ByteArray, uri: Uri): Int {
        return try {
            ByteArrayInputStream(imageBytes).use { exifStream ->
                val exif = ExifInterface(exifStream)
                exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }
        } catch (e: Exception) {
            Log.v(TAG, "Could not read EXIF for $uri: ${e.message}")
            ExifInterface.ORIENTATION_NORMAL
        }
    }

    /**
     * Apply EXIF orientation rotation to bitmap.
     */
    private fun applyExifRotation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.preScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(-90f)
                matrix.preScale(-1f, 1f)
            }
            else -> return bitmap // No rotation needed
        }

        return try {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) {
                bitmap.recycle()
            }
            rotated
        } catch (e: Exception) {
            Log.e(TAG, "Error applying EXIF rotation", e)
            bitmap
        }
    }

    /**
     * Calculate sample size for efficient bitmap loading.
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
            // FIXED: Changed && to || so we scale down when EITHER dimension exceeds max
            while ((halfWidth / sampleSize) >= maxWidth || (halfHeight / sampleSize) >= maxHeight) {
                sampleSize *= 2
            }
        }
        return sampleSize
    }

    /**
     * Calculate sharpness score using Laplacian variance.
     *
     * The Laplacian operator detects edges in an image. Sharp images have
     * strong edges (high variance), while blurry images have weak edges (low variance).
     *
     * @param bitmap The face bitmap to analyze
     * @return Sharpness score from 0 to 20 (20 = very sharp)
     */
    private fun calculateSharpnessScore(bitmap: Bitmap): Float {
        try {
            // Use a smaller sample for efficiency (scale down if too large)
            val sampleBitmap = if (bitmap.width > 100 || bitmap.height > 100) {
                val scale = 100f / max(bitmap.width, bitmap.height)
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(10),
                    (bitmap.height * scale).toInt().coerceAtLeast(10),
                    true
                )
            } else {
                bitmap
            }

            val width = sampleBitmap.width
            val height = sampleBitmap.height

            // Convert to grayscale
            val pixels = IntArray(width * height)
            sampleBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            val grayscale = FloatArray(width * height) { i ->
                val pixel = pixels[i]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                // Luminance formula
                0.299f * r + 0.587f * g + 0.114f * b
            }

            // Apply Laplacian kernel: [0, 1, 0], [1, -4, 1], [0, 1, 0]
            val laplacianValues = mutableListOf<Float>()
            for (y in 1 until height - 1) {
                for (x in 1 until width - 1) {
                    val idx = y * width + x
                    val laplacian = grayscale[(y - 1) * width + x] +      // top
                            grayscale[y * width + (x - 1)] +               // left
                            -4 * grayscale[idx] +                           // center
                            grayscale[y * width + (x + 1)] +               // right
                            grayscale[(y + 1) * width + x]                  // bottom
                    laplacianValues.add(laplacian)
                }
            }

            // Calculate variance of Laplacian
            if (laplacianValues.isEmpty()) {
                return 10f // Default for tiny images
            }

            val mean = laplacianValues.average().toFloat()
            var variance = 0f
            for (value in laplacianValues) {
                val diff = value - mean
                variance += diff * diff
            }
            variance /= laplacianValues.size

            // Recycle sample bitmap if we created a scaled version
            if (sampleBitmap != bitmap) {
                sampleBitmap.recycle()
            }

            // Map variance to score (calibrated thresholds based on testing)
            // Higher variance = sharper image
            // Updated to 0-25 range for new quality formula
            return when {
                variance >= 500f -> 25f      // Very sharp - full score
                variance >= 300f -> 22f + (variance - 300f) / 66f  // Very sharp
                variance >= 150f -> 18f + (variance - 150f) / 37.5f  // Sharp
                variance >= 80f -> 12f + (variance - 80f) / 11.7f    // Acceptable
                variance >= 40f -> 6f + (variance - 40f) / 6.7f      // Slightly blurry
                variance >= 15f -> 2f + (variance - 15f) / 6.25f     // Blurry
                else -> variance / 7.5f                               // Very blurry
            }.coerceIn(0f, 25f)
        } catch (e: Exception) {
            Log.w(TAG, "Error calculating sharpness score", e)
            return 10f // Default on error
        }
    }

    /**
     * Calculate brightness score for the face.
     * Optimal brightness is around 100-160 (on 0-255 scale).
     * Under/overexposed faces produce unreliable embeddings.
     *
     * Also checks contrast - flat lighting (low contrast) is problematic.
     *
     * @param bitmap The face bitmap to analyze
     * @return Brightness score from 0 to 15 (15 = optimal lighting)
     */
    private fun calculateBrightnessScore(bitmap: Bitmap): Float {
        try {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            var totalBrightness = 0L
            var minBrightness = 255
            var maxBrightness = 0

            for (pixel in pixels) {
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val brightness = (r + g + b) / 3
                totalBrightness += brightness
                minBrightness = minOf(minBrightness, brightness)
                maxBrightness = maxOf(maxBrightness, brightness)
            }

            val avgBrightness = totalBrightness.toFloat() / pixels.size
            val contrast = maxBrightness - minBrightness

            // Score based on average brightness (0-10 points)
            val brightnessScore = when {
                avgBrightness in 100f..160f -> 10f  // Optimal range
                avgBrightness in 80f..100f || avgBrightness in 160f..180f -> 8f  // Good
                avgBrightness in 60f..80f || avgBrightness in 180f..200f -> 5f   // Acceptable
                avgBrightness in 40f..60f || avgBrightness in 200f..220f -> 3f   // Marginal
                avgBrightness < 30f -> 0f   // Very dark - unacceptable
                avgBrightness > 230f -> 0f  // Very bright - unacceptable
                else -> 1f                   // Poor
            }

            // Contrast bonus (0-5 points) - good lighting has good contrast
            val contrastScore = when {
                contrast >= 150 -> 5f   // Excellent contrast
                contrast >= 100 -> 4f   // Good contrast
                contrast >= 70 -> 3f    // Acceptable
                contrast >= 40 -> 2f    // Low contrast (flat lighting)
                else -> 0f              // Very flat - no features distinguishable
            }

            return (brightnessScore + contrastScore).coerceIn(0f, 15f)
        } catch (e: Exception) {
            Log.w(TAG, "Error calculating brightness score", e)
            return 7f  // Default on error
        }
    }

    /**
     * Close the detector when done.
     */
    fun close() {
        detector.close()
    }
}
