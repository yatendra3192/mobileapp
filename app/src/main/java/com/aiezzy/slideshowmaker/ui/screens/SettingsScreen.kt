package com.aiezzy.slideshowmaker.ui.screens

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import com.aiezzy.slideshowmaker.data.models.AutoCaptionStyle
import com.aiezzy.slideshowmaker.data.models.MusicMood
import com.aiezzy.slideshowmaker.data.models.PlatformPreset
import com.aiezzy.slideshowmaker.data.models.TextAnimation
import com.aiezzy.slideshowmaker.data.models.TextOverlay
import com.aiezzy.slideshowmaker.data.models.TextPosition
import com.aiezzy.slideshowmaker.data.models.TextStyle
import com.aiezzy.slideshowmaker.data.models.TransitionEffect
import com.aiezzy.slideshowmaker.data.models.VideoResolution
import androidx.compose.ui.graphics.Color
import com.aiezzy.slideshowmaker.ui.components.*
import com.aiezzy.slideshowmaker.ui.theme.*
import com.aiezzy.slideshowmaker.viewmodel.SlideshowViewModel

// Theme colors matching HomeScreen
private val AccentYellow = Color(0xFFE5FF00)
private val AccentYellowDark = Color(0xFF3A3A00) // For containers/backgrounds
private val DarkBackground = Color(0xFF1A1A1A)
private val CardBackground = Color(0xFF2A2A2A)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SlideshowViewModel,
    onNavigateBack: () -> Unit,
    onGenerateVideo: () -> Unit,
    onNavigateToMusicLibrary: (MusicMood?) -> Unit = {}
) {
    val haptic = LocalHapticFeedback.current
    val images by viewModel.images.collectAsState()
    val durationPerImage by viewModel.durationPerImage.collectAsState()
    val transition by viewModel.transition.collectAsState()
    val resolution by viewModel.resolution.collectAsState()
    val audioConfig by viewModel.audioConfig.collectAsState()
    val selectedPlatform by viewModel.selectedPlatform.collectAsState()
    val selectedTemplate by viewModel.selectedTemplate.collectAsState()
    val textOverlays by viewModel.textOverlays.collectAsState()
    val watermarkEnabled by viewModel.watermarkEnabled.collectAsState()
    val beatSyncEnabled by viewModel.beatSyncEnabled.collectAsState()
    val autoCaptionConfig by viewModel.autoCaptionConfig.collectAsState()

    var showAddTextDialog by remember { mutableStateOf(false) }
    var newTextInput by remember { mutableStateOf("") }
    var selectedTextPosition by remember { mutableStateOf(TextPosition.BOTTOM_CENTER) }
    var selectedTextStyle by remember { mutableStateOf(TextStyle.DEFAULT) }
    var selectedTextAnimation by remember { mutableStateOf(TextAnimation.FADE_IN) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { selectedUri ->
            // Copy audio file to cache to avoid permission expiration
            scope.launch(Dispatchers.IO) {
                val cachedPath = copyAudioToCache(context, selectedUri)
                withContext(Dispatchers.Main) {
                    if (cachedPath != null) {
                        viewModel.setAudioFromFile(cachedPath, "Selected audio")
                    } else {
                        // Fallback to direct URI if copy fails
                        viewModel.setAudio(selectedUri, "Selected audio")
                    }
                }
            }
        }
    }

    Scaffold(
        containerColor = DarkBackground,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Video Settings",
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
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Go back to home screen"
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
        bottomBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = DarkBackground
            ) {
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onGenerateVideo()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(AiezzySpacing.base)
                        .height(56.dp),
                    enabled = images.isNotEmpty(),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentYellow,
                        contentColor = Color.Black,
                        disabledContainerColor = AccentYellow.copy(alpha = 0.3f),
                        disabledContentColor = Color.Black.copy(alpha = 0.5f)
                    )
                ) {
                    Icon(Icons.Default.Movie, contentDescription = null, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Generate Video", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AiezzySpacing.base),
            verticalArrangement = Arrangement.spacedBy(AiezzySpacing.base)
        ) {
            Spacer(modifier = Modifier.height(AiezzySpacing.sm))

            // Preview count card - dark theme with yellow accent
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = CardBackground
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(
                                color = AccentYellow.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(12.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Photo,
                            contentDescription = null,
                            tint = AccentYellow,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            "${images.size} Images Selected",
                            style = AiezzyType.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        val estimatedDuration = images.size * durationPerImage
                        Text(
                            "Estimated duration: ${String.format("%.1f", estimatedDuration)}s",
                            style = AiezzyType.bodySmall,
                            color = Color.White.copy(alpha = 0.6f)
                        )
                    }
                }
            }

            // Platform Quick Presets
            PremiumSettingsCard(title = "Quick Platform Export", icon = Icons.Default.Devices) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(AiezzySpacing.sm)
                ) {
                    PlatformPreset.entries.forEach { preset ->
                        PremiumPlatformChip(
                            preset = preset,
                            isSelected = selectedPlatform == preset,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.applyPlatformPreset(preset)
                            }
                        )
                    }
                }
            }

            // Duration per image
            PremiumSettingsCard(title = "Duration per Image", icon = Icons.Default.Timer) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "${String.format("%.1f", durationPerImage)} seconds",
                            style = AiezzyType.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = AccentYellow
                        )
                    }
                    Spacer(modifier = Modifier.height(AiezzySpacing.sm))
                    Slider(
                        value = durationPerImage,
                        onValueChange = { viewModel.setDurationPerImage(it) },
                        valueRange = 0.5f..10f,
                        steps = 18,
                        colors = SliderDefaults.colors(
                            thumbColor = AccentYellow,
                            activeTrackColor = AccentYellow,
                            inactiveTrackColor = CardBackground
                        )
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("0.5s", style = AiezzyType.labelSmall, color = AiezzyTheme.colors.textTertiary)
                        Text("10s", style = AiezzyType.labelSmall, color = AiezzyTheme.colors.textTertiary)
                    }
                }
            }

            // Transition effect
            PremiumSettingsCard(title = "Transition Effect", icon = Icons.Default.Animation) {
                Column(verticalArrangement = Arrangement.spacedBy(AiezzySpacing.sm)) {
                    TransitionEffect.entries.forEach { effect ->
                        PremiumTransitionOption(
                            effect = effect,
                            isSelected = transition == effect,
                            onSelect = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.setTransition(effect)
                            }
                        )
                    }
                }
            }

            // Resolution
            PremiumSettingsCard(title = "Video Resolution", icon = Icons.Default.Hd) {
                Column(verticalArrangement = Arrangement.spacedBy(AiezzySpacing.sm)) {
                    VideoResolution.entries.forEach { res ->
                        PremiumResolutionOption(
                            resolution = res,
                            isSelected = resolution == res,
                            onSelect = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.setResolution(res)
                            }
                        )
                    }
                }
            }

            // Audio settings
            PremiumSettingsCard(title = "Background Music", icon = Icons.Default.MusicNote) {
                Column {
                    if (audioConfig == null) {
                        // Browse Music Library button (primary option)
                        Button(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onNavigateToMusicLibrary(selectedTemplate?.musicMood)
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AccentYellow,
                                contentColor = Color.Black
                            )
                        ) {
                            Icon(Icons.Default.LibraryMusic, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Browse Music Library", fontWeight = FontWeight.SemiBold)
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Or select from device
                        OutlinedButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                audioPickerLauncher.launch("audio/*")
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentYellow),
                            border = BorderStroke(1.dp, AccentYellow.copy(alpha = 0.5f))
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Select from Device", fontWeight = FontWeight.Medium)
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            "Choose royalty-free music or your own audio files",
                            style = AiezzyType.bodySmall,
                            color = Color.White.copy(alpha = 0.5f)
                        )
                    } else {
                        PremiumAudioSelectedCard(
                            fileName = audioConfig?.fileName ?: "Audio selected",
                            onRemove = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.clearAudio()
                            }
                        )

                        Spacer(modifier = Modifier.height(AiezzySpacing.base))

                        // Audio trim options
                        AudioTrimOptions(
                            useFullAudio = audioConfig?.useFullAudio ?: true,
                            startTime = audioConfig?.startTimeSeconds ?: 0,
                            endTime = audioConfig?.endTimeSeconds,
                            onUseFullAudioChange = { viewModel.setUseFullAudio(it) },
                            onTrimChange = { start, end ->
                                viewModel.updateAudioTrim(start, end)
                            }
                        )

                        Spacer(modifier = Modifier.height(AiezzySpacing.base))
                        HorizontalDivider(color = AiezzyTheme.colors.border)
                        Spacer(modifier = Modifier.height(AiezzySpacing.base))

                        // Beat sync toggle
                        PremiumToggleRow(
                            title = "Beat Sync",
                            description = "Sync transitions to music beats",
                            checked = beatSyncEnabled,
                            onCheckedChange = { viewModel.setBeatSyncEnabled(it) }
                        )
                    }
                }
            }

            // Auto-Caption settings
            PremiumSettingsCard(title = "Smart Captions", icon = Icons.Default.ClosedCaption) {
                Column {
                    // Auto-caption toggle
                    PremiumToggleRow(
                        title = "Auto-Generate Captions",
                        description = "Create captions from photo date & location",
                        checked = autoCaptionConfig.enabled,
                        onCheckedChange = { viewModel.setAutoCaptionEnabled(it) }
                    )

                    // Style options (only show if enabled)
                    AnimatedVisibility(
                        visible = autoCaptionConfig.enabled,
                        enter = AiezzyTransition.expand,
                        exit = AiezzyTransition.collapse
                    ) {
                        Column {
                            Spacer(modifier = Modifier.height(AiezzySpacing.base))

                            Text(
                                "Caption Style",
                                style = AiezzyType.labelMedium,
                                color = AiezzyTheme.colors.textSecondary
                            )
                            Spacer(modifier = Modifier.height(AiezzySpacing.sm))

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(AiezzySpacing.sm)
                            ) {
                                AutoCaptionStyle.entries.forEach { style ->
                                    PremiumChip(
                                        text = style.displayName,
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            viewModel.setAutoCaptionStyle(style)
                                        },
                                        selected = autoCaptionConfig.style == style
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Text & Watermark settings
            PremiumSettingsCard(title = "Text & Watermark", icon = Icons.Default.TextFields) {
                Column {
                    // Add text button
                    OutlinedButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            showAddTextDialog = true
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentYellow),
                        border = BorderStroke(1.dp, AccentYellow.copy(alpha = 0.5f))
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Add Title/Caption", fontWeight = FontWeight.Medium)
                    }

                    // Show existing text overlays
                    AnimatedVisibility(
                        visible = textOverlays.isNotEmpty(),
                        enter = AiezzyTransition.expand,
                        exit = AiezzyTransition.collapse
                    ) {
                        Column {
                            Spacer(modifier = Modifier.height(AiezzySpacing.md))
                            textOverlays.forEachIndexed { index, overlay ->
                                PremiumTextOverlayItem(
                                    overlay = overlay,
                                    onRemove = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        viewModel.removeTextOverlay(overlay.id)
                                    }
                                )
                                if (index < textOverlays.lastIndex) {
                                    Spacer(modifier = Modifier.height(AiezzySpacing.sm))
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(AiezzySpacing.base))
                    HorizontalDivider(color = AiezzyTheme.colors.border)
                    Spacer(modifier = Modifier.height(AiezzySpacing.base))

                    // Watermark toggle
                    PremiumToggleRow(
                        title = "Add Watermark",
                        description = "\"Aiezzy\" branding",
                        checked = watermarkEnabled,
                        onCheckedChange = { viewModel.setWatermarkEnabled(it) }
                    )
                }
            }

            // Spacer for bottom padding
            Spacer(modifier = Modifier.height(AiezzySpacing.huge))
        }
    }

    // Add Text Dialog
    if (showAddTextDialog) {
        AlertDialog(
            onDismissRequest = { showAddTextDialog = false },
            title = {
                Text(
                    "Add Text",
                    style = AiezzyType.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    OutlinedTextField(
                        value = newTextInput,
                        onValueChange = { newTextInput = it },
                        label = { Text("Enter text") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = AiezzyShapes.input
                    )

                    Spacer(modifier = Modifier.height(AiezzySpacing.base))

                    Text("Position", style = AiezzyType.labelMedium, color = AiezzyTheme.colors.textSecondary)
                    Spacer(modifier = Modifier.height(AiezzySpacing.sm))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(AiezzySpacing.sm)
                    ) {
                        listOf(
                            TextPosition.TOP_CENTER,
                            TextPosition.CENTER,
                            TextPosition.BOTTOM_CENTER
                        ).forEach { position ->
                            PremiumChip(
                                text = position.displayName,
                                onClick = { selectedTextPosition = position },
                                selected = selectedTextPosition == position
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(AiezzySpacing.base))

                    Text("Style", style = AiezzyType.labelMedium, color = AiezzyTheme.colors.textSecondary)
                    Spacer(modifier = Modifier.height(AiezzySpacing.sm))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(AiezzySpacing.sm)
                    ) {
                        TextStyle.entries.forEach { style ->
                            PremiumChip(
                                text = style.displayName,
                                onClick = { selectedTextStyle = style },
                                selected = selectedTextStyle == style
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(AiezzySpacing.base))

                    Text("Animation", style = AiezzyType.labelMedium, color = AiezzyTheme.colors.textSecondary)
                    Spacer(modifier = Modifier.height(AiezzySpacing.sm))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(AiezzySpacing.sm)
                    ) {
                        TextAnimation.entries.forEach { animation ->
                            PremiumChip(
                                text = animation.displayName,
                                onClick = { selectedTextAnimation = animation },
                                selected = selectedTextAnimation == animation
                            )
                        }
                    }
                }
            },
            confirmButton = {
                PremiumButton(
                    text = "Add",
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (newTextInput.isNotBlank()) {
                            viewModel.addTextOverlay(
                                text = newTextInput,
                                position = selectedTextPosition,
                                style = selectedTextStyle,
                                animation = selectedTextAnimation
                            )
                            newTextInput = ""
                            showAddTextDialog = false
                        }
                    },
                    enabled = newTextInput.isNotBlank(),
                    variant = ButtonVariant.Primary,
                    size = ButtonSize.Small
                )
            },
            dismissButton = {
                PremiumButton(
                    text = "Cancel",
                    onClick = { showAddTextDialog = false },
                    variant = ButtonVariant.Ghost,
                    size = ButtonSize.Small
                )
            },
            shape = AiezzyShapes.dialog,
            containerColor = MaterialTheme.colorScheme.surface
        )
    }
}

// ============================================
// PREMIUM HELPER COMPOSABLES
// ============================================

@Composable
private fun PremiumSettingsCard(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = CardBackground
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = AccentYellow,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = AiezzyType.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun PremiumTransitionOption(
    effect: TransitionEffect,
    isSelected: Boolean,
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
        targetValue = if (isSelected) AccentYellowDark
        else CardBackground,
        animationSpec = tween(AiezzyDuration.fast),
        label = "bg"
    )

    val icon = when (effect) {
        TransitionEffect.NONE -> Icons.Default.Stop
        TransitionEffect.FADE -> Icons.Default.BlurOn
        TransitionEffect.SLIDE -> Icons.Default.SwapHoriz
        TransitionEffect.ZOOM -> Icons.Default.ZoomIn
        TransitionEffect.ZOOM_OUT -> Icons.Default.ZoomOut
        TransitionEffect.KEN_BURNS -> Icons.Default.AutoAwesome
        TransitionEffect.ROTATE -> Icons.Default.Refresh
        TransitionEffect.BLUR -> Icons.Default.BlurCircular
        TransitionEffect.WIPE -> Icons.Default.ViewCarousel
        TransitionEffect.DISSOLVE -> Icons.Default.BrokenImage
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) AccentYellow else Color.White.copy(alpha = 0.1f),
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onSelect
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (isSelected) AccentYellow else Color.White.copy(alpha = 0.6f),
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                effect.displayName,
                style = AiezzyType.bodyLarge,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = Color.White
            )
            Text(
                effect.description,
                style = AiezzyType.bodySmall,
                color = Color.White.copy(alpha = 0.5f)
            )
        }
        RadioButton(
            selected = isSelected,
            onClick = onSelect,
            colors = RadioButtonDefaults.colors(
                selectedColor = AccentYellow,
                unselectedColor = Color.White.copy(alpha = 0.3f)
            )
        )
    }
}

@Composable
private fun PremiumResolutionOption(
    resolution: VideoResolution,
    isSelected: Boolean,
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
        targetValue = if (isSelected) AccentYellowDark
        else CardBackground,
        animationSpec = tween(AiezzyDuration.fast),
        label = "bg"
    )

    val isVertical = resolution.height > resolution.width

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) AccentYellow else Color.White.copy(alpha = 0.1f),
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onSelect
            )
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (isVertical) Icons.Default.StayCurrentPortrait else Icons.Default.StayCurrentLandscape,
            contentDescription = null,
            tint = if (isSelected) AccentYellow else Color.White.copy(alpha = 0.6f),
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                resolution.displayName,
                style = AiezzyType.bodyLarge,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = Color.White
            )
            Text(
                "${resolution.width} x ${resolution.height}",
                style = AiezzyType.bodySmall,
                color = Color.White.copy(alpha = 0.6f)
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        RadioButton(
            selected = isSelected,
            onClick = onSelect,
            colors = RadioButtonDefaults.colors(
                selectedColor = AccentYellow,
                unselectedColor = Color.White.copy(alpha = 0.5f)
            )
        )
    }
}

@Composable
private fun PremiumAudioSelectedCard(
    fileName: String,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AiezzyShapes.card)
            .background(
                brush = Brush.horizontalGradient(
                    listOf(
                        MaterialTheme.colorScheme.secondaryContainer,
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
                    )
                )
            )
            .padding(AiezzySpacing.base),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(AiezzyDimens.Icon.sizeXL)
                .clip(AiezzyShapes.cardSmall)
                .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(AiezzyDimens.Icon.size)
            )
        }
        Spacer(modifier = Modifier.width(AiezzySpacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Audio Selected",
                style = AiezzyType.labelSmall,
                color = AiezzyTheme.colors.textSecondary
            )
            Text(
                fileName,
                style = AiezzyType.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = AiezzyTheme.colors.textPrimary
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Remove audio",
                tint = AiezzyTheme.colors.warning,
                modifier = Modifier.size(AiezzyDimens.Icon.sizeMD)
            )
        }
    }
}

@Composable
private fun PremiumToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = AiezzyType.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = Color.White
            )
            Text(
                description,
                style = AiezzyType.bodySmall,
                color = Color.White.copy(alpha = 0.5f)
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = AccentYellow,
                checkedTrackColor = AccentYellowDark,
                uncheckedThumbColor = Color.White.copy(alpha = 0.6f),
                uncheckedTrackColor = CardBackground
            )
        )
    }
}

@Composable
private fun AudioTrimOptions(
    useFullAudio: Boolean,
    startTime: Int,
    endTime: Int?,
    onUseFullAudioChange: (Boolean) -> Unit,
    onTrimChange: (Int, Int?) -> Unit
) {
    var startMinutes by remember { mutableStateOf(startTime / 60) }
    var startSeconds by remember { mutableStateOf(startTime % 60) }
    var endMinutes by remember { mutableStateOf((endTime ?: 0) / 60) }
    var endSeconds by remember { mutableStateOf((endTime ?: 0) % 60) }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = useFullAudio,
                onCheckedChange = { onUseFullAudioChange(it) },
                colors = CheckboxDefaults.colors(
                    checkedColor = AccentYellow,
                    uncheckedColor = Color.White.copy(alpha = 0.5f),
                    checkmarkColor = Color.Black
                )
            )
            Spacer(modifier = Modifier.width(AiezzySpacing.sm))
            Text(
                "Use full audio",
                style = AiezzyType.bodyMedium,
                color = AiezzyTheme.colors.textPrimary
            )
        }

        AnimatedVisibility(
            visible = !useFullAudio,
            enter = AiezzyTransition.expand,
            exit = AiezzyTransition.collapse
        ) {
            Column {
                Spacer(modifier = Modifier.height(AiezzySpacing.base))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AiezzySpacing.base)
                ) {
                    // Start time
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Start Time",
                            style = AiezzyType.labelMedium,
                            color = AiezzyTheme.colors.textSecondary
                        )
                        Spacer(modifier = Modifier.height(AiezzySpacing.xs))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(AiezzySpacing.xs),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = startMinutes.toString().padStart(2, '0'),
                                onValueChange = { value ->
                                    value.toIntOrNull()?.let { minutes ->
                                        startMinutes = minutes.coerceIn(0, 59)
                                        onTrimChange(
                                            startMinutes * 60 + startSeconds,
                                            endMinutes * 60 + endSeconds
                                        )
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                shape = AiezzyShapes.buttonSmall
                            )
                            Text(":", color = AiezzyTheme.colors.textSecondary)
                            OutlinedTextField(
                                value = startSeconds.toString().padStart(2, '0'),
                                onValueChange = { value ->
                                    value.toIntOrNull()?.let { seconds ->
                                        startSeconds = seconds.coerceIn(0, 59)
                                        onTrimChange(
                                            startMinutes * 60 + startSeconds,
                                            endMinutes * 60 + endSeconds
                                        )
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                shape = AiezzyShapes.buttonSmall
                            )
                        }
                    }

                    // End time
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "End Time",
                            style = AiezzyType.labelMedium,
                            color = AiezzyTheme.colors.textSecondary
                        )
                        Spacer(modifier = Modifier.height(AiezzySpacing.xs))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(AiezzySpacing.xs),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = endMinutes.toString().padStart(2, '0'),
                                onValueChange = { value ->
                                    value.toIntOrNull()?.let { minutes ->
                                        endMinutes = minutes.coerceIn(0, 59)
                                        onTrimChange(
                                            startMinutes * 60 + startSeconds,
                                            endMinutes * 60 + endSeconds
                                        )
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                shape = AiezzyShapes.buttonSmall
                            )
                            Text(":", color = AiezzyTheme.colors.textSecondary)
                            OutlinedTextField(
                                value = endSeconds.toString().padStart(2, '0'),
                                onValueChange = { value ->
                                    value.toIntOrNull()?.let { seconds ->
                                        endSeconds = seconds.coerceIn(0, 59)
                                        onTrimChange(
                                            startMinutes * 60 + startSeconds,
                                            endMinutes * 60 + endSeconds
                                        )
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                shape = AiezzyShapes.buttonSmall
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PremiumPlatformChip(
    preset: PlatformPreset,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = AiezzyAnimSpec.fast,
        label = "scale"
    )

    val icon = when (preset) {
        PlatformPreset.TIKTOK -> Icons.Default.MusicNote
        PlatformPreset.INSTAGRAM_REELS -> Icons.Default.CameraAlt
        PlatformPreset.YOUTUBE_SHORTS -> Icons.Default.PlayCircle
        PlatformPreset.INSTAGRAM_POST -> Icons.Default.GridOn
        PlatformPreset.YOUTUBE -> Icons.Default.OndemandVideo
        PlatformPreset.WHATSAPP_STATUS -> Icons.Default.MarkChatUnread
    }

    FilterChip(
        selected = isSelected,
        onClick = onClick,
        modifier = Modifier.scale(scale),
        interactionSource = interactionSource,
        label = {
            Column(
                modifier = Modifier.padding(vertical = AiezzySpacing.xs)
            ) {
                Text(
                    preset.displayName,
                    style = AiezzyType.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    preset.aspectRatio,
                    style = AiezzyType.labelSmall,
                    color = if (isSelected) Color.White.copy(alpha = 0.8f)
                    else Color.White.copy(alpha = 0.5f)
                )
            }
        },
        leadingIcon = {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(AiezzyDimens.Icon.sizeSM),
                tint = if (isSelected) AccentYellow else Color.White.copy(alpha = 0.6f)
            )
        },
        shape = AiezzyShapes.input,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = AccentYellowDark,
            selectedLabelColor = Color.White,
            containerColor = CardBackground,
            labelColor = Color.White
        )
    )
}

@Composable
private fun PremiumTextOverlayItem(
    overlay: TextOverlay,
    onRemove: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.98f else 1f,
        animationSpec = AiezzyAnimSpec.fast,
        label = "scale"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(AiezzyShapes.cardSmall)
            .background(AiezzyTheme.colors.backgroundSecondary)
            .padding(AiezzySpacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(AiezzyDimens.Icon.sizeXL)
                .clip(AiezzyShapes.cardSmall)
                .background(AccentYellow.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.TextFields,
                contentDescription = null,
                tint = AccentYellow,
                modifier = Modifier.size(AiezzyDimens.Icon.sizeMD)
            )
        }
        Spacer(modifier = Modifier.width(AiezzySpacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = overlay.text,
                style = AiezzyType.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                color = AiezzyTheme.colors.textPrimary
            )
            Text(
                text = "${overlay.position.displayName} - ${overlay.style.displayName}",
                style = AiezzyType.bodySmall,
                color = AiezzyTheme.colors.textTertiary
            )
        }
        IconButton(
            onClick = onRemove,
            modifier = Modifier.size(AiezzyDimens.Chip.height)
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Remove",
                tint = AiezzyTheme.colors.warning,
                modifier = Modifier.size(AiezzyDimens.Icon.sizeSM)
            )
        }
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
            Log.d("SettingsScreen", "Audio URI: $uri, MIME type: $mimeType")

            val extension = when (mimeType) {
                "audio/mpeg", "audio/mp3" -> "mp3"
                "audio/mp4", "audio/m4a", "audio/x-m4a" -> "m4a"
                "audio/aac" -> "aac"
                "audio/wav", "audio/x-wav" -> "wav"
                "audio/ogg" -> "ogg"
                "audio/flac" -> "flac"
                else -> {
                    // Try to get extension from URI path
                    val path = uri.lastPathSegment ?: ""
                    val ext = path.substringAfterLast('.', "mp3")
                    if (ext.length <= 5) ext else "mp3"
                }
            }

            val destFile = File(audioDir, "selected_audio_${System.currentTimeMillis()}.$extension")
            Log.d("SettingsScreen", "Copying audio to: ${destFile.absolutePath}")

            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                destFile.outputStream().use { outputStream ->
                    val bytesCopied = inputStream.copyTo(outputStream)
                    Log.d("SettingsScreen", "Copied $bytesCopied bytes")
                }
            }

            if (destFile.exists() && destFile.length() > 0) {
                Log.d("SettingsScreen", "Audio copied successfully: ${destFile.absolutePath}, size: ${destFile.length()}")
                destFile.absolutePath
            } else {
                Log.e("SettingsScreen", "Failed to copy audio - file is empty or doesn't exist")
                null
            }
        } catch (e: Exception) {
            Log.e("SettingsScreen", "Failed to copy audio to cache", e)
            e.printStackTrace()
            null
        }
    }
}
