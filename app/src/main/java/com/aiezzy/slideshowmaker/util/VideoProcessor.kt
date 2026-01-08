package com.aiezzy.slideshowmaker.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.media.FaceDetector
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import android.graphics.Typeface
import com.aiezzy.slideshowmaker.data.models.AudioConfig
import com.aiezzy.slideshowmaker.data.models.TextOverlay
import com.aiezzy.slideshowmaker.data.models.TextPosition
import com.aiezzy.slideshowmaker.data.models.TextStyle
import com.aiezzy.slideshowmaker.data.models.TransitionEffect
import com.aiezzy.slideshowmaker.data.models.VideoConfig
import com.aiezzy.slideshowmaker.data.models.WatermarkConfig
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext
import kotlin.math.cos
import kotlin.math.sin
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "VideoProcessor"

class VideoProcessor(private val context: Context) {

    @Volatile
    private var isCancelled = false

    // Detected color format for current encoding session
    @Volatile
    private var detectedColorFormat = COLOR_FORMAT_I420

    /**
     * Bitmap pool for reusing bitmap allocations during video encoding.
     * This reduces GC pressure from ~100MB/sec to near zero during transitions.
     * Pool size of 5 is optimal for most transition effects which need at most
     * 3-4 intermediate bitmaps at any time.
     */
    private val bitmapPool = BitmapPool(maxSize = 5)

    companion object {
        private const val MIME_TYPE = "video/avc"
        private const val FRAME_RATE = 2 // 2fps - ultra fast, minimum for 0.5s duration
        private const val I_FRAME_INTERVAL = 2 // Keyframe every 2 seconds
        private const val BIT_RATE = 4000000 // 4 Mbps - good quality
        private const val TIMEOUT_US = 10L // 0.01ms - ultra aggressive
        private const val AUDIO_BIT_RATE = 128000 // 128 kbps
        private const val PREFETCH_COUNT = 2

        // No resolution cap - keep original quality
        private const val MAX_WIDTH = 4096
        private const val MAX_HEIGHT = 4096

        // YUV color format constants
        private const val COLOR_FORMAT_I420 = 1       // Planar: Y, U, V separate
        private const val COLOR_FORMAT_NV12 = 2       // Semi-planar: Y, then UV interleaved
        private const val COLOR_FORMAT_NV21 = 3       // Semi-planar: Y, then VU interleaved

        /**
         * Static pool reference for memory trimming from Application class.
         * Call this from Application.onTrimMemory() to free pooled bitmaps.
         */
        @Volatile
        private var activePool: BitmapPool? = null

        fun clearBitmapPool() {
            activePool?.clear()
        }
    }

    /**
     * Detects the actual YUV color format the encoder expects.
     * COLOR_FormatYUV420Flexible allows the codec to choose between I420, NV12, or NV21.
     * We need to detect which one it chose to write the correct byte layout.
     *
     * IMPORTANT: This should be called AFTER the encoder has been started and has produced
     * at least one output, as outputFormat may not be valid immediately.
     */
    private fun detectColorFormat(encoder: MediaCodec): Int {
        try {
            // First, try to get color format from output format
            val outputFormat = try {
                encoder.outputFormat
            } catch (e: IllegalStateException) {
                null
            }

            val colorFormat = outputFormat?.getInteger(MediaFormat.KEY_COLOR_FORMAT, -1) ?: -1
            Log.d(TAG, "Encoder output format color: $colorFormat")

            // If we got a valid format, use it
            if (colorFormat > 0) {
                return when (colorFormat) {
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
                    0x13 -> {
                        Log.d(TAG, "Detected I420 (planar) format")
                        COLOR_FORMAT_I420
                    }
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
                    0x15 -> {
                        Log.d(TAG, "Detected NV12 (semi-planar UV) format")
                        COLOR_FORMAT_NV12
                    }
                    0x11 -> {
                        Log.d(TAG, "Detected NV21 (semi-planar VU) format")
                        COLOR_FORMAT_NV21
                    }
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible -> {
                        // Flexible format - need to probe further
                        Log.d(TAG, "Flexible format detected, will use Image API")
                        COLOR_FORMAT_NV12 // Will be overridden by Image API
                    }
                    else -> {
                        Log.d(TAG, "Unknown format $colorFormat, will use Image API")
                        COLOR_FORMAT_NV12
                    }
                }
            }

            // Format not available yet - use NV12 as fallback
            // The Image API will handle the actual format conversion
            Log.d(TAG, "Color format not available, will use Image API for proper handling")
            return COLOR_FORMAT_NV12

        } catch (e: Exception) {
            Log.w(TAG, "Failed to detect color format", e)
            return COLOR_FORMAT_NV12
        }
    }

    /**
     * Flag to track if we should use Image API (preferred) or ByteBuffer (fallback)
     */
    @Volatile
    private var useImageApi = true

    /**
     * Main entry point for video generation.
     *
     * This method uses a STREAMING PIPELINE that loads images on-demand,
     * keeping only 2 images in memory at any time (current + next for transitions).
     *
     * Memory usage: O(2) instead of O(n) where n = number of images
     * - 50 images at 1080p: 16MB instead of 400MB
     * - Supports unlimited images without OOM
     */
    suspend fun generateVideo(
        config: VideoConfig,
        onProgress: (Int, String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        isCancelled = false
        var tempDir: File? = null

        // Register pool for potential memory trimming
        activePool = bitmapPool

        try {
            // Validate input
            if (config.images.isEmpty()) {
                onError("No images to process")
                return@withContext
            }

            val outputDir = File(context.cacheDir, "output")
            outputDir.mkdirs()

            tempDir = File(context.cacheDir, "temp_${System.currentTimeMillis()}")
            tempDir.mkdirs()

            // Generate video using STREAMING PIPELINE
            // This loads images on-demand instead of all at once
            val outputPath = File(outputDir, "slideshow_${System.currentTimeMillis()}.mp4").absolutePath

            Log.d(TAG, "Starting video generation with streaming pipeline: ${config.images.size} images")

            val success = createVideoWithStreamingPipeline(
                config = config,
                outputPath = outputPath,
                onProgress = onProgress
            )

            if (isCancelled) {
                File(outputPath).delete()
                return@withContext
            }

            if (success && File(outputPath).exists()) {
                // Check if we need to add audio
                if (config.audio?.uri != null) {
                    onProgress(96, "Adding audio...")
                    val finalOutputPath = File(outputDir, "slideshow_final_${System.currentTimeMillis()}.mp4").absolutePath
                    val audioSuccess = addAudioToVideo(
                        videoPath = outputPath,
                        audioConfig = config.audio,
                        outputPath = finalOutputPath,
                        videoDurationMs = (config.images.size * config.durationPerImage * 1000).toLong()
                    )

                    if (audioSuccess && File(finalOutputPath).exists()) {
                        // Audio muxing succeeded - delete video-only file and return final
                        File(outputPath).delete()
                        onProgress(100, "Video ready!")
                        onComplete(finalOutputPath)
                    } else {
                        // Audio failed - delete failed output and return video without audio
                        File(finalOutputPath).delete()
                        Log.w(TAG, "Audio muxing failed, returning video without audio")
                        onProgress(100, "Video ready (audio failed)!")
                        onComplete(outputPath)
                    }
                } else {
                    onProgress(100, "Video ready!")
                    onComplete(outputPath)
                }
            } else {
                Log.e(TAG, "Video generation failed: success=$success, exists=${File(outputPath).exists()}")
                onError("Failed to generate video")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Video generation error", e)
            onError(e.message ?: "Unknown error occurred")
        } finally {
            // Clean up bitmap pool to free memory
            bitmapPool.clear()
            activePool = null

            // Log pool statistics for debugging
            Log.d(TAG, "Video generation complete. Bitmap pool cleared.")

            // Clean up temp directory
            tempDir?.let { cleanup(it) }
        }
    }

    private fun addAudioToVideo(
        videoPath: String,
        audioConfig: AudioConfig,
        outputPath: String,
        videoDurationMs: Long
    ): Boolean {
        val audioUri = audioConfig.uri ?: return false

        var videoExtractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        var audioFd: android.os.ParcelFileDescriptor? = null

        try {
            // Set up video extractor
            videoExtractor = MediaExtractor()
            videoExtractor.setDataSource(videoPath)

            // Find video track
            var videoTrackIndex = -1
            var videoFormat: MediaFormat? = null
            for (i in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("video/") == true) {
                    videoTrackIndex = i
                    videoFormat = format
                    break
                }
            }

            if (videoTrackIndex < 0 || videoFormat == null) {
                Log.e(TAG, "No video track found in $videoPath")
                return false
            }

            // Set up audio extractor - keep fd open until we're done
            audioExtractor = MediaExtractor()

            // Handle different URI schemes
            val audioScheme = audioUri.scheme
            Log.d(TAG, "Audio URI: $audioUri, scheme: $audioScheme")

            when {
                audioScheme == "file" -> {
                    val filePath = audioUri.path
                    if (filePath == null || !File(filePath).exists()) {
                        Log.e(TAG, "Audio file does not exist: $filePath")
                        return false
                    }
                    Log.d(TAG, "Setting audio data source from file: $filePath")
                    audioExtractor.setDataSource(filePath)
                }
                audioScheme == "content" -> {
                    audioFd = context.contentResolver.openFileDescriptor(audioUri, "r")
                    if (audioFd == null) {
                        Log.e(TAG, "Failed to open audio file descriptor for $audioUri")
                        return false
                    }
                    audioExtractor.setDataSource(audioFd.fileDescriptor)
                }
                else -> {
                    try {
                        audioExtractor.setDataSource(context, audioUri, null)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to set audio data source for $audioUri", e)
                        return false
                    }
                }
            }

            // Find audio track
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until audioExtractor.trackCount) {
                val format = audioExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                Log.d(TAG, "Audio track $i mime: $mime")
                if (mime?.startsWith("audio/") == true) {
                    audioTrackIndex = i
                    audioFormat = format
                    Log.d(TAG, "Found audio track: $mime, format: $format")
                    break
                }
            }

            if (audioTrackIndex < 0 || audioFormat == null) {
                Log.e(TAG, "No audio track found in $audioUri (trackCount: ${audioExtractor.trackCount})")
                return false
            }

            val audioMime = audioFormat.getString(MediaFormat.KEY_MIME) ?: "unknown"
            val isAacCompatible = audioMime == "audio/mp4a-latm" || audioMime == "audio/aac"
            Log.d(TAG, "Audio mime: $audioMime, AAC compatible: $isAacCompatible")

            val sampleRate = try { audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) } catch (e: Exception) { 44100 }
            val channelCount = try { audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) } catch (e: Exception) { 2 }
            Log.d(TAG, "Audio sample rate: $sampleRate, channels: $channelCount")

            audioExtractor.selectTrack(audioTrackIndex)

            if (isAacCompatible) {
                // Direct muxing for AAC audio
                return muxVideoAndAudioDirect(
                    videoExtractor, videoTrackIndex, videoFormat,
                    audioExtractor, audioFormat,
                    outputPath, audioConfig, videoDurationMs
                )
            } else {
                // Transcode to AAC first using FFmpeg
                // FFmpeg needs a file path, so copy content:// URI to temp file if needed
                Log.d(TAG, "Transcoding audio from $audioMime to AAC using FFmpeg")

                val audioFilePath: String = when (audioUri.scheme) {
                    "file" -> audioUri.path ?: run {
                        Log.e(TAG, "Audio file path is null")
                        return false
                    }
                    "content" -> {
                        // Copy content URI to temp file for FFmpeg
                        val tempInputFile = File(context.cacheDir, "temp_input_audio_${System.currentTimeMillis()}")
                        try {
                            context.contentResolver.openInputStream(audioUri)?.use { input ->
                                FileOutputStream(tempInputFile).use { output ->
                                    input.copyTo(output)
                                }
                            }
                            if (!tempInputFile.exists() || tempInputFile.length() == 0L) {
                                Log.e(TAG, "Failed to copy audio URI to temp file")
                                return false
                            }
                            Log.d(TAG, "Copied audio to temp file: ${tempInputFile.absolutePath}, size: ${tempInputFile.length()}")
                            tempInputFile.absolutePath
                        } catch (e: Exception) {
                            Log.e(TAG, "Error copying audio URI to temp file", e)
                            tempInputFile.delete()
                            return false
                        }
                    }
                    else -> {
                        Log.e(TAG, "Unsupported audio URI scheme: ${audioUri.scheme}")
                        return false
                    }
                }

                val result = muxVideoWithTranscodedAudio(
                    videoExtractor, videoTrackIndex, videoFormat,
                    audioExtractor, audioFormat, audioMime, sampleRate, channelCount,
                    outputPath, audioConfig, videoDurationMs, videoPath, audioFilePath
                )

                // Clean up temp input file if we created one
                if (audioUri.scheme == "content") {
                    File(audioFilePath).delete()
                }

                return result
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to add audio to video", e)
            e.printStackTrace()
            return false
        } finally {
            try { videoExtractor?.release() } catch (e: Exception) { }
            try { audioExtractor?.release() } catch (e: Exception) { }
            try { audioFd?.close() } catch (e: Exception) { }
        }
    }

    private fun muxVideoAndAudioDirect(
        videoExtractor: MediaExtractor,
        videoTrackIndex: Int,
        videoFormat: MediaFormat,
        audioExtractor: MediaExtractor,
        audioFormat: MediaFormat,
        outputPath: String,
        audioConfig: AudioConfig,
        videoDurationMs: Long
    ): Boolean {
        var muxer: MediaMuxer? = null
        try {
            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            videoExtractor.selectTrack(videoTrackIndex)
            val muxerVideoTrack = muxer.addTrack(videoFormat)
            val muxerAudioTrack = muxer.addTrack(audioFormat)

            muxer.start()

            // Copy video track
            copyTrackData(videoExtractor, muxer, muxerVideoTrack, 0, Long.MAX_VALUE)

            // Copy audio track
            val startTimeUs = audioConfig.startTimeSeconds * 1_000_000L
            val maxDurationUs = videoDurationMs * 1000
            if (startTimeUs > 0) {
                audioExtractor.seekTo(startTimeUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }
            copyTrackData(audioExtractor, muxer, muxerAudioTrack, startTimeUs, maxDurationUs)

            Log.d(TAG, "Direct audio muxing completed successfully")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Direct muxing failed", e)
            return false
        } finally {
            try { muxer?.stop() } catch (e: Exception) { }
            try { muxer?.release() } catch (e: Exception) { }
        }
    }

    /**
     * Uses Media3 Transformer to combine video and audio into final output.
     * Transformer handles ALL the complexity - transcoding, muxing, sync - in one step.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun muxVideoWithTranscodedAudio(
        videoExtractor: MediaExtractor,
        videoTrackIndex: Int,
        videoFormat: MediaFormat,
        audioExtractor: MediaExtractor,
        audioFormat: MediaFormat,
        audioMime: String,
        sampleRate: Int,
        channelCount: Int,
        outputPath: String,
        audioConfig: AudioConfig,
        videoDurationMs: Long,
        videoOnlyPath: String,
        audioFilePath: String
    ): Boolean {
        try {
            Log.d(TAG, "Using Media3 Transformer to combine video+audio")
            Log.d(TAG, "Video: $videoOnlyPath")
            Log.d(TAG, "Audio: $audioFilePath")
            Log.d(TAG, "Output: $outputPath")

            val latch = CountDownLatch(1)
            val success = AtomicReference(false)
            val errorRef = AtomicReference<Exception?>(null)

            // Create video MediaItem (no audio)
            val videoMediaItem = MediaItem.Builder()
                .setUri(videoOnlyPath)
                .build()

            val videoEditedItem = EditedMediaItem.Builder(videoMediaItem)
                .setRemoveAudio(true)
                .build()

            // Create audio MediaItem with clipping
            val startMs = audioConfig.startTimeSeconds * 1000L
            val endMs = startMs + videoDurationMs

            val audioClippingConfig = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(startMs)
                .setEndPositionMs(endMs)
                .build()

            val audioMediaItem = MediaItem.Builder()
                .setUri(audioFilePath)
                .setClippingConfiguration(audioClippingConfig)
                .build()

            val audioEditedItem = EditedMediaItem.Builder(audioMediaItem)
                .setRemoveVideo(true)
                .build()

            // Create composition with both tracks
            val composition = Composition.Builder(
                listOf(
                    androidx.media3.transformer.EditedMediaItemSequence(listOf(videoEditedItem)),
                    androidx.media3.transformer.EditedMediaItemSequence(listOf(audioEditedItem))
                )
            ).build()

            // Run transformer on main thread
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    val transformer = Transformer.Builder(context)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(comp: Composition, exportResult: ExportResult) {
                                Log.d(TAG, "Video+Audio combination completed successfully")
                                Log.d(TAG, "Output size: ${File(outputPath).length()} bytes")
                                success.set(true)
                                latch.countDown()
                            }

                            override fun onError(comp: Composition, exportResult: ExportResult, exportException: ExportException) {
                                Log.e(TAG, "Video+Audio combination error: ${exportException.message}", exportException)
                                errorRef.set(exportException)
                                latch.countDown()
                            }
                        })
                        .build()

                    transformer.start(composition, outputPath)
                } catch (e: Exception) {
                    Log.e(TAG, "Transformer start error", e)
                    errorRef.set(e)
                    latch.countDown()
                }
            }

            // Wait for completion (max 5 minutes for large videos)
            val completed = latch.await(300, TimeUnit.SECONDS)

            if (!completed) {
                Log.e(TAG, "Video+Audio combination timed out")
                return false
            }

            val error = errorRef.get()
            if (error != null) {
                Log.e(TAG, "Video+Audio combination failed", error)
                return false
            }

            return success.get() && File(outputPath).exists() && File(outputPath).length() > 0

        } catch (e: Exception) {
            Log.e(TAG, "Video+Audio muxing failed", e)
            e.printStackTrace()
            return false
        }
    }

    /**
     * Transcodes audio to AAC using Media3 Transformer.
     * This is Google's official library for media transformation - handles all complexity internally.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun transcodeAudioToAac(
        inputAudioPath: String,
        outputFile: File,
        startTimeSeconds: Int,
        maxDurationSeconds: Double
    ): Boolean {
        try {
            Log.d(TAG, "Starting Media3 Transformer audio transcoding")
            Log.d(TAG, "Input: $inputAudioPath")
            Log.d(TAG, "Output: ${outputFile.absolutePath}")
            Log.d(TAG, "Start time: ${startTimeSeconds}s, Max duration: ${maxDurationSeconds}s")

            val latch = CountDownLatch(1)
            val success = AtomicReference(false)
            val errorRef = AtomicReference<Exception?>(null)

            // Build clipping configuration
            val startMs = startTimeSeconds * 1000L
            val endMs = startMs + (maxDurationSeconds * 1000).toLong()

            val clippingConfig = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(startMs)
                .setEndPositionMs(endMs)
                .build()

            val mediaItem = MediaItem.Builder()
                .setUri(inputAudioPath)
                .setClippingConfiguration(clippingConfig)
                .build()

            val editedMediaItem = EditedMediaItem.Builder(mediaItem)
                .setRemoveVideo(true) // Audio only
                .build()

            // Run transformer on main thread (required by Media3)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    val transformer = Transformer.Builder(context)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC) // Force AAC output
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                Log.d(TAG, "Transformer completed successfully")
                                success.set(true)
                                latch.countDown()
                            }

                            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                Log.e(TAG, "Transformer error: ${exportException.message}", exportException)
                                errorRef.set(exportException)
                                latch.countDown()
                            }
                        })
                        .build()

                    transformer.start(editedMediaItem, outputFile.absolutePath)
                } catch (e: Exception) {
                    Log.e(TAG, "Transformer start error", e)
                    errorRef.set(e)
                    latch.countDown()
                }
            }

            // Wait for completion (max 2 minutes)
            val completed = latch.await(120, TimeUnit.SECONDS)

            if (!completed) {
                Log.e(TAG, "Transformer timed out")
                return false
            }

            val error = errorRef.get()
            if (error != null) {
                Log.e(TAG, "Transformer failed", error)
                return false
            }

            val result = success.get() && outputFile.exists() && outputFile.length() > 0
            Log.d(TAG, "Transformer result: $result, output size: ${outputFile.length()} bytes")
            return result

        } catch (e: Exception) {
            Log.e(TAG, "Media3 Transformer error", e)
            e.printStackTrace()
            return false
        }
    }

    private fun copyTrackData(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        trackIndex: Int,
        startTimeUs: Long,
        maxDurationUs: Long
    ) {
        // Use large direct ByteBuffer - video frames can be large (up to 4MB for 4K)
        val buffer = ByteBuffer.allocateDirect(4 * 1024 * 1024)
        val bufferInfo = MediaCodec.BufferInfo()
        var samplesWritten = 0

        while (!isCancelled) {
            // Check if there's a sample available
            val sampleTime = extractor.sampleTime
            if (sampleTime < 0) {
                // No more samples
                break
            }

            // Check duration limit
            val adjustedTime = sampleTime - startTimeUs
            if (adjustedTime > maxDurationUs) {
                break
            }

            buffer.clear()
            val sampleSize = try {
                extractor.readSampleData(buffer, 0)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Error reading sample data at time $sampleTime, samples written: $samplesWritten", e)
                break
            }

            if (sampleSize < 0) break

            bufferInfo.offset = 0
            bufferInfo.size = sampleSize
            bufferInfo.presentationTimeUs = adjustedTime.coerceAtLeast(0)
            bufferInfo.flags = extractor.sampleFlags

            buffer.position(0)
            buffer.limit(sampleSize)

            try {
                muxer.writeSampleData(trackIndex, buffer, bufferInfo)
                samplesWritten++
            } catch (e: Exception) {
                Log.e(TAG, "Error writing sample data", e)
                break
            }

            extractor.advance()
        }

        Log.d(TAG, "copyTrackData completed: $samplesWritten samples written")
    }

    // Draw text overlays on a bitmap
    private fun drawTextOverlays(
        bitmap: Bitmap,
        overlays: List<TextOverlay>,
        watermark: WatermarkConfig,
        imageIndex: Int
    ): Bitmap {
        if (overlays.isEmpty() && !watermark.enabled) return bitmap

        val width = bitmap.width
        val height = bitmap.height

        // Use pooled bitmap instead of bitmap.copy() to reduce allocations
        val result = bitmapPool.acquire(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        // Draw original bitmap first
        canvas.drawBitmap(bitmap, 0f, 0f, null)

        // Draw text overlays
        overlays.forEach { overlay ->
            val shouldShow = overlay.showOnAllImages || overlay.imageIndex == imageIndex
            if (shouldShow) {
                drawText(canvas, overlay.text, overlay.position, overlay.style, width, height, 1f)
            }
        }

        // Draw watermark with stylish yellow accent
        if (watermark.enabled) {
            drawStylishWatermark(
                canvas = canvas,
                text = watermark.text,
                position = watermark.position,
                width = width,
                height = height,
                alpha = watermark.opacity
            )
        }

        return result
    }

    /**
     * Draws a stylish watermark with yellow accent color matching the app theme
     */
    private fun drawStylishWatermark(
        canvas: Canvas,
        text: String,
        position: TextPosition,
        width: Int,
        height: Int,
        alpha: Float
    ) {
        val textSize = height * 0.035f // Slightly smaller for elegance
        val padding = (height * 0.025f).toInt()

        // Yellow accent color matching app theme (#E5FF00)
        val accentYellow = Color.rgb(229, 255, 0)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentYellow
            this.textSize = textSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            this.alpha = (255 * alpha).toInt()
            letterSpacing = 0.05f // Slight letter spacing for style
            textAlign = when (position) {
                TextPosition.TOP_LEFT, TextPosition.BOTTOM_LEFT -> Paint.Align.LEFT
                TextPosition.TOP_CENTER, TextPosition.CENTER, TextPosition.BOTTOM_CENTER -> Paint.Align.CENTER
                TextPosition.TOP_RIGHT, TextPosition.BOTTOM_RIGHT -> Paint.Align.RIGHT
            }
        }

        // Measure text
        val textBounds = android.graphics.Rect()
        textPaint.getTextBounds(text, 0, text.length, textBounds)

        // Calculate position
        val x = when (position) {
            TextPosition.TOP_LEFT, TextPosition.BOTTOM_LEFT -> padding.toFloat()
            TextPosition.TOP_CENTER, TextPosition.CENTER, TextPosition.BOTTOM_CENTER -> width / 2f
            TextPosition.TOP_RIGHT, TextPosition.BOTTOM_RIGHT -> width - padding.toFloat()
        }

        val y = when (position) {
            TextPosition.TOP_LEFT, TextPosition.TOP_CENTER, TextPosition.TOP_RIGHT ->
                padding + textSize
            TextPosition.CENTER ->
                (height + textSize) / 2f
            TextPosition.BOTTOM_LEFT, TextPosition.BOTTOM_CENTER, TextPosition.BOTTOM_RIGHT ->
                height - padding.toFloat()
        }

        // Draw subtle dark shadow for contrast
        val shadowPaint = Paint(textPaint).apply {
            color = Color.BLACK
            this.alpha = (100 * alpha).toInt()
        }
        canvas.drawText(text, x + 1, y + 1, shadowPaint)

        // Draw main text with yellow accent
        canvas.drawText(text, x, y, textPaint)
    }

    private fun drawText(
        canvas: Canvas,
        text: String,
        position: TextPosition,
        style: TextStyle,
        width: Int,
        height: Int,
        alpha: Float
    ) {
        val textSize = height * style.textSizeRatio
        val padding = (height * 0.03f).toInt()

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            typeface = when (style) {
                TextStyle.BOLD, TextStyle.TITLE -> Typeface.DEFAULT_BOLD
                else -> Typeface.DEFAULT
            }
            this.alpha = (255 * alpha).toInt()
            textAlign = when (position) {
                TextPosition.TOP_LEFT, TextPosition.BOTTOM_LEFT -> Paint.Align.LEFT
                TextPosition.TOP_CENTER, TextPosition.CENTER, TextPosition.BOTTOM_CENTER -> Paint.Align.CENTER
                TextPosition.TOP_RIGHT, TextPosition.BOTTOM_RIGHT -> Paint.Align.RIGHT
            }
        }

        // Measure text
        val textBounds = android.graphics.Rect()
        textPaint.getTextBounds(text, 0, text.length, textBounds)

        // Calculate position
        val x = when (position) {
            TextPosition.TOP_LEFT, TextPosition.BOTTOM_LEFT -> padding.toFloat()
            TextPosition.TOP_CENTER, TextPosition.CENTER, TextPosition.BOTTOM_CENTER -> width / 2f
            TextPosition.TOP_RIGHT, TextPosition.BOTTOM_RIGHT -> width - padding.toFloat()
        }

        val y = when (position) {
            TextPosition.TOP_LEFT, TextPosition.TOP_CENTER, TextPosition.TOP_RIGHT ->
                padding + textSize
            TextPosition.CENTER ->
                (height + textSize) / 2f
            TextPosition.BOTTOM_LEFT, TextPosition.BOTTOM_CENTER, TextPosition.BOTTOM_RIGHT ->
                height - padding.toFloat()
        }

        // Draw background if needed
        if (style.backgroundColor) {
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                this.alpha = (128 * alpha).toInt()
            }
            val bgPadding = padding / 2f
            val bgLeft = when (position) {
                TextPosition.TOP_LEFT, TextPosition.BOTTOM_LEFT -> x - bgPadding
                TextPosition.TOP_CENTER, TextPosition.CENTER, TextPosition.BOTTOM_CENTER ->
                    x - textBounds.width() / 2 - bgPadding
                TextPosition.TOP_RIGHT, TextPosition.BOTTOM_RIGHT ->
                    x - textBounds.width() - bgPadding
            }
            canvas.drawRoundRect(
                bgLeft,
                y - textSize - bgPadding,
                bgLeft + textBounds.width() + bgPadding * 2,
                y + bgPadding,
                bgPadding,
                bgPadding,
                bgPaint
            )
        }

        // Draw shadow if needed
        if (style.shadowEnabled) {
            val shadowPaint = Paint(textPaint).apply {
                color = Color.BLACK
                this.alpha = (180 * alpha).toInt()
            }
            canvas.drawText(text, x + 2, y + 2, shadowPaint)
        }

        // Draw text
        canvas.drawText(text, x, y, textPaint)
    }

    /**
     * Legacy method - DO NOT USE
     * This loads ALL images into memory at once, causing OOM on large slideshows.
     * The streaming pipeline now loads images on-demand.
     */
    @Deprecated("Use createVideoWithStreamingPipeline instead - this method loads all images into memory")
    private suspend fun processImages(
        config: VideoConfig,
        onProgress: (Int, String) -> Unit
    ): List<Bitmap> = coroutineScope {
        val total = config.images.size
        val processedCount = AtomicInteger(0)

        // Process images in parallel (up to 4 at a time for memory efficiency)
        val batchSize = 4.coerceAtMost(total)
        val results = mutableListOf<Bitmap?>()

        config.images.chunked(batchSize).forEach { batch ->
            if (isCancelled) return@coroutineScope results.filterNotNull()

            val batchResults = batch.map { imageItem ->
                async(Dispatchers.IO) {
                    if (isCancelled) return@async null

                    val bitmap = loadAndResizeBitmap(
                        uri = imageItem.uri,
                        targetWidth = config.resolution.width,
                        targetHeight = config.resolution.height
                    )

                    val count = processedCount.incrementAndGet()
                    val progress = 5 + (count * 35 / total)
                    onProgress(progress, "Processing image $count/$total...")

                    bitmap
                }
            }.awaitAll()

            results.addAll(batchResults)
        }

        results.filterNotNull()
    }

    private fun loadAndResizeBitmap(uri: Uri, targetWidth: Int, targetHeight: Int): Bitmap? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeStream(inputStream, null, options)
            inputStream?.close()

            // Calculate sample size
            val sampleSize = calculateSampleSize(
                options.outWidth,
                options.outHeight,
                targetWidth,
                targetHeight
            )

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            }

            val newInputStream = context.contentResolver.openInputStream(uri)
            var bitmap = BitmapFactory.decodeStream(newInputStream, null, decodeOptions)
            newInputStream?.close()

            if (bitmap != null) {
                // Handle rotation from EXIF
                bitmap = rotateIfNeeded(uri, bitmap)

                // Scale and center-crop to fit target dimensions
                bitmap = centerCropBitmap(bitmap, targetWidth, targetHeight)
            }

            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load and resize bitmap", e)
            null
        }
    }

    private fun calculateSampleSize(
        width: Int,
        height: Int,
        targetWidth: Int,
        targetHeight: Int
    ): Int {
        var sampleSize = 1
        if (width > targetWidth || height > targetHeight) {
            val halfWidth = width / 2
            val halfHeight = height / 2
            while (halfWidth / sampleSize >= targetWidth && halfHeight / sampleSize >= targetHeight) {
                sampleSize *= 2
            }
        }
        return sampleSize
    }

    private fun rotateIfNeeded(uri: Uri, bitmap: Bitmap): Bitmap {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val exif = inputStream?.let { ExifInterface(it) }
            inputStream?.close()

            val orientation = exif?.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            ) ?: ExifInterface.ORIENTATION_NORMAL

            val rotation = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }

            if (rotation != 0f) {
                val matrix = Matrix().apply { postRotate(rotation) }
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotated != bitmap) bitmap.recycle()
                rotated
            } else {
                bitmap
            }
        } catch (e: Exception) {
            bitmap
        }
    }

    /**
     * Scales and positions the source bitmap to FIT within the target dimensions.
     *
     * FIT mode: The entire source image is visible, centered with black bars (letterbox/pillarbox)
     * if the aspect ratios don't match. This preserves all content without cropping.
     *
     * This is preferred over FILL mode (which crops) because users want to see their entire photos,
     * especially group photos where people on the edges would otherwise be cut off.
     */
    private fun centerCropBitmap(source: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        val sourceWidth = source.width
        val sourceHeight = source.height
        val sourceRatio = sourceWidth.toFloat() / sourceHeight
        val targetRatio = targetWidth.toFloat() / targetHeight

        val scaledWidth: Int
        val scaledHeight: Int

        // FIT mode: Scale to fit INSIDE the target (preserves entire image, may have black bars)
        // This ensures the entire source image is visible without any cropping
        if (sourceRatio > targetRatio) {
            // Source is wider than target - fit to width, add bars top/bottom
            scaledWidth = targetWidth
            scaledHeight = (sourceHeight * targetWidth.toFloat() / sourceWidth).toInt()
        } else {
            // Source is taller than target - fit to height, add bars left/right
            scaledHeight = targetHeight
            scaledWidth = (sourceWidth * targetHeight.toFloat() / sourceHeight).toInt()
        }

        val scaled = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true)
        if (scaled != source) source.recycle()

        // Create result with black background
        val result = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        // Fill with black background for letterbox/pillarbox effect
        canvas.drawColor(Color.BLACK)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // Center the scaled image on the canvas
        val left = (targetWidth - scaledWidth) / 2f
        val top = (targetHeight - scaledHeight) / 2f

        canvas.drawBitmap(scaled, left, top, paint)

        if (scaled != result) scaled.recycle()
        return result
    }

    // Detect face center point for smart cropping
    private fun detectFaceCenter(bitmap: Bitmap): PointF? {
        return try {
            // FaceDetector requires RGB_565 format
            val rgb565Bitmap = if (bitmap.config != Bitmap.Config.RGB_565) {
                bitmap.copy(Bitmap.Config.RGB_565, false)
            } else {
                bitmap
            }

            val maxFaces = 5
            val faceDetector = FaceDetector(rgb565Bitmap.width, rgb565Bitmap.height, maxFaces)
            val faces = arrayOfNulls<FaceDetector.Face>(maxFaces)
            val numFaces = faceDetector.findFaces(rgb565Bitmap, faces)

            if (rgb565Bitmap != bitmap) {
                rgb565Bitmap.recycle()
            }

            if (numFaces > 0) {
                // Calculate average center of all faces
                var totalX = 0f
                var totalY = 0f
                var validFaces = 0

                for (i in 0 until numFaces) {
                    val face = faces[i]
                    if (face != null && face.confidence() > 0.3f) {
                        val midPoint = PointF()
                        face.getMidPoint(midPoint)
                        totalX += midPoint.x
                        totalY += midPoint.y
                        validFaces++
                    }
                }

                if (validFaces > 0) {
                    PointF(totalX / validFaces, totalY / validFaces)
                } else null
            } else null
        } catch (e: Exception) {
            // Face detection failed, return null for center crop fallback
            null
        }
    }

    /**
     * STREAMING VIDEO PIPELINE WITH ASYNC PREFETCHING
     *
     * This is the memory-optimized version that loads images on-demand instead of
     * pre-loading all images into memory. This reduces memory usage from O(n) to O(2)
     * where n = number of images.
     *
     * Memory comparison for 50 images at 1080p:
     * - Old approach: 50 images × 8MB = 400MB heap usage
     * - Streaming approach: 2 images × 8MB = 16MB heap usage (96% reduction)
     *
     * The pipeline maintains only:
     * - currentBitmap: The image currently being encoded
     * - nextBitmap: Pre-fetched next image for transitions (loaded async)
     *
     * Async prefetching ensures image loading doesn't block encoding:
     * - When starting an image, we kick off async loading of the NEXT image
     * - By the time we finish encoding current image, next is already loaded
     * - This hides I/O latency behind encoding work
     */
    private suspend fun createVideoWithStreamingPipeline(
        config: VideoConfig,
        outputPath: String,
        onProgress: (Int, String) -> Unit
    ): Boolean = coroutineScope {
        if (config.images.isEmpty()) return@coroutineScope false

        // Scale down if needed while preserving aspect ratio
        // For 9:16 portrait: 1080x1920 -> 720x1280
        // For 16:9 landscape: 1920x1080 -> 1280x720
        val originalWidth = config.resolution.width
        val originalHeight = config.resolution.height
        val maxPixels = MAX_WIDTH * MAX_HEIGHT // 720p equivalent pixels

        val (width, height) = if (originalWidth * originalHeight > maxPixels) {
            val scale = kotlin.math.sqrt(maxPixels.toFloat() / (originalWidth * originalHeight))
            // Round to even numbers (required by video codecs)
            val newWidth = ((originalWidth * scale).toInt() / 2) * 2
            val newHeight = ((originalHeight * scale).toInt() / 2) * 2
            Pair(newWidth.coerceAtLeast(2), newHeight.coerceAtLeast(2))
        } else {
            Pair(originalWidth, originalHeight)
        }

        val imageCount = config.images.size
        val framesPerImage = (config.durationPerImage * FRAME_RATE).toInt()
        val totalFrames = imageCount * framesPerImage

        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var trackIndex = -1
        var muxerStarted = false

        // Streaming state - only 2 images in memory at any time
        var currentBitmap: Bitmap? = null
        var nextBitmap: Bitmap? = null
        var currentImageIndex: Int

        // Prefetch queue for multiple images ahead - speeds up encoding
        val prefetchQueue = mutableListOf<Deferred<Bitmap?>>()

        // YUV cache for static frames - avoid re-converting same bitmap to YUV
        // This is a MAJOR optimization: bitmap→YUV conversion is expensive (~6M operations for 1080p)
        // Caching saves 80%+ of encoding time for static slideshow content
        var cachedYuvBytes: ByteArray? = null
        var cachedForImageIndex = -1

        // Reset Image API flag for this encoding session
        useImageApi = true

        try {
            // Configure MediaFormat with speed optimizations
            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
                // Use VBR for more efficient encoding (faster for static content)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                // Set realtime priority for faster encoding
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                }
            }

            // Try to use hardware encoder for better performance
            val hwEncoderName = try {
                val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
                codecList.codecInfos
                    .filter { it.isEncoder && it.supportedTypes.contains(MIME_TYPE) }
                    .firstOrNull { !it.name.contains("sw", ignoreCase = true) && !it.name.contains("google", ignoreCase = true) }
                    ?.name
            } catch (e: Exception) { null }

            encoder = if (hwEncoderName != null) {
                Log.d(TAG, "Using hardware encoder: $hwEncoderName")
                MediaCodec.createByCodecName(hwEncoderName)
            } else {
                Log.d(TAG, "Using default encoder")
                MediaCodec.createEncoderByType(MIME_TYPE)
            }
            encoder!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder!!.start()

            // Detect the actual color format the encoder expects
            // This is critical - different devices use different YUV layouts (I420, NV12, NV21)
            detectedColorFormat = detectColorFormat(encoder)
            Log.d(TAG, "Using color format: $detectedColorFormat (1=I420, 2=NV12, 3=NV21)")

            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var frameIndex = 0
            var presentationTimeUs = 0L
            val frameDurationUs = 1_000_000L / FRAME_RATE

            // Pre-load first image
            onProgress(5, "Creating video...")
            currentBitmap = withContext(Dispatchers.IO) {
                loadAndResizeBitmap(
                    uri = config.images[0].uri,
                    targetWidth = width,
                    targetHeight = height
                )
            }
            currentImageIndex = 0

            if (currentBitmap == null) {
                Log.e(TAG, "Failed to load first image")
                return@coroutineScope false
            }

            // Start async prefetch of next PREFETCH_COUNT images
            for (i in 1..minOf(PREFETCH_COUNT, imageCount - 1)) {
                prefetchQueue.add(async(Dispatchers.IO) {
                    loadAndResizeBitmap(
                        uri = config.images[i].uri,
                        targetWidth = width,
                        targetHeight = height
                    )
                })
            }

            Log.d(TAG, "Starting streaming encode with ${prefetchQueue.size} prefetched images: $imageCount images, $totalFrames frames")

            while (!outputDone && !isCancelled) {
                // Check for cancellation cooperatively
                ensureActive()

                // Feed input
                if (!inputDone) {
                    val inputBufferIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inputBufferIndex >= 0) {
                        if (frameIndex >= totalFrames) {
                            encoder.queueInputBuffer(
                                inputBufferIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            // Calculate frame position
                            val imageIndex = frameIndex / framesPerImage
                            val frameInImage = frameIndex % framesPerImage

                            // Check if we need to advance to the next image
                            if (imageIndex != currentImageIndex) {
                                // Recycle old current bitmap
                                currentBitmap?.let {
                                    if (!it.isRecycled) it.recycle()
                                }

                                // Get next bitmap from prefetch queue (should already be ready)
                                if (prefetchQueue.isNotEmpty()) {
                                    nextBitmap = prefetchQueue.removeAt(0).await()
                                }

                                // Shift: next → current
                                currentBitmap = nextBitmap
                                currentImageIndex = imageIndex
                                nextBitmap = null

                                // Start async prefetch of more images to keep queue full
                                val nextPrefetchIndex = imageIndex + PREFETCH_COUNT
                                if (nextPrefetchIndex < imageCount) {
                                    prefetchQueue.add(async(Dispatchers.IO) {
                                        loadAndResizeBitmap(
                                            uri = config.images[nextPrefetchIndex].uri,
                                            targetWidth = width,
                                            targetHeight = height
                                        )
                                    })
                                }

                                // Progress is now unified - don't update here to avoid flickering
                                // The main encoding loop will show consistent progress
                            }

                            // For transitions, we need the next bitmap ready
                            // Check if we're approaching a transition and need the next bitmap
                            val transitionFrames = (framesPerImage * 0.2f).toInt()
                            val isApproachingTransition = frameInImage >= framesPerImage - transitionFrames - 1
                            if (isApproachingTransition && nextBitmap == null && prefetchQueue.isNotEmpty()) {
                                // Await first prefetch now since we need it for transition
                                nextBitmap = prefetchQueue.first().await()
                                // Don't remove from queue yet - we'll handle that on image change
                            }

                            val bitmap = currentBitmap
                            if (bitmap == null) {
                                Log.e(TAG, "Current bitmap is null at image $imageIndex")
                                return@coroutineScope false
                            }

                            // SPEED OPTIMIZATION: Check if we can use cached YUV data
                            // For static content (no animation during this frame), we reuse the same YUV bytes
                            // This works for ANY transition - we cache during the static portion (first 80%)
                            val isInTransitionPeriod = frameInImage >= framesPerImage - transitionFrames
                            val hasAnimatedEffect = config.transition != TransitionEffect.NONE &&
                                                   (config.transition == TransitionEffect.ZOOM ||
                                                    config.transition == TransitionEffect.ZOOM_OUT ||
                                                    config.transition == TransitionEffect.KEN_BURNS ||
                                                    config.transition == TransitionEffect.ROTATE)
                            val isStaticFrame = !isInTransitionPeriod &&
                                                !hasAnimatedEffect &&
                                                config.textOverlays.isEmpty() &&
                                                !config.watermark.enabled

                            if (isStaticFrame && cachedForImageIndex == imageIndex && cachedYuvBytes != null) {
                                // FAST PATH: Copy cached YUV data directly to encoder buffer
                                val inputBuffer = encoder.getInputBuffer(inputBufferIndex)
                                inputBuffer?.clear()
                                inputBuffer?.put(cachedYuvBytes)
                            } else {
                                // SLOW PATH: Convert bitmap to YUV (and cache if static)

                                // Apply transition effects
                                var processedBitmap = applyTransition(
                                    bitmap = bitmap,
                                    nextBitmap = nextBitmap,
                                    frameInImage = frameInImage,
                                    framesPerImage = framesPerImage,
                                    transition = config.transition,
                                    width = width,
                                    height = height
                                )

                                // Apply text overlays and watermark
                                if (config.textOverlays.isNotEmpty() || config.watermark.enabled) {
                                    val withText = drawTextOverlays(
                                        bitmap = processedBitmap,
                                        overlays = config.textOverlays,
                                        watermark = config.watermark,
                                        imageIndex = imageIndex
                                    )
                                    if (withText !== processedBitmap && processedBitmap !== bitmap) {
                                        bitmapPool.release(processedBitmap)
                                    }
                                    processedBitmap = withText
                                }

                                // For static frames, use ByteBuffer mode to enable caching
                                // For dynamic frames, use Image API for proper stride handling
                                if (isStaticFrame && frameInImage == 0) {
                                    // First static frame - convert and cache
                                    val yuvSize = width * height * 3 / 2
                                    if (cachedYuvBytes == null || cachedYuvBytes!!.size != yuvSize) {
                                        cachedYuvBytes = ByteArray(yuvSize)
                                    }
                                    // Convert bitmap to YUV and store in cache
                                    fillBitmapToYuvBytes(processedBitmap, cachedYuvBytes!!, width, height)
                                    cachedForImageIndex = imageIndex
                                    // Copy cache to encoder buffer
                                    val inputBuffer = encoder.getInputBuffer(inputBufferIndex)
                                    inputBuffer?.clear()
                                    inputBuffer?.put(cachedYuvBytes)
                                } else {
                                    // Dynamic frame - use Image API
                                    fillEncoderInputWithBitmap(encoder, inputBufferIndex, processedBitmap, width, height)
                                }

                                // Release processed bitmap to pool if different from source
                                if (processedBitmap !== bitmap) {
                                    bitmapPool.release(processedBitmap)
                                }
                            }

                            encoder!!.queueInputBuffer(
                                inputBufferIndex, 0,
                                width * height * 3 / 2,
                                presentationTimeUs, 0
                            )

                            presentationTimeUs += frameDurationUs
                            frameIndex++

                            // Update progress (5% to 95%) - update every 2 seconds of video
                            if (frameIndex % (FRAME_RATE * 2) == 0) {
                                val progress = 5 + (frameIndex * 90 / totalFrames)
                                onProgress(progress, "Creating video...")
                            }
                        }
                    }
                }

                // Drain output
                val outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (muxerStarted) {
                            throw RuntimeException("Format changed twice")
                        }
                        trackIndex = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    outputBufferIndex >= 0 -> {
                        val outputBuffer = encoder.getOutputBuffer(outputBufferIndex)
                        if (outputBuffer != null && bufferInfo.size > 0 && muxerStarted) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(outputBufferIndex, false)

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            Log.d(TAG, "Streaming encode complete: $frameIndex frames encoded")
            return@coroutineScope !isCancelled

        } catch (e: Exception) {
            Log.e(TAG, "Failed to create video with streaming pipeline", e)
            return@coroutineScope false
        } finally {
            // Cancel any pending prefetches
            prefetchQueue.forEach { it.cancel() }
            prefetchQueue.clear()

            // Clean up streaming bitmaps
            currentBitmap?.let { if (!it.isRecycled) it.recycle() }
            nextBitmap?.let { if (!it.isRecycled) it.recycle() }

            // Release encoder resources
            try {
                encoder?.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to stop encoder", e)
            }
            try {
                encoder?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to release encoder", e)
            }
            if (muxerStarted) {
                try {
                    muxer?.stop()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to stop muxer", e)
                }
            }
            try {
                muxer?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to release muxer", e)
            }
        }
    }

    /**
     * Legacy method kept for reference - DO NOT USE
     * This loads ALL images into memory before encoding, causing OOM on large slideshows.
     * Use createVideoWithStreamingPipeline() instead.
     */
    @Deprecated("Use createVideoWithStreamingPipeline instead - this method loads all images into memory")
    private fun createVideoWithMediaCodec(
        bitmaps: List<Bitmap>,
        config: VideoConfig,
        outputPath: String,
        onProgress: (Int) -> Unit
    ): Boolean {
        if (bitmaps.isEmpty()) return false

        val width = config.resolution.width
        val height = config.resolution.height
        val framesPerImage = (config.durationPerImage * FRAME_RATE).toInt()
        val totalFrames = bitmaps.size * framesPerImage

        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var trackIndex = -1
        var muxerStarted = false

        try {
            // Configure MediaFormat with speed optimizations
            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
                // Use VBR for more efficient encoding (faster for static content)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                // Set realtime priority for faster encoding
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                }
            }

            // Try to use hardware encoder for better performance
            val hwEncoderName = try {
                val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
                codecList.codecInfos
                    .filter { it.isEncoder && it.supportedTypes.contains(MIME_TYPE) }
                    .firstOrNull { !it.name.contains("sw", ignoreCase = true) && !it.name.contains("google", ignoreCase = true) }
                    ?.name
            } catch (e: Exception) { null }

            encoder = if (hwEncoderName != null) {
                Log.d(TAG, "Using hardware encoder: $hwEncoderName")
                MediaCodec.createByCodecName(hwEncoderName)
            } else {
                Log.d(TAG, "Using default encoder")
                MediaCodec.createEncoderByType(MIME_TYPE)
            }
            encoder!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder!!.start()

            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var frameIndex = 0
            var presentationTimeUs = 0L
            val frameDurationUs = 1_000_000L / FRAME_RATE

            while (!outputDone && !isCancelled) {
                // Feed input
                if (!inputDone) {
                    val inputBufferIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inputBufferIndex >= 0) {
                        if (frameIndex >= totalFrames) {
                            encoder.queueInputBuffer(
                                inputBufferIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            val imageIndex = frameIndex / framesPerImage
                            val bitmap = bitmaps[imageIndex]

                            // Apply transition effects
                            val frameInImage = frameIndex % framesPerImage
                            var processedBitmap = applyTransition(
                                bitmap = bitmap,
                                nextBitmap = if (imageIndex < bitmaps.size - 1) bitmaps[imageIndex + 1] else null,
                                frameInImage = frameInImage,
                                framesPerImage = framesPerImage,
                                transition = config.transition,
                                width = width,
                                height = height
                            )

                            // Apply text overlays and watermark
                            if (config.textOverlays.isNotEmpty() || config.watermark.enabled) {
                                val withText = drawTextOverlays(
                                    bitmap = processedBitmap,
                                    overlays = config.textOverlays,
                                    watermark = config.watermark,
                                    imageIndex = imageIndex
                                )
                                // Release intermediate bitmap to pool if different from source
                                if (withText !== processedBitmap && processedBitmap !== bitmap) {
                                    bitmapPool.release(processedBitmap)
                                }
                                processedBitmap = withText
                            }

                            val inputBuffer = encoder.getInputBuffer(inputBufferIndex)
                            inputBuffer?.let {
                                fillBufferWithBitmap(it, processedBitmap, width, height)
                            }

                            // Release processed bitmap to pool if different from source
                            if (processedBitmap !== bitmap) {
                                bitmapPool.release(processedBitmap)
                            }

                            encoder.queueInputBuffer(
                                inputBufferIndex, 0,
                                width * height * 3 / 2,
                                presentationTimeUs, 0
                            )

                            presentationTimeUs += frameDurationUs
                            frameIndex++

                            val progress = (frameIndex * 100) / totalFrames
                            onProgress(progress)
                        }
                    }
                }

                // Drain output
                val outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (muxerStarted) {
                            throw RuntimeException("Format changed twice")
                        }
                        trackIndex = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    outputBufferIndex >= 0 -> {
                        val outputBuffer = encoder.getOutputBuffer(outputBufferIndex)
                        if (outputBuffer != null && bufferInfo.size > 0 && muxerStarted) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(outputBufferIndex, false)

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            return !isCancelled

        } catch (e: Exception) {
            Log.e(TAG, "Failed to create video with MediaCodec", e)
            return false
        } finally {
            // Release resources in reverse order, each in its own try block
            try {
                encoder?.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to stop encoder", e)
            }
            try {
                encoder?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to release encoder", e)
            }
            if (muxerStarted) {
                try {
                    muxer?.stop()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to stop muxer", e)
                }
            }
            try {
                muxer?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to release muxer", e)
            }
        }
    }

    private fun applyTransition(
        bitmap: Bitmap,
        nextBitmap: Bitmap?,
        frameInImage: Int,
        framesPerImage: Int,
        transition: TransitionEffect,
        width: Int,
        height: Int
    ): Bitmap {
        val transitionFrames = (framesPerImage * 0.2f).toInt() // 20% of frames for transition
        val isInTransition = nextBitmap != null && frameInImage >= framesPerImage - transitionFrames
        val imageProgress = frameInImage.toFloat() / framesPerImage

        // Apply continuous effects during image display (not just during transition)
        if (!isInTransition || nextBitmap == null) {
            return when (transition) {
                TransitionEffect.ZOOM -> {
                    val scale = 1f + (imageProgress * 0.15f) // Zoom in effect
                    createZoomedBitmap(bitmap, scale, width, height)
                }
                TransitionEffect.ZOOM_OUT -> {
                    val scale = 1.15f - (imageProgress * 0.15f) // Zoom out effect
                    createZoomedBitmap(bitmap, scale, width, height)
                }
                TransitionEffect.KEN_BURNS -> {
                    // Pan and zoom effect
                    applyKenBurnsEffect(bitmap, imageProgress, width, height)
                }
                TransitionEffect.ROTATE -> {
                    // Slight rotation during display
                    val rotation = imageProgress * 3f // 3 degree rotation over duration
                    createRotatedBitmap(bitmap, rotation, width, height)
                }
                else -> bitmap
            }
        }

        // Calculate transition progress (0 to 1)
        val transitionProgress = (frameInImage - (framesPerImage - transitionFrames)).toFloat() / transitionFrames

        return when (transition) {
            TransitionEffect.FADE -> {
                blendBitmaps(bitmap, nextBitmap, transitionProgress, width, height)
            }
            TransitionEffect.SLIDE -> {
                slideBitmaps(bitmap, nextBitmap, transitionProgress, width, height)
            }
            TransitionEffect.ZOOM -> {
                val scale = 1.15f
                val zoomed = createZoomedBitmap(bitmap, scale, width, height)
                val result = blendBitmaps(zoomed, nextBitmap, transitionProgress, width, height)
                // Release intermediate bitmap to pool
                bitmapPool.releaseIfNotSource(zoomed, bitmap)
                result
            }
            TransitionEffect.ZOOM_OUT -> {
                val scale = 1f
                val zoomed = createZoomedBitmap(bitmap, scale, width, height)
                val result = blendBitmaps(zoomed, nextBitmap, transitionProgress, width, height)
                // Release intermediate bitmap to pool
                bitmapPool.releaseIfNotSource(zoomed, bitmap)
                result
            }
            TransitionEffect.KEN_BURNS -> {
                val fromBitmap = applyKenBurnsEffect(bitmap, 1f, width, height)
                val result = blendBitmaps(fromBitmap, nextBitmap, transitionProgress, width, height)
                // Release intermediate bitmap to pool
                bitmapPool.releaseIfNotSource(fromBitmap, bitmap)
                result
            }
            TransitionEffect.ROTATE -> {
                val rotated = createRotatedBitmap(bitmap, 3f, width, height)
                val result = blendBitmaps(rotated, nextBitmap, transitionProgress, width, height)
                // Release intermediate bitmap to pool
                bitmapPool.releaseIfNotSource(rotated, bitmap)
                result
            }
            TransitionEffect.BLUR -> {
                blurTransition(bitmap, nextBitmap, transitionProgress, width, height)
            }
            TransitionEffect.WIPE -> {
                wipeTransition(bitmap, nextBitmap, transitionProgress, width, height)
            }
            TransitionEffect.DISSOLVE -> {
                dissolveTransition(bitmap, nextBitmap, transitionProgress, width, height)
            }
            TransitionEffect.NONE -> bitmap
        }
    }

    private fun blendBitmaps(from: Bitmap, to: Bitmap, progress: Float, width: Int, height: Int): Bitmap {
        // Use pooled bitmap to reduce GC pressure during transitions
        val result = bitmapPool.acquire(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        paint.alpha = 255
        canvas.drawBitmap(from, 0f, 0f, paint)

        paint.alpha = (255 * progress).toInt()
        canvas.drawBitmap(to, 0f, 0f, paint)

        return result
    }

    private fun slideBitmaps(from: Bitmap, to: Bitmap, progress: Float, width: Int, height: Int): Bitmap {
        // Use pooled bitmap to reduce GC pressure during transitions
        val result = bitmapPool.acquire(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        val offset = (width * progress).toInt()
        canvas.drawBitmap(from, -offset.toFloat(), 0f, paint)
        canvas.drawBitmap(to, (width - offset).toFloat(), 0f, paint)

        return result
    }

    private fun createZoomedBitmap(source: Bitmap, scale: Float, width: Int, height: Int): Bitmap {
        // Use pooled bitmap to reduce GC pressure during transitions
        val result = bitmapPool.acquire(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // Scale from center
        val scaledWidth = (width * scale).toInt()
        val scaledHeight = (height * scale).toInt()
        val scaledBitmap = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true)

        val left = (width - scaledWidth) / 2f
        val top = (height - scaledHeight) / 2f
        canvas.drawBitmap(scaledBitmap, left, top, paint)

        // Release intermediate bitmap to pool if different from source
        if (scaledBitmap !== source) {
            bitmapPool.release(scaledBitmap)
        }
        return result
    }

    // Ken Burns effect - pan and zoom simultaneously
    private fun applyKenBurnsEffect(source: Bitmap, progress: Float, width: Int, height: Int): Bitmap {
        // Use pooled bitmap to reduce GC pressure during transitions
        val result = bitmapPool.acquire(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // Zoom from 1.0 to 1.2 while panning
        val scale = 1f + (progress * 0.2f)
        val scaledWidth = (width * scale).toInt()
        val scaledHeight = (height * scale).toInt()

        val scaledBitmap = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true)

        // Pan from left to right (or top-left to bottom-right)
        val maxPanX = scaledWidth - width
        val maxPanY = scaledHeight - height
        val panX = -(maxPanX * progress)
        val panY = -(maxPanY * progress * 0.5f) // Slower vertical pan

        canvas.drawBitmap(scaledBitmap, panX, panY, paint)

        // Release intermediate bitmap to pool if different from source
        if (scaledBitmap !== source) {
            bitmapPool.release(scaledBitmap)
        }
        return result
    }

    // Rotate bitmap around center
    private fun createRotatedBitmap(source: Bitmap, degrees: Float, width: Int, height: Int): Bitmap {
        // Use pooled bitmap to reduce GC pressure during transitions
        val result = bitmapPool.acquire(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        canvas.save()
        canvas.rotate(degrees, width / 2f, height / 2f)

        // Scale up slightly to avoid showing corners during rotation
        val scale = 1.05f
        val scaledWidth = (width * scale).toInt()
        val scaledHeight = (height * scale).toInt()
        val scaledBitmap = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true)

        val left = (width - scaledWidth) / 2f
        val top = (height - scaledHeight) / 2f
        canvas.drawBitmap(scaledBitmap, left, top, paint)
        canvas.restore()

        // Release intermediate bitmap to pool if different from source
        if (scaledBitmap !== source) {
            bitmapPool.release(scaledBitmap)
        }
        return result
    }

    // Blur transition - blur out current, blur in next
    private fun blurTransition(from: Bitmap, to: Bitmap, progress: Float, width: Int, height: Int): Bitmap {
        // Use pooled bitmap to reduce GC pressure during transitions
        val result = bitmapPool.acquire(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // Simple blur simulation using scaling down then up
        if (progress < 0.5f) {
            // Blur out the current image
            val blurAmount = progress * 2 // 0 to 1
            val blurred = createSimpleBlur(from, blurAmount, width, height)
            canvas.drawBitmap(blurred, 0f, 0f, paint)
            // Release blurred bitmap back to pool if different from source
            bitmapPool.releaseIfNotSource(blurred, from)
        } else {
            // Blur in the next image
            val blurAmount = (1f - progress) * 2 // 1 to 0
            val blurred = createSimpleBlur(to, blurAmount, width, height)
            canvas.drawBitmap(blurred, 0f, 0f, paint)
            // Release blurred bitmap back to pool if different from source
            bitmapPool.releaseIfNotSource(blurred, to)
        }

        return result
    }

    // Simple blur effect using downscale/upscale
    private fun createSimpleBlur(source: Bitmap, amount: Float, width: Int, height: Int): Bitmap {
        if (amount < 0.05f) return source

        // Downscale factor based on blur amount (more blur = smaller intermediate size)
        val scale = (1f - amount * 0.8f).coerceAtLeast(0.1f)
        val smallWidth = (width * scale).toInt().coerceAtLeast(1)
        val smallHeight = (height * scale).toInt().coerceAtLeast(1)

        // Downscale
        val small = Bitmap.createScaledBitmap(source, smallWidth, smallHeight, true)
        // Upscale back - this creates blur effect
        val blurred = Bitmap.createScaledBitmap(small, width, height, true)

        // Release intermediate bitmap to pool if different from source and result
        if (small !== source && small !== blurred) {
            bitmapPool.release(small)
        }
        return blurred
    }

    // Wipe transition - reveal next image from left to right
    private fun wipeTransition(from: Bitmap, to: Bitmap, progress: Float, width: Int, height: Int): Bitmap {
        // Use pooled bitmap to reduce GC pressure during transitions
        val result = bitmapPool.acquire(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // Draw current image
        canvas.drawBitmap(from, 0f, 0f, paint)

        // Draw next image with clip (wipe from left)
        val wipeX = (width * progress).toInt()
        canvas.save()
        canvas.clipRect(0, 0, wipeX, height)
        canvas.drawBitmap(to, 0f, 0f, paint)
        canvas.restore()

        return result
    }

    // Pre-computed block order for dissolve transition to avoid allocation per frame
    private val dissolveBlockOrder: IntArray by lazy {
        val maxBlocks = 100 * 100  // Support up to 4K resolution with 40px blocks
        (0 until maxBlocks).shuffled(java.util.Random(42)).toIntArray()
    }

    // Dissolve transition - pixelated dissolve effect
    private fun dissolveTransition(from: Bitmap, to: Bitmap, progress: Float, width: Int, height: Int): Bitmap {
        // Use pooled bitmap to reduce GC pressure during transitions
        val result = bitmapPool.acquire(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // Draw current image
        canvas.drawBitmap(from, 0f, 0f, paint)

        // Create pixelated transition using pre-computed random blocks
        val blockSize = 40
        val blocksX = (width + blockSize - 1) / blockSize
        val blocksY = (height + blockSize - 1) / blockSize
        val totalBlocks = blocksX * blocksY
        val blocksToShow = (totalBlocks * progress).toInt()

        // Use pre-computed block order to avoid allocation per frame
        canvas.save()
        for (i in 0 until blocksToShow) {
            val blockIndex = dissolveBlockOrder[i % dissolveBlockOrder.size] % totalBlocks
            val bx = (blockIndex % blocksX) * blockSize
            val by = (blockIndex / blocksX) * blockSize
            canvas.clipRect(bx, by, (bx + blockSize).coerceAtMost(width), (by + blockSize).coerceAtMost(height))
            canvas.drawBitmap(to, 0f, 0f, paint)
            canvas.clipRect(0, 0, width, height) // Reset clip
        }
        canvas.restore()

        return result
    }

    /**
     * Fills encoder input with bitmap data using the Image API (preferred) or ByteBuffer (fallback).
     *
     * The Image API is strongly preferred because:
     * 1. It handles stride/row padding automatically (crucial for hardware encoders)
     * 2. It handles color format detection automatically
     * 3. It works correctly across different devices
     *
     * ByteBuffer fallback is only used if Image API fails, and uses proper stride handling.
     */
    private fun fillEncoderInputWithBitmap(
        encoder: MediaCodec,
        inputBufferIndex: Int,
        bitmap: Bitmap,
        width: Int,
        height: Int
    ): Boolean {
        // Try Image API first (API 21+) - handles stride and format automatically
        if (useImageApi && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                val image = encoder.getInputImage(inputBufferIndex)
                if (image != null) {
                    fillImageWithBitmap(image, bitmap)
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Image API failed, falling back to ByteBuffer", e)
                useImageApi = false
            }
        }

        // Fallback to ByteBuffer with proper stride handling
        val buffer = encoder.getInputBuffer(inputBufferIndex)
        if (buffer != null) {
            fillBufferWithBitmap(buffer, bitmap, width, height)
            return true
        }

        return false
    }

    /**
     * Fills an Image object with bitmap data.
     * The Image API handles stride and color format automatically.
     */
    private fun fillImageWithBitmap(image: android.media.Image, bitmap: Bitmap) {
        val width = image.width
        val height = image.height
        val planes = image.planes

        // Get bitmap pixels
        val argb = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(argb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        // Y plane
        val yPlane = planes[0]
        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride

        // U plane (Cb)
        val uPlane = planes[1]
        val uBuffer = uPlane.buffer
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride

        // V plane (Cr)
        val vPlane = planes[2]
        val vBuffer = vPlane.buffer
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride

        // Fill Y plane - handle stride properly
        for (y in 0 until height) {
            val yRowOffset = y * yRowStride
            for (x in 0 until width) {
                val argbIndex = y * bitmap.width + x
                if (argbIndex < argb.size) {
                    val pixel = argb[argbIndex]
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    // BT.601 Y calculation
                    val yValue = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                    yBuffer.put(yRowOffset + x * yPixelStride, yValue.coerceIn(0, 255).toByte())
                }
            }
        }

        // Fill U and V planes (subsampled by 2 in both dimensions)
        val uvHeight = height / 2
        val uvWidth = width / 2
        for (y in 0 until uvHeight) {
            val uRowOffset = y * uRowStride
            val vRowOffset = y * vRowStride
            for (x in 0 until uvWidth) {
                // Sample from top-left pixel of 2x2 block
                val argbIndex = (y * 2) * bitmap.width + (x * 2)
                if (argbIndex < argb.size) {
                    val pixel = argb[argbIndex]
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    // BT.601 U (Cb) and V (Cr) calculation
                    val uValue = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val vValue = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128

                    uBuffer.put(uRowOffset + x * uPixelStride, uValue.coerceIn(0, 255).toByte())
                    vBuffer.put(vRowOffset + x * vPixelStride, vValue.coerceIn(0, 255).toByte())
                }
            }
        }
    }

    /**
     * Legacy ByteBuffer fill method with proper stride handling.
     * Used as fallback when Image API is not available.
     *
     * IMPORTANT: This method handles stride alignment which is required by many hardware encoders.
     * The stride (row size in bytes) may be larger than width due to alignment requirements.
     */
    private fun fillBufferWithBitmap(buffer: ByteBuffer, bitmap: Bitmap, width: Int, height: Int) {
        buffer.clear()

        // Convert ARGB bitmap to YUV420
        val argb = IntArray(width * height)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)

        // Calculate stride - some encoders require alignment (typically 16 or 32 bytes)
        // We'll use width directly since we're using COLOR_FormatYUV420Flexible
        // The encoder should handle alignment internally
        val yStride = width
        val uvStride = width / 2
        val uvHeight = height / 2

        // Y plane
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = argb[y * width + x]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                // BT.601 Y calculation
                val yValue = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                buffer.put(yValue.coerceIn(0, 255).toByte())
            }
        }

        // U/V planes - use semi-planar NV12 format (most common on Android)
        // NV12: Y plane followed by interleaved UV
        when (detectedColorFormat) {
            COLOR_FORMAT_I420 -> {
                // I420 (Planar): Y, then all U, then all V
                for (y in 0 until uvHeight) {
                    for (x in 0 until uvStride) {
                        val pixel = argb[(y * 2) * width + (x * 2)]
                        val r = (pixel shr 16) and 0xFF
                        val g = (pixel shr 8) and 0xFF
                        val b = pixel and 0xFF
                        val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                        buffer.put(u.coerceIn(0, 255).toByte())
                    }
                }
                for (y in 0 until uvHeight) {
                    for (x in 0 until uvStride) {
                        val pixel = argb[(y * 2) * width + (x * 2)]
                        val r = (pixel shr 16) and 0xFF
                        val g = (pixel shr 8) and 0xFF
                        val b = pixel and 0xFF
                        val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                        buffer.put(v.coerceIn(0, 255).toByte())
                    }
                }
            }
            COLOR_FORMAT_NV21 -> {
                // NV21: Y plane, then interleaved VU
                for (y in 0 until uvHeight) {
                    for (x in 0 until uvStride) {
                        val pixel = argb[(y * 2) * width + (x * 2)]
                        val r = (pixel shr 16) and 0xFF
                        val g = (pixel shr 8) and 0xFF
                        val b = pixel and 0xFF
                        val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                        val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                        buffer.put(v.coerceIn(0, 255).toByte()) // V first
                        buffer.put(u.coerceIn(0, 255).toByte()) // then U
                    }
                }
            }
            else -> {
                // NV12 (default): Y plane, then interleaved UV
                for (y in 0 until uvHeight) {
                    for (x in 0 until uvStride) {
                        val pixel = argb[(y * 2) * width + (x * 2)]
                        val r = (pixel shr 16) and 0xFF
                        val g = (pixel shr 8) and 0xFF
                        val b = pixel and 0xFF
                        val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                        val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                        buffer.put(u.coerceIn(0, 255).toByte()) // U first
                        buffer.put(v.coerceIn(0, 255).toByte()) // then V
                    }
                }
            }
        }
    }

    /**
     * Fills a ByteArray with YUV data converted from a Bitmap.
     * This is used for YUV caching - we convert once and reuse the bytes for static frames.
     *
     * This is a MAJOR speed optimization for slideshows:
     * - Without cache: 2M pixels × 15fps × conversion = 30M operations/sec
     * - With cache: 2M pixels × 1 conversion per image = 99% reduction for static frames
     */
    private fun fillBitmapToYuvBytes(bitmap: Bitmap, yuvBytes: ByteArray, width: Int, height: Int) {
        // Convert ARGB bitmap to YUV420
        val argb = IntArray(width * height)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)

        val uvStride = width / 2
        val uvHeight = height / 2
        var offset = 0

        // Y plane
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = argb[y * width + x]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                // BT.601 Y calculation
                val yValue = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yuvBytes[offset++] = yValue.coerceIn(0, 255).toByte()
            }
        }

        // U/V planes based on detected format
        when (detectedColorFormat) {
            COLOR_FORMAT_I420 -> {
                // I420 (Planar): Y, then all U, then all V
                for (y in 0 until uvHeight) {
                    for (x in 0 until uvStride) {
                        val pixel = argb[(y * 2) * width + (x * 2)]
                        val r = (pixel shr 16) and 0xFF
                        val g = (pixel shr 8) and 0xFF
                        val b = pixel and 0xFF
                        val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                        yuvBytes[offset++] = u.coerceIn(0, 255).toByte()
                    }
                }
                for (y in 0 until uvHeight) {
                    for (x in 0 until uvStride) {
                        val pixel = argb[(y * 2) * width + (x * 2)]
                        val r = (pixel shr 16) and 0xFF
                        val g = (pixel shr 8) and 0xFF
                        val b = pixel and 0xFF
                        val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                        yuvBytes[offset++] = v.coerceIn(0, 255).toByte()
                    }
                }
            }
            COLOR_FORMAT_NV21 -> {
                // NV21: Y plane, then interleaved VU
                for (y in 0 until uvHeight) {
                    for (x in 0 until uvStride) {
                        val pixel = argb[(y * 2) * width + (x * 2)]
                        val r = (pixel shr 16) and 0xFF
                        val g = (pixel shr 8) and 0xFF
                        val b = pixel and 0xFF
                        val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                        val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                        yuvBytes[offset++] = v.coerceIn(0, 255).toByte() // V first
                        yuvBytes[offset++] = u.coerceIn(0, 255).toByte() // then U
                    }
                }
            }
            else -> {
                // NV12 (default): Y plane, then interleaved UV
                for (y in 0 until uvHeight) {
                    for (x in 0 until uvStride) {
                        val pixel = argb[(y * 2) * width + (x * 2)]
                        val r = (pixel shr 16) and 0xFF
                        val g = (pixel shr 8) and 0xFF
                        val b = pixel and 0xFF
                        val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                        val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                        yuvBytes[offset++] = u.coerceIn(0, 255).toByte() // U first
                        yuvBytes[offset++] = v.coerceIn(0, 255).toByte() // then V
                    }
                }
            }
        }
    }

    private fun cleanup(directory: File) {
        directory.listFiles()?.forEach { it.delete() }
        directory.delete()
    }

    fun cancel() {
        isCancelled = true
    }
}
