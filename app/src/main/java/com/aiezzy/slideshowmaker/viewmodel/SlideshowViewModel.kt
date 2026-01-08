package com.aiezzy.slideshowmaker.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiezzy.slideshowmaker.SlideshowApp
import com.aiezzy.slideshowmaker.data.models.*
import com.aiezzy.slideshowmaker.data.templates.Templates
import com.aiezzy.slideshowmaker.util.BeatDetector
import com.aiezzy.slideshowmaker.util.ExifHelper
import com.aiezzy.slideshowmaker.util.StoryModeHelper
import com.aiezzy.slideshowmaker.util.VideoProcessor
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "SlideshowViewModel"

class SlideshowViewModel : ViewModel() {

    private val _images = MutableStateFlow<List<ImageItem>>(emptyList())
    val images: StateFlow<List<ImageItem>> = _images.asStateFlow()

    private val _durationPerImage = MutableStateFlow(2f)
    val durationPerImage: StateFlow<Float> = _durationPerImage.asStateFlow()

    private val _transition = MutableStateFlow(TransitionEffect.NONE)
    val transition: StateFlow<TransitionEffect> = _transition.asStateFlow()

    private val _resolution = MutableStateFlow(VideoResolution.STORIES_FHD)
    val resolution: StateFlow<VideoResolution> = _resolution.asStateFlow()

    private val _audioConfig = MutableStateFlow<AudioConfig?>(null)
    val audioConfig: StateFlow<AudioConfig?> = _audioConfig.asStateFlow()

    private val _selectedTemplate = MutableStateFlow<VideoTemplate?>(null)
    val selectedTemplate: StateFlow<VideoTemplate?> = _selectedTemplate.asStateFlow()

    private val _selectedPlatform = MutableStateFlow<PlatformPreset?>(null)
    val selectedPlatform: StateFlow<PlatformPreset?> = _selectedPlatform.asStateFlow()

    private val _textOverlays = MutableStateFlow<List<TextOverlay>>(emptyList())
    val textOverlays: StateFlow<List<TextOverlay>> = _textOverlays.asStateFlow()

    private val _watermarkEnabled = MutableStateFlow(true)
    val watermarkEnabled: StateFlow<Boolean> = _watermarkEnabled.asStateFlow()

    private val _beatSyncEnabled = MutableStateFlow(false)
    val beatSyncEnabled: StateFlow<Boolean> = _beatSyncEnabled.asStateFlow()

    private val _autoCaptionConfig = MutableStateFlow(AutoCaptionConfig())
    val autoCaptionConfig: StateFlow<AutoCaptionConfig> = _autoCaptionConfig.asStateFlow()

    private val _beatSyncedDurations = MutableStateFlow<List<Float>?>(null)
    val beatSyncedDurations: StateFlow<List<Float>?> = _beatSyncedDurations.asStateFlow()

    private val _storyModeConfig = MutableStateFlow<StoryModeHelper.StoryConfig?>(null)
    val storyModeConfig: StateFlow<StoryModeHelper.StoryConfig?> = _storyModeConfig.asStateFlow()

    private val _isAnalyzingStory = MutableStateFlow(false)
    val isAnalyzingStory: StateFlow<Boolean> = _isAnalyzingStory.asStateFlow()

    private val _processingState = MutableStateFlow<ProcessingState>(ProcessingState.Idle)
    val processingState: StateFlow<ProcessingState> = _processingState.asStateFlow()

    private val context: Context get() = SlideshowApp.instance

    private val videoProcessor = VideoProcessor(context)

    // Mutex to prevent concurrent video generation
    private val videoGenerationMutex = Mutex()
    private var currentVideoJob: Job? = null

    fun addImages(uris: List<Uri>) {
        val currentImages = _images.value.toMutableList()
        val startOrder = currentImages.size
        val newImages = uris.mapIndexed { index, uri ->
            ImageItem(uri = uri, order = startOrder + index)
        }
        _images.value = currentImages + newImages
    }

    fun removeImage(imageId: String) {
        _images.value = _images.value.filter { it.id != imageId }
            .mapIndexed { index, item -> item.copy(order = index) }
    }

    fun reorderImages(fromIndex: Int, toIndex: Int) {
        val list = _images.value.toMutableList()
        if (fromIndex in list.indices && toIndex in list.indices) {
            val item = list.removeAt(fromIndex)
            list.add(toIndex, item)
            _images.value = list.mapIndexed { index, imageItem ->
                imageItem.copy(order = index)
            }
        }
    }

    fun setDurationPerImage(duration: Float) {
        _durationPerImage.value = duration.coerceIn(0.5f, 10f)
    }

    fun setTransition(effect: TransitionEffect) {
        _transition.value = effect
    }

    fun setResolution(resolution: VideoResolution) {
        _resolution.value = resolution
    }

    fun setAudio(uri: Uri?, fileName: String = "") {
        _audioConfig.value = if (uri != null) {
            AudioConfig(uri = uri, fileName = fileName)
        } else {
            null
        }
    }

    fun setAudioFromFile(filePath: String, fileName: String = "") {
        val uri = Uri.fromFile(java.io.File(filePath))
        _audioConfig.value = AudioConfig(uri = uri, fileName = fileName)
    }

    fun updateAudioTrim(startTime: Int, endTime: Int?) {
        _audioConfig.value = _audioConfig.value?.copy(
            startTimeSeconds = startTime,
            endTimeSeconds = endTime,
            useFullAudio = endTime == null
        )
    }

    fun setUseFullAudio(useFull: Boolean) {
        _audioConfig.value = _audioConfig.value?.copy(useFullAudio = useFull)
    }

    fun clearAudio() {
        _audioConfig.value = null
    }

    // Text overlay management
    fun addTextOverlay(
        text: String,
        position: TextPosition = TextPosition.BOTTOM_CENTER,
        style: TextStyle = TextStyle.DEFAULT,
        animation: TextAnimation = TextAnimation.FADE_IN
    ) {
        val overlay = TextOverlay(text = text, position = position, style = style, animation = animation)
        _textOverlays.value = _textOverlays.value + overlay
    }

    fun removeTextOverlay(id: String) {
        _textOverlays.value = _textOverlays.value.filter { it.id != id }
    }

    fun clearTextOverlays() {
        _textOverlays.value = emptyList()
    }

    fun setWatermarkEnabled(enabled: Boolean) {
        _watermarkEnabled.value = enabled
    }

    // Beat sync methods
    fun setBeatSyncEnabled(enabled: Boolean) {
        _beatSyncEnabled.value = enabled
        if (enabled && _audioConfig.value?.uri != null) {
            analyzeBeatSync()
        } else if (!enabled) {
            _beatSyncedDurations.value = null
        }
    }

    private fun analyzeBeatSync() {
        val audioUri = _audioConfig.value?.uri ?: return
        val imageCount = _images.value.size
        if (imageCount == 0) return

        viewModelScope.launch {
            try {
                val beatInfo = BeatDetector.detectBeats(context, audioUri)
                val durations = BeatDetector.calculateBeatSyncedDurations(
                    beatInfo = beatInfo,
                    imageCount = imageCount,
                    minDuration = 0.5f,
                    maxDuration = 5f
                )
                _beatSyncedDurations.value = durations
            } catch (e: Exception) {
                Log.e(TAG, "Failed to analyze beat sync", e)
                _beatSyncedDurations.value = null
            }
        }
    }

    // Auto-caption methods
    fun setAutoCaptionEnabled(enabled: Boolean) {
        _autoCaptionConfig.value = _autoCaptionConfig.value.copy(enabled = enabled)
    }

    fun setAutoCaptionStyle(style: AutoCaptionStyle) {
        _autoCaptionConfig.value = _autoCaptionConfig.value.copy(style = style)
    }

    fun setAutoCaptionPosition(position: TextPosition) {
        _autoCaptionConfig.value = _autoCaptionConfig.value.copy(position = position)
    }

    // Generate auto-captions from EXIF metadata
    suspend fun generateAutoCaptions(): List<TextOverlay> {
        val config = _autoCaptionConfig.value
        if (!config.enabled) return emptyList()

        val imageUris = _images.value.map { it.uri }
        val exifStyle = when (config.style) {
            AutoCaptionStyle.FULL -> ExifHelper.CaptionStyle.FULL
            AutoCaptionStyle.DATE_ONLY -> ExifHelper.CaptionStyle.DATE_ONLY
            AutoCaptionStyle.LOCATION_ONLY -> ExifHelper.CaptionStyle.LOCATION_ONLY
            AutoCaptionStyle.MINIMAL -> ExifHelper.CaptionStyle.MINIMAL
        }

        val captions = ExifHelper.generateSmartCaptions(context, imageUris, exifStyle)

        return captions.mapIndexedNotNull { index, caption ->
            if (caption.isNotBlank()) {
                TextOverlay(
                    text = caption,
                    position = config.position,
                    style = TextStyle.CAPTION,
                    showOnAllImages = false,
                    imageIndex = index
                )
            } else null
        }
    }

    // One-Tap Story Mode
    fun analyzeForStoryMode() {
        if (_images.value.isEmpty()) return

        viewModelScope.launch {
            _isAnalyzingStory.value = true
            try {
                val config = StoryModeHelper.analyzeAndConfigure(context, _images.value)
                _storyModeConfig.value = config
            } catch (e: Exception) {
                Log.e(TAG, "Failed to analyze for story mode", e)
                _storyModeConfig.value = null
            } finally {
                _isAnalyzingStory.value = false
            }
        }
    }

    fun applyStoryModeConfig() {
        val config = _storyModeConfig.value ?: return

        // Apply the auto-configured settings
        _images.value = config.orderedImages
        applyTemplate(config.template)
        _durationPerImage.value = config.estimatedDuration
        _textOverlays.value = config.suggestedCaptions

        // Note: Music would need to be downloaded separately
        // The UI should handle showing the recommended music
    }

    fun generateVideoWithStoryMode() {
        val config = _storyModeConfig.value
        if (config == null) {
            generateVideo()
            return
        }

        // Apply story mode config first
        applyStoryModeConfig()

        // Then generate
        generateVideo()
    }

    // Apply a template and auto-configure all settings
    fun applyTemplate(template: VideoTemplate) {
        _selectedTemplate.value = template
        _selectedPlatform.value = null
        _durationPerImage.value = template.durationPerImage
        _transition.value = template.transition
        _resolution.value = template.resolution
        // Auto-calculate duration based on image count if needed
        val imageCount = _images.value.size
        if (imageCount > 0) {
            val smartDuration = Templates.calculateSmartDuration(imageCount)
            _durationPerImage.value = smartDuration.coerceAtMost(template.durationPerImage)
        }
    }

    // Apply a platform preset for quick export
    fun applyPlatformPreset(preset: PlatformPreset) {
        _selectedPlatform.value = preset
        _selectedTemplate.value = null
        _resolution.value = preset.resolution
        _durationPerImage.value = preset.recommendedDuration
        _transition.value = TransitionEffect.FADE
        // Smart duration calculation based on platform limits
        val imageCount = _images.value.size
        if (imageCount > 0) {
            val smartDuration = Templates.calculateSmartDuration(imageCount, preset)
            _durationPerImage.value = smartDuration
        }
    }

    fun generateVideo() {
        if (_images.value.isEmpty()) {
            _processingState.value = ProcessingState.Error("No images selected")
            return
        }

        // Prevent multiple concurrent video generations
        if (videoGenerationMutex.isLocked) {
            Log.w(TAG, "Video generation already in progress, ignoring request")
            return
        }

        currentVideoJob = viewModelScope.launch {
            videoGenerationMutex.withLock {
                _processingState.value = ProcessingState.Processing(0, "Preparing...")

                try {
                    // Generate auto-captions if enabled
                    val autoCaptions = if (_autoCaptionConfig.value.enabled) {
                        _processingState.value = ProcessingState.Processing(2, "Generating captions from photo metadata...")
                        generateAutoCaptions()
                    } else emptyList()

                    // Combine manual text overlays with auto-captions
                    val allTextOverlays = _textOverlays.value + autoCaptions

                    // Use beat-synced durations if available
                    val durations = if (_beatSyncEnabled.value) _beatSyncedDurations.value else null

                    val config = VideoConfig(
                        images = _images.value.sortedBy { it.order },
                        durationPerImage = _durationPerImage.value,
                        imageDurations = durations,
                        transition = _transition.value,
                        resolution = _resolution.value,
                        audio = _audioConfig.value?.copy(beatSyncEnabled = _beatSyncEnabled.value),
                        textOverlays = allTextOverlays,
                        watermark = WatermarkConfig(enabled = _watermarkEnabled.value),
                        autoCaption = _autoCaptionConfig.value
                    )

                    videoProcessor.generateVideo(
                        config = config,
                        onProgress = { progress, message ->
                            _processingState.value = ProcessingState.Processing(progress, message)
                        },
                        onComplete = { outputPath ->
                            _processingState.value = ProcessingState.Success(outputPath)
                        },
                        onError = { error ->
                            _processingState.value = ProcessingState.Error(error)
                        }
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Video generation failed", e)
                    _processingState.value = ProcessingState.Error(e.message ?: "Unknown error")
                }
            }
        }
    }

    fun cancelProcessing() {
        currentVideoJob?.cancel()
        currentVideoJob = null
        videoProcessor.cancel()
        _processingState.value = ProcessingState.Idle
    }

    fun reset() {
        _images.value = emptyList()
        _durationPerImage.value = 2f
        _transition.value = TransitionEffect.NONE
        _resolution.value = VideoResolution.STORIES_FHD
        _audioConfig.value = null
        _selectedTemplate.value = null
        _selectedPlatform.value = null
        _textOverlays.value = emptyList()
        _watermarkEnabled.value = true
        _beatSyncEnabled.value = false
        _beatSyncedDurations.value = null
        _autoCaptionConfig.value = AutoCaptionConfig()
        _storyModeConfig.value = null
        _isAnalyzingStory.value = false
        _processingState.value = ProcessingState.Idle
    }

    fun resetProcessingState() {
        _processingState.value = ProcessingState.Idle
    }
}
