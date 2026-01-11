package com.aiezzy.slideshowmaker.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aiezzy.slideshowmaker.data.face.entities.PersonWithFace
import com.aiezzy.slideshowmaker.ui.components.FaceThumbnail
import com.aiezzy.slideshowmaker.viewmodel.PeopleViewModel

// Theme colors
private val AccentYellow = Color(0xFFE5FF00)
private val DarkBackground = Color(0xFF1A1A1A)
private val CardBackground = Color(0xFF2A2A2A)
private val SkeletonBase = Color(0xFF3A3A3A)
private val SkeletonHighlight = Color(0xFF4A4A4A)

/**
 * People grid screen showing all detected faces/persons
 * Features:
 * - Skeleton loading states
 * - Pull-to-refresh
 * - Error states with retry
 * - Empty states
 * - Merge mode selection
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleScreen(
    viewModel: PeopleViewModel,
    onNavigateBack: () -> Unit,
    onPersonClick: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val scanState by viewModel.scanState.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val isScanRunning by viewModel.isScanRunning.collectAsState()
    val scanPercentage by viewModel.scanPercentage.collectAsState()
    val mergeSelectionMode by viewModel.mergeSelectionMode.collectAsState()
    val selectedForMerge by viewModel.selectedForMerge.collectAsState()
    val undoAction by viewModel.undoAction.collectAsState()

    // Collect messages
    val snackbarHostState = remember { SnackbarHostState() }
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
    val persons = when (val state = uiState) {
        is PeopleViewModel.PeopleUiState.Success -> state.persons
        else -> emptyList()
    }

    // Check if loading
    val isLoading = uiState is PeopleViewModel.PeopleUiState.Loading

    // Check for error
    val errorState = uiState as? PeopleViewModel.PeopleUiState.Error

    // Extract scan progress info from scan state
    val scanProgressInfo = when (val state = scanState) {
        is PeopleViewModel.ScanState.Scanning -> state
        else -> null
    }

    var showRenameDialog by remember { mutableStateOf<PersonWithFace?>(null) }
    var showMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (mergeSelectionMode) "${selectedForMerge.size} selected" else "People and pets",
                        color = Color.White
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (mergeSelectionMode) {
                            viewModel.exitMergeMode()
                        } else {
                            onNavigateBack()
                        }
                    }) {
                        Icon(
                            imageVector = if (mergeSelectionMode) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = if (mergeSelectionMode) "Cancel" else "Back",
                            tint = Color.White
                        )
                    }
                },
                actions = {
                    if (!mergeSelectionMode) {
                        // Menu button
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "More options",
                                    tint = Color.White
                                )
                            }

                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                                modifier = Modifier.background(CardBackground)
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Merge people", color = Color.White) },
                                    onClick = {
                                        showMenu = false
                                        viewModel.enterMergeMode()
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Group,
                                            contentDescription = null,
                                            tint = Color.White
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Rescan gallery", color = Color.White) },
                                    onClick = {
                                        showMenu = false
                                        viewModel.resetAndRescan()
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Refresh,
                                            contentDescription = null,
                                            tint = Color.White
                                        )
                                    }
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkBackground
                )
            )
        },
        bottomBar = {
            // Merge bottom bar
            AnimatedVisibility(
                visible = mergeSelectionMode && selectedForMerge.size >= 2,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = DarkBackground,
                    shadowElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${selectedForMerge.size} people selected",
                            color = Color.White
                        )
                        Button(
                            onClick = { viewModel.mergeSelectedPersons() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AccentYellow,
                                contentColor = Color.Black
                            ),
                            shape = RoundedCornerShape(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Group,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Merge All", fontWeight = FontWeight.SemiBold)
                        }
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Scanning progress indicator
                AnimatedVisibility(
                    visible = isScanRunning,
                    enter = slideInVertically() + fadeIn(),
                    exit = slideOutVertically() + fadeOut()
                ) {
                    ScanProgressCard(
                        scanProgressInfo = scanProgressInfo,
                        scanPercentage = scanPercentage,
                        isPaused = scanState is PeopleViewModel.ScanState.Paused,
                        onPause = { viewModel.pauseScan() },
                        onResume = { viewModel.resumeScan() },
                        onCancel = { viewModel.cancelScan() }
                    )
                }

                // Content based on state
                when {
                    isLoading -> {
                        // Skeleton loading
                        SkeletonPeopleGrid()
                    }
                    errorState != null -> {
                        // Error state
                        ErrorContent(
                            message = errorState.message,
                            canRetry = errorState.canRetry,
                            onRetry = { viewModel.retry() }
                        )
                    }
                    persons.isEmpty() && !isScanRunning -> {
                        // Empty state
                        EmptyContent(
                            onStartScan = { viewModel.startGalleryScan() }
                        )
                    }
                    else -> {
                        // People grid with stable keys for smooth updates
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            contentPadding = PaddingValues(vertical = 16.dp)
                        ) {
                            gridItems(
                                items = persons,
                                key = { it.personId },
                                contentType = { "person" }
                            ) { person ->
                                // Stable key ensures smooth updates without flickering
                                PersonGridItem(
                                    person = person,
                                    isSelected = person.personId in selectedForMerge,
                                    isMergeMode = mergeSelectionMode,
                                    onClick = {
                                        if (mergeSelectionMode) {
                                            viewModel.toggleMergeSelection(person.personId)
                                        } else {
                                            onPersonClick(person.personId)
                                        }
                                    },
                                    onLongClick = {
                                        if (!mergeSelectionMode) {
                                            viewModel.enterMergeMode()
                                            viewModel.toggleMergeSelection(person.personId)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Edit person dialog (name + birthday)
    showRenameDialog?.let { person ->
        RenamePersonDialog(
            person = person,
            onDismiss = { showRenameDialog = null },
            onConfirm = { newName, birthday ->
                viewModel.updatePersonProfile(person.personId, newName, birthday)
                showRenameDialog = null
            },
            onHide = {
                viewModel.hidePerson(person.personId)
                showRenameDialog = null
            }
        )
    }
}

/**
 * Scan progress card with pause/resume controls
 */
@Composable
private fun ScanProgressCard(
    scanProgressInfo: PeopleViewModel.ScanState.Scanning?,
    scanPercentage: Float,
    isPaused: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBackground)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isPaused) "Scan paused" else "Scanning photos...",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White
            )

            Row {
                scanProgressInfo?.let { progress ->
                    Text(
                        text = "${progress.scannedCount}/${progress.totalCount}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                // Pause/Resume button
                IconButton(
                    onClick = if (isPaused) onResume else onPause,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = if (isPaused) "Resume" else "Pause",
                        tint = AccentYellow,
                        modifier = Modifier.size(20.dp)
                    )
                }
                // Cancel button
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancel",
                        tint = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        LinearProgressIndicator(
            progress = { scanPercentage },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = if (isPaused) Color.White.copy(alpha = 0.5f) else AccentYellow,
            trackColor = Color.White.copy(alpha = 0.2f)
        )

        scanProgressInfo?.let { progress ->
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${progress.facesFound} faces found",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.6f)
            )
        }
    }
}

/**
 * Skeleton loading grid for people
 */
@Composable
private fun SkeletonPeopleGrid() {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        userScrollEnabled = false
    ) {
        // Show 12 skeleton items
        gridItems((1..12).toList()) {
            SkeletonPersonItem()
        }
    }
}

/**
 * Individual skeleton item for a person
 */
@Composable
private fun SkeletonPersonItem() {
    val infiniteTransition = rememberInfiniteTransition(label = "skeleton")
    val shimmerTranslate by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer"
    )

    val shimmerBrush = Brush.linearGradient(
        colors = listOf(
            SkeletonBase,
            SkeletonHighlight,
            SkeletonBase
        ),
        start = Offset(shimmerTranslate - 500f, 0f),
        end = Offset(shimmerTranslate, 0f)
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Circular face placeholder
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(RoundedCornerShape(40.dp))
                .background(shimmerBrush)
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Name placeholder
        Box(
            modifier = Modifier
                .width(60.dp)
                .height(12.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(shimmerBrush)
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Photo count placeholder
        Box(
            modifier = Modifier
                .width(40.dp)
                .height(10.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(shimmerBrush)
        )
    }
}

/**
 * Empty state content
 */
@Composable
private fun EmptyContent(onStartScan: () -> Unit) {
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
                imageVector = Icons.Default.Face,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.3f),
                modifier = Modifier.size(64.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "No faces found yet",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Tap the button below to scan your photo gallery for faces",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.5f),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onStartScan,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentYellow,
                    contentColor = Color.Black
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Scan Gallery")
            }
        }
    }
}

/**
 * Error state content
 */
@Composable
private fun ErrorContent(
    message: String,
    canRetry: Boolean,
    onRetry: () -> Unit
) {
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
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = Color.Red.copy(alpha = 0.7f),
                modifier = Modifier.size(64.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Something went wrong",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.5f),
                textAlign = TextAlign.Center
            )

            if (canRetry) {
                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentYellow,
                        contentColor = Color.Black
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Try Again")
                }
            }
        }
    }
}

/**
 * Grid item for a person with selection support for merge mode
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PersonGridItem(
    person: PersonWithFace,
    isSelected: Boolean,
    isMergeMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val selectionColor = Color(0xFF2196F3) // Blue for selection

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box {
            // Face thumbnail with selection border
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .then(
                        if (isSelected) {
                            Modifier
                                .clip(RoundedCornerShape(44.dp))
                                .background(selectionColor)
                                .padding(3.dp)
                        } else {
                            Modifier
                        }
                    )
            ) {
                FaceThumbnail(
                    person = person,
                    size = if (isSelected) 82.dp else 80.dp,
                    showName = false,
                    onClick = onClick
                )
            }

            // Selection checkmark badge
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(24.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(selectionColor),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Selected",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Person name
        Text(
            text = person.getDisplayName(),
            style = MaterialTheme.typography.bodySmall,
            color = Color.White,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )

        // Photo count badge
        Text(
            text = "${person.photoCount} photos",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.5f)
        )
    }
}

/**
 * Dialog for editing a person's profile (name, birthday)
 */
@Composable
private fun RenamePersonDialog(
    person: PersonWithFace,
    onDismiss: () -> Unit,
    onConfirm: (name: String, birthday: String?) -> Unit,
    onHide: () -> Unit
) {
    var name by remember { mutableStateOf(person.name ?: "") }
    var birthday by remember { mutableStateOf(person.birthday ?: "") }
    var showDatePicker by remember { mutableStateOf(false) }

    // Parse existing birthday for display
    val birthdayDisplay = remember(birthday) {
        if (birthday.isNotBlank() && birthday.contains("-")) {
            try {
                val parts = birthday.split("-")
                val month = parts[0].toInt()
                val day = parts[1].toInt()
                val monthNames = listOf("", "Jan", "Feb", "Mar", "Apr", "May", "Jun",
                    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
                "${monthNames[month]} $day"
            } catch (e: Exception) {
                ""
            }
        } else ""
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardBackground,
        title = {
            Text(
                text = "Edit person",
                color = Color.White
            )
        },
        text = {
            Column {
                // Name field
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name", color = Color.White.copy(alpha = 0.7f)) },
                    placeholder = { Text(person.getDisplayName(), color = Color.White.copy(alpha = 0.4f)) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = AccentYellow,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                        cursorColor = AccentYellow
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Birthday selector
                Text(
                    text = "Birthday",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { showDatePicker = true },
                        color = Color.White.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Cake,
                                contentDescription = null,
                                tint = AccentYellow,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (birthdayDisplay.isNotBlank()) birthdayDisplay else "Select birthday",
                                color = if (birthdayDisplay.isNotBlank()) Color.White else Color.White.copy(alpha = 0.5f),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    if (birthday.isNotBlank()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = { birthday = "" }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear birthday",
                                tint = Color.White.copy(alpha = 0.6f)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "We'll notify you on their birthday!",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.5f)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Hide button
                TextButton(
                    onClick = onHide,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = Color.Red.copy(alpha = 0.8f)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.VisibilityOff,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Hide this person")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, birthday.ifBlank { null }) },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = AccentYellow
                )
            ) {
                Text("Save")
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

    // Date picker dialog
    if (showDatePicker) {
        BirthdayPickerDialog(
            initialBirthday = birthday,
            onDismiss = { showDatePicker = false },
            onConfirm = { selectedBirthday ->
                birthday = selectedBirthday
                showDatePicker = false
            }
        )
    }
}

/**
 * Simple birthday picker dialog (month and day only)
 */
@Composable
private fun BirthdayPickerDialog(
    initialBirthday: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val months = listOf("January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December")

    // Parse initial values
    val initialMonth = if (initialBirthday.contains("-")) {
        try { initialBirthday.split("-")[0].toInt() - 1 } catch (e: Exception) { 0 }
    } else 0

    val initialDay = if (initialBirthday.contains("-")) {
        try { initialBirthday.split("-")[1].toInt() } catch (e: Exception) { 1 }
    } else 1

    var selectedMonth by remember { mutableIntStateOf(initialMonth) }
    var selectedDay by remember { mutableIntStateOf(initialDay) }

    // Days in each month
    val daysInMonth = when (selectedMonth) {
        1 -> 29 // Feb - allow 29 for leap years
        3, 5, 8, 10 -> 30 // Apr, Jun, Sep, Nov
        else -> 31
    }

    // Adjust day if it exceeds days in selected month
    LaunchedEffect(selectedMonth) {
        if (selectedDay > daysInMonth) {
            selectedDay = daysInMonth
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardBackground,
        title = {
            Text(
                text = "Select Birthday",
                color = Color.White
            )
        },
        text = {
            Column {
                // Month selector
                Text(
                    text = "Month",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(8.dp))

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(months) { index, month ->
                        FilterChip(
                            selected = selectedMonth == index,
                            onClick = { selectedMonth = index },
                            label = { Text(month.take(3)) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AccentYellow,
                                selectedLabelColor = Color.Black,
                                containerColor = Color.White.copy(alpha = 0.1f),
                                labelColor = Color.White
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Day selector
                Text(
                    text = "Day",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(8.dp))

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(daysInMonth) { dayIndex ->
                        val day = dayIndex + 1
                        FilterChip(
                            selected = selectedDay == day,
                            onClick = { selectedDay = day },
                            label = { Text("$day") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AccentYellow,
                                selectedLabelColor = Color.Black,
                                containerColor = Color.White.copy(alpha = 0.1f),
                                labelColor = Color.White
                            )
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // Format as MM-DD
                    val birthdayStr = String.format("%02d-%02d", selectedMonth + 1, selectedDay)
                    onConfirm(birthdayStr)
                },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = AccentYellow
                )
            ) {
                Text("Confirm")
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
