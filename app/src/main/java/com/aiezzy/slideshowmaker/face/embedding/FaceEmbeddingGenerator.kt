package com.aiezzy.slideshowmaker.face.embedding

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.sqrt

/**
 * Generates face embeddings using TensorFlow Lite MobileFaceNet model.
 * Falls back to perceptual hashing if model is not available.
 */
class FaceEmbeddingGenerator(private val context: Context) {

    companion object {
        private const val TAG = "FaceEmbeddingGenerator"
        private const val MODEL_FILENAME = "mobilefacenet.tflite"
        private const val INPUT_SIZE = 112  // MobileFaceNet input size
        private const val EMBEDDING_SIZE = 128  // Output embedding dimension
        private const val PIXEL_SIZE = 3  // RGB
        private const val IMAGE_MEAN = 127.5f
        private const val IMAGE_STD = 128f
    }

    private var interpreter: Interpreter? = null
    private var isModelAvailable = false

    init {
        loadModel()
    }

    /**
     * Load the TFLite model from assets
     */
    private fun loadModel() {
        try {
            val modelBuffer = loadModelFile()
            if (modelBuffer != null) {
                val options = Interpreter.Options().apply {
                    setNumThreads(4)
                }
                interpreter = Interpreter(modelBuffer, options)
                isModelAvailable = true
                Log.i(TAG, "MobileFaceNet model loaded successfully")
            } else {
                Log.w(TAG, "MobileFaceNet model not found, using fallback hash-based similarity")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load MobileFaceNet model", e)
            isModelAvailable = false
        }
    }

    /**
     * Load model file from assets
     */
    private fun loadModelFile(): MappedByteBuffer? {
        return try {
            val assetFileDescriptor = context.assets.openFd(MODEL_FILENAME)
            val fileInputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
            val fileChannel = fileInputStream.channel
            val startOffset = assetFileDescriptor.startOffset
            val declaredLength = assetFileDescriptor.declaredLength
            fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
        } catch (e: Exception) {
            Log.w(TAG, "Model file not found in assets: $MODEL_FILENAME")
            null
        }
    }

    /**
     * Generate embedding for a face bitmap
     * @param faceBitmap The cropped face bitmap
     * @return FloatArray embedding (128-dimensional) or null on failure
     */
    suspend fun generateEmbedding(faceBitmap: Bitmap): FloatArray? = withContext(Dispatchers.Default) {
        try {
            if (isModelAvailable && interpreter != null) {
                generateTFLiteEmbedding(faceBitmap)
            } else {
                // Fallback to perceptual hash-based embedding
                generateHashBasedEmbedding(faceBitmap)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating embedding", e)
            null
        }
    }

    /**
     * Generate embedding using TFLite model
     */
    private fun generateTFLiteEmbedding(faceBitmap: Bitmap): FloatArray? {
        val scaledBitmap = Bitmap.createScaledBitmap(faceBitmap, INPUT_SIZE, INPUT_SIZE, true)
        val inputBuffer = convertBitmapToByteBuffer(scaledBitmap)

        val outputArray = Array(1) { FloatArray(EMBEDDING_SIZE) }

        interpreter?.run(inputBuffer, outputArray)

        if (scaledBitmap != faceBitmap) {
            scaledBitmap.recycle()
        }

        // Normalize the embedding
        return normalizeEmbedding(outputArray[0])
    }

    /**
     * Convert bitmap to ByteBuffer for model input
     */
    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * PIXEL_SIZE)
        byteBuffer.order(ByteOrder.nativeOrder())

        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        for (pixel in pixels) {
            val r = ((pixel shr 16) and 0xFF)
            val g = ((pixel shr 8) and 0xFF)
            val b = (pixel and 0xFF)

            // Normalize to [-1, 1]
            byteBuffer.putFloat((r - IMAGE_MEAN) / IMAGE_STD)
            byteBuffer.putFloat((g - IMAGE_MEAN) / IMAGE_STD)
            byteBuffer.putFloat((b - IMAGE_MEAN) / IMAGE_STD)
        }

        return byteBuffer
    }

    /**
     * Fallback: Generate a perceptual hash-based embedding
     * This allows basic face grouping without the ML model
     */
    private fun generateHashBasedEmbedding(faceBitmap: Bitmap): FloatArray {
        // Scale to small size for feature extraction
        val smallBitmap = Bitmap.createScaledBitmap(faceBitmap, 32, 32, true)
        val embedding = FloatArray(EMBEDDING_SIZE)

        // Extract color histogram features
        val pixels = IntArray(32 * 32)
        smallBitmap.getPixels(pixels, 0, 32, 0, 0, 32, 32)

        // Divide image into 4x4 grid and extract features from each cell
        var embIdx = 0
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

                if (embIdx < EMBEDDING_SIZE) embedding[embIdx++] = avgR / count / 255f
                if (embIdx < EMBEDDING_SIZE) embedding[embIdx++] = avgG / count / 255f
                if (embIdx < EMBEDDING_SIZE) embedding[embIdx++] = avgB / count / 255f
            }
        }

        // Add gradient features
        for (y in 0 until 31) {
            for (x in 0 until 31) {
                if (embIdx >= EMBEDDING_SIZE) break
                val p1 = pixels[y * 32 + x]
                val p2 = pixels[(y + 1) * 32 + x + 1]
                val diff = ((p1 and 0xFF) - (p2 and 0xFF)).toFloat() / 255f
                embedding[embIdx++] = diff
            }
            if (embIdx >= EMBEDDING_SIZE) break
        }

        smallBitmap.recycle()

        return normalizeEmbedding(embedding)
    }

    /**
     * Normalize embedding to unit length
     */
    private fun normalizeEmbedding(embedding: FloatArray): FloatArray {
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
     * Calculate cosine similarity between two embeddings
     */
    fun calculateSimilarity(embedding1: FloatArray, embedding2: FloatArray): Float {
        if (embedding1.size != embedding2.size) return 0f

        var dotProduct = 0f
        var norm1 = 0f
        var norm2 = 0f

        for (i in embedding1.indices) {
            dotProduct += embedding1[i] * embedding2[i]
            norm1 += embedding1[i] * embedding1[i]
            norm2 += embedding2[i] * embedding2[i]
        }

        norm1 = sqrt(norm1)
        norm2 = sqrt(norm2)

        return if (norm1 > 0 && norm2 > 0) {
            dotProduct / (norm1 * norm2)
        } else {
            0f
        }
    }

    /**
     * Check if the TFLite model is available
     */
    fun isModelAvailable(): Boolean = isModelAvailable

    /**
     * Close the interpreter
     */
    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
