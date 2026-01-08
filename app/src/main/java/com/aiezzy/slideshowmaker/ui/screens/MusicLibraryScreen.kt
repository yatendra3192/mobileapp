package com.aiezzy.slideshowmaker.ui.screens

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aiezzy.slideshowmaker.data.models.MusicMood
import com.aiezzy.slideshowmaker.data.music.MusicCategory
import com.aiezzy.slideshowmaker.data.music.MusicLibrary
import com.aiezzy.slideshowmaker.data.music.MusicTrack
import com.aiezzy.slideshowmaker.ui.components.*
import com.aiezzy.slideshowmaker.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

private const val TAG = "MusicLibraryScreen"
private const val CONNECT_TIMEOUT_MS = 15000
private const val READ_TIMEOUT_MS = 30000

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicLibraryScreen(
    recommendedMood: MusicMood? = null,
    onMusicSelected: (MusicTrack, String) -> Unit,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    var selectedCategory by remember { mutableStateOf<MusicCategory?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var currentlyPlaying by remember { mutableStateOf<String?>(null) }
    var isDownloading by remember { mutableStateOf<String?>(null) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    // Filter tracks
    val displayedTracks = remember(selectedCategory, searchQuery) {
        when {
            searchQuery.isNotBlank() -> MusicLibrary.searchTracks(searchQuery)
            selectedCategory != null -> MusicLibrary.getTracksByCategory(selectedCategory!!)
            else -> MusicLibrary.allTracks
        }
    }

    val recommendedTracks = remember(recommendedMood) {
        MusicLibrary.getRecommendedTracks(recommendedMood)
    }

    // Cleanup media player
    DisposableEffect(Unit) {
        onDispose {
            mediaPlayer?.release()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Music Library",
                        style = AiezzyType.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            mediaPlayer?.release()
                            onNavigateBack()
                        },
                        modifier = Modifier.size(AiezzyDimens.Touch.comfortable)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Go back to settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = AiezzyTheme.colors.textPrimary,
                    navigationIconContentColor = AiezzyTheme.colors.textPrimary
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(AiezzySpacing.base),
            verticalArrangement = Arrangement.spacedBy(AiezzySpacing.md)
        ) {
            // Search bar
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            "Search music...",
                            color = AiezzyTheme.colors.textTertiary
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            tint = AiezzyTheme.colors.textSecondary
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotBlank()) {
                            IconButton(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                searchQuery = ""
                            }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                    },
                    singleLine = true,
                    shape = AiezzyShapes.searchBar,
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = AiezzyTheme.colors.border,
                        focusedBorderColor = MaterialTheme.colorScheme.primary
                    )
                )
            }

            // Category chips
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(AiezzySpacing.sm)
                ) {
                    PremiumChip(
                        text = "All",
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            selectedCategory = null
                        },
                        selected = selectedCategory == null,
                        icon = if (selectedCategory == null) Icons.Default.Check else null
                    )

                    MusicCategory.entries.forEach { category ->
                        PremiumChip(
                            text = "${category.emoji} ${category.displayName}",
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                selectedCategory = category
                            },
                            selected = selectedCategory == category,
                            icon = if (selectedCategory == category) Icons.Default.Check else null
                        )
                    }
                }
            }

            // Recommended section (only if mood is provided and no search/filter)
            if (recommendedMood != null && searchQuery.isBlank() && selectedCategory == null) {
                item {
                    Text(
                        text = "Recommended for your slideshow",
                        style = AiezzyType.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = AiezzyTheme.colors.textPrimary,
                        modifier = Modifier.padding(top = AiezzySpacing.sm)
                    )
                }

                items(recommendedTracks) { track ->
                    PremiumMusicTrackItem(
                        track = track,
                        isPlaying = currentlyPlaying == track.id,
                        isDownloading = isDownloading == track.id,
                        isRecommended = true,
                        onPlayPause = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (currentlyPlaying == track.id) {
                                mediaPlayer?.release()
                                mediaPlayer = null
                                currentlyPlaying = null
                            } else {
                                mediaPlayer?.release()
                                currentlyPlaying = track.id
                                scope.launch {
                                    try {
                                        mediaPlayer = MediaPlayer().apply {
                                            setDataSource(track.previewUrl)
                                            prepareAsync()
                                            setOnPreparedListener { start() }
                                            setOnCompletionListener {
                                                currentlyPlaying = null
                                            }
                                            setOnErrorListener { _, _, _ ->
                                                currentlyPlaying = null
                                                true
                                            }
                                        }
                                    } catch (e: Exception) {
                                        currentlyPlaying = null
                                        Toast.makeText(context, "Failed to play preview", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        },
                        onSelect = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            scope.launch {
                                isDownloading = track.id
                                val localPath = downloadMusic(context, track)
                                isDownloading = null
                                if (localPath != null) {
                                    mediaPlayer?.release()
                                    onMusicSelected(track, localPath)
                                } else {
                                    Toast.makeText(context, "Failed to download music", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    )
                }

                item {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = AiezzySpacing.sm),
                        color = AiezzyTheme.colors.border
                    )
                    Text(
                        text = "All Music",
                        style = AiezzyType.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = AiezzyTheme.colors.textPrimary
                    )
                }
            }

            // All tracks
            items(displayedTracks) { track ->
                PremiumMusicTrackItem(
                    track = track,
                    isPlaying = currentlyPlaying == track.id,
                    isDownloading = isDownloading == track.id,
                    isRecommended = false,
                    onPlayPause = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (currentlyPlaying == track.id) {
                            mediaPlayer?.release()
                            mediaPlayer = null
                            currentlyPlaying = null
                        } else {
                            mediaPlayer?.release()
                            currentlyPlaying = track.id
                            scope.launch {
                                try {
                                    mediaPlayer = MediaPlayer().apply {
                                        setDataSource(track.previewUrl)
                                        prepareAsync()
                                        setOnPreparedListener { start() }
                                        setOnCompletionListener {
                                            currentlyPlaying = null
                                        }
                                        setOnErrorListener { _, _, _ ->
                                            currentlyPlaying = null
                                            true
                                        }
                                    }
                                } catch (e: Exception) {
                                    currentlyPlaying = null
                                    Toast.makeText(context, "Failed to play preview", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    onSelect = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        scope.launch {
                            isDownloading = track.id
                            val localPath = downloadMusic(context, track)
                            isDownloading = null
                            if (localPath != null) {
                                mediaPlayer?.release()
                                onMusicSelected(track, localPath)
                            } else {
                                Toast.makeText(context, "Failed to download music", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
            }

            // Empty state
            if (displayedTracks.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(AiezzySpacing.xxl),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Surface(
                                modifier = Modifier.size(80.dp),
                                shape = CircleShape,
                                color = AiezzyTheme.colors.backgroundSecondary
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.MusicOff,
                                        contentDescription = null,
                                        modifier = Modifier.size(AiezzyDimens.Icon.sizeXL),
                                        tint = AiezzyTheme.colors.textTertiary
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(AiezzySpacing.base))
                            Text(
                                "No music found",
                                style = AiezzyType.bodyLarge,
                                color = AiezzyTheme.colors.textSecondary
                            )
                            Text(
                                "Try a different search term",
                                style = AiezzyType.bodySmall,
                                color = AiezzyTheme.colors.textTertiary
                            )
                        }
                    }
                }
            }

            // Bottom padding
            item {
                Spacer(modifier = Modifier.height(AiezzySpacing.base))
            }
        }
    }
}

@Composable
private fun PremiumMusicTrackItem(
    track: MusicTrack,
    isPlaying: Boolean,
    isDownloading: Boolean,
    isRecommended: Boolean,
    onPlayPause: () -> Unit,
    onSelect: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.98f else 1f,
        animationSpec = AiezzyAnimSpec.fast,
        label = "scale"
    )

    val backgroundColor by animateColorAsState(
        targetValue = if (isRecommended)
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        else
            AiezzyTheme.colors.backgroundSecondary,
        animationSpec = androidx.compose.animation.core.tween(AiezzyDuration.fast),
        label = "bg"
    )

    PremiumCard(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .animateContentSize(),
        elevation = if (isRecommended) AiezzyDimens.Card.elevation else 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(backgroundColor)
                .padding(AiezzySpacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Play/Pause button with animation
            val playButtonScale by animateFloatAsState(
                targetValue = if (isPlaying) 1.1f else 1f,
                animationSpec = AiezzyAnimSpec.bouncy,
                label = "playScale"
            )

            FilledIconButton(
                onClick = onPlayPause,
                modifier = Modifier
                    .size(AiezzyDimens.Touch.comfortable)
                    .scale(playButtonScale),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (isPlaying)
                        MaterialTheme.colorScheme.primary
                    else
                        AiezzyTheme.colors.border
                ),
                shape = CircleShape
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = if (isPlaying)
                        MaterialTheme.colorScheme.onPrimary
                    else
                        AiezzyTheme.colors.textSecondary,
                    modifier = Modifier.size(AiezzyDimens.Icon.size)
                )
            }

            Spacer(modifier = Modifier.width(AiezzySpacing.md))

            // Track info
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = track.title,
                        style = AiezzyType.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = AiezzyTheme.colors.textPrimary,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isRecommended) {
                        Spacer(modifier = Modifier.width(AiezzySpacing.sm))
                        Surface(
                            shape = AiezzyShapes.chip,
                            color = MaterialTheme.colorScheme.primary
                        ) {
                            Text(
                                text = "Recommended",
                                style = AiezzyType.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = AiezzySpacing.sm, vertical = AiezzySpacing.xxs)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(AiezzySpacing.xxs))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${track.category.emoji} ${track.category.displayName}",
                        style = AiezzyType.bodySmall,
                        color = AiezzyTheme.colors.textTertiary
                    )
                    Text(
                        text = " • ",
                        style = AiezzyType.bodySmall,
                        color = AiezzyTheme.colors.textTertiary
                    )
                    Text(
                        text = MusicLibrary.formatDuration(track.duration),
                        style = AiezzyType.bodySmall,
                        color = AiezzyTheme.colors.textTertiary
                    )
                }
            }

            Spacer(modifier = Modifier.width(AiezzySpacing.sm))

            // Select button
            PremiumButton(
                text = if (isDownloading) "" else "Use",
                onClick = onSelect,
                enabled = !isDownloading,
                variant = ButtonVariant.Primary,
                size = ButtonSize.Small,
                loading = isDownloading,
                icon = if (!isDownloading) Icons.Default.Add else null
            )
        }
    }
}

private suspend fun downloadMusic(context: Context, track: MusicTrack): String? {
    return withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val musicDir = File(context.cacheDir, "music")
            if (!musicDir.exists()) musicDir.mkdirs()

            val fileName = "${track.id}.mp3"
            val outputFile = File(musicDir, fileName)

            // Check if already downloaded
            if (outputFile.exists() && outputFile.length() > 0) {
                return@withContext outputFile.absolutePath
            }

            // Validate and upgrade URL to HTTPS
            var downloadUrl = track.downloadUrl
            if (downloadUrl.startsWith("http://")) {
                downloadUrl = downloadUrl.replace("http://", "https://")
                Log.d(TAG, "Upgraded URL to HTTPS: $downloadUrl")
            }

            if (!downloadUrl.startsWith("https://")) {
                Log.e(TAG, "Invalid URL protocol: $downloadUrl")
                return@withContext null
            }

            // Download the file with proper timeouts
            val url = URL(downloadUrl)
            connection = (url.openConnection() as HttpsURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", "AiezzySlideshowMaker/1.0")
            }

            connection.connect()

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "Download failed with HTTP $responseCode")
                return@withContext null
            }

            val inputStream = connection.inputStream
            val outputStream = FileOutputStream(outputFile)

            inputStream.use { input ->
                outputStream.use { output ->
                    input.copyTo(output)
                }
            }

            Log.d(TAG, "Music downloaded successfully: ${outputFile.absolutePath}")
            outputFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download music: ${track.title}", e)
            null
        } finally {
            connection?.disconnect()
        }
    }
}
