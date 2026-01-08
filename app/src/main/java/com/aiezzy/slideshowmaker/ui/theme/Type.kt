package com.aiezzy.slideshowmaker.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Premium Typography System - Aiezzy Slideshow Maker
 *
 * Design principles:
 * - Clear typographic hierarchy
 * - Negative letter-spacing for large text (headings)
 * - Positive letter-spacing for small caps/labels
 * - Comfortable line heights (1.4-1.6 for body)
 * - Maximum 2-3 font weights per screen
 */

// ============================================
// FONT FAMILY
// Using system default for optimal rendering
// Consider adding custom fonts like Inter, SF Pro, or Manrope
// ============================================

val AiezzyFontFamily = FontFamily.Default

// ============================================
// TEXT STYLES - Extended for premium design
// ============================================

object AiezzyType {

    // Display - Hero text, splash screens
    val displayLarge = TextStyle(
        fontFamily = AiezzyFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 48.sp,
        lineHeight = 56.sp,
        letterSpacing = (-0.5).sp
    )

    val displayMedium = TextStyle(
        fontFamily = AiezzyFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 40.sp,
        lineHeight = 48.sp,
        letterSpacing = (-0.25).sp
    )

    val displaySmall = TextStyle(
        fontFamily = AiezzyFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.25).sp
    )

    // Headlines - Section headers
    val headlineLarge = TextStyle(
        fontFamily = AiezzyFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.25).sp
    )

    val headlineMedium = TextStyle(
        fontFamily = AiezzyFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.15).sp
    )

    val headlineSmall = TextStyle(
        fontFamily = AiezzyFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    )

    // Titles - Card headers, toolbar titles
    val titleLarge = TextStyle(
        fontFamily = AiezzyFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    )

    val titleMedium = TextStyle(
        fontFamily = AiezzyFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.1.sp
    )

    val titleSmall = TextStyle(
        fontFamily = AiezzyFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    )

    // Body - Main content text
    val bodyLarge = TextStyle(
        fontFamily = AiezzyFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    )

    val bodyMedium = TextStyle(
        fontFamily = AiezzyFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.15.sp
    )

    val bodySmall = TextStyle(
        fontFamily = AiezzyFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.25.sp
    )

    // Labels - Buttons, chips, tags
    val labelLarge = TextStyle(
        fontFamily = AiezzyFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    )

    val labelMedium = TextStyle(
        fontFamily = AiezzyFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )

    val labelSmall = TextStyle(
        fontFamily = AiezzyFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.5.sp
    )

    // ============================================
    // SPECIAL STYLES
    // ============================================

    // Overline - Small caps style for section labels
    val overline = TextStyle(
        fontFamily = AiezzyFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.5.sp
    )

    // Caption - Helper text, timestamps
    val caption = TextStyle(
        fontFamily = AiezzyFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.4.sp
    )

    // Button text
    val button = TextStyle(
        fontFamily = AiezzyFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    )

    // Large button text
    val buttonLarge = TextStyle(
        fontFamily = AiezzyFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    )

    // Numeric displays (progress, timers)
    val numeric = TextStyle(
        fontFamily = AiezzyFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.5).sp
    )

    val numericSmall = TextStyle(
        fontFamily = AiezzyFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.25).sp
    )
}

// ============================================
// MATERIAL 3 TYPOGRAPHY
// ============================================

val AiezzyTypography = Typography(
    displayLarge = AiezzyType.displayLarge,
    displayMedium = AiezzyType.displayMedium,
    displaySmall = AiezzyType.displaySmall,
    headlineLarge = AiezzyType.headlineLarge,
    headlineMedium = AiezzyType.headlineMedium,
    headlineSmall = AiezzyType.headlineSmall,
    titleLarge = AiezzyType.titleLarge,
    titleMedium = AiezzyType.titleMedium,
    titleSmall = AiezzyType.titleSmall,
    bodyLarge = AiezzyType.bodyLarge,
    bodyMedium = AiezzyType.bodyMedium,
    bodySmall = AiezzyType.bodySmall,
    labelLarge = AiezzyType.labelLarge,
    labelMedium = AiezzyType.labelMedium,
    labelSmall = AiezzyType.labelSmall
)
