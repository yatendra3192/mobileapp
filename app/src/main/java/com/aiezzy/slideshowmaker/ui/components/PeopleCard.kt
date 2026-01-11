package com.aiezzy.slideshowmaker.ui.components

import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Size
import coil.transform.Transformation
import com.aiezzy.slideshowmaker.data.face.entities.PersonWithFace

// Theme colors matching the app
private val AccentYellow = Color(0xFFE5FF00)
private val DarkBackground = Color(0xFF1A1A1A)
private val CardBackground = Color(0xFF2A2A2A)

/**
 * Custom Coil transformation to crop to face bounding box
 */
class FaceCropTransformation(
    private val left: Float,
    private val top: Float,
    private val right: Float,
    private val bottom: Float,
    private val padding: Float = 0.2f  // Add padding around face
) : Transformation {

    override val cacheKey: String = "face_crop_${left}_${top}_${right}_${bottom}_$padding"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val width = input.width
        val height = input.height

        // Convert normalized coordinates to pixel coordinates
        val faceWidth = (right - left) * width
        val faceHeight = (bottom - top) * height
        val paddingX = faceWidth * padding
        val paddingY = faceHeight * padding

        val cropLeft = ((left * width) - paddingX).coerceAtLeast(0f).toInt()
        val cropTop = ((top * height) - paddingY).coerceAtLeast(0f).toInt()
        val cropRight = ((right * width) + paddingX).coerceAtMost(width.toFloat()).toInt()
        val cropBottom = ((bottom * height) + paddingY).coerceAtMost(height.toFloat()).toInt()

        val cropWidth = (cropRight - cropLeft).coerceAtLeast(1)
        val cropHeight = (cropBottom - cropTop).coerceAtLeast(1)

        return try {
            Bitmap.createBitmap(input, cropLeft, cropTop, cropWidth, cropHeight)
        } catch (e: Exception) {
            input // Return original if crop fails
        }
    }
}

/**
 * Circular face thumbnail with optional selection state
 */
@Composable
fun FaceThumbnail(
    person: PersonWithFace,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    isSelected: Boolean = false,
    showName: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    val context = LocalContext.current

    Column(
        modifier = modifier
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                }
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(size),
            contentAlignment = Alignment.Center
        ) {
            // Face image with crop transformation to show just the face
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(Uri.parse(person.photoUri))
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .crossfade(true)
                    .transformations(
                        FaceCropTransformation(
                            left = person.boundingBoxLeft,
                            top = person.boundingBoxTop,
                            right = person.boundingBoxRight,
                            bottom = person.boundingBoxBottom,
                            padding = 0.3f  // Add 30% padding around face
                        )
                    )
                    .build(),
                contentDescription = person.getDisplayName(),
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .then(
                        if (isSelected) {
                            Modifier.border(3.dp, AccentYellow, CircleShape)
                        } else {
                            Modifier.border(2.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                        }
                    ),
                contentScale = ContentScale.Crop
            )

            // Selection checkmark
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(20.dp)
                        .background(AccentYellow, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Selected",
                        tint = Color.Black,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }

        // Name label
        if (showName) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = person.getDisplayName(),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = size + 16.dp)
            )
        }
    }
}

/**
 * Small face thumbnail for chips/filters
 */
@Composable
fun SmallFaceThumbnail(
    person: PersonWithFace,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp
) {
    val context = LocalContext.current

    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(Uri.parse(person.photoUri))
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .crossfade(true)
            .transformations(
                FaceCropTransformation(
                    left = person.boundingBoxLeft,
                    top = person.boundingBoxTop,
                    right = person.boundingBoxRight,
                    bottom = person.boundingBoxBottom,
                    padding = 0.25f
                )
            )
            .build(),
        contentDescription = person.getDisplayName(),
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape),
        contentScale = ContentScale.Crop
    )
}

/**
 * Filter chip with face thumbnail (like "Only them" in Google Photos)
 */
@Composable
fun PersonFilterChip(
    person: PersonWithFace,
    isSelected: Boolean,
    onToggle: () -> Unit,
    onRemove: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onToggle),
        color = if (isSelected) CardBackground else Color.Transparent,
        shape = RoundedCornerShape(24.dp),
        border = if (!isSelected) {
            androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.3f))
        } else null
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SmallFaceThumbnail(person = person, size = 24.dp)

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = if (isSelected) "Only them" else person.getDisplayName(),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White
            )

            if (isSelected && onRemove != null) {
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Remove filter",
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier
                        .size(16.dp)
                        .clickable(onClick = onRemove)
                )
            }
        }
    }
}

/**
 * "People and pets" card for home screen
 */
@Composable
fun PeopleCard(
    persons: List<PersonWithFace>,
    scanProgress: Float?,
    isScanning: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        color = CardBackground,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth()
        ) {
            // Face grid (2x2)
            if (persons.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.2f)
                ) {
                    // 2x2 grid of faces
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            persons.getOrNull(0)?.let { person ->
                                FaceThumbnailGridItem(
                                    person = person,
                                    modifier = Modifier.weight(1f)
                                )
                            } ?: Spacer(modifier = Modifier.weight(1f))

                            persons.getOrNull(1)?.let { person ->
                                FaceThumbnailGridItem(
                                    person = person,
                                    modifier = Modifier.weight(1f)
                                )
                            } ?: Spacer(modifier = Modifier.weight(1f))
                        }

                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            persons.getOrNull(2)?.let { person ->
                                FaceThumbnailGridItem(
                                    person = person,
                                    modifier = Modifier.weight(1f)
                                )
                            } ?: Spacer(modifier = Modifier.weight(1f))

                            persons.getOrNull(3)?.let { person ->
                                FaceThumbnailGridItem(
                                    person = person,
                                    modifier = Modifier.weight(1f)
                                )
                            } ?: Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            } else {
                // Empty state or scanning
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.2f),
                    contentAlignment = Alignment.Center
                ) {
                    if (isScanning) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(
                                progress = { scanProgress ?: 0f },
                                modifier = Modifier.size(40.dp),
                                color = AccentYellow,
                                trackColor = Color.White.copy(alpha = 0.2f)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Scanning...",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.6f)
                            )
                        }
                    } else {
                        Text(
                            text = "No faces found yet",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.6f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Label
            Text(
                text = "People and pets",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                fontWeight = FontWeight.Medium
            )

            // Scanning progress bar
            if (isScanning && scanProgress != null) {
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { scanProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .clip(RoundedCornerShape(1.dp)),
                    color = AccentYellow,
                    trackColor = Color.White.copy(alpha = 0.2f)
                )
            }
        }
    }
}

/**
 * Face thumbnail for grid display (simpler, no name)
 */
@Composable
private fun FaceThumbnailGridItem(
    person: PersonWithFace,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(Uri.parse(person.photoUri))
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .crossfade(true)
            .transformations(
                FaceCropTransformation(
                    left = person.boundingBoxLeft,
                    top = person.boundingBoxTop,
                    right = person.boundingBoxRight,
                    bottom = person.boundingBoxBottom,
                    padding = 0.25f
                )
            )
            .build(),
        contentDescription = person.getDisplayName(),
        modifier = modifier
            .aspectRatio(1f)
            .clip(CircleShape)
            .border(2.dp, Color.White.copy(alpha = 0.1f), CircleShape),
        contentScale = ContentScale.Crop
    )
}

/**
 * Albums card for home screen (placeholder)
 */
@Composable
fun AlbumsCard(
    albumCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        color = CardBackground,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.2f)
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color(0xFF3A3A3A),
                                Color(0xFF2A2A2A)
                            )
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$albumCount",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White.copy(alpha = 0.5f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Albums",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
