package com.aiezzy.slideshowmaker.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Premium Color System - Aiezzy Slideshow Maker
 *
 * Design principles:
 * - Never use pure black (#000) or pure white (#FFF)
 * - Add subtle color tints to grays (cool tint for professional feel)
 * - Accent colors should feel vibrant but not overwhelming
 * - Dark mode is a first-class experience, not just inverted colors
 */
object AiezzyColors {

    // ============================================
    // BRAND COLORS
    // ============================================

    // Primary - Vibrant Indigo with depth
    val Primary = Color(0xFF6366F1)
    val PrimaryLight = Color(0xFF818CF8)
    val PrimaryDark = Color(0xFF4F46E5)
    val PrimaryContainer = Color(0xFFE0E7FF)
    val PrimaryContainerDark = Color(0xFF312E81)
    val OnPrimary = Color(0xFFFFFFFF)
    val OnPrimaryContainer = Color(0xFF1E1B4B)
    val OnPrimaryContainerDark = Color(0xFFE0E7FF)

    // Secondary - Warm Pink for accents
    val Secondary = Color(0xFFEC4899)
    val SecondaryLight = Color(0xFFF472B6)
    val SecondaryDark = Color(0xFFDB2777)
    val SecondaryContainer = Color(0xFFFCE7F3)
    val SecondaryContainerDark = Color(0xFF831843)
    val OnSecondary = Color(0xFFFFFFFF)
    val OnSecondaryContainer = Color(0xFF500724)
    val OnSecondaryContainerDark = Color(0xFFFCE7F3)

    // Tertiary - Teal for variety
    val Tertiary = Color(0xFF14B8A6)
    val TertiaryContainer = Color(0xFFCCFBF1)
    val TertiaryContainerDark = Color(0xFF134E4A)
    val OnTertiary = Color(0xFFFFFFFF)
    val OnTertiaryContainer = Color(0xFF042F2E)

    // ============================================
    // LIGHT MODE FOUNDATION
    // ============================================

    object Light {
        // Backgrounds - Subtle warm undertone
        val Background = Color(0xFFFAFAFC)        // Not pure white
        val BackgroundSecondary = Color(0xFFF4F4F7)
        val BackgroundTertiary = Color(0xFFEBEBF0)

        // Surfaces
        val Surface = Color(0xFFFFFFFF)
        val SurfaceVariant = Color(0xFFF4F4F7)
        val SurfaceElevated = Color(0xFFFFFFFF)   // Cards, sheets

        // Borders
        val Border = Color(0xFFE5E5EB)            // Barely visible
        val BorderFocused = Color(0xFFD1D1DB)
        val BorderStrong = Color(0xFFC1C1CC)

        // Text hierarchy
        val TextPrimary = Color(0xFF1A1A2E)       // Not pure black
        val TextSecondary = Color(0xFF6B6B80)
        val TextTertiary = Color(0xFF9898A8)
        val TextDisabled = Color(0xFFC1C1CC)
        val TextInverse = Color(0xFFF8F8FA)

        // Semantic colors
        val Success = Color(0xFF10B981)
        val SuccessContainer = Color(0xFFD1FAE5)
        val OnSuccessContainer = Color(0xFF064E3B)

        val Warning = Color(0xFFF59E0B)
        val WarningContainer = Color(0xFFFEF3C7)
        val OnWarningContainer = Color(0xFF78350F)

        val Error = Color(0xFFEF4444)
        val ErrorContainer = Color(0xFFFEE2E2)
        val OnErrorContainer = Color(0xFF7F1D1D)

        val Info = Color(0xFF3B82F6)
        val InfoContainer = Color(0xFFDBEAFE)
        val OnInfoContainer = Color(0xFF1E3A8A)

        // Overlay
        val Scrim = Color(0x99000000)
        val Overlay = Color(0x0D000000)
    }

    // ============================================
    // DARK MODE FOUNDATION
    // ============================================

    object Dark {
        // Backgrounds - Rich, deep, not pure black
        val Background = Color(0xFF0C0C10)        // Rich dark
        val BackgroundSecondary = Color(0xFF141418)
        val BackgroundTertiary = Color(0xFF1C1C22)

        // Surfaces - Slightly elevated
        val Surface = Color(0xFF1C1C22)
        val SurfaceVariant = Color(0xFF232329)
        val SurfaceElevated = Color(0xFF282830)   // Cards, sheets

        // Borders - Subtle
        val Border = Color(0xFF2A2A32)
        val BorderFocused = Color(0xFF3A3A44)
        val BorderStrong = Color(0xFF4A4A56)

        // Text hierarchy
        val TextPrimary = Color(0xFFF4F4F6)       // Not pure white
        val TextSecondary = Color(0xFF9898A8)
        val TextTertiary = Color(0xFF6B6B80)
        val TextDisabled = Color(0xFF4A4A56)
        val TextInverse = Color(0xFF1A1A2E)

        // Semantic colors - Slightly muted for comfort
        val Success = Color(0xFF34D399)
        val SuccessContainer = Color(0xFF064E3B)
        val OnSuccessContainer = Color(0xFFD1FAE5)

        val Warning = Color(0xFFFBBF24)
        val WarningContainer = Color(0xFF78350F)
        val OnWarningContainer = Color(0xFFFEF3C7)

        val Error = Color(0xFFF87171)
        val ErrorContainer = Color(0xFF7F1D1D)
        val OnErrorContainer = Color(0xFFFEE2E2)

        val Info = Color(0xFF60A5FA)
        val InfoContainer = Color(0xFF1E3A8A)
        val OnInfoContainer = Color(0xFFDBEAFE)

        // Overlay
        val Scrim = Color(0xCC000000)
        val Overlay = Color(0x1AFFFFFF)
    }

    // ============================================
    // GRADIENTS
    // ============================================

    // Primary gradient for hero elements
    val GradientPrimary = listOf(
        Color(0xFF6366F1),
        Color(0xFF8B5CF6)
    )

    val GradientPrimaryDark = listOf(
        Color(0xFF4F46E5),
        Color(0xFF7C3AED)
    )

    // Accent gradient for special actions
    val GradientAccent = listOf(
        Color(0xFFEC4899),
        Color(0xFFF472B6)
    )

    // Mesh gradient colors for backgrounds
    val GradientMesh = listOf(
        Color(0xFF6366F1),
        Color(0xFFEC4899),
        Color(0xFF14B8A6)
    )

    // ============================================
    // SOCIAL PLATFORM COLORS
    // ============================================

    object Social {
        val TikTok = Color(0xFF000000)
        val TikTokAlt = Color(0xFFEE1D52)
        val Instagram = Color(0xFFE4405F)
        val InstagramGradient = listOf(
            Color(0xFF405DE6),
            Color(0xFF833AB4),
            Color(0xFFE1306C),
            Color(0xFFF77737),
            Color(0xFFFCAF45)
        )
        val YouTube = Color(0xFFFF0000)
        val WhatsApp = Color(0xFF25D366)
        val Facebook = Color(0xFF1877F2)
        val Twitter = Color(0xFF1DA1F2)
        val Snapchat = Color(0xFFFFFC00)
    }

    // ============================================
    // TRANSITION EFFECT COLORS
    // ============================================

    object Transitions {
        val None = Color(0xFF6B7280)
        val Fade = Color(0xFF8B5CF6)
        val Slide = Color(0xFF3B82F6)
        val Zoom = Color(0xFF10B981)
        val KenBurns = Color(0xFFF59E0B)
        val Rotate = Color(0xFFEF4444)
        val Blur = Color(0xFFEC4899)
        val Wipe = Color(0xFF14B8A6)
        val Dissolve = Color(0xFF6366F1)
    }
}

// ============================================
// EXTENSION FUNCTIONS
// ============================================

/**
 * Returns a color with the specified alpha while maintaining the RGB values.
 */
fun Color.withAlpha(alpha: Float): Color {
    return this.copy(alpha = alpha)
}

/**
 * Lightens a color by the specified factor (0.0 to 1.0).
 */
fun Color.lighten(factor: Float): Color {
    return Color(
        red = (red + (1f - red) * factor).coerceIn(0f, 1f),
        green = (green + (1f - green) * factor).coerceIn(0f, 1f),
        blue = (blue + (1f - blue) * factor).coerceIn(0f, 1f),
        alpha = alpha
    )
}

/**
 * Darkens a color by the specified factor (0.0 to 1.0).
 */
fun Color.darken(factor: Float): Color {
    return Color(
        red = (red * (1f - factor)).coerceIn(0f, 1f),
        green = (green * (1f - factor)).coerceIn(0f, 1f),
        blue = (blue * (1f - factor)).coerceIn(0f, 1f),
        alpha = alpha
    )
}
