package com.aiezzy.slideshowmaker.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Premium Shape System - Aiezzy Slideshow Maker
 *
 * Design principles:
 * - Consistent radius within component families
 * - Larger elements = larger radius
 * - Nested elements: inner radius = outer radius - padding
 * - Border radius creates visual cohesion
 */

// ============================================
// BORDER RADIUS SCALE
// ============================================

object AiezzyRadius {
    val none = 0.dp
    val xs = 4.dp      // Small chips, tags, badges
    val sm = 6.dp      // Inputs, small buttons
    val md = 8.dp      // Buttons, small cards
    val lg = 12.dp     // Cards, containers
    val xl = 16.dp     // Large cards, modals
    val xxl = 20.dp    // Hero cards
    val xxxl = 24.dp   // Feature cards, images
    val full = 9999.dp // Pills, avatars, circular elements
}

// ============================================
// SHAPE TOKENS
// ============================================

object AiezzyShapes {
    // Small elements
    val chip = RoundedCornerShape(AiezzyRadius.xs)
    val badge = RoundedCornerShape(AiezzyRadius.xs)
    val tag = RoundedCornerShape(AiezzyRadius.sm)

    // Inputs and controls
    val input = RoundedCornerShape(AiezzyRadius.md)
    val searchBar = RoundedCornerShape(AiezzyRadius.lg)
    val slider = RoundedCornerShape(AiezzyRadius.full)

    // Buttons
    val buttonSmall = RoundedCornerShape(AiezzyRadius.sm)
    val button = RoundedCornerShape(AiezzyRadius.md)
    val buttonLarge = RoundedCornerShape(AiezzyRadius.lg)
    val buttonPill = RoundedCornerShape(AiezzyRadius.full)
    val fab = CircleShape

    // Cards and containers
    val cardSmall = RoundedCornerShape(AiezzyRadius.md)
    val card = RoundedCornerShape(AiezzyRadius.lg)
    val cardLarge = RoundedCornerShape(AiezzyRadius.xl)
    val cardHero = RoundedCornerShape(AiezzyRadius.xxl)

    // Images and media
    val imageThumbnail = RoundedCornerShape(AiezzyRadius.md)
    val imageCard = RoundedCornerShape(AiezzyRadius.lg)
    val imageLarge = RoundedCornerShape(AiezzyRadius.xl)
    val avatar = CircleShape
    val avatarSquare = RoundedCornerShape(AiezzyRadius.md)

    // Modals and sheets
    val bottomSheet = RoundedCornerShape(
        topStart = AiezzyRadius.xxl,
        topEnd = AiezzyRadius.xxl,
        bottomStart = 0.dp,
        bottomEnd = 0.dp
    )
    val dialog = RoundedCornerShape(AiezzyRadius.xxl)
    val popup = RoundedCornerShape(AiezzyRadius.lg)
    val dropdown = RoundedCornerShape(AiezzyRadius.md)

    // Special
    val tooltip = RoundedCornerShape(AiezzyRadius.sm)
    val snackbar = RoundedCornerShape(AiezzyRadius.md)
    val progressBar = RoundedCornerShape(AiezzyRadius.full)
    val indicator = CircleShape

    // Top rounded (for headers, app bars)
    val topRounded = RoundedCornerShape(
        topStart = AiezzyRadius.lg,
        topEnd = AiezzyRadius.lg,
        bottomStart = 0.dp,
        bottomEnd = 0.dp
    )

    // Bottom rounded
    val bottomRounded = RoundedCornerShape(
        topStart = 0.dp,
        topEnd = 0.dp,
        bottomStart = AiezzyRadius.lg,
        bottomEnd = AiezzyRadius.lg
    )
}

// ============================================
// MATERIAL 3 SHAPES
// ============================================

val AiezzyMaterialShapes = Shapes(
    extraSmall = RoundedCornerShape(AiezzyRadius.xs),
    small = RoundedCornerShape(AiezzyRadius.sm),
    medium = RoundedCornerShape(AiezzyRadius.md),
    large = RoundedCornerShape(AiezzyRadius.lg),
    extraLarge = RoundedCornerShape(AiezzyRadius.xl)
)

// ============================================
// HELPER FUNCTION
// ============================================

/**
 * Creates a shape with asymmetric corner radii.
 * Useful for nested elements where inner radius should be outer radius minus padding.
 */
fun nestedRadius(outerRadius: androidx.compose.ui.unit.Dp, padding: androidx.compose.ui.unit.Dp): RoundedCornerShape {
    val innerRadius = (outerRadius - padding).coerceAtLeast(0.dp)
    return RoundedCornerShape(innerRadius)
}
