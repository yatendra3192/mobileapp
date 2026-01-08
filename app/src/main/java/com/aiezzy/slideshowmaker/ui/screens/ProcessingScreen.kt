package com.aiezzy.slideshowmaker.ui.screens

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aiezzy.slideshowmaker.data.models.ProcessingState
import com.aiezzy.slideshowmaker.ui.theme.*
import com.aiezzy.slideshowmaker.viewmodel.SlideshowViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

private const val TAG = "ProcessingScreen"

// Theme colors matching HomeScreen
private val AccentYellow = Color(0xFFE5FF00)
private val DarkBackground = Color(0xFF1A1A1A)
private val CardBackground = Color(0xFF2A2A2A)

@Composable
fun ProcessingScreen(
    viewModel: SlideshowViewModel,
    onNavigateBack: () -> Unit,
    onVideoReady: (String) -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val processingState by viewModel.processingState.collectAsState()

    var showCancelDialog by remember { mutableStateOf(false) }
    var isSavingToGallery by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.generateVideo()
    }

    LaunchedEffect(processingState) {
        when (val state = processingState) {
            is ProcessingState.Success -> {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                isSavingToGallery = true
                autoSaveVideoToGallery(context, state.outputPath)
                isSavingToGallery = false
                delay(300)
                onVideoReady(state.outputPath)
            }
            else -> {}
        }
    }

    BackHandler {
        if (processingState is ProcessingState.Processing) {
            showCancelDialog = true
        } else {
            onNavigateBack()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground),
        contentAlignment = Alignment.Center
    ) {
        when (val state = processingState) {
            is ProcessingState.Idle -> {
                CircularProgressIndicator(color = AccentYellow, strokeWidth = 3.dp)
            }
            is ProcessingState.Processing -> {
                ProcessingContent(
                    progress = state.progress,
                    message = state.message,
                    onCancel = { showCancelDialog = true }
                )
            }
            is ProcessingState.Success -> {
                SuccessContent()
            }
            is ProcessingState.Error -> {
                ErrorContent(
                    message = state.message,
                    onRetry = { viewModel.generateVideo() },
                    onBack = {
                        viewModel.resetProcessingState()
                        onNavigateBack()
                    }
                )
            }
        }
    }

    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            title = { Text("Cancel Video Generation?", fontWeight = FontWeight.Bold, color = Color.White) },
            text = { Text("Are you sure you want to cancel? All progress will be lost.", color = Color.White.copy(alpha = 0.7f)) },
            confirmButton = {
                TextButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.cancelProcessing()
                    showCancelDialog = false
                    onNavigateBack()
                }) { Text("Cancel Generation", color = Color.Red) }
            },
            dismissButton = {
                TextButton(onClick = { showCancelDialog = false }) { Text("Continue", color = AccentYellow) }
            },
            containerColor = CardBackground,
            shape = RoundedCornerShape(16.dp)
        )
    }
}

@Composable
private fun ProcessingContent(progress: Int, message: String, onCancel: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val infiniteTransition = rememberInfiniteTransition(label = "processing")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Restart),
        label = "rotation"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(32.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(120.dp)) {
            Surface(modifier = Modifier.size(120.dp), shape = CircleShape, color = CardBackground) {}
            Surface(modifier = Modifier.size(100.dp), shape = CircleShape, color = AccentYellow.copy(alpha = 0.1f)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Movie, null, Modifier.size(48.dp).rotate(rotation), tint = AccentYellow)
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
        Text("Creating Your Video", style = AiezzyType.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(modifier = Modifier.height(8.dp))
        Text(message, style = AiezzyType.bodyMedium, color = Color.White.copy(alpha = 0.6f), textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(32.dp))

        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
                    .height(8.dp)
                    .background(CardBackground, RoundedCornerShape(4.dp))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress / 100f)
                        .fillMaxHeight()
                        .background(Brush.horizontalGradient(listOf(AccentYellow, AccentYellow.copy(alpha = 0.7f))), RoundedCornerShape(4.dp))
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text("$progress%", style = AiezzyType.headlineMedium, fontWeight = FontWeight.Bold, color = AccentYellow)
        }

        Spacer(modifier = Modifier.height(32.dp))
        TextButton(onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onCancel() }) {
            Icon(Icons.Default.Close, null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Cancel", color = Color.White.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun SuccessContent() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
        Surface(modifier = Modifier.size(120.dp), shape = CircleShape, color = Color(0xFF4CAF50).copy(alpha = 0.2f)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.CheckCircle, null, Modifier.size(64.dp), tint = Color(0xFF4CAF50))
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text("Video Ready!", style = AiezzyType.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Your slideshow has been created successfully", style = AiezzyType.bodyMedium, color = Color.White.copy(alpha = 0.6f), textAlign = TextAlign.Center)
    }
}

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit, onBack: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
        Surface(modifier = Modifier.size(120.dp), shape = CircleShape, color = Color.Red.copy(alpha = 0.2f)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Error, null, Modifier.size(64.dp), tint = Color.Red)
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text("Something went wrong", style = AiezzyType.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(modifier = Modifier.height(8.dp))
        Text(message, style = AiezzyType.bodyMedium, color = Color.White.copy(alpha = 0.6f), textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(32.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onBack() },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) { Text("Go Back") }
            Button(onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onRetry() },
                colors = ButtonDefaults.buttonColors(containerColor = AccentYellow, contentColor = Color.Black)) { Text("Try Again") }
        }
    }
}

private suspend fun autoSaveVideoToGallery(context: Context, videoPath: String): String? {
    return withContext(Dispatchers.IO) {
        try {
            val sourceFile = File(videoPath)
            if (!sourceFile.exists()) { Log.e(TAG, "Source video file does not exist: $videoPath"); return@withContext null }
            val fileName = "Slideshow_${System.currentTimeMillis()}.mp4"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/AiezzySlideshows")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues) ?: return@withContext null
                resolver.openOutputStream(uri)?.use { out -> FileInputStream(sourceFile).use { it.copyTo(out) } }
                contentValues.clear()
                contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
                withContext(Dispatchers.Main) { Toast.makeText(context, "Video saved to Movies/AiezzySlideshows", Toast.LENGTH_SHORT).show() }
                "Movies/AiezzySlideshows/$fileName"
            } else {
                val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                val appDir = File(moviesDir, "AiezzySlideshows").apply { if (!exists()) mkdirs() }
                val destFile = File(appDir, fileName)
                sourceFile.copyTo(destFile, overwrite = true)
                MediaScannerConnection.scanFile(context, arrayOf(destFile.absolutePath), arrayOf("video/mp4"), null)
                withContext(Dispatchers.Main) { Toast.makeText(context, "Video saved", Toast.LENGTH_SHORT).show() }
                destFile.absolutePath
            }
        } catch (e: Exception) { Log.e(TAG, "Failed to save", e); null }
    }
}
