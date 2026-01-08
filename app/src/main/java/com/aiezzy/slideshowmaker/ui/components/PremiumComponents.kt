package com.aiezzy.slideshowmaker.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aiezzy.slideshowmaker.ui.theme.*

/**
 * Premium UI Components - Aiezzy Slideshow Maker
 *
 * A collection of world-class UI components with:
 * - Subtle animations and micro-interactions
 * - Haptic feedback on touch
 * - Consistent spacing and typography
 * - Full accessibility support
 */

// ============================================
// PREMIUM BUTTON
// ============================================

@Composable
fun PremiumButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
    variant: ButtonVariant = ButtonVariant.Primary,
    size: ButtonSize = ButtonSize.Medium,
    hapticEnabled: Boolean = true
) {
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    // Scale animation on press
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) AiezzyInteraction.pressedScale else 1f,
        animationSpec = tween(AiezzyDuration.fast, easing = AiezzyEasing.snappy),
        label = "buttonScale"
    )

    val height = when (size) {
        ButtonSize.Small -> AiezzyDimens.Button.heightSmall
        ButtonSize.Medium -> AiezzyDimens.Button.height
        ButtonSize.Large -> AiezzyDimens.Button.heightLarge
    }

    val horizontalPadding = when (size) {
        ButtonSize.Small -> AiezzyDimens.Button.paddingHorizontalSmall
        ButtonSize.Medium -> AiezzyDimens.Button.paddingHorizontal
        ButtonSize.Large -> AiezzyDimens.Button.paddingHorizontalLarge
    }

    val shape = when (size) {
        ButtonSize.Small -> AiezzyShapes.buttonSmall
        ButtonSize.Medium -> AiezzyShapes.button
        ButtonSize.Large -> AiezzyShapes.buttonLarge
    }

    val colors = when (variant) {
        ButtonVariant.Primary -> ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f),
            disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.38f)
        )
        ButtonVariant.Secondary -> ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            disabledContainerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.38f),
            disabledContentColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.38f)
        )
        ButtonVariant.Tertiary -> ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.colorScheme.primary,
            disabledContentColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)
        )
        ButtonVariant.Outline -> ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.primary,
            disabledContentColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)
        )
        ButtonVariant.Ghost -> ButtonDefaults.textButtonColors(
            contentColor = AiezzyTheme.colors.textSecondary,
            disabledContentColor = AiezzyTheme.colors.textDisabled
        )
    }

    val buttonContent: @Composable RowScope.() -> Unit = {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = when (variant) {
                    ButtonVariant.Primary -> MaterialTheme.colorScheme.onPrimary
                    else -> MaterialTheme.colorScheme.primary
                },
                strokeWidth = 2.dp
            )
        } else {
            icon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    modifier = Modifier.size(AiezzyDimens.Button.iconSize)
                )
                Spacer(modifier = Modifier.width(AiezzyDimens.Button.iconSpacing))
            }
            Text(
                text = text,
                style = if (size == ButtonSize.Large) AiezzyType.buttonLarge else AiezzyType.button,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    val buttonModifier = modifier
        .scale(scale)
        .height(height)
        .semantics { contentDescription = text }

    when (variant) {
        ButtonVariant.Primary, ButtonVariant.Secondary -> {
            Button(
                onClick = {
                    if (hapticEnabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                },
                modifier = buttonModifier,
                enabled = enabled && !loading,
                shape = shape,
                colors = colors,
                contentPadding = PaddingValues(horizontal = horizontalPadding),
                interactionSource = interactionSource,
                content = buttonContent
            )
        }
        ButtonVariant.Outline -> {
            OutlinedButton(
                onClick = {
                    if (hapticEnabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                },
                modifier = buttonModifier,
                enabled = enabled && !loading,
                shape = shape,
                colors = colors,
                border = BorderStroke(1.dp, if (enabled) MaterialTheme.colorScheme.primary else AiezzyTheme.colors.border),
                contentPadding = PaddingValues(horizontal = horizontalPadding),
                interactionSource = interactionSource,
                content = buttonContent
            )
        }
        ButtonVariant.Tertiary, ButtonVariant.Ghost -> {
            TextButton(
                onClick = {
                    if (hapticEnabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                },
                modifier = buttonModifier,
                enabled = enabled && !loading,
                shape = shape,
                colors = colors,
                contentPadding = PaddingValues(horizontal = horizontalPadding),
                interactionSource = interactionSource,
                content = buttonContent
            )
        }
    }
}

enum class ButtonVariant { Primary, Secondary, Tertiary, Outline, Ghost }
enum class ButtonSize { Small, Medium, Large }

// ============================================
// PREMIUM CARD
// ============================================

@Composable
fun PremiumCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: Shape = AiezzyShapes.card,
    elevation: Dp = AiezzyDimens.Card.elevation,
    contentPadding: PaddingValues = PaddingValues(AiezzyDimens.Card.padding),
    content: @Composable ColumnScope.() -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.98f else 1f,
        animationSpec = tween(AiezzyDuration.fast, easing = AiezzyEasing.snappy),
        label = "cardScale"
    )

    val animatedElevation by animateDpAsState(
        targetValue = if (isPressed) AiezzyDimens.Card.elevationPressed else elevation,
        animationSpec = tween(AiezzyDuration.fast),
        label = "cardElevation"
    )

    val cardModifier = if (onClick != null) {
        modifier
            .scale(scale)
            .shadow(animatedElevation, shape)
            .clip(shape)
            .background(AiezzyTheme.colors.surfaceElevated)
            .clickable(
                interactionSource = interactionSource,
                indication = rememberRipple(),
                role = Role.Button
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
    } else {
        modifier
            .shadow(elevation, shape)
            .clip(shape)
            .background(AiezzyTheme.colors.surfaceElevated)
    }

    Column(
        modifier = cardModifier.padding(contentPadding),
        content = content
    )
}

// ============================================
// PREMIUM CHIP
// ============================================

@Composable
fun PremiumChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    icon: ImageVector? = null,
    enabled: Boolean = true
) {
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = tween(AiezzyDuration.fast, easing = AiezzyEasing.snappy),
        label = "chipScale"
    )

    val backgroundColor by animateColorAsState(
        targetValue = when {
            selected -> MaterialTheme.colorScheme.primaryContainer
            else -> AiezzyTheme.colors.backgroundSecondary
        },
        animationSpec = tween(AiezzyDuration.normal),
        label = "chipBg"
    )

    val contentColor by animateColorAsState(
        targetValue = when {
            selected -> MaterialTheme.colorScheme.onPrimaryContainer
            else -> AiezzyTheme.colors.textSecondary
        },
        animationSpec = tween(AiezzyDuration.normal),
        label = "chipContent"
    )

    Row(
        modifier = modifier
            .scale(scale)
            .height(AiezzyDimens.Chip.height)
            .clip(RoundedCornerShape(AiezzyRadius.full))
            .background(backgroundColor)
            .border(
                width = if (selected) 0.dp else 1.dp,
                color = if (selected) Color.Transparent else AiezzyTheme.colors.border,
                shape = RoundedCornerShape(AiezzyRadius.full)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .padding(horizontal = AiezzyDimens.Chip.paddingHorizontal),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        icon?.let {
            Icon(
                imageVector = it,
                contentDescription = null,
                modifier = Modifier.size(AiezzyDimens.Chip.iconSize),
                tint = contentColor
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = text,
            style = AiezzyType.labelMedium,
            color = contentColor,
            maxLines = 1
        )
    }
}

// ============================================
// PREMIUM SECTION HEADER
// ============================================

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = AiezzySpacing.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = AiezzyType.titleMedium,
                color = AiezzyTheme.colors.textPrimary
            )
            subtitle?.let {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = it,
                    style = AiezzyType.bodySmall,
                    color = AiezzyTheme.colors.textTertiary
                )
            }
        }
        action?.invoke()
    }
}

// ============================================
// PREMIUM EMPTY STATE
// ============================================

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(AiezzySpacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Animated icon container
        val infiniteTransition = rememberInfiniteTransition(label = "emptyStateIcon")
        val scale by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.05f,
            animationSpec = AiezzyInfinite.breathe,
            label = "iconScale"
        )

        Box(
            modifier = Modifier
                .size(96.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.height(AiezzySpacing.xl))

        Text(
            text = title,
            style = AiezzyType.headlineSmall,
            color = AiezzyTheme.colors.textPrimary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(AiezzySpacing.sm))

        Text(
            text = subtitle,
            style = AiezzyType.bodyMedium,
            color = AiezzyTheme.colors.textSecondary,
            textAlign = TextAlign.Center
        )

        action?.let {
            Spacer(modifier = Modifier.height(AiezzySpacing.xl))
            it()
        }
    }
}

// ============================================
// PREMIUM LOADING SKELETON
// ============================================

@Composable
fun SkeletonBox(
    modifier: Modifier = Modifier,
    shape: Shape = AiezzyShapes.card
) {
    val infiniteTransition = rememberInfiniteTransition(label = "skeleton")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = AiezzyInfinite.shimmer,
        label = "shimmer"
    )

    Box(
        modifier = modifier
            .clip(shape)
            .background(AiezzyTheme.colors.backgroundTertiary.copy(alpha = alpha))
    )
}

@Composable
fun SkeletonText(
    modifier: Modifier = Modifier,
    width: Dp = 120.dp,
    height: Dp = AiezzyDimens.Skeleton.textHeight
) {
    SkeletonBox(
        modifier = modifier
            .width(width)
            .height(height),
        shape = RoundedCornerShape(4.dp)
    )
}

// ============================================
// PREMIUM DIVIDER
// ============================================

@Composable
fun PremiumDivider(
    modifier: Modifier = Modifier,
    color: Color = AiezzyTheme.colors.border,
    thickness: Dp = AiezzyDimens.Divider.thickness
) {
    HorizontalDivider(
        modifier = modifier,
        color = color,
        thickness = thickness
    )
}

// ============================================
// GRADIENT BUTTON
// ============================================

@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null
) {
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.96f else 1f,
        animationSpec = tween(AiezzyDuration.fast, easing = AiezzyEasing.snappy),
        label = "gradientButtonScale"
    )

    val gradientColors = AiezzyTheme.colors.gradientPrimary

    Box(
        modifier = modifier
            .scale(scale)
            .height(AiezzyDimens.Button.heightLarge)
            .clip(AiezzyShapes.buttonLarge)
            .background(
                brush = Brush.horizontalGradient(gradientColors),
                alpha = if (enabled) 1f else 0.5f
            )
            .clickable(
                interactionSource = interactionSource,
                indication = rememberRipple(),
                enabled = enabled && !loading
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .padding(horizontal = AiezzyDimens.Button.paddingHorizontalLarge),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
            } else {
                icon?.let {
                    Icon(
                        imageVector = it,
                        contentDescription = null,
                        modifier = Modifier.size(AiezzyDimens.Button.iconSizeLarge),
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.width(AiezzyDimens.Button.iconSpacing))
                }
                Text(
                    text = text,
                    style = AiezzyType.buttonLarge,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

// ============================================
// ICON BUTTON WITH HAPTIC
// ============================================

@Composable
fun PremiumIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    enabled: Boolean = true,
    tint: Color = AiezzyTheme.colors.textSecondary
) {
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.85f else 1f,
        animationSpec = tween(AiezzyDuration.fast, easing = AiezzyEasing.bouncy),
        label = "iconButtonScale"
    )

    IconButton(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        },
        modifier = modifier
            .size(AiezzyDimens.Touch.comfortable)
            .scale(scale),
        enabled = enabled,
        interactionSource = interactionSource
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(AiezzyDimens.Icon.size),
            tint = if (enabled) tint else tint.copy(alpha = 0.38f)
        )
    }
}

// ============================================
// ANIMATED VISIBILITY WRAPPER
// ============================================

@Composable
fun AnimatedSection(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = AiezzyTransition.expand,
        exit = AiezzyTransition.collapse
    ) {
        content()
    }
}
