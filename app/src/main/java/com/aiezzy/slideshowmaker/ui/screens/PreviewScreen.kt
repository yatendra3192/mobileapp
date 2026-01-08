package com.aiezzy.slideshowmaker.ui.screens

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.aiezzy.slideshowmaker.ui.components.*
import com.aiezzy.slideshowmaker.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

private const val TAG = "PreviewScreen"

// Theme colors matching other screens
private val AccentYellow = Color(0xFFE5FF00)
private val DarkBackground = Color(0xFF1A1A1A)
private val CardBackground = Color(0xFF2A2A2A)

/**
 * Validates that the given path is within the app's allowed directories.
 * Prevents path traversal attacks.
 */
private fun isValidVideoPath(context: Context, path: String): Boolean {
    val file = File(path)

    // Check if file exists first
    if (!file.exists()) {
        Log.w(TAG, "Video file does not exist: $path")
        return false
    }

    val canonicalPath = try {
        file.canonicalPath
    } catch (e: Exception) {
        Log.w(TAG, "Failed to get canonical path", e)
        return false
    }

    // Allow paths within app's cache directory, external files directory, or Movies folder
    val allowedDirs = mutableListOf<String>()

    // App directories
    context.cacheDir.canonicalPath.let { allowedDirs.add(it) }
    context.filesDir.canonicalPath.let { allowedDirs.add(it) }
    context.getExternalFilesDir(null)?.canonicalPath?.let { allowedDirs.add(it) }

    // Movies directory (where saved videos go)
    try {
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            ?.canonicalPath?.let { allowedDirs.add(it) }
    } catch (e: Exception) {
        Log.w(TAG, "Failed to get Movies directory", e)
    }

    // Also allow /storage/emulated paths for saved videos
    if (canonicalPath.contains("/Movies/AiezzySlideshows/")) {
        return true
    }

    return allowedDirs.any { canonicalPath.startsWith(it) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(
    videoPath: String,
    onNavigateBack: () -> Unit,
    onCreateNew: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    var isSaving by remember { mutableStateOf(false) }
    var saveSuccess by remember { mutableStateOf<Boolean?>(null) }
    var pathError by remember { mutableStateOf<String?>(null) }

    // Validate path on first composition
    LaunchedEffect(videoPath) {
        if (!isValidVideoPath(context, videoPath)) {
            Log.e(TAG, "Invalid video path detected: path validation failed")
            pathError = "Invalid video path"
        }
    }

    // ExoPlayer setup with path validation
    val exoPlayer = remember(videoPath) {
        if (isValidVideoPath(context, videoPath)) {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(Uri.fromFile(File(videoPath))))
                prepare()
                playWhenReady = true
                repeatMode = Player.REPEAT_MODE_ALL
            }
        } else {
            null
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer?.release()
        }
    }

    // Show error state if path is invalid
    if (pathError != null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            "Error",
                            style = AiezzyType.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onNavigateBack()
                            },
                            modifier = Modifier.size(AiezzyDimens.Touch.comfortable)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Close and go back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        modifier = Modifier.size(120.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Error,
                                contentDescription = null,
                                modifier = Modifier.size(AiezzyDimens.Icon.sizeDisplay),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(AiezzySpacing.base))
                    Text(
                        "Failed to load video",
                        style = AiezzyType.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = AiezzyTheme.colors.textPrimary
                    )
                    Spacer(modifier = Modifier.height(AiezzySpacing.sm))
                    PremiumButton(
                        text = "Go Back",
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onNavigateBack()
                        },
                        variant = ButtonVariant.Primary
                    )
                }
            }
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Preview",
                        style = AiezzyType.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onNavigateBack()
                        },
                        modifier = Modifier.size(AiezzyDimens.Touch.comfortable)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close preview and go back",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkBackground,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
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
            // Video player
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.Black)
                    .semantics { contentDescription = "Video preview player" },
                contentAlignment = Alignment.Center
            ) {
                exoPlayer?.let { player ->
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                this.player = player
                                useController = true
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // Action buttons - Dark theme matching other screens
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = DarkBackground
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Success/error message
                    AnimatedVisibility(visible = saveSuccess != null) {
                        saveSuccess?.let { success ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = AiezzyShapes.card,
                                color = if (success) Color(0xFF1B3D1B) else Color(0xFF3D1B1B)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        if (success) Icons.Default.CheckCircle else Icons.Default.Error,
                                        contentDescription = null,
                                        tint = if (success) AccentYellow else Color(0xFFFF6B6B)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        if (success) "Video saved to gallery!"
                                        else "Failed to save video",
                                        style = AiezzyType.bodyMedium,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }

                    // Save button - Yellow accent
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            scope.launch {
                                isSaving = true
                                saveSuccess = saveVideoToGallery(context, videoPath)
                                isSaving = false
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .semantics { contentDescription = "Save video to gallery" },
                        enabled = !isSaving,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentYellow,
                            contentColor = Color.Black
                        )
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = Color.Black,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Saving...",
                                style = AiezzyType.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        } else {
                            Icon(
                                Icons.Default.Download,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Save to Gallery",
                                style = AiezzyType.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    // Create new button - Outline style
                    OutlinedButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onCreateNew()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .semantics { contentDescription = "Create a new slideshow video" },
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = AccentYellow
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, AccentYellow.copy(alpha = 0.5f))
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Create New Slideshow",
                            style = AiezzyType.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

private suspend fun saveVideoToGallery(context: Context, videoPath: String): Boolean {
    return withContext(Dispatchers.IO) {
        try {
            val sourceFile = File(videoPath)
            if (!sourceFile.exists()) {
                Log.e(TAG, "Source video file does not exist: $videoPath")
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Video file not found", Toast.LENGTH_SHORT).show()
                }
                return@withContext false
            }

            Log.d(TAG, "Saving video from: $videoPath, size: ${sourceFile.length()} bytes")

            val fileName = "Slideshow_${System.currentTimeMillis()}.mp4"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Use MediaStore for Android 10+
                val contentValues = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/AiezzySlideshows")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }

                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)

                if (uri == null) {
                    Log.e(TAG, "Failed to create MediaStore entry")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Failed to access storage", Toast.LENGTH_SHORT).show()
                    }
                    return@withContext false
                }

                var bytesWritten = 0L
                resolver.openOutputStream(uri)?.use { outputStream ->
                    FileInputStream(sourceFile).use { inputStream ->
                        bytesWritten = inputStream.copyTo(outputStream)
                    }
                } ?: run {
                    Log.e(TAG, "Failed to open output stream")
                    resolver.delete(uri, null, null)
                    return@withContext false
                }

                Log.d(TAG, "Wrote $bytesWritten bytes to MediaStore")

                contentValues.clear()
                contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Saved to Movies/AiezzySlideshows/$fileName", Toast.LENGTH_LONG).show()
                }

                Log.d(TAG, "Video saved successfully to MediaStore: $fileName")
                true
            } else {
                // Legacy approach for older Android versions
                val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                val appDir = File(moviesDir, "AiezzySlideshows")
                if (!appDir.exists()) {
                    val created = appDir.mkdirs()
                    Log.d(TAG, "Created directory: $appDir, success: $created")
                }

                val destFile = File(appDir, fileName)
                sourceFile.copyTo(destFile, overwrite = true)

                Log.d(TAG, "Video copied to: ${destFile.absolutePath}")

                // Notify media scanner using non-deprecated API
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(destFile.absolutePath),
                    arrayOf("video/mp4")
                ) { path, scannedUri ->
                    Log.d(TAG, "Media scanner completed: $path -> $scannedUri")
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Saved to ${destFile.absolutePath}", Toast.LENGTH_LONG).show()
                }

                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save video to gallery", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
            false
        }
    }
}

private fun shareVideo(context: Context, videoPath: String) {
    try {
        val file = File(videoPath)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(Intent.createChooser(shareIntent, "Share Video"))
    } catch (e: Exception) {
        Log.e(TAG, "Failed to share video", e)
        Toast.makeText(context, "Failed to share video", Toast.LENGTH_SHORT).show()
    }
}

