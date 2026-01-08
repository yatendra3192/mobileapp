package com.aiezzy.slideshowmaker.data.music

import com.aiezzy.slideshowmaker.data.models.MusicMood

// Music track model
data class MusicTrack(
    val id: String,
    val title: String,
    val artist: String,
    val duration: Int, // in seconds
    val mood: MusicMood,
    val category: MusicCategory,
    val previewUrl: String, // For streaming preview
    val downloadUrl: String, // For downloading
    val license: String = "Royalty-Free"
)

enum class MusicCategory(val displayName: String, val emoji: String) {
    TRENDING("Trending", "🔥"),
    UPBEAT("Upbeat", "🎉"),
    CHILL("Chill", "😌"),
    CINEMATIC("Cinematic", "🎬"),
    EMOTIONAL("Emotional", "💝"),
    ENERGETIC("Energetic", "⚡"),
    ACOUSTIC("Acoustic", "🎸"),
    ELECTRONIC("Electronic", "🎧")
}

// Music library repository with royalty-free tracks
object MusicLibrary {

    // Curated list of royalty-free music from Pixabay (all CC0/royalty-free)
    val allTracks: List<MusicTrack> = listOf(
        // Upbeat / Happy
        MusicTrack(
            id = "upbeat_1",
            title = "Happy Day",
            artist = "Pixabay",
            duration = 120,
            mood = MusicMood.HAPPY,
            category = MusicCategory.UPBEAT,
            previewUrl = "https://cdn.pixabay.com/audio/2022/10/25/audio_7ae1bca33d.mp3",
            downloadUrl = "https://cdn.pixabay.com/audio/2022/10/25/audio_7ae1bca33d.mp3"
        ),
        MusicTrack(
            id = "upbeat_2",
            title = "Good Vibes",
            artist = "Pixabay",
            duration = 105,
            mood = MusicMood.HAPPY,
            category = MusicCategory.UPBEAT,
            previewUrl = "https://cdn.pixabay.com/audio/2022/05/27/audio_1808fbf07a.mp3",
            downloadUrl = "https://cdn.pixabay.com/audio/2022/05/27/audio_1808fbf07a.mp3"
        ),
        MusicTrack(
            id = "upbeat_3",
            title = "Celebrate",
            artist = "Pixabay",
            duration = 138,
            mood = MusicMood.HAPPY,
            category = MusicCategory.UPBEAT,
            previewUrl = "https://cdn.pixabay.com/audio/2022/03/15/audio_115b9cde3f.mp3",
            downloadUrl = "https://cdn.pixabay.com/audio/2022/03/15/audio_115b9cde3f.mp3"
        ),

        // Chill / Calm
        MusicTrack(
            id = "chill_1",
            title = "Lofi Study",
            artist = "Pixabay",
            duration = 147,
            mood = MusicMood.CALM,
            category = MusicCategory.CHILL,
            previewUrl = "https://cdn.pixabay.com/audio/2022/05/16/audio_1333dfb16b.mp3",
            downloadUrl = "https://cdn.pixabay.com/audio/2022/05/16/audio_1333dfb16b.mp3"
        ),
        MusicTrack(
            id = "chill_2",
            title = "Peaceful Morning",
            artist = "Pixabay",
            duration = 180,
            mood = MusicMood.CALM,
            category = MusicCategory.CHILL,
            previewUrl = "https://cdn.pixabay.com/audio/2022/01/18/audio_d0a13f69d2.mp3",
            downloadUrl = "https://cdn.pixabay.com/audio/2022/01/18/audio_d0a13f69d2.mp3"
        ),
        MusicTrack(
            id = "chill_3",
            title = "Ambient Dreams",
            artist = "Pixabay",
            duration = 195,
            mood = MusicMood.CALM,
            category = MusicCategory.CHILL,
            previewUrl = "https://cdn.pixabay.com/audio/2022/08/02/audio_884fe92c21.mp3",
            downloadUrl = "https://cdn.pixabay.com/audio/2022/08/02/audio_884fe92c21.mp3"
        ),

        // Cinematic
        MusicTrack(
            id = "cinematic_1",
            title = "Epic Adventure",
            artist = "Pixabay",
            duration = 162,
            mood = MusicMood.CINEMATIC,
            category = MusicCategory.CINEMATIC,
            previewUrl = "https://cdn.pixabay.com/audio/2022/02/15/audio_4614b7ec94.mp3",
            downloadUrl = "https://cdn.pixabay.com/audio/2022/02/15/audio_4614b7ec94.mp3"
        ),
        MusicTrack(
            id = "cinematic_2",
            title = "Inspiring Moments",
            artist = "Pixabay",
            duration = 144,
            mood = MusicMood.CINEMATIC,
            category = MusicCategory.CINEMATIC,
            previewUrl = "https://cdn.pixabay.com/audio/2022/11/22/audio_a1e8d65738.mp3",
            downloadUrl = "https://cdn.pixabay.com/audio/2022/11/22/audio_a1e8d65738.mp3"
        ),
        MusicTrack(
            id = "cinematic_3",
            title = "Documentary",
            artist = "Pixabay",
            duration = 180,
            mood = MusicMood.CINEMATIC,
            category = MusicCategory.CINEMATIC,
            previewUrl = "https://cdn.pixabay.com/audio/2022/03/10/audio_b6f0e39bad.mp3",
            downloadUrl = "https://cdn.pixabay.com/audio/2022/03/10/audio_b6f0e39bad.mp3"
        ),

        // Emotional
        MusicTrack(
            id = "emotional_1",
            title = "Tender Moments",
            artist = "Pixabay",
            duration = 156,
            mood = MusicMood.EMOTIONAL,
            category = MusicCategory.EMOTIONAL,
            previewUrl = "https://cdn.pixabay.com/audio/2022/08/31/audio_419263fc12.mp3",
            downloadUrl = "https://cdn.pixabay.com/audio/2022/08/31/audio_419263fc12.mp3"
        ),
        MusicTrack(
            id = "emotional_2",
            title = "Memories",
            artist = "Pixabay",
            duration = 168,
            mood = MusicMood.EMOTIONAL,
            category = MusicCategory.EMOTIONAL,
            previewUrl = "https://cdn.pixabay.com/audio/2022/01/20/audio_de3fc25ed5.mp3",
            downloadUrl = "https://cdn.pixabay.com/audio/2022/01/20/audio_de3fc25ed5.mp3"
        ),
        MusicTrack(
            id = "emotional_3",
            title = "Piano Dreams",
            artist = "Pixabay",
            duration = 189,
            mood = MusicMood.EMOTIONAL,
            category = MusicCategory.EMOTIONAL,
            previewUrl = "https://cdn.pixabay.com/audio/2022/09/07/audio_ba59116544.mp3",
            downloadUrl = "https://cdn.pixabay.com/audio/2022/09/07/audio_ba59116544.mp3"
        ),

        // Energetic
        MusicTrack(
            id = "energetic_1",
            title = "Power Up",
            artist = "Pixabay",
            duration = 126,
            mood = MusicMood.ENERGETIC,
            category = MusicCategory.ENERGETIC,
            previewUrl = "https://cdn.pixabay.com/audio/2022/10/30/audio_65f521b990.mp3",
            downloadUrl = "https://cdn.pixabay.com/audio/2022/10/30/audio_65f521b990.mp3"
        ),
        MusicTrack(
            id = "energetic_2",
            title = "Workout Beats",
            artist = "Pixabay",
            duration = 138,
            mood = MusicMood.ENERGETIC,
            category = MusicCategory.ENERGETIC,
            previewUrl = "https://cdn.pixabay.com/audio/2022/08/23/audio_d45de1c07c.mp3",
            downloadUrl = "https://cdn.pixabay.com/audio/2022/08/23/audio_d45de1c07c.mp3"
        ),
        MusicTrack(
            id = "energetic_3",
            title = "Action Time",
            artist = "Pixabay",
            duration = 114,
            mood = MusicMood.ENERGETIC,
            category = MusicCategory.ENERGETIC,
            previewUrl = "https://cdn.pixabay.com/audio/2022/04/27/audio_67bcb4a6ed.mp3",
            downloadUrl = "https://cdn.pixabay.com/audio/2022/04/27/audio_67bcb4a6ed.mp3"
        ),

        // Electronic
        MusicTrack(
            id = "electronic_1",
            title = "Future Bass",
            artist = "Pixabay",
            duration = 132,
            mood = MusicMood.ENERGETIC,
            category = MusicCategory.ELECTRONIC,
            previewUrl = "https://cdn.pixabay.com/audio/2022/06/08/audio_6a57c67515.mp3",
            downloadUrl = "https://cdn.pixabay.com/audio/2022/06/08/audio_6a57c67515.mp3"
        ),
        MusicTrack(
            id = "electronic_2",
            title = "Synthwave",
            artist = "Pixabay",
            duration = 156,
            mood = MusicMood.ENERGETIC,
            category = MusicCategory.ELECTRONIC,
            previewUrl = "https://cdn.pixabay.com/audio/2022/07/19/audio_9b68f5e76e.mp3",
            downloadUrl = "https://cdn.pixabay.com/audio/2022/07/19/audio_9b68f5e76e.mp3"
        ),

        // Acoustic
        MusicTrack(
            id = "acoustic_1",
            title = "Acoustic Morning",
            artist = "Pixabay",
            duration = 174,
            mood = MusicMood.CALM,
            category = MusicCategory.ACOUSTIC,
            previewUrl = "https://cdn.pixabay.com/audio/2022/10/18/audio_5c4b3a1b0a.mp3",
            downloadUrl = "https://cdn.pixabay.com/audio/2022/10/18/audio_5c4b3a1b0a.mp3"
        ),
        MusicTrack(
            id = "acoustic_2",
            title = "Guitar Sunset",
            artist = "Pixabay",
            duration = 186,
            mood = MusicMood.CALM,
            category = MusicCategory.ACOUSTIC,
            previewUrl = "https://cdn.pixabay.com/audio/2022/12/13/audio_3dab9e6250.mp3",
            downloadUrl = "https://cdn.pixabay.com/audio/2022/12/13/audio_3dab9e6250.mp3"
        )
    )

    fun getTracksByCategory(category: MusicCategory): List<MusicTrack> {
        return allTracks.filter { it.category == category }
    }

    fun getTracksByMood(mood: MusicMood): List<MusicTrack> {
        return allTracks.filter { it.mood == mood }
    }

    fun getTrackById(id: String): MusicTrack? {
        return allTracks.find { it.id == id }
    }

    fun searchTracks(query: String): List<MusicTrack> {
        val lowerQuery = query.lowercase()
        return allTracks.filter {
            it.title.lowercase().contains(lowerQuery) ||
            it.artist.lowercase().contains(lowerQuery) ||
            it.category.displayName.lowercase().contains(lowerQuery)
        }
    }

    // Get recommended tracks based on template mood
    fun getRecommendedTracks(mood: MusicMood?): List<MusicTrack> {
        return if (mood != null) {
            getTracksByMood(mood).take(5)
        } else {
            allTracks.shuffled().take(5)
        }
    }

    fun formatDuration(seconds: Int): String {
        val mins = seconds / 60
        val secs = seconds % 60
        return String.format("%d:%02d", mins, secs)
    }
}
