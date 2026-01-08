package com.aiezzy.slideshowmaker.data.models

import android.net.Uri
import androidx.annotation.DrawableRes

data class ImageItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val uri: Uri,
    val order: Int = 0
)

enum class TransitionEffect(val displayName: String, val description: String) {
    NONE("None", "No transition"),
    FADE("Fade", "Smooth fade between images"),
    SLIDE("Slide", "Slide left to right"),
    ZOOM("Zoom", "Gentle zoom effect"),
    ZOOM_OUT("Zoom Out", "Pull back zoom"),
    KEN_BURNS("Ken Burns", "Pan and zoom documentary style"),
    ROTATE("Rotate", "Spin transition"),
    BLUR("Blur", "Blur fade transition"),
    WIPE("Wipe", "Wipe across screen"),
    DISSOLVE("Dissolve", "Pixelated dissolve")
}

// Platform presets for one-tap export
enum class PlatformPreset(
    val displayName: String,
    val resolution: VideoResolution,
    val maxDurationSeconds: Int,
    val recommendedDuration: Float,
    val aspectRatio: String
) {
    TIKTOK("TikTok", VideoResolution.STORIES_FHD, 60, 2f, "9:16"),
    INSTAGRAM_REELS("Instagram Reels", VideoResolution.STORIES_FHD, 90, 2f, "9:16"),
    YOUTUBE_SHORTS("YouTube Shorts", VideoResolution.STORIES_FHD, 60, 2.5f, "9:16"),
    INSTAGRAM_POST("Instagram Post", VideoResolution.SQUARE_HD, 60, 3f, "1:1"),
    YOUTUBE("YouTube", VideoResolution.YOUTUBE_FHD, 600, 3f, "16:9"),
    WHATSAPP_STATUS("WhatsApp Status", VideoResolution.STORIES_HD, 30, 2f, "9:16")
}

// Video template for one-tap creation
data class VideoTemplate(
    val id: String,
    val name: String,
    val description: String,
    val category: TemplateCategory,
    val transition: TransitionEffect,
    val durationPerImage: Float,
    val resolution: VideoResolution,
    val musicMood: MusicMood? = null,
    val iconEmoji: String = "🎬"
)

enum class TemplateCategory(val displayName: String) {
    MEMORIES("Memories"),
    TRAVEL("Travel"),
    CELEBRATION("Celebration"),
    SOCIAL("Social Media"),
    PROFESSIONAL("Professional")
}

enum class MusicMood(val displayName: String) {
    HAPPY("Happy & Upbeat"),
    CALM("Calm & Relaxing"),
    ENERGETIC("Energetic"),
    EMOTIONAL("Emotional"),
    CINEMATIC("Cinematic")
}

enum class VideoResolution(
    val displayName: String,
    val width: Int,
    val height: Int
) {
    STORIES_HD("Stories/Reels HD", 720, 1280),
    STORIES_FHD("Stories/Reels Full HD", 1080, 1920),
    SQUARE_HD("Square HD", 1080, 1080),
    YOUTUBE_HD("YouTube HD", 1280, 720),
    YOUTUBE_FHD("YouTube Full HD", 1920, 1080)
}

data class AudioConfig(
    val uri: Uri? = null,
    val fileName: String = "",
    val startTimeSeconds: Int = 0,
    val endTimeSeconds: Int? = null,
    val useFullAudio: Boolean = true,
    val beatSyncEnabled: Boolean = false // Sync transitions to music beats
)

// Auto-caption configuration
data class AutoCaptionConfig(
    val enabled: Boolean = false,
    val style: AutoCaptionStyle = AutoCaptionStyle.FULL,
    val position: TextPosition = TextPosition.BOTTOM_CENTER
)

enum class AutoCaptionStyle(val displayName: String) {
    FULL("Location + Date"),
    DATE_ONLY("Date Only"),
    LOCATION_ONLY("Location Only"),
    MINIMAL("Minimal (Month Year)")
}

// Text overlay configuration
data class TextOverlay(
    val id: String = java.util.UUID.randomUUID().toString(),
    val text: String,
    val position: TextPosition = TextPosition.BOTTOM_CENTER,
    val style: TextStyle = TextStyle.DEFAULT,
    val animation: TextAnimation = TextAnimation.FADE_IN,
    val showOnAllImages: Boolean = true,
    val imageIndex: Int? = null // If not showOnAllImages, which image to show on
)

enum class TextPosition(val displayName: String) {
    TOP_LEFT("Top Left"),
    TOP_CENTER("Top Center"),
    TOP_RIGHT("Top Right"),
    CENTER("Center"),
    BOTTOM_LEFT("Bottom Left"),
    BOTTOM_CENTER("Bottom Center"),
    BOTTOM_RIGHT("Bottom Right")
}

enum class TextStyle(
    val displayName: String,
    val textSizeRatio: Float, // Relative to video height
    val shadowEnabled: Boolean,
    val backgroundColor: Boolean
) {
    DEFAULT("Default", 0.05f, true, false),
    TITLE("Title", 0.08f, true, false),
    CAPTION("Caption", 0.04f, true, true),
    BOLD("Bold", 0.06f, true, false),
    MINIMAL("Minimal", 0.035f, false, false)
}

// Text animation presets
enum class TextAnimation(val displayName: String, val durationMs: Int) {
    NONE("None", 0),
    FADE_IN("Fade In", 500),
    FADE_OUT("Fade Out", 500),
    FADE_IN_OUT("Fade In/Out", 500),
    TYPEWRITER("Typewriter", 1000),
    SLIDE_UP("Slide Up", 400),
    SLIDE_DOWN("Slide Down", 400),
    BOUNCE("Bounce", 600),
    SCALE_UP("Scale Up", 400),
    BLUR_IN("Blur In", 500)
}

// Watermark configuration
data class WatermarkConfig(
    val enabled: Boolean = false,
    val text: String = "Aiezzy",
    val position: TextPosition = TextPosition.BOTTOM_RIGHT,
    val opacity: Float = 0.7f
)

data class VideoConfig(
    val images: List<ImageItem> = emptyList(),
    val durationPerImage: Float = 2f,
    val imageDurations: List<Float>? = null, // Individual durations per image (for beat sync)
    val transition: TransitionEffect = TransitionEffect.FADE,
    val resolution: VideoResolution = VideoResolution.YOUTUBE_HD,
    val audio: AudioConfig? = null,
    val outputPath: String = "",
    val templateId: String? = null,
    val platformPreset: PlatformPreset? = null,
    val textOverlays: List<TextOverlay> = emptyList(),
    val watermark: WatermarkConfig = WatermarkConfig(),
    val autoCaption: AutoCaptionConfig = AutoCaptionConfig()
) {
    // Get duration for a specific image index
    fun getDurationForImage(index: Int): Float {
        return imageDurations?.getOrNull(index) ?: durationPerImage
    }
    companion object {
        fun fromTemplate(template: VideoTemplate, images: List<ImageItem>): VideoConfig {
            return VideoConfig(
                images = images,
                durationPerImage = template.durationPerImage,
                transition = template.transition,
                resolution = template.resolution,
                templateId = template.id
            )
        }

        fun fromPlatformPreset(preset: PlatformPreset, images: List<ImageItem>): VideoConfig {
            return VideoConfig(
                images = images,
                durationPerImage = preset.recommendedDuration,
                transition = TransitionEffect.FADE,
                resolution = preset.resolution,
                platformPreset = preset
            )
        }
    }
}

sealed class ProcessingState {
    object Idle : ProcessingState()
    data class Processing(val progress: Int, val message: String) : ProcessingState()
    data class Success(val outputPath: String) : ProcessingState()
    data class Error(val message: String) : ProcessingState()
}
