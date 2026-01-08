package com.aiezzy.slideshowmaker.util

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Simple beat detector that analyzes audio energy to find beat timestamps.
 * Uses energy-based detection for identifying rhythmic patterns.
 */
object BeatDetector {

    data class BeatInfo(
        val timestamps: List<Long>, // Beat timestamps in milliseconds
        val averageBpm: Float,
        val totalDuration: Long
    )

    /**
     * Analyze audio file and detect beats.
     * Returns list of beat timestamps in milliseconds.
     */
    suspend fun detectBeats(
        context: Context,
        audioUri: Uri,
        sensitivity: Float = 1.0f // Higher = more beats detected
    ): BeatInfo = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        val beats = mutableListOf<Long>()
        var totalDuration = 0L

        try {
            extractor.setDataSource(context, audioUri, null)

            // Find audio track
            var audioTrackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    totalDuration = format.getLong(MediaFormat.KEY_DURATION) / 1000 // Convert to ms
                    break
                }
            }

            if (audioTrackIndex == -1) {
                return@withContext BeatInfo(emptyList(), 0f, 0L)
            }

            extractor.selectTrack(audioTrackIndex)
            val format = extractor.getTrackFormat(audioTrackIndex)
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 2)

            // Decode audio to PCM
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return@withContext BeatInfo(emptyList(), 0f, totalDuration)
            val decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()

            val energySamples = mutableListOf<Pair<Long, Float>>() // timestamp to energy
            val windowSizeMs = 50 // 50ms windows for energy calculation
            val samplesPerWindow = (sampleRate * windowSizeMs / 1000) * channelCount

            var currentWindowSamples = mutableListOf<Short>()
            var currentWindowStartTime = 0L
            var inputDone = false
            var outputDone = false

            val bufferInfo = MediaCodec.BufferInfo()

            while (!outputDone) {
                // Feed input
                if (!inputDone) {
                    val inputBufferIndex = decoder.dequeueInputBuffer(10000)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputBufferIndex)
                        if (inputBuffer != null) {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                val presentationTimeUs = extractor.sampleTime
                                decoder.queueInputBuffer(inputBufferIndex, 0, sampleSize, presentationTimeUs, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                // Get output
                val outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000)
                if (outputBufferIndex >= 0) {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }

                    val outputBuffer = decoder.getOutputBuffer(outputBufferIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        val timeMs = bufferInfo.presentationTimeUs / 1000

                        // Read samples as shorts (16-bit PCM)
                        val samples = ShortArray(bufferInfo.size / 2)
                        outputBuffer.asShortBuffer().get(samples)

                        for (sample in samples) {
                            currentWindowSamples.add(sample)

                            if (currentWindowSamples.size >= samplesPerWindow) {
                                // Calculate RMS energy for this window
                                val energy = calculateRmsEnergy(currentWindowSamples)
                                energySamples.add(Pair(currentWindowStartTime, energy))

                                currentWindowSamples = mutableListOf()
                                currentWindowStartTime = timeMs
                            }
                        }
                    }

                    decoder.releaseOutputBuffer(outputBufferIndex, false)
                }
            }

            decoder.stop()
            decoder.release()

            // Find beats using energy threshold
            if (energySamples.isNotEmpty()) {
                val averageEnergy = energySamples.map { it.second }.average().toFloat()
                val threshold = averageEnergy * (1.5f / sensitivity)

                var lastBeatTime = -500L // Minimum 500ms between beats
                for ((time, energy) in energySamples) {
                    if (energy > threshold && time - lastBeatTime > 300) {
                        beats.add(time)
                        lastBeatTime = time
                    }
                }
            }

            // Calculate average BPM
            val avgBpm = if (beats.size > 1) {
                val intervals = beats.zipWithNext { a, b -> b - a }
                val avgIntervalMs = intervals.average()
                (60000.0 / avgIntervalMs).toFloat()
            } else {
                120f // Default BPM
            }

            BeatInfo(beats, avgBpm, totalDuration)

        } catch (e: Exception) {
            e.printStackTrace()
            // Return evenly spaced beats as fallback
            val fallbackBeats = generateEvenBeats(totalDuration, 120f)
            BeatInfo(fallbackBeats, 120f, totalDuration)
        } finally {
            extractor.release()
        }
    }

    /**
     * Generate evenly spaced beat timestamps at a given BPM.
     */
    fun generateEvenBeats(durationMs: Long, bpm: Float): List<Long> {
        if (durationMs <= 0 || bpm <= 0) return emptyList()

        val intervalMs = (60000f / bpm).toLong()
        val beats = mutableListOf<Long>()
        var time = 0L

        while (time < durationMs) {
            beats.add(time)
            time += intervalMs
        }

        return beats
    }

    /**
     * Calculate durations for each image based on beat timestamps.
     * Returns list of durations in seconds.
     */
    fun calculateBeatSyncedDurations(
        beatInfo: BeatInfo,
        imageCount: Int,
        minDuration: Float = 0.5f,
        maxDuration: Float = 5f
    ): List<Float> {
        if (beatInfo.timestamps.isEmpty() || imageCount <= 0) {
            return List(imageCount) { 2f } // Default 2 seconds
        }

        val durations = mutableListOf<Float>()
        val totalDuration = beatInfo.totalDuration

        // Distribute images across beats
        val beatsPerImage = (beatInfo.timestamps.size.toFloat() / imageCount).coerceAtLeast(1f)

        var currentBeatIndex = 0f
        for (i in 0 until imageCount) {
            val startBeatIndex = currentBeatIndex.toInt().coerceIn(0, beatInfo.timestamps.size - 1)
            currentBeatIndex += beatsPerImage
            val endBeatIndex = currentBeatIndex.toInt().coerceIn(0, beatInfo.timestamps.size - 1)

            val startTime = beatInfo.timestamps[startBeatIndex]
            val endTime = if (endBeatIndex < beatInfo.timestamps.size - 1) {
                beatInfo.timestamps[endBeatIndex]
            } else {
                totalDuration
            }

            val duration = ((endTime - startTime) / 1000f).coerceIn(minDuration, maxDuration)
            durations.add(duration)
        }

        return durations
    }

    private fun calculateRmsEnergy(samples: List<Short>): Float {
        if (samples.isEmpty()) return 0f
        val sumSquares = samples.sumOf { (it.toFloat() * it.toFloat()).toDouble() }
        return sqrt(sumSquares / samples.size).toFloat()
    }

    private fun MediaFormat.getIntegerOrDefault(key: String, default: Int): Int {
        return try {
            getInteger(key)
        } catch (e: Exception) {
            default
        }
    }
}
