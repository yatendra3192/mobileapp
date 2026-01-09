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
    val context = LocalContext.current
    val currentPerson by viewModel.currentPerson.collectAsState()
    val selectedPersonIds by viewModel.selectedPersonIds.collectAsState()
    val filteredPhotos by viewModel.filteredPhotos.collectAsState()
    val isLoadingPhotos by viewModel.isLoadingPhotos.collectAsState()
    val allPersons by viewModel.persons.collectAsState()

    // Selected photos for slideshow
    var selectedPhotos by remember { mutableStateOf<Set<Uri>>(emptySet()) }
    var isSelectionMode by remember { mutableStateOf(false) }

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
                            imageVector = if (isSelectionMode) Icons.Default.Close else Icons.Default.ArrowBack,
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
                        text = person.name ?: "Unknown",
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
                                    // Long press to start selection
                                    isSelectionMode = true
                                    selectedPhotos = setOf(photoUri)
                                }
                            },
                            onLongClick = {
                                isSelectionMode = true
                                selectedPhotos = if (photoUri in selectedPhotos) {
                                    selectedPhotos - photoUri
                                } else {
                                    selectedPhotos + photoUri
                                }
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
 * Photo grid item with selection state
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PhotoGridItem(
    uri: Uri,
    isSelected: Boolean,
    isSelectionMode: Boolean,
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
