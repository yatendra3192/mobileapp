package com.aiezzy.slideshowmaker.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Premium Theme System - Aiezzy Slideshow Maker
 *
 * A world-class design system inspired by Linear, Airbnb, and Stripe.
 * Features:
 * - Comprehensive light/dark mode support
 * - Extended color palette beyond Material defaults
 * - Custom typography scale
 * - Consistent spacing and shapes
 * - Premium animation system
 */

// ============================================
// LIGHT COLOR SCHEME
// ============================================

private val LightColorScheme = lightColorScheme(
    // Primary
    primary = AiezzyColors.Primary,
    onPrimary = AiezzyColors.OnPrimary,
    primaryContainer = AiezzyColors.PrimaryContainer,
    onPrimaryContainer = AiezzyColors.OnPrimaryContainer,

    // Secondary
    secondary = AiezzyColors.Secondary,
    onSecondary = AiezzyColors.OnSecondary,
    secondaryContainer = AiezzyColors.SecondaryContainer,
    onSecondaryContainer = AiezzyColors.OnSecondaryContainer,

    // Tertiary
    tertiary = AiezzyColors.Tertiary,
    onTertiary = AiezzyColors.OnTertiary,
    tertiaryContainer = AiezzyColors.TertiaryContainer,
    onTertiaryContainer = AiezzyColors.OnTertiaryContainer,

    // Backgrounds
    background = AiezzyColors.Light.Background,
    onBackground = AiezzyColors.Light.TextPrimary,

    // Surfaces
    surface = AiezzyColors.Light.Surface,
    onSurface = AiezzyColors.Light.TextPrimary,
    surfaceVariant = AiezzyColors.Light.SurfaceVariant,
    onSurfaceVariant = AiezzyColors.Light.TextSecondary,

    // Error
    error = AiezzyColors.Light.Error,
    onError = Color.White,
    errorContainer = AiezzyColors.Light.ErrorContainer,
    onErrorContainer = AiezzyColors.Light.OnErrorContainer,

    // Outline
    outline = AiezzyColors.Light.Border,
    outlineVariant = AiezzyColors.Light.BorderFocused,

    // Inverse
    inverseSurface = AiezzyColors.Dark.Surface,
    inverseOnSurface = AiezzyColors.Dark.TextPrimary,
    inversePrimary = AiezzyColors.PrimaryLight,

    // Scrim
    scrim = AiezzyColors.Light.Scrim
)

// ============================================
// DARK COLOR SCHEME
// ============================================

private val DarkColorScheme = darkColorScheme(
    // Primary
    primary = AiezzyColors.PrimaryLight,
    onPrimary = AiezzyColors.PrimaryContainerDark,
    primaryContainer = AiezzyColors.PrimaryContainerDark,
    onPrimaryContainer = AiezzyColors.OnPrimaryContainerDark,

    // Secondary
    secondary = AiezzyColors.SecondaryLight,
    onSecondary = AiezzyColors.SecondaryContainerDark,
    secondaryContainer = AiezzyColors.SecondaryContainerDark,
    onSecondaryContainer = AiezzyColors.OnSecondaryContainerDark,

    // Tertiary
    tertiary = AiezzyColors.Tertiary,
    onTertiary = AiezzyColors.TertiaryContainerDark,
    tertiaryContainer = AiezzyColors.TertiaryContainerDark,
    onTertiaryContainer = AiezzyColors.OnTertiaryContainer,

    // Backgrounds
    background = AiezzyColors.Dark.Background,
    onBackground = AiezzyColors.Dark.TextPrimary,

    // Surfaces
    surface = AiezzyColors.Dark.Surface,
    onSurface = AiezzyColors.Dark.TextPrimary,
    surfaceVariant = AiezzyColors.Dark.SurfaceVariant,
    onSurfaceVariant = AiezzyColors.Dark.TextSecondary,

    // Error
    error = AiezzyColors.Dark.Error,
    onError = AiezzyColors.Dark.ErrorContainer,
    errorContainer = AiezzyColors.Dark.ErrorContainer,
    onErrorContainer = AiezzyColors.Dark.OnErrorContainer,

    // Outline
    outline = AiezzyColors.Dark.Border,
    outlineVariant = AiezzyColors.Dark.BorderFocused,

    // Inverse
    inverseSurface = AiezzyColors.Light.Surface,
    inverseOnSurface = AiezzyColors.Light.TextPrimary,
    inversePrimary = AiezzyColors.Primary,

    // Scrim
    scrim = AiezzyColors.Dark.Scrim
)

// ============================================
// EXTENDED COLORS (Beyond Material3)
// ============================================

data class ExtendedColors(
    val isDark: Boolean,

    // Backgrounds
    val backgroundSecondary: Color,
    val backgroundTertiary: Color,
    val surfaceElevated: Color,

    // Text
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textDisabled: Color,
    val textInverse: Color,

    // Borders
    val border: Color,
    val borderFocused: Color,
    val borderStrong: Color,

    // Semantic
    val success: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val info: Color,
    val infoContainer: Color,
    val onInfoContainer: Color,

    // Overlay
    val overlay: Color,

    // Gradients
    val gradientPrimary: List<Color>,
    val gradientAccent: List<Color>
)

private val LightExtendedColors = ExtendedColors(
    isDark = false,
    backgroundSecondary = AiezzyColors.Light.BackgroundSecondary,
    backgroundTertiary = AiezzyColors.Light.BackgroundTertiary,
    surfaceElevated = AiezzyColors.Light.SurfaceElevated,
    textPrimary = AiezzyColors.Light.TextPrimary,
    textSecondary = AiezzyColors.Light.TextSecondary,
    textTertiary = AiezzyColors.Light.TextTertiary,
    textDisabled = AiezzyColors.Light.TextDisabled,
    textInverse = AiezzyColors.Light.TextInverse,
    border = AiezzyColors.Light.Border,
    borderFocused = AiezzyColors.Light.BorderFocused,
    borderStrong = AiezzyColors.Light.BorderStrong,
    success = AiezzyColors.Light.Success,
    successContainer = AiezzyColors.Light.SuccessContainer,
    onSuccessContainer = AiezzyColors.Light.OnSuccessContainer,
    warning = AiezzyColors.Light.Warning,
    warningContainer = AiezzyColors.Light.WarningContainer,
    onWarningContainer = AiezzyColors.Light.OnWarningContainer,
    info = AiezzyColors.Light.Info,
    infoContainer = AiezzyColors.Light.InfoContainer,
    onInfoContainer = AiezzyColors.Light.OnInfoContainer,
    overlay = AiezzyColors.Light.Overlay,
    gradientPrimary = AiezzyColors.GradientPrimary,
    gradientAccent = AiezzyColors.GradientAccent
)

private val DarkExtendedColors = ExtendedColors(
    isDark = true,
    backgroundSecondary = AiezzyColors.Dark.BackgroundSecondary,
    backgroundTertiary = AiezzyColors.Dark.BackgroundTertiary,
    surfaceElevated = AiezzyColors.Dark.SurfaceElevated,
    textPrimary = AiezzyColors.Dark.TextPrimary,
    textSecondary = AiezzyColors.Dark.TextSecondary,
    textTertiary = AiezzyColors.Dark.TextTertiary,
    textDisabled = AiezzyColors.Dark.TextDisabled,
    textInverse = AiezzyColors.Dark.TextInverse,
    border = AiezzyColors.Dark.Border,
    borderFocused = AiezzyColors.Dark.BorderFocused,
    borderStrong = AiezzyColors.Dark.BorderStrong,
    success = AiezzyColors.Dark.Success,
    successContainer = AiezzyColors.Dark.SuccessContainer,
    onSuccessContainer = AiezzyColors.Dark.OnSuccessContainer,
    warning = AiezzyColors.Dark.Warning,
    warningContainer = AiezzyColors.Dark.WarningContainer,
    onWarningContainer = AiezzyColors.Dark.OnWarningContainer,
    info = AiezzyColors.Dark.Info,
    infoContainer = AiezzyColors.Dark.InfoContainer,
    onInfoContainer = AiezzyColors.Dark.OnInfoContainer,
    overlay = AiezzyColors.Dark.Overlay,
    gradientPrimary = AiezzyColors.GradientPrimaryDark,
    gradientAccent = AiezzyColors.GradientAccent
)

// CompositionLocal for extended colors
val LocalExtendedColors = staticCompositionLocalOf { LightExtendedColors }

// ============================================
// THEME COMPOSABLE
// ============================================

@Composable
fun AiezzySlideshowTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Disabled by default for consistent branding
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        // Dynamic color is available on Android 12+
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val extendedColors = if (darkTheme) DarkExtendedColors else LightExtendedColors

    // Set status bar and navigation bar colors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window

            // Status bar - transparent with appropriate icon colors
            window.statusBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme

            // Navigation bar - match background
            window.navigationBarColor = if (darkTheme) {
                AiezzyColors.Dark.Background.toArgb()
            } else {
                AiezzyColors.Light.Background.toArgb()
            }
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }

    CompositionLocalProvider(
        LocalExtendedColors provides extendedColors
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AiezzyTypography,
            shapes = AiezzyMaterialShapes,
            content = content
        )
    }
}

// ============================================
// THEME ACCESSORS
// ============================================

/**
 * Access extended colors from anywhere in the composition.
 */
object AiezzyTheme {
    val colors: ExtendedColors
        @Composable
        @ReadOnlyComposable
        get() = LocalExtendedColors.current

    val isDarkTheme: Boolean
        @Composable
        @ReadOnlyComposable
        get() = LocalExtendedColors.current.isDark
}

// ============================================
// CONVENIENCE EXTENSIONS
// ============================================

/**
 * Get the appropriate surface color for elevated elements.
 */
@Composable
@ReadOnlyComposable
fun elevatedSurface(): Color = AiezzyTheme.colors.surfaceElevated

/**
 * Get text color with appropriate hierarchy.
 */
@Composable
@ReadOnlyComposable
fun textColor(
    secondary: Boolean = false,
    tertiary: Boolean = false,
    disabled: Boolean = false
): Color = when {
    disabled -> AiezzyTheme.colors.textDisabled
    tertiary -> AiezzyTheme.colors.textTertiary
    secondary -> AiezzyTheme.colors.textSecondary
    else -> AiezzyTheme.colors.textPrimary
}

/**
 * Get border color based on state.
 */
@Composable
@ReadOnlyComposable
fun borderColor(focused: Boolean = false, strong: Boolean = false): Color = when {
    strong -> AiezzyTheme.colors.borderStrong
    focused -> AiezzyTheme.colors.borderFocused
    else -> AiezzyTheme.colors.border
}
