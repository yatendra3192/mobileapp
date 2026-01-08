package com.aiezzy.slideshowmaker.util

import android.content.Context
import android.net.Uri
import com.aiezzy.slideshowmaker.data.models.*
import com.aiezzy.slideshowmaker.data.music.MusicLibrary
import com.aiezzy.slideshowmaker.data.music.MusicTrack
import com.aiezzy.slideshowmaker.data.templates.Templates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*

/**
 * Helper for One-Tap Story Mode.
 * Analyzes photos and automatically configures the best video settings.
 */
object StoryModeHelper {

    data class StoryConfig(
        val template: VideoTemplate,
        val orderedImages: List<ImageItem>,
        val recommendedMusic: MusicTrack?,
        val suggestedCaptions: List<TextOverlay>,
        val estimatedDuration: Float
    )

    /**
     * Analyze photos and generate optimal story configuration.
     */
    suspend fun analyzeAndConfigure(
        context: Context,
        images: List<ImageItem>
    ): StoryConfig = withContext(Dispatchers.IO) {
        if (images.isEmpty()) {
            return@withContext StoryConfig(
                template = Templates.allTemplates.first(),
                orderedImages = emptyList(),
                recommendedMusic = null,
                suggestedCaptions = emptyList(),
                estimatedDuration = 0f
            )
        }

        // 1. Extract metadata from all images
        val metadataList = images.map { image ->
            image to ExifHelper.extractMetadata(context, image.uri)
        }

        // 2. Order images by date (if available)
        val orderedImages = orderImagesByDate(metadataList)

        // 3. Detect the story type based on metadata patterns
        val storyType = detectStoryType(metadataList)

        // 4. Select best template for this story type
        val template = selectBestTemplate(storyType, images.size)

        // 5. Get recommended music
        val recommendedMusic = template.musicMood?.let { mood ->
            MusicLibrary.getRecommendedTracks(mood).firstOrNull()
        }

        // 6. Generate smart captions
        val captions = generateSmartCaptions(context, orderedImages, storyType)

        // 7. Calculate duration
        val estimatedDuration = calculateOptimalDuration(images.size, template)

        StoryConfig(
            template = template,
            orderedImages = orderedImages,
            recommendedMusic = recommendedMusic,
            suggestedCaptions = captions,
            estimatedDuration = estimatedDuration
        )
    }

    /**
     * Order images chronologically based on EXIF date.
     */
    private fun orderImagesByDate(
        metadataList: List<Pair<ImageItem, ExifHelper.PhotoMetadata>>
    ): List<ImageItem> {
        return metadataList
            .sortedBy { (_, metadata) ->
                metadata.dateTaken?.time ?: Long.MAX_VALUE
            }
            .mapIndexed { index, (image, _) ->
                image.copy(order = index)
            }
    }

    /**
     * Detect what type of story this is based on metadata patterns.
     */
    private fun detectStoryType(
        metadataList: List<Pair<ImageItem, ExifHelper.PhotoMetadata>>
    ): StoryType {
        val dates = metadataList.mapNotNull { it.second.dateTaken }
        val locations = metadataList.mapNotNull { it.second.location }

        // Check date patterns
        val dateSpan = if (dates.size >= 2) {
            val sortedDates = dates.sorted()
            val daysBetween = (sortedDates.last().time - sortedDates.first().time) / (1000 * 60 * 60 * 24)
            daysBetween
        } else 0L

        // Check location diversity
        val uniqueLocations = locations.mapNotNull { it.placeName }.distinct().size

        // Determine story type
        return when {
            // Single day event (birthday, wedding, party)
            dateSpan <= 1 && metadataList.size >= 5 -> StoryType.CELEBRATION

            // Multi-day with multiple locations (travel)
            dateSpan > 2 && uniqueLocations >= 2 -> StoryType.TRAVEL

            // Long time span (year in review, baby milestones)
            dateSpan > 30 -> StoryType.MEMORIES

            // Quick social content
            metadataList.size <= 5 -> StoryType.SOCIAL

            // Default to memories
            else -> StoryType.MEMORIES
        }
    }

    /**
     * Select the best template for this story type.
     */
    private fun selectBestTemplate(storyType: StoryType, imageCount: Int): VideoTemplate {
        val category = when (storyType) {
            StoryType.CELEBRATION -> TemplateCategory.CELEBRATION
            StoryType.TRAVEL -> TemplateCategory.TRAVEL
            StoryType.MEMORIES -> TemplateCategory.MEMORIES
            StoryType.SOCIAL -> TemplateCategory.SOCIAL
            StoryType.PROFESSIONAL -> TemplateCategory.PROFESSIONAL
        }

        // Get templates for this category
        val categoryTemplates = Templates.getTemplatesByCategory(category)

        // Prefer shorter durations for more photos
        return if (imageCount > 10) {
            categoryTemplates.minByOrNull { it.durationPerImage } ?: Templates.allTemplates.first()
        } else {
            categoryTemplates.firstOrNull() ?: Templates.allTemplates.first()
        }
    }

    /**
     * Generate smart captions based on story type and metadata.
     */
    private suspend fun generateSmartCaptions(
        context: Context,
        images: List<ImageItem>,
        storyType: StoryType
    ): List<TextOverlay> {
        if (images.isEmpty()) return emptyList()

        val captions = mutableListOf<TextOverlay>()

        // Add title caption for first image
        val firstMetadata = ExifHelper.extractMetadata(context, images.first().uri)
        val titleText = when (storyType) {
            StoryType.TRAVEL -> {
                firstMetadata.location?.placeName?.let { "Adventures in $it" }
                    ?: "Travel Memories"
            }
            StoryType.CELEBRATION -> {
                firstMetadata.dateTaken?.let { date ->
                    val calendar = Calendar.getInstance().apply { time = date }
                    val year = calendar.get(Calendar.YEAR)
                    "Memories of $year"
                } ?: "Celebration"
            }
            StoryType.MEMORIES -> "Our Memories"
            StoryType.SOCIAL -> null // No title for social
            StoryType.PROFESSIONAL -> null
        }

        titleText?.let {
            captions.add(
                TextOverlay(
                    text = it,
                    position = TextPosition.CENTER,
                    style = TextStyle.TITLE,
                    showOnAllImages = false,
                    imageIndex = 0
                )
            )
        }

        // Add location captions for travel stories
        if (storyType == StoryType.TRAVEL) {
            var lastLocation: String? = null
            images.forEachIndexed { index, image ->
                val metadata = ExifHelper.extractMetadata(context, image.uri)
                val location = metadata.location?.placeName
                if (location != null && location != lastLocation) {
                    captions.add(
                        TextOverlay(
                            text = location,
                            position = TextPosition.BOTTOM_CENTER,
                            style = TextStyle.CAPTION,
                            showOnAllImages = false,
                            imageIndex = index
                        )
                    )
                    lastLocation = location
                }
            }
        }

        return captions
    }

    /**
     * Calculate optimal duration based on image count and template.
     */
    private fun calculateOptimalDuration(imageCount: Int, template: VideoTemplate): Float {
        // Target 30-60 seconds total
        val targetDuration = when {
            imageCount <= 5 -> 30f
            imageCount <= 10 -> 45f
            else -> 60f
        }

        val perImageDuration = targetDuration / imageCount
        return perImageDuration.coerceIn(1f, template.durationPerImage)
    }

    enum class StoryType {
        CELEBRATION,
        TRAVEL,
        MEMORIES,
        SOCIAL,
        PROFESSIONAL
    }
}
