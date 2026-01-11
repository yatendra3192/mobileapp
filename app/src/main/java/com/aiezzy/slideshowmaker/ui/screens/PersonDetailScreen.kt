package com.aiezzy.slideshowmaker.ui.screens

import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.aiezzy.slideshowmaker.data.face.entities.PersonWithFace
import com.aiezzy.slideshowmaker.ui.components.PersonFilterChip
import com.aiezzy.slideshowmaker.ui.components.SmallFaceThumbnail
import com.aiezzy.slideshowmaker.viewmodel.PeopleViewModel

// Theme colors
private val AccentYellow = Color(0xFFE5FF00)
private val DarkBackground = Color(0xFF1A1A1A)
private val CardBackground = Color(0xFF2A2A2A)

/**
 * Screen showing photos filtered by selected person(s)
 * Similar to Google Photos person detail view
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonDetailScreen(
    viewModel: PeopleViewModel,
    personId: String,
    onNavigateBack: () -> Unit,
    onCreateSlideshow: (List<Uri>) -> Unit
) {
    val currentPerson by viewModel.currentPerson.collectAsState()
    val selectedPersonIds by viewModel.selectedPersonIds.collectAsState()
    val filteredPhotos by viewModel.filteredPhotos.collectAsState()
    val isLoadingPhotos by viewModel.isLoadingPhotos.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val undoAction by viewModel.undoAction.collectAsState()
    val onlyThemMode by viewModel.onlyThemMode.collectAsState()

    // Snackbar host state
    val snackbarHostState = remember { SnackbarHostState() }

    // Collect error/success messages
    LaunchedEffect(Unit) {
        viewModel.errorMessage.collect { message ->
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
        }
    }
    LaunchedEffect(Unit) {
        viewModel.successMessage.collect { message ->
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
        }
    }

    // Extract persons from UI state
    val allPersons = when (val state = uiState) {
        is PeopleViewModel.PeopleUiState.Success -> state.persons
        else -> emptyList()
    }

    // Selected photos for slideshow
    var selectedPhotos by remember { mutableStateOf<Set<Uri>>(emptySet()) }
    var isSelectionMode by remember { mutableStateOf(false) }

    // Remove from group dialog state
    var photoToRemove by remember { mutableStateOf<Uri?>(null) }

    // Load person on first composition
    LaunchedEffect(personId) {
        viewModel.loadPerson(personId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isSelectionMode) {
                        Text(
                            "${selectedPhotos.size} selected",
                            color = Color.White
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isSelectionMode) {
                            isSelectionMode = false
                            selectedPhotos = emptySet()
                        } else {
                            viewModel.clearSelection()
                            onNavigateBack()
                        }
                    }) {
                        Icon(
                            imageVector = if (isSelectionMode) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                actions = {
                    if (isSelectionMode && selectedPhotos.isNotEmpty()) {
                        IconButton(onClick = {
                            // Select all
                            selectedPhotos = filteredPhotos.toSet()
                        }) {
                            Icon(
                                imageVector = Icons.Default.SelectAll,
                                contentDescription = "Select all",
                                tint = Color.White
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkBackground
                )
            )
        },
        bottomBar = {
            // Create slideshow button
            AnimatedVisibility(
                visible = isSelectionMode && selectedPhotos.isNotEmpty(),
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = DarkBackground,
                    shadowElevation = 8.dp
                ) {
                    Button(
                        onClick = { onCreateSlideshow(selectedPhotos.toList()) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentYellow,
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Movie,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Create Slideshow (${selectedPhotos.size})",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // Default bottom bar when not in selection mode
            AnimatedVisibility(
                visible = !isSelectionMode && filteredPhotos.isNotEmpty(),
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = DarkBackground,
                    shadowElevation = 8.dp
                ) {
                    Button(
                        onClick = {
                            isSelectionMode = true
                            selectedPhotos = filteredPhotos.toSet()  // Select all by default
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentYellow,
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Movie,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Create Slideshow",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                // Undo snackbar
                undoAction?.let { action ->
                    Snackbar(
                        modifier = Modifier.padding(16.dp),
                        action = {
                            TextButton(onClick = { viewModel.executeUndo() }) {
                                Text("Undo", color = AccentYellow)
                            }
                        },
                        dismissAction = {
                            IconButton(onClick = { viewModel.dismissUndo() }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Dismiss",
                                    tint = Color.White
                                )
                            }
                        },
                        containerColor = CardBackground,
                        contentColor = Color.White
                    ) {
                        Text(action.message)
                    }
                } ?: Snackbar(
                    snackbarData = data,
                    containerColor = CardBackground,
                    contentColor = Color.White,
                    actionColor = AccentYellow
                )
            }
        },
        containerColor = DarkBackground
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Person filter chips row
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Currently selected person chips
                items(selectedPersonIds.toList()) { selectedId ->
                    val person = allPersons.find { it.personId == selectedId }
                    person?.let {
                        PersonFilterChip(
                            person = it,
                            isSelected = true,
                            onToggle = { viewModel.deselectPerson(selectedId) },
                            onRemove = { viewModel.deselectPerson(selectedId) }
                        )
                    }
                }

                // "Only them" filter toggle chip
                currentPerson?.let { person ->
                    item {
                        OnlyThemFilterChip(
                            personName = person.getDisplayName(),
                            isEnabled = onlyThemMode,
                            onClick = { viewModel.toggleOnlyThemMode() }
                        )
                    }
                }

                // Add more people chips (unselected)
                items(allPersons.filter { it.personId !in selectedPersonIds }.take(5)) { person ->
                    AddPersonChip(
                        person = person,
                        onClick = { viewModel.selectPerson(person.personId) }
                    )
                }
            }

            // Photo count and person name
            currentPerson?.let { person ->
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = person.getDisplayName(),
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${filteredPhotos.size} photos",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                }
            }

            // Loading indicator
            if (isLoadingPhotos) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = AccentYellow)
                }
            }

            // Photo grid
            if (!isLoadingPhotos && filteredPhotos.isNotEmpty()) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(filteredPhotos, key = { it.toString() }) { photoUri ->
                        PhotoGridItem(
                            uri = photoUri,
                            isSelected = photoUri in selectedPhotos,
                            isSelectionMode = isSelectionMode,
                            onClick = {
                                if (isSelectionMode) {
                                    selectedPhotos = if (photoUri in selectedPhotos) {
                                        selectedPhotos - photoUri
                                    } else {
                                        selectedPhotos + photoUri
                                    }
                                } else {
                                    // Tap to start selection
                                    isSelectionMode = true
                                    selectedPhotos = setOf(photoUri)
                                }
                            },
                            onLongClick = {
                                // Long press shows remove from group option
                                photoToRemove = photoUri
                            }
                        )
                    }
                }
            }

            // Empty state
            if (!isLoadingPhotos && filteredPhotos.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhotoLibrary,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.3f),
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No photos found",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }

    // Remove from group dialog
    photoToRemove?.let { photo ->
        RemoveFromGroupDialog(
            photoUri = photo,
            personName = currentPerson?.getDisplayName() ?: "this person",
            onDismiss = { photoToRemove = null },
            onConfirm = {
                viewModel.removePhotoFromPerson(photo, personId)
                photoToRemove = null
            }
        )
    }
}

/**
 * Chip for adding another person to filter
 */
@Composable
private fun AddPersonChip(
    person: PersonWithFace,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onClick),
        color = Color.Transparent,
        shape = RoundedCornerShape(24.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SmallFaceThumbnail(person = person, size = 24.dp)
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Add ${person.name ?: "person"}",
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * Toggle chip for "Only them" filter mode
 * Shows photos where only the selected person appears (no other people in photo)
 */
@Composable
private fun OnlyThemFilterChip(
    personName: String,
    isEnabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onClick),
        color = if (isEnabled) AccentYellow else Color.Transparent,
        shape = RoundedCornerShape(24.dp),
        border = if (isEnabled) null else androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isEnabled) Icons.Default.Person else Icons.Default.PersonOutline,
                contentDescription = null,
                tint = if (isEnabled) Color.Black else Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (isEnabled) "Only $personName" else "Only them",
                style = MaterialTheme.typography.bodySmall,
                color = if (isEnabled) Color.Black else Color.White.copy(alpha = 0.7f),
                fontWeight = if (isEnabled) FontWeight.SemiBold else FontWeight.Normal
            )
        }
    }
}

/**
 * Photo grid item with selection state and optional quality indicator.
 *
 * @param uri The photo URI
 * @param isSelected Whether the photo is selected
 * @param isSelectionMode Whether selection mode is active
 * @param isLowQuality Whether the face in this photo is low quality (DISPLAY_ONLY tier)
 *                     NOTE: To enable this, modify FaceRepository.getPhotosForPerson() to return
 *                     PhotoWithQuality instead of String, and update PeopleViewModel accordingly.
 * @param onClick Callback when photo is tapped
 * @param onLongClick Callback when photo is long-pressed
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PhotoGridItem(
    uri: Uri,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    isLowQuality: Boolean = false,  // TODO: Pass actual quality from data layer
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(2.dp))
            .then(
                if (isSelected) {
                    Modifier.border(3.dp, AccentYellow, RoundedCornerShape(2.dp))
                } else {
                    Modifier
                }
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(uri)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .crossfade(true)
                .build(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // Low quality face indicator badge
        if (isLowQuality && !isSelectionMode) {
            LowQualityBadge(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
            )
        }

        // Selection checkbox
        if (isSelectionMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(24.dp)
                    .background(
                        if (isSelected) AccentYellow else Color.Black.copy(alpha = 0.5f),
                        CircleShape
                    )
                    .border(2.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Selected",
                        tint = Color.Black,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

/**
 * Badge indicating a low-quality face detection.
 * Shows when the face in the photo was detected with DISPLAY_ONLY quality tier
 * (blurry, poor lighting, extreme pose, etc.)
 */
@Composable
private fun LowQualityBadge(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = Color.Black.copy(alpha = 0.7f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.BlurOn,
                contentDescription = "Low quality",
                tint = Color.Yellow.copy(alpha = 0.9f),
                modifier = Modifier.size(12.dp)
            )
            Spacer(modifier = Modifier.width(2.dp))
            Text(
                text = "LQ",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.9f)
            )
        }
    }
}

/**
 * Dialog for removing a photo from a person's group
 */
@Composable
private fun RemoveFromGroupDialog(
    photoUri: Uri,
    personName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardBackground,
        title = {
            Text(
                text = "Remove from group?",
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column {
                // Photo preview
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .clip(RoundedCornerShape(8.dp))
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(photoUri)
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "This photo will be removed from \"$personName\" group. The photo won't be deleted from your device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = Color.Red.copy(alpha = 0.9f)
                )
            ) {
                Text("Remove", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = Color.White.copy(alpha = 0.7f)
                )
            ) {
                Text("Cancel")
            }
        }
    )
}
