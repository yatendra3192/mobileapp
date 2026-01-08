package com.aiezzy.slideshowmaker.data.templates

import com.aiezzy.slideshowmaker.data.models.*

object Templates {

    val allTemplates: List<VideoTemplate> = listOf(
        // Birthday/Celebration template
        VideoTemplate(
            id = "birthday_memories",
            name = "Birthday Memories",
            description = "Perfect for birthday celebrations with joyful transitions",
            category = TemplateCategory.CELEBRATION,
            transition = TransitionEffect.FADE,
            durationPerImage = 2.5f,
            resolution = VideoResolution.STORIES_FHD,
            musicMood = MusicMood.HAPPY,
            iconEmoji = "🎂"
        ),

        // Travel template
        VideoTemplate(
            id = "travel_adventure",
            name = "Travel Adventure",
            description = "Cinematic style for your travel memories",
            category = TemplateCategory.TRAVEL,
            transition = TransitionEffect.KEN_BURNS,
            durationPerImage = 3f,
            resolution = VideoResolution.YOUTUBE_FHD,
            musicMood = MusicMood.CINEMATIC,
            iconEmoji = "✈️"
        ),

        // Social media story template
        VideoTemplate(
            id = "quick_story",
            name = "Quick Story",
            description = "Fast-paced for TikTok, Reels & Shorts",
            category = TemplateCategory.SOCIAL,
            transition = TransitionEffect.SLIDE,
            durationPerImage = 1.5f,
            resolution = VideoResolution.STORIES_FHD,
            musicMood = MusicMood.ENERGETIC,
            iconEmoji = "📱"
        ),

        // Memories/Family template
        VideoTemplate(
            id = "family_moments",
            name = "Family Moments",
            description = "Warm and emotional for precious memories",
            category = TemplateCategory.MEMORIES,
            transition = TransitionEffect.DISSOLVE,
            durationPerImage = 3f,
            resolution = VideoResolution.YOUTUBE_HD,
            musicMood = MusicMood.EMOTIONAL,
            iconEmoji = "👨‍👩‍👧‍👦"
        ),

        // Product/Professional template
        VideoTemplate(
            id = "product_showcase",
            name = "Product Showcase",
            description = "Clean and professional for products & business",
            category = TemplateCategory.PROFESSIONAL,
            transition = TransitionEffect.ZOOM,
            durationPerImage = 2f,
            resolution = VideoResolution.SQUARE_HD,
            musicMood = MusicMood.CALM,
            iconEmoji = "💼"
        ),

        // Wedding template
        VideoTemplate(
            id = "wedding_highlights",
            name = "Wedding Highlights",
            description = "Elegant and romantic for wedding memories",
            category = TemplateCategory.CELEBRATION,
            transition = TransitionEffect.FADE,
            durationPerImage = 3.5f,
            resolution = VideoResolution.YOUTUBE_FHD,
            musicMood = MusicMood.EMOTIONAL,
            iconEmoji = "💒"
        ),

        // Fitness/Before-After template
        VideoTemplate(
            id = "fitness_journey",
            name = "Fitness Journey",
            description = "Energetic style for transformation stories",
            category = TemplateCategory.SOCIAL,
            transition = TransitionEffect.WIPE,
            durationPerImage = 2f,
            resolution = VideoResolution.STORIES_FHD,
            musicMood = MusicMood.ENERGETIC,
            iconEmoji = "💪"
        ),

        // Pet Moments template
        VideoTemplate(
            id = "pet_moments",
            name = "Pet Moments",
            description = "Playful and cute for your furry friends",
            category = TemplateCategory.MEMORIES,
            transition = TransitionEffect.ZOOM,
            durationPerImage = 2f,
            resolution = VideoResolution.SQUARE_HD,
            musicMood = MusicMood.HAPPY,
            iconEmoji = "🐾"
        ),

        // Food/Recipe template
        VideoTemplate(
            id = "food_story",
            name = "Food Story",
            description = "Appetizing style for food & recipes",
            category = TemplateCategory.SOCIAL,
            transition = TransitionEffect.ZOOM_OUT,
            durationPerImage = 2f,
            resolution = VideoResolution.SQUARE_HD,
            musicMood = MusicMood.CALM,
            iconEmoji = "🍕"
        ),

        // Real Estate template
        VideoTemplate(
            id = "real_estate",
            name = "Real Estate Tour",
            description = "Professional property showcase",
            category = TemplateCategory.PROFESSIONAL,
            transition = TransitionEffect.KEN_BURNS,
            durationPerImage = 3f,
            resolution = VideoResolution.YOUTUBE_FHD,
            musicMood = MusicMood.CALM,
            iconEmoji = "🏠"
        ),

        // Baby's First Year template
        VideoTemplate(
            id = "baby_milestones",
            name = "Baby Milestones",
            description = "Soft and tender for baby memories",
            category = TemplateCategory.MEMORIES,
            transition = TransitionEffect.DISSOLVE,
            durationPerImage = 3f,
            resolution = VideoResolution.YOUTUBE_HD,
            musicMood = MusicMood.EMOTIONAL,
            iconEmoji = "👶"
        ),

        // Graduation template
        VideoTemplate(
            id = "graduation_day",
            name = "Graduation Day",
            description = "Celebrate academic achievements",
            category = TemplateCategory.CELEBRATION,
            transition = TransitionEffect.SLIDE,
            durationPerImage = 2.5f,
            resolution = VideoResolution.STORIES_FHD,
            musicMood = MusicMood.HAPPY,
            iconEmoji = "🎓"
        ),

        // Music Video Style template
        VideoTemplate(
            id = "music_vibe",
            name = "Music Vibe",
            description = "Fast cuts like a music video",
            category = TemplateCategory.SOCIAL,
            transition = TransitionEffect.BLUR,
            durationPerImage = 1f,
            resolution = VideoResolution.STORIES_FHD,
            musicMood = MusicMood.ENERGETIC,
            iconEmoji = "🎵"
        ),

        // Nature/Landscape template
        VideoTemplate(
            id = "nature_escape",
            name = "Nature Escape",
            description = "Cinematic style for landscapes",
            category = TemplateCategory.TRAVEL,
            transition = TransitionEffect.KEN_BURNS,
            durationPerImage = 4f,
            resolution = VideoResolution.YOUTUBE_FHD,
            musicMood = MusicMood.CINEMATIC,
            iconEmoji = "🏞️"
        ),

        // Romantic template
        VideoTemplate(
            id = "love_story",
            name = "Love Story",
            description = "Romantic moments with your partner",
            category = TemplateCategory.MEMORIES,
            transition = TransitionEffect.FADE,
            durationPerImage = 3f,
            resolution = VideoResolution.STORIES_FHD,
            musicMood = MusicMood.EMOTIONAL,
            iconEmoji = "❤️"
        ),

        // Gaming Highlights template
        VideoTemplate(
            id = "gaming_highlights",
            name = "Gaming Highlights",
            description = "Epic moments from your gaming sessions",
            category = TemplateCategory.SOCIAL,
            transition = TransitionEffect.BLUR,
            durationPerImage = 1.5f,
            resolution = VideoResolution.YOUTUBE_FHD,
            musicMood = MusicMood.ENERGETIC,
            iconEmoji = "🎮"
        ),

        // Art Portfolio template
        VideoTemplate(
            id = "art_portfolio",
            name = "Art Portfolio",
            description = "Showcase your artwork professionally",
            category = TemplateCategory.PROFESSIONAL,
            transition = TransitionEffect.DISSOLVE,
            durationPerImage = 3.5f,
            resolution = VideoResolution.SQUARE_HD,
            musicMood = MusicMood.CALM,
            iconEmoji = "🎨"
        ),

        // Throwback Thursday template
        VideoTemplate(
            id = "throwback",
            name = "Throwback",
            description = "Nostalgic memories from the past",
            category = TemplateCategory.MEMORIES,
            transition = TransitionEffect.FADE,
            durationPerImage = 2.5f,
            resolution = VideoResolution.STORIES_FHD,
            musicMood = MusicMood.EMOTIONAL,
            iconEmoji = "📸"
        ),

        // Holiday Season template
        VideoTemplate(
            id = "holiday_season",
            name = "Holiday Season",
            description = "Festive moments and celebrations",
            category = TemplateCategory.CELEBRATION,
            transition = TransitionEffect.ZOOM,
            durationPerImage = 2.5f,
            resolution = VideoResolution.STORIES_FHD,
            musicMood = MusicMood.HAPPY,
            iconEmoji = "🎄"
        ),

        // School Memories template
        VideoTemplate(
            id = "school_memories",
            name = "School Memories",
            description = "Cherished moments from school days",
            category = TemplateCategory.MEMORIES,
            transition = TransitionEffect.SLIDE,
            durationPerImage = 2.5f,
            resolution = VideoResolution.YOUTUBE_HD,
            musicMood = MusicMood.HAPPY,
            iconEmoji = "🏫"
        )
    )

    fun getTemplateById(id: String): VideoTemplate? {
        return allTemplates.find { it.id == id }
    }

    fun getTemplatesByCategory(category: TemplateCategory): List<VideoTemplate> {
        return allTemplates.filter { it.category == category }
    }

    // Calculate optimal duration per image based on photo count and platform
    fun calculateSmartDuration(
        photoCount: Int,
        platformPreset: PlatformPreset? = null,
        targetTotalDuration: Int? = null
    ): Float {
        val maxDuration = platformPreset?.maxDurationSeconds ?: targetTotalDuration ?: 60

        // Calculate duration that fits all photos within max duration
        val calculatedDuration = maxDuration.toFloat() / photoCount

        // Clamp between reasonable bounds (1s - 5s per image)
        return calculatedDuration.coerceIn(1f, 5f)
    }
}
