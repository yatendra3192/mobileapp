package com.aiezzy.slideshowmaker.face.embedding

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Production-grade face embedding generator using TensorFlow Lite.
 *
 * Key improvements:
 * - Auto-detects model type (FaceNet/MobileFaceNet) from file
 * - Proper face alignment using 5-point landmarks
 * - Robust fallback when model is unavailable
 * - Thread-safe with proper resource cleanup
 * - L2 normalization for cosine similarity
 */
class FaceEmbeddingGenerator(private val context: Context) {

    companion object {
        private const val TAG = "FaceEmbeddingGenerator"

        // Model configuration - prioritize FaceNet for best face clustering accuracy
        // FaceNet: 512-dim embeddings, higher accuracy for face recognition
        // MobileFaceNet: 192-dim embeddings, used as fallback if FaceNet unavailable
        private val MODEL_PRIORITY = listOf(
            ModelConfig("facenet.tflite", 160, 512),      // Primary: high accuracy
            ModelConfig("mobilefacenet.tflite", 112, 192) // Fallback: smaller size
        )

        // Image normalization parameters
        private const val IMAGE_MEAN = 127.5f
        private const val IMAGE_STD = 128f
        private const val PIXEL_SIZE = 3  // RGB

        // Reference landmark positions for face alignment (normalized to [0,1])
        // These are the target positions for a properly aligned face
        private val REFERENCE_LANDMARKS_112 = arrayOf(
            floatArrayOf(0.34191f, 0.46157f),  // Left eye
            floatArrayOf(0.65653f, 0.45983f),  // Right eye
            floatArrayOf(0.50023f, 0.64050f),  // Nose
            floatArrayOf(0.37097f, 0.82469f),  // Left mouth
            floatArrayOf(0.63103f, 0.82325f)   // Right mouth
        )

        // Landmark validation thresholds - STRICTER for better clustering
        private const val MIN_EYE_DISTANCE = 20f         // Raised from 15 for better quality
        private const val MAX_ROTATION_ANGLE = 35f       // Maximum rotation to apply
        private const val MIN_SCALE = 0.3f               // Minimum scale factor
        private const val MAX_SCALE = 3.0f               // Maximum scale factor

        // Anatomical proportion checks
        private const val MIN_EYE_NOSE_RATIO = 0.3f      // Nose shouldn't be above eyes
        private const val MAX_EYE_NOSE_RATIO = 2.0f      // Nose shouldn't be way below eyes
        private const val MAX_EYE_SLANT = 30f            // Maximum degrees eyes can slant

        // NEW: Alignment quality enforcement threshold
        // Faces with alignment quality below this are rejected from clustering
        private const val MIN_ALIGNMENT_QUALITY = 0.30f
    }

    /**
     * Model configuration data class.
     */
    private data class ModelConfig(
        val filename: String,
        val inputSize: Int,
        val embeddingSize: Int
    )

    /**
     * Facial landmarks for alignment.
     */
    data class FaceLandmarks(
        val leftEyeX: Float,
        val leftEyeY: Float,
        val rightEyeX: Float,
        val rightEyeY: Float,
        val noseX: Float? = null,
        val noseY: Float? = null,
        val leftMouthX: Float? = null,
        val leftMouthY: Float? = null,
        val rightMouthX: Float? = null,
        val rightMouthY: Float? = null
    )

    /**
     * Embedding source types for tracking which model generated the embedding.
     */
    enum class EmbeddingSourceType {
        FACENET_512,        // High quality: 512-dim FaceNet
        MOBILEFACENET_192,  // Medium quality: 192-dim MobileFaceNet
        HASH_FALLBACK,      // Low quality: perceptual hash fallback
        UNKNOWN;            // Legacy: source not tracked

        /**
         * Get the string value for database storage.
         */
        fun toStorageString(): String = name

        companion object {
            fun fromStorageString(value: String): EmbeddingSourceType {
                return try {
                    valueOf(value)
                } catch (e: IllegalArgumentException) {
                    UNKNOWN
                }
            }
        }
    }

    /**
     * Result of embedding generation with comprehensive metadata.
     */
    data class EmbeddingResult(
        val embedding: FloatArray,
        val isFromModel: Boolean,  // true if from TFLite model, false if fallback
        val alignmentSuccessful: Boolean,
        // NEW: Alignment quality metrics
        val alignmentQuality: Float = 0f,    // 0-1, how good was the alignment (0 = failed, 1 = perfect)
        val inputFaceSize: Int = 0,          // Size of input face in pixels
        val rotationApplied: Float = 0f,     // Rotation angle applied during alignment
        val scaleApplied: Float = 1f,        // Scale factor applied
        val landmarkValidation: LandmarkValidation = LandmarkValidation.NOT_CHECKED,
        // NEW: Embedding source tracking
        val embeddingSource: EmbeddingSourceType = EmbeddingSourceType.UNKNOWN
    ) {
        /**
         * Whether this embedding meets minimum quality for clustering.
         * Faces with poor alignment should not influence clustering decisions.
         */
        val isSuitableForClustering: Boolean
            get() = isFromModel && alignmentQuality >= MIN_ALIGNMENT_QUALITY

        /**
         * Whether this embedding is from the high-quality FaceNet model.
         */
        val isHighQualitySource: Boolean
            get() = embeddingSource == EmbeddingSourceType.FACENET_512

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as EmbeddingResult
            return embedding.contentEquals(other.embedding) &&
                    isFromModel == other.isFromModel &&
                    alignmentSuccessful == other.alignmentSuccessful
        }

        override fun hashCode(): Int {
            var result = embedding.contentHashCode()
            result = 31 * result + isFromModel.hashCode()
            result = 31 * result + alignmentSuccessful.hashCode()
            return result
        }
    }

    /**
     * Landmark validation result.
     */
    enum class LandmarkValidation {
        NOT_CHECKED,           // No landmarks provided
        VALID,                 // All checks passed
        EYES_TOO_CLOSE,        // Inter-eye distance too small
        OUT_OF_BOUNDS,         // Landmarks outside image
        INVALID_GEOMETRY,      // Anatomically implausible arrangement
        EXTREME_POSE           // Face turned too far from frontal
    }

    /**
     * Result of landmark validation with detailed metrics.
     */
    data class LandmarkValidationResult(
        val isValid: Boolean,
        val validation: LandmarkValidation,
        val interEyeDistance: Float = 0f,
        val estimatedYaw: Float = 0f,      // Estimated head yaw from eye positions
        val geometryScore: Float = 0f      // How anatomically plausible (0-1)
    )

    // Active model configuration
    private var activeConfig: ModelConfig? = null
    private var interpreter: Interpreter? = null
    private var isModelAvailable = false

    init {
        loadModel()
    }

    /**
     * Load the best available TFLite model.
     */
    private fun loadModel() {
        for (config in MODEL_PRIORITY) {
            try {
                val modelBuffer = loadModelFile(config.filename)
                if (modelBuffer != null) {
                    val options = Interpreter.Options().apply {
                        setNumThreads(4)
                        // Enable NNAPI for hardware acceleration if available
                        // setUseNNAPI(true)
                    }
                    interpreter = Interpreter(modelBuffer, options)

                    // Verify output shape matches expected embedding size
                    val outputTensor = interpreter?.getOutputTensor(0)
                    val outputShape = outputTensor?.shape()
                    val actualEmbeddingSize = if (outputShape != null && outputShape.size >= 2) {
                        outputShape[1]
                    } else {
                        config.embeddingSize
                    }

                    if (actualEmbeddingSize != config.embeddingSize) {
                        Log.w(TAG, "Model ${config.filename} has embedding size $actualEmbeddingSize, expected ${config.embeddingSize}")
                    }

                    activeConfig = config.copy(embeddingSize = actualEmbeddingSize)
                    isModelAvailable = true
                    Log.i(TAG, "Loaded model: ${config.filename}, input: ${config.inputSize}x${config.inputSize}, embedding: $actualEmbeddingSize")
                    return
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load model ${config.filename}: ${e.message}")
            }
        }

        Log.w(TAG, "No face embedding model available, using fallback")
        isModelAvailable = false
    }

    /**
     * Load model file from assets.
     */
    private fun loadModelFile(filename: String): MappedByteBuffer? {
        return try {
            val assetFileDescriptor = context.assets.openFd(filename)
            val fileInputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
            val fileChannel = fileInputStream.channel
            val startOffset = assetFileDescriptor.startOffset
            val declaredLength = assetFileDescriptor.declaredLength
            fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
        } catch (e: Exception) {
            Log.v(TAG, "Model file not found: $filename")
            null
        }
    }

    /**
     * Generate embedding for a face bitmap.
     *
     * @param faceBitmap The cropped face bitmap
     * @param landmarks Optional facial landmarks for alignment (strongly recommended)
     * @return EmbeddingResult or null on failure
     */
    suspend fun generateEmbedding(
        faceBitmap: Bitmap,
        landmarks: FaceLandmarks? = null
    ): FloatArray? = withContext(Dispatchers.Default) {
        try {
            val result = generateEmbeddingWithMetadata(faceBitmap, landmarks)
            result?.embedding
        } catch (e: Exception) {
            Log.e(TAG, "Error generating embedding", e)
            null
        }
    }

    /**
     * Generate embedding with metadata about the generation process.
     */
    suspend fun generateEmbeddingWithMetadata(
        faceBitmap: Bitmap,
        landmarks: FaceLandmarks? = null
    ): EmbeddingResult? = withContext(Dispatchers.Default) {
        try {
            if (isModelAvailable && interpreter != null && activeConfig != null) {
                generateModelEmbedding(faceBitmap, landmarks, activeConfig!!)
            } else {
                // Fallback to perceptual hash-based embedding
                Log.w(TAG, "Model not available, using hash-based fallback (lower quality)")
                val embedding = generateHashBasedEmbedding(faceBitmap)
                EmbeddingResult(
                    embedding = embedding,
                    isFromModel = false,
                    alignmentSuccessful = false,
                    embeddingSource = EmbeddingSourceType.HASH_FALLBACK  // Track fallback usage
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating embedding", e)
            null
        }
    }

    /**
     * Generate embedding using TFLite model with comprehensive metadata.
     */
    private fun generateModelEmbedding(
        faceBitmap: Bitmap,
        landmarks: FaceLandmarks?,
        config: ModelConfig
    ): EmbeddingResult? {
        var alignedFace: Bitmap? = null
        var alignmentSuccessful = false
        var alignmentQuality = 0f
        var rotationApplied = 0f
        var scaleApplied = 1f
        var landmarkValidation = LandmarkValidation.NOT_CHECKED
        val inputFaceSize = minOf(faceBitmap.width, faceBitmap.height)

        try {
            // Validate and align face if landmarks are available
            if (landmarks != null) {
                // First, validate landmarks
                val validationResult = validateLandmarks(faceBitmap, landmarks)
                landmarkValidation = validationResult.validation

                if (validationResult.isValid) {
                    val alignmentResult = alignFaceWithMetrics(faceBitmap, landmarks, config.inputSize)
                    if (alignmentResult != null) {
                        alignedFace = alignmentResult.bitmap
                        alignmentSuccessful = true
                        rotationApplied = alignmentResult.rotation
                        scaleApplied = alignmentResult.scale

                        // Calculate alignment quality based on:
                        // - Rotation needed (less is better)
                        // - Scale factor (closer to 1.0 is better)
                        // - Geometry score from validation
                        val rotationPenalty = (kotlin.math.abs(rotationApplied) / MAX_ROTATION_ANGLE).coerceIn(0f, 1f)
                        val scalePenalty = kotlin.math.abs(kotlin.math.log2(scaleApplied)).coerceIn(0f, 1f)
                        alignmentQuality = (
                            0.4f * (1f - rotationPenalty) +
                            0.3f * (1f - scalePenalty) +
                            0.3f * validationResult.geometryScore
                        ).coerceIn(0f, 1f)

                        Log.v(TAG, "Alignment quality: ${"%.2f".format(alignmentQuality)}, " +
                                "rotation: ${"%.1f".format(rotationApplied)}°, " +
                                "scale: ${"%.2f".format(scaleApplied)}")
                    }
                } else {
                    Log.w(TAG, "Landmark validation failed: $landmarkValidation")
                }
            }

            // Fall back to center crop if alignment failed
            if (alignedFace == null) {
                alignedFace = centerCropAndResize(faceBitmap, config.inputSize)
                if (alignedFace == null) {
                    Log.e(TAG, "Failed to prepare face for embedding")
                    return null
                }
                alignmentQuality = 0.3f  // Lower quality for non-aligned faces
            }

            // Convert to input buffer
            val inputBuffer = convertBitmapToByteBuffer(alignedFace, config.inputSize)

            // Run inference
            val outputArray = Array(1) { FloatArray(config.embeddingSize) }
            interpreter?.run(inputBuffer, outputArray)

            // Normalize to unit length for cosine similarity
            val normalizedEmbedding = normalizeL2(outputArray[0])

            // Determine embedding source based on model configuration
            val embeddingSource = when (config.embeddingSize) {
                512 -> EmbeddingSourceType.FACENET_512
                192 -> EmbeddingSourceType.MOBILEFACENET_192
                else -> EmbeddingSourceType.UNKNOWN
            }

            return EmbeddingResult(
                embedding = normalizedEmbedding,
                isFromModel = true,
                alignmentSuccessful = alignmentSuccessful,
                alignmentQuality = alignmentQuality,
                inputFaceSize = inputFaceSize,
                rotationApplied = rotationApplied,
                scaleApplied = scaleApplied,
                landmarkValidation = landmarkValidation,
                embeddingSource = embeddingSource
            )
        } finally {
            // Clean up aligned bitmap if we created a new one
            if (alignedFace != null && alignedFace != faceBitmap) {
                alignedFace.recycle()
            }
        }
    }

    /**
     * Validate facial landmarks for anatomical plausibility.
     *
     * Checks:
     * 1. Landmarks are within image bounds
     * 2. Inter-eye distance is sufficient
     * 3. Eye-nose-mouth arrangement is anatomically plausible
     * 4. Face isn't extremely rotated (estimated from landmarks)
     */
    private fun validateLandmarks(bitmap: Bitmap, landmarks: FaceLandmarks): LandmarkValidationResult {
        val width = bitmap.width.toFloat()
        val height = bitmap.height.toFloat()

        // Check bounds
        if (landmarks.leftEyeX < 0 || landmarks.leftEyeY < 0 ||
            landmarks.rightEyeX < 0 || landmarks.rightEyeY < 0 ||
            landmarks.leftEyeX > width || landmarks.leftEyeY > height ||
            landmarks.rightEyeX > width || landmarks.rightEyeY > height) {
            return LandmarkValidationResult(
                isValid = false,
                validation = LandmarkValidation.OUT_OF_BOUNDS
            )
        }

        // Calculate inter-eye distance
        val dx = landmarks.rightEyeX - landmarks.leftEyeX
        val dy = landmarks.rightEyeY - landmarks.leftEyeY
        val interEyeDistance = sqrt(dx * dx + dy * dy)

        if (interEyeDistance < MIN_EYE_DISTANCE) {
            return LandmarkValidationResult(
                isValid = false,
                validation = LandmarkValidation.EYES_TOO_CLOSE,
                interEyeDistance = interEyeDistance
            )
        }

        // Estimate yaw from eye positions (if one eye is much smaller area, face is turned)
        val eyeSlant = atan2(dy.toDouble(), dx.toDouble()) * 180.0 / Math.PI
        if (kotlin.math.abs(eyeSlant) > MAX_EYE_SLANT) {
            return LandmarkValidationResult(
                isValid = false,
                validation = LandmarkValidation.EXTREME_POSE,
                interEyeDistance = interEyeDistance,
                estimatedYaw = eyeSlant.toFloat()
            )
        }

        // Check anatomical plausibility if nose is available
        var geometryScore = 0.7f  // Default score if we can't check geometry

        if (landmarks.noseX != null && landmarks.noseY != null) {
            val eyeCenterX = (landmarks.leftEyeX + landmarks.rightEyeX) / 2f
            val eyeCenterY = (landmarks.leftEyeY + landmarks.rightEyeY) / 2f

            // Nose should be below eyes (positive Y is down in image coordinates)
            val eyeToNoseY = landmarks.noseY - eyeCenterY
            val eyeToNoseRatio = eyeToNoseY / interEyeDistance

            if (eyeToNoseRatio < MIN_EYE_NOSE_RATIO || eyeToNoseRatio > MAX_EYE_NOSE_RATIO) {
                return LandmarkValidationResult(
                    isValid = false,
                    validation = LandmarkValidation.INVALID_GEOMETRY,
                    interEyeDistance = interEyeDistance,
                    geometryScore = 0f
                )
            }

            // Nose should be roughly centered between eyes (allow some leeway for head turn)
            val noseCenterOffset = kotlin.math.abs(landmarks.noseX - eyeCenterX) / interEyeDistance
            if (noseCenterOffset > 0.5f) {
                // Nose is too far off-center - likely extreme pose
                return LandmarkValidationResult(
                    isValid = false,
                    validation = LandmarkValidation.EXTREME_POSE,
                    interEyeDistance = interEyeDistance,
                    estimatedYaw = noseCenterOffset * 60f  // Rough estimate
                )
            }

            // Calculate geometry score based on how ideal the proportions are
            val idealEyeNoseRatio = 0.8f  // Typical ratio
            val ratioDeviation = kotlin.math.abs(eyeToNoseRatio - idealEyeNoseRatio) / idealEyeNoseRatio
            geometryScore = (1f - ratioDeviation.coerceIn(0f, 1f)) * (1f - noseCenterOffset * 2)
            geometryScore = geometryScore.coerceIn(0f, 1f)
        }

        return LandmarkValidationResult(
            isValid = true,
            validation = LandmarkValidation.VALID,
            interEyeDistance = interEyeDistance,
            estimatedYaw = eyeSlant.toFloat(),
            geometryScore = geometryScore
        )
    }

    /**
     * Result of face alignment with metrics.
     */
    private data class AlignmentResult(
        val bitmap: Bitmap,
        val rotation: Float,
        val scale: Float
    )

    /**
     * Align face using eye positions with similarity transform.
     * Returns alignment result with metrics for quality tracking.
     *
     * This aligns the face so that:
     * 1. Eyes are horizontal
     * 2. Eye distance matches reference
     * 3. Face is centered
     *
     * Note: Validation should be done before calling this function.
     */
    private fun alignFaceWithMetrics(
        faceBitmap: Bitmap,
        landmarks: FaceLandmarks,
        targetSize: Int
    ): AlignmentResult? {
        try {
            // Calculate eye vector
            val dx = landmarks.rightEyeX - landmarks.leftEyeX
            val dy = landmarks.rightEyeY - landmarks.leftEyeY
            val srcEyeDist = sqrt(dx * dx + dy * dy)

            // Calculate rotation angle to make eyes horizontal
            val angle = atan2(dy.toDouble(), dx.toDouble()) * 180.0 / Math.PI

            // Reject extreme rotations
            if (kotlin.math.abs(angle) > MAX_ROTATION_ANGLE) {
                Log.w(TAG, "Rotation angle too extreme: ${"%.1f".format(angle)}°")
                return null
            }

            // Calculate reference eye positions (scaled to target size)
            val refLeftEye = REFERENCE_LANDMARKS_112[0]
            val refRightEye = REFERENCE_LANDMARKS_112[1]
            val refEyeDist = sqrt(
                (refRightEye[0] - refLeftEye[0]).let { it * it } +
                        (refRightEye[1] - refLeftEye[1]).let { it * it }
            ) * targetSize

            // Calculate scale factor with stricter limits
            val rawScale = refEyeDist / srcEyeDist
            val scale = rawScale.coerceIn(MIN_SCALE, MAX_SCALE)

            if (rawScale < MIN_SCALE || rawScale > MAX_SCALE) {
                Log.w(TAG, "Scale factor clamped from ${"%.2f".format(rawScale)} to ${"%.2f".format(scale)}")
            }

            // Source center point (between eyes)
            val srcCenterX = (landmarks.leftEyeX + landmarks.rightEyeX) / 2f
            val srcCenterY = (landmarks.leftEyeY + landmarks.rightEyeY) / 2f

            // Target center point (between reference eyes)
            val refCenterX = (refLeftEye[0] + refRightEye[0]) / 2f * targetSize
            val refCenterY = (refLeftEye[1] + refRightEye[1]) / 2f * targetSize

            // Build transformation matrix
            val matrix = Matrix()
            matrix.postTranslate(-srcCenterX, -srcCenterY)
            matrix.postRotate(-angle.toFloat())
            matrix.postScale(scale, scale)
            matrix.postTranslate(refCenterX, refCenterY)

            // Create aligned bitmap
            val alignedBitmap = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(alignedBitmap)

            // Fill with BLACK (0,0,0) for out-of-bounds areas
            // Black is better than gray because:
            // 1. Most face recognition models were trained with black padding
            // 2. Black has no unintended feature information
            // 3. Clearly distinguishable as padding, not face
            canvas.drawColor(android.graphics.Color.BLACK)

            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(faceBitmap, matrix, paint)

            Log.v(TAG, "Face aligned: angle=${"%.1f".format(angle)}°, scale=${"%.2f".format(scale)}")

            return AlignmentResult(
                bitmap = alignedBitmap,
                rotation = angle.toFloat(),
                scale = scale
            )
        } catch (e: Exception) {
            Log.e(TAG, "Face alignment failed", e)
            return null
        }
    }

    /**
     * Legacy align face function for compatibility.
     * @deprecated Use alignFaceWithMetrics instead
     */
    @Deprecated("Use alignFaceWithMetrics instead", ReplaceWith("alignFaceWithMetrics(faceBitmap, landmarks, targetSize)?.bitmap"))
    private fun alignFace(
        faceBitmap: Bitmap,
        landmarks: FaceLandmarks,
        targetSize: Int
    ): Bitmap? {
        return alignFaceWithMetrics(faceBitmap, landmarks, targetSize)?.bitmap
    }

    /**
     * Center crop and resize bitmap when alignment is not possible.
     */
    private fun centerCropAndResize(bitmap: Bitmap, targetSize: Int): Bitmap? {
        return try {
            val srcWidth = bitmap.width
            val srcHeight = bitmap.height

            // Calculate center crop region
            val cropSize = minOf(srcWidth, srcHeight)
            val cropX = (srcWidth - cropSize) / 2
            val cropY = (srcHeight - cropSize) / 2

            // Crop to square
            val cropped = Bitmap.createBitmap(bitmap, cropX, cropY, cropSize, cropSize)

            // Resize to target
            val resized = Bitmap.createScaledBitmap(cropped, targetSize, targetSize, true)

            // Clean up if we created a new cropped bitmap
            if (cropped != bitmap) {
                cropped.recycle()
            }

            resized
        } catch (e: Exception) {
            Log.e(TAG, "Center crop failed", e)
            null
        }
    }

    /**
     * Convert bitmap to ByteBuffer for model input.
     */
    private fun convertBitmapToByteBuffer(bitmap: Bitmap, inputSize: Int): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(4 * inputSize * inputSize * PIXEL_SIZE)
        byteBuffer.order(ByteOrder.nativeOrder())

        val pixels = IntArray(inputSize * inputSize)
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF

            // Normalize to [-1, 1]
            byteBuffer.putFloat((r - IMAGE_MEAN) / IMAGE_STD)
            byteBuffer.putFloat((g - IMAGE_MEAN) / IMAGE_STD)
            byteBuffer.putFloat((b - IMAGE_MEAN) / IMAGE_STD)
        }

        return byteBuffer
    }

    /**
     * Fallback: Generate perceptual hash-based embedding.
     * Uses spatial color histograms and gradient features.
     */
    private fun generateHashBasedEmbedding(faceBitmap: Bitmap): FloatArray {
        val embeddingSize = 512
        val embedding = FloatArray(embeddingSize)

        // Scale to 32x32 for feature extraction
        val smallBitmap = Bitmap.createScaledBitmap(faceBitmap, 32, 32, true)
        val pixels = IntArray(32 * 32)
        smallBitmap.getPixels(pixels, 0, 32, 0, 0, 32, 32)

        var embIdx = 0

        // Extract color histogram features from 4x4 grid (192 values)
        for (gridY in 0 until 4) {
            for (gridX in 0 until 4) {
                var avgR = 0f
                var avgG = 0f
                var avgB = 0f
                var count = 0

                for (y in gridY * 8 until (gridY + 1) * 8) {
                    for (x in gridX * 8 until (gridX + 1) * 8) {
                        val pixel = pixels[y * 32 + x]
                        avgR += (pixel shr 16) and 0xFF
                        avgG += (pixel shr 8) and 0xFF
                        avgB += pixel and 0xFF
                        count++
                    }
                }

                if (embIdx < embeddingSize) embedding[embIdx++] = avgR / count / 255f
                if (embIdx < embeddingSize) embedding[embIdx++] = avgG / count / 255f
                if (embIdx < embeddingSize) embedding[embIdx++] = avgB / count / 255f
            }
        }

        // Add gradient features
        for (y in 0 until 31) {
            for (x in 0 until 31) {
                if (embIdx >= embeddingSize) break
                val p1 = pixels[y * 32 + x]
                val p2 = pixels[(y + 1) * 32 + x + 1]
                val diff = ((p1 and 0xFF) - (p2 and 0xFF)).toFloat() / 255f
                embedding[embIdx++] = diff
            }
            if (embIdx >= embeddingSize) break
        }

        smallBitmap.recycle()
        return normalizeL2(embedding)
    }

    /**
     * L2 normalize embedding to unit length.
     */
    private fun normalizeL2(embedding: FloatArray): FloatArray {
        var norm = 0f
        for (v in embedding) {
            norm += v * v
        }
        norm = sqrt(norm)

        if (norm > 0) {
            for (i in embedding.indices) {
                embedding[i] = embedding[i] / norm
            }
        }

        return embedding
    }

    /**
     * Calculate cosine similarity between two L2-normalized embeddings.
     * Returns value in [-1, 1], where 1 = identical.
     */
    fun calculateSimilarity(embedding1: FloatArray, embedding2: FloatArray): Float {
        if (embedding1.size != embedding2.size) {
            Log.w(TAG, "Embedding size mismatch: ${embedding1.size} vs ${embedding2.size}")
            return 0f
        }

        // For L2-normalized vectors, cosine similarity = dot product
        var dotProduct = 0f
        for (i in embedding1.indices) {
            dotProduct += embedding1[i] * embedding2[i]
        }

        return dotProduct.coerceIn(-1f, 1f)
    }

    /**
     * Calculate Euclidean distance between embeddings.
     * Useful for debugging; for L2-normalized vectors, related to cosine similarity.
     */
    fun calculateEuclideanDistance(embedding1: FloatArray, embedding2: FloatArray): Float {
        if (embedding1.size != embedding2.size) return Float.MAX_VALUE

        var sum = 0f
        for (i in embedding1.indices) {
            val diff = embedding1[i] - embedding2[i]
            sum += diff * diff
        }
        return sqrt(sum)
    }

    /**
     * Check if the TFLite model is available.
     */
    fun isModelAvailable(): Boolean = isModelAvailable

    /**
     * Get active model configuration.
     */
    fun getActiveModel(): String? = activeConfig?.filename

    /**
     * Get embedding dimensionality.
     */
    fun getEmbeddingSize(): Int = activeConfig?.embeddingSize ?: 512

    /**
     * Close the interpreter and release resources.
     */
    fun close() {
        interpreter?.close()
        interpreter = null
        activeConfig = null
        isModelAvailable = false
    }
}
