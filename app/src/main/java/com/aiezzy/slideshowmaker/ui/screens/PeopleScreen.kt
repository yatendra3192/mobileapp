package com.aiezzy.slideshowmaker.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

/**
 * People grid screen showing all detected faces/persons
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleScreen(
    viewModel: PeopleViewModel,
    onNavigateBack: () -> Unit,
    onPersonClick: (String) -> Unit
) {
    val persons by viewModel.persons.collectAsState()
    val scanProgress by viewModel.scanProgress.collectAsState()
    val isScanRunning by viewModel.isScanRunning.collectAsState()
    val scanPercentage by viewModel.scanPercentage.collectAsState()

    var showRenameDialog by remember { mutableStateOf<PersonWithFace?>(null) }
    var showMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "People and pets",
                        color = Color.White
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                actions = {
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
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkBackground
                )
            )
        },
        containerColor = DarkBackground
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Scanning progress indicator
            AnimatedVisibility(
                visible = isScanRunning,
                enter = slideInVertically() + fadeIn(),
                exit = slideOutVertically() + fadeOut()
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
                            text = "Scanning photos...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White
                        )

                        scanProgress?.let { progress ->
                            Text(
                                text = "${progress.scannedPhotos}/${progress.totalPhotos}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    LinearProgressIndicator(
                        progress = { scanPercentage },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = AccentYellow,
                        trackColor = Color.White.copy(alpha = 0.2f)
                    )

                    scanProgress?.let { progress ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${progress.facesDetected} faces found",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.6f)
                        )
                    }
                }
            }

            if (persons.isEmpty() && !isScanRunning) {
                // Empty state
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
                            onClick = { viewModel.startGalleryScan() },
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
            } else {
                // People grid
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(vertical = 16.dp)
                ) {
                    items(persons, key = { it.personId }) { person ->
                        PersonGridItem(
                            person = person,
                            onClick = { onPersonClick(person.personId) },
                            onLongClick = { showRenameDialog = person }
                        )
                    }
                }
            }
        }
    }

    // Rename dialog
    showRenameDialog?.let { person ->
        RenamePersonDialog(
            person = person,
            onDismiss = { showRenameDialog = null },
            onConfirm = { newName ->
                viewModel.updatePersonName(person.personId, newName)
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
 * Grid item for a person
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PersonGridItem(
    person: PersonWithFace,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        FaceThumbnail(
            person = person,
            size = 80.dp,
            showName = true,
            onClick = onClick
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
 * Dialog for renaming/hiding a person
 */
@Composable
private fun RenamePersonDialog(
    person: PersonWithFace,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    onHide: () -> Unit
) {
    var name by remember { mutableStateOf(person.name ?: "") }

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
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name", color = Color.White.copy(alpha = 0.7f)) },
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
                onClick = { onConfirm(name) },
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
}
