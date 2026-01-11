package com.aiezzy.slideshowmaker.ui.screens

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import android.net.Uri
import android.util.Log
import com.aiezzy.slideshowmaker.SlideshowApp
import com.aiezzy.slideshowmaker.data.models.AudioConfig
import com.aiezzy.slideshowmaker.data.models.ImageItem
import com.aiezzy.slideshowmaker.data.models.VideoTemplate
import com.aiezzy.slideshowmaker.data.models.PlatformPreset
import com.aiezzy.slideshowmaker.ui.components.*
import com.aiezzy.slideshowmaker.ui.theme.*
import com.aiezzy.slideshowmaker.viewmodel.PeopleViewModel
import com.aiezzy.slideshowmaker.viewmodel.SlideshowViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Accent color matching the reference design
private val AccentYellow = Color(0xFFE5FF00)
private val DarkBackground = Color(0xFF1A1A1A)
private val CardBackground = Color(0xFF2A2A2A)

/**
 * Data class representing a saved video project
 */
data class SavedVideo(
    val id: Long,
    val name: String,
    val path: String,
    val dateCreated: Long,
    val duration: Long,
    val size: Long
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: SlideshowViewModel,
    peopleViewModel: PeopleViewModel? = null,
    onNavigateToSettings: () -> Unit,
    onNavigateToProcessing: () -> Unit = {},
    onNavigateToPreview: (String) -> Unit = {},
    onNavigateToPeople: () -> Unit = {}
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val images by viewModel.images.collectAsState()
    val audioConfig by viewModel.audioConfig.collectAsState()

    // Load saved videos/projects
    var savedVideos by remember { mutableStateOf<List<SavedVideo>>(emptyList()) }
    LaunchedEffect(Unit) {
        savedVideos = loadSavedVideos(context)
    }

    var hasPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_MEDIA_IMAGES
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasPermission = isGranted
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            viewModel.addImages(uris)
        }
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_IMAGES
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
            permissionLauncher.launch(permission)
        }
    }

    Scaffold(
        containerColor = DarkBackground
    ) { paddingValues ->
        if (images.isEmpty()) {
            // Show the new home layout when no images selected
            NewHomeLayout(
                savedVideos = savedVideos,
                onStartProject = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (hasPermission) {
                        imagePickerLauncher.launch("image/*")
                    } else {
                        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            Manifest.permission.READ_MEDIA_IMAGES
                        } else {
                            Manifest.permission.READ_EXTERNAL_STORAGE
                        }
                        permissionLauncher.launch(permission)
                    }
                },
                onVideoClick = { video ->
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onNavigateToPreview(video.path)
                },
                onSettingsClick = onNavigateToSettings,
                onRefreshVideos = { savedVideos = loadSavedVideos(context) },
                peopleViewModel = peopleViewModel,
                onNavigateToPeople = onNavigateToPeople,
                modifier = Modifier.padding(paddingValues)
            )
        } else {
            // Show image selection/editing layout
            ImageSelectionLayout(
                images = images,
                viewModel = viewModel,
                audioConfig = audioConfig,
                onAddMoreImages = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    imagePickerLauncher.launch("image/*")
                },
                onCreateVideo = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onNavigateToProcessing()
                },
                onClearAll = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.reset()
                },
                onSettingsClick = onNavigateToSettings,
                modifier = Modifier.padding(paddingValues)
            )
        }
    }
}

@Composable
private fun NewHomeLayout(
    savedVideos: List<SavedVideo>,
    onStartProject: () -> Unit,
    onVideoClick: (SavedVideo) -> Unit,
    onSettingsClick: () -> Unit,
    onRefreshVideos: () -> Unit,
    peopleViewModel: PeopleViewModel?,
    onNavigateToPeople: () -> Unit,
    modifier: Modifier = Modifier
) {
    // People state - extract from MVI ui states
    val uiState = peopleViewModel?.uiState?.collectAsState()?.value
    val scanState = peopleViewModel?.scanState?.collectAsState()?.value
    val isScanRunning = peopleViewModel?.isScanRunning?.collectAsState()?.value ?: false
    val scanPercentage = peopleViewModel?.scanPercentage?.collectAsState()?.value ?: 0f

    val persons = when (uiState) {
        is PeopleViewModel.PeopleUiState.Success -> uiState.persons
        else -> emptyList()
    }

    // Auto-start scanning when app launches (for new photos)
    LaunchedEffect(Unit) {
        peopleViewModel?.startGalleryScan(forceRescan = false)
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Slideshow Maker",
                style = AiezzyType.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = onRefreshVideos) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Refresh",
                        tint = Color.White.copy(alpha = 0.7f)
                    )
                }
                IconButton(onClick = onSettingsClick) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = Color.White.copy(alpha = 0.7f)
                    )
                }
            }
        }

        // People and pets section - only show when scan is complete and faces found
        if (!isScanRunning && persons.isNotEmpty()) {
            // People and pets card (full width, no Albums)
            PeopleCard(
                persons = persons.take(4),
                scanProgress = scanPercentage,
                isScanning = isScanRunning,
                onClick = onNavigateToPeople,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))
        } else if (isScanRunning) {
            // Show scanning progress
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .clip(RoundedCornerShape(16.dp)),
                color = CardBackground,
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(20.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(
                        progress = { scanPercentage },
                        modifier = Modifier.size(48.dp),
                        color = AccentYellow,
                        trackColor = Color.White.copy(alpha = 0.2f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Scanning photos for faces...",
                        style = AiezzyType.bodyMedium,
                        color = Color.White
                    )
                    Text(
                        text = "${(scanPercentage * 100).toInt()}% complete",
                        style = AiezzyType.bodySmall,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }

        // Create New Slideshow Section
        Column(
            modifier = Modifier.padding(horizontal = 20.dp)
        ) {
            Text(
                text = "Create New Slideshow",
                style = AiezzyType.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Turn your photos into stunning videos with music and effects in seconds.",
                style = AiezzyType.bodyMedium,
                color = Color.White.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.height(16.dp))

            // Start Project Button
            val interactionSource = remember { MutableInteractionSource() }
            val isPressed by interactionSource.collectIsPressedAsState()
            val scale by animateFloatAsState(
                targetValue = if (isPressed) 0.96f else 1f,
                animationSpec = tween(100),
                label = "buttonScale"
            )

            Button(
                onClick = onStartProject,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .scale(scale),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentYellow,
                    contentColor = Color.Black
                ),
                interactionSource = interactionSource
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Start Project",
                    style = AiezzyType.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Recent Projects Section
        if (savedVideos.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Recent Projects",
                    style = AiezzyType.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "See All",
                    style = AiezzyType.labelMedium,
                    color = AccentYellow,
                    modifier = Modifier.clickable { /* TODO: Show all */ }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Projects Grid - 2 columns
            Column(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                savedVideos.chunked(2).forEach { rowVideos ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        rowVideos.forEach { video ->
                            ProjectCard(
                                video = video,
                                onClick = { onVideoClick(video) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        // Fill empty space if odd number
                        if (rowVideos.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        } else {
            // Empty state for no recent projects
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.VideoLibrary,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.3f),
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "No projects yet",
                    style = AiezzyType.bodyMedium,
                    color = Color.White.copy(alpha = 0.5f)
                )
                Text(
                    text = "Create your first slideshow!",
                    style = AiezzyType.bodySmall,
                    color = Color.White.copy(alpha = 0.3f)
                )
            }
        }
    }
}

@Composable
private fun ProjectCard(
    video: SavedVideo,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = tween(100),
        label = "cardScale"
    )

    // Load video thumbnail
    var thumbnail by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(video.path) {
        thumbnail = loadVideoThumbnail(video.path)
    }

    Column(
        modifier = modifier
            .scale(scale)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    ) {
        // Thumbnail
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
                .clip(RoundedCornerShape(12.dp))
                .background(CardBackground),
            contentAlignment = Alignment.Center
        ) {
            if (thumbnail != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(thumbnail)
                        .crossfade(true)
                        .build(),
                    contentDescription = "Project thumbnail",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // Placeholder while loading
                Icon(
                    Icons.Default.VideoFile,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.3f),
                    modifier = Modifier.size(32.dp)
                )
            }

            // Duration badge
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp),
                shape = RoundedCornerShape(4.dp),
                color = Color.Black.copy(alpha = 0.7f)
            ) {
                Text(
                    text = formatDuration(video.duration),
                    style = AiezzyType.labelSmall,
                    color = Color.White,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Title and menu - show formatted date/time
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = formatVideoName(video.dateCreated),
                    style = AiezzyType.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Edited ${formatTimeAgo(video.dateCreated)}",
                    style = AiezzyType.labelSmall,
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 11.sp
                )
            }
            Icon(
                Icons.Default.MoreVert,
                contentDescription = "More options",
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun ImageSelectionLayout(
    images: List<ImageItem>,
    viewModel: SlideshowViewModel,
    audioConfig: AudioConfig?,
    onAddMoreImages: () -> Unit,
    onCreateVideo: () -> Unit,
    onClearAll: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showClearConfirm by remember { mutableStateOf(false) }

    // Audio picker launcher
    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { selectedUri ->
            scope.launch(Dispatchers.IO) {
                val cachedPath = copyAudioToCache(context, selectedUri)
                withContext(Dispatchers.Main) {
                    if (cachedPath != null) {
                        viewModel.setAudioFromFile(cachedPath, "Background music")
                    } else {
                        viewModel.setAudio(selectedUri, "Background music")
                    }
                }
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { showClearConfirm = true }) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Cancel",
                        tint = Color.White
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${images.size} selected",
                    style = AiezzyType.titleMedium,
                    color = Color.White
                )
            }
            IconButton(onClick = onSettingsClick) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = Color.White.copy(alpha = 0.7f)
                )
            }
        }

        // Image Grid
        val sortedImages = remember(images) { images.sortedBy { it.order } }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            itemsIndexed(
                items = sortedImages,
                key = { _, item -> item.id }
            ) { index, imageItem ->
                ImageGridItem(
                    imageItem = imageItem,
                    index = index + 1,
                    onRemove = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.removeImage(imageItem.id)
                    }
                )
            }

            item {
                AddMoreButton(onClick = onAddMoreImages)
            }
        }

        // Music Selection Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CardBackground)
                .clickable {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    audioPickerLauncher.launch("audio/*")
                }
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        color = if (audioConfig != null) AccentYellow.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.MusicNote,
                    contentDescription = "Music",
                    tint = if (audioConfig != null) AccentYellow else Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (audioConfig != null) "Background Music" else "Add Music",
                    style = AiezzyType.bodyLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = if (audioConfig != null) audioConfig.fileName else "Tap to select audio",
                    style = AiezzyType.bodySmall,
                    color = Color.White.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (audioConfig != null) {
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.clearAudio()
                    }
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Remove music",
                        tint = Color.White.copy(alpha = 0.6f)
                    )
                }
            } else {
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.4f)
                )
            }
        }

        // Bottom Create Button
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(DarkBackground)
                .padding(20.dp)
        ) {
            Button(
                onClick = onCreateVideo,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentYellow,
                    contentColor = Color.Black
                )
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Create Video",
                    style = AiezzyType.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }

    // Clear confirmation dialog
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear Selection?", color = Color.White) },
            text = { Text("Remove all ${images.size} selected images?", color = Color.White.copy(alpha = 0.7f)) },
            confirmButton = {
                TextButton(onClick = {
                    onClearAll()
                    showClearConfirm = false
                }) {
                    Text("Clear", color = Color.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("Cancel", color = AccentYellow)
                }
            },
            containerColor = CardBackground
        )
    }
}

@Composable
private fun ImageGridItem(
    imageItem: ImageItem,
    index: Int,
    onRemove: () -> Unit
) {
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(imageItem.uri)
                .size(300, 300)
                .crossfade(true)
                .build(),
            contentDescription = "Image $index",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Index badge
        Surface(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(4.dp),
            shape = RoundedCornerShape(4.dp),
            color = AccentYellow
        ) {
            Text(
                text = "$index",
                style = AiezzyType.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Color.Black,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }

        // Remove button
        IconButton(
            onClick = onRemove,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(32.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.6f),
                modifier = Modifier.size(24.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Remove",
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AddMoreButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .border(
                width = 2.dp,
                color = AccentYellow.copy(alpha = 0.5f),
                shape = RoundedCornerShape(8.dp)
            )
            .background(CardBackground)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.Add,
                contentDescription = null,
                tint = AccentYellow,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Add",
                style = AiezzyType.labelSmall,
                color = AccentYellow
            )
        }
    }
}

// ============================================
// HELPER FUNCTIONS
// ============================================

/**
 * Loads a thumbnail from a video file using MediaMetadataRetriever
 */
private fun loadVideoThumbnail(videoPath: String): Bitmap? {
    return try {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(videoPath)
        val bitmap = retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        retriever.release()
        bitmap
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

/**
 * Formats video name as "10:21 PM 16-Jan-26"
 */
private fun formatVideoName(timestamp: Long): String {
    val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
    val dateFormat = SimpleDateFormat("d-MMM-yy", Locale.getDefault())
    val date = Date(timestamp)
    return "${timeFormat.format(date)} ${dateFormat.format(date)}"
}

private fun loadSavedVideos(context: Context): List<SavedVideo> {
    val videos = mutableListOf<SavedVideo>()

    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DATA,
                MediaStore.Video.Media.DATE_ADDED,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.SIZE
            )

            val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
            val selectionArgs = arrayOf("%AiezzySlideshows%")
            val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)

                while (cursor.moveToNext()) {
                    videos.add(
                        SavedVideo(
                            id = cursor.getLong(idColumn),
                            name = cursor.getString(nameColumn),
                            path = cursor.getString(dataColumn),
                            dateCreated = cursor.getLong(dateColumn) * 1000,
                            duration = cursor.getLong(durationColumn),
                            size = cursor.getLong(sizeColumn)
                        )
                    )
                }
            }
        } else {
            val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            val appDir = File(moviesDir, "AiezzySlideshows")

            if (appDir.exists() && appDir.isDirectory) {
                appDir.listFiles()?.filter { it.extension.lowercase() == "mp4" }?.forEach { file ->
                    videos.add(
                        SavedVideo(
                            id = file.absolutePath.hashCode().toLong(),
                            name = file.name,
                            path = file.absolutePath,
                            dateCreated = file.lastModified(),
                            duration = 0,
                            size = file.length()
                        )
                    )
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    return videos.sortedByDescending { it.dateCreated }.take(6)
}

private fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0) return "0:00"
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

private fun formatTimeAgo(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60_000 -> "just now"
        diff < 3600_000 -> "${diff / 60_000}m ago"
        diff < 86400_000 -> "${diff / 3600_000}h ago"
        diff < 604800_000 -> "${diff / 86400_000}d ago"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp))
    }
}

/**
 * Copies audio file from content URI to app's cache directory.
 * This ensures the file remains accessible even after URI permissions expire.
 */
private suspend fun copyAudioToCache(context: Context, uri: Uri): String? {
    return withContext(Dispatchers.IO) {
        try {
            val audioDir = File(context.cacheDir, "audio")
            if (!audioDir.exists()) {
                audioDir.mkdirs()
            }

            // Clean up old audio files to save space
            audioDir.listFiles()?.forEach { it.delete() }

            // Get the MIME type to determine the correct extension
            val mimeType = context.contentResolver.getType(uri)
            Log.d("HomeScreen", "Audio URI: $uri, MIME type: $mimeType")

            val extension = when (mimeType) {
                "audio/mpeg", "audio/mp3" -> "mp3"
                "audio/mp4", "audio/m4a", "audio/x-m4a" -> "m4a"
                "audio/aac" -> "aac"
                "audio/wav", "audio/x-wav" -> "wav"
                "audio/ogg" -> "ogg"
                "audio/flac" -> "flac"
                else -> {
                    val path = uri.lastPathSegment ?: ""
                    val ext = path.substringAfterLast('.', "mp3")
                    if (ext.length <= 5) ext else "mp3"
                }
            }

            val destFile = File(audioDir, "selected_audio_${System.currentTimeMillis()}.$extension")
            Log.d("HomeScreen", "Copying audio to: ${destFile.absolutePath}")

            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                destFile.outputStream().use { outputStream ->
                    val bytesCopied = inputStream.copyTo(outputStream)
                    Log.d("HomeScreen", "Copied $bytesCopied bytes")
                }
            }

            if (destFile.exists() && destFile.length() > 0) {
                Log.d("HomeScreen", "Audio copied successfully: ${destFile.absolutePath}")
                destFile.absolutePath
            } else {
                Log.e("HomeScreen", "Audio copy failed - file empty or not created")
                null
            }
        } catch (e: Exception) {
            Log.e("HomeScreen", "Error copying audio to cache", e)
            null
        }
    }
}
