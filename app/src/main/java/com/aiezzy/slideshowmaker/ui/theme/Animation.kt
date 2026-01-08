package com.aiezzy.slideshowmaker.ui.theme

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.IntOffset

/**
 * Premium Animation System - Aiezzy Slideshow Maker
 *
 * Design principles:
 * - Everything animates - no instant state changes
 * - Stagger lists with sequential delays
 * - Transform + fade, not just fade alone
 * - Exit animations are as graceful as entries
 * - Respect reduced motion preferences
 */

// ============================================
// TIMING FUNCTIONS (EASING)
// ============================================

object AiezzyEasing {
    // Smooth, natural feel - best for most transitions
    val smooth = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)

    // Quick response, smooth end - buttons, hovers
    val emphasized = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)

    // Snappy, responsive - small UI elements
    val snappy = CubicBezierEasing(0.25f, 1f, 0.5f, 1f)

    // Decelerate - elements entering screen
    val decelerate = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f)

    // Accelerate - elements leaving screen
    val accelerate = CubicBezierEasing(0.4f, 0.0f, 1.0f, 1.0f)

    // Bouncy, playful - success states, celebrations
    val bouncy = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)

    // Linear - constant speed, progress bars
    val linear = LinearEasing

    // Expo out - smooth landing, modals
    val expoOut = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
}

// ============================================
// DURATION SCALE
// ============================================

object AiezzyDuration {
    const val instant = 100       // Hover states, toggles
    const val fast = 150          // Buttons, small elements
    const val normal = 200        // Most transitions
    const val medium = 250        // Moderate movements
    const val slow = 300          // Modals, large elements
    const val slower = 400        // Page transitions
    const val deliberate = 500    // Emphasis, celebrations

    // Stagger delays for lists
    const val staggerDelay = 40   // Delay between list items
    const val staggerMax = 10     // Maximum items to stagger
}

// ============================================
// ANIMATION SPECS
// ============================================

object AiezzyAnimSpec {

    // Default tween for most animations
    val default = tween<Float>(
        durationMillis = AiezzyDuration.normal,
        easing = AiezzyEasing.smooth
    )

    // Fast for small UI feedback
    val fast = tween<Float>(
        durationMillis = AiezzyDuration.fast,
        easing = AiezzyEasing.snappy
    )

    // Slow for important transitions
    val slow = tween<Float>(
        durationMillis = AiezzyDuration.slow,
        easing = AiezzyEasing.expoOut
    )

    // Bouncy for celebrations
    val bouncy = tween<Float>(
        durationMillis = AiezzyDuration.medium,
        easing = AiezzyEasing.bouncy
    )

    // Spring for natural movement
    val spring = spring<Float>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMedium
    )

    val springGentle = spring<Float>(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessLow
    )

    val springSnappy = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessHigh
    )
}

// ============================================
// ENTER/EXIT TRANSITIONS
// ============================================

object AiezzyTransition {

    // Screen enter - slide up + fade
    @OptIn(ExperimentalAnimationApi::class)
    val screenEnter = fadeIn(
        animationSpec = tween(AiezzyDuration.slow, easing = AiezzyEasing.decelerate)
    ) + slideInVertically(
        animationSpec = tween(AiezzyDuration.slow, easing = AiezzyEasing.expoOut),
        initialOffsetY = { it / 10 }
    )

    // Screen exit - slide up + fade
    @OptIn(ExperimentalAnimationApi::class)
    val screenExit = fadeOut(
        animationSpec = tween(AiezzyDuration.fast, easing = AiezzyEasing.accelerate)
    ) + slideOutVertically(
        animationSpec = tween(AiezzyDuration.fast, easing = AiezzyEasing.accelerate),
        targetOffsetY = { -it / 20 }
    )

    // Modal enter - scale + fade
    val modalEnter = fadeIn(
        animationSpec = tween(AiezzyDuration.normal, easing = AiezzyEasing.decelerate)
    ) + scaleIn(
        animationSpec = tween(AiezzyDuration.normal, easing = AiezzyEasing.expoOut),
        initialScale = 0.92f
    )

    // Modal exit - scale + fade
    val modalExit = fadeOut(
        animationSpec = tween(AiezzyDuration.fast, easing = AiezzyEasing.accelerate)
    ) + scaleOut(
        animationSpec = tween(AiezzyDuration.fast, easing = AiezzyEasing.accelerate),
        targetScale = 0.96f
    )

    // List item enter - slide + fade
    val listItemEnter = fadeIn(
        animationSpec = tween(AiezzyDuration.normal, easing = AiezzyEasing.decelerate)
    ) + slideInHorizontally(
        animationSpec = tween(AiezzyDuration.normal, easing = AiezzyEasing.expoOut),
        initialOffsetX = { -it / 8 }
    )

    // List item exit
    val listItemExit = fadeOut(
        animationSpec = tween(AiezzyDuration.fast, easing = AiezzyEasing.accelerate)
    ) + slideOutHorizontally(
        animationSpec = tween(AiezzyDuration.fast, easing = AiezzyEasing.accelerate),
        targetOffsetX = { -it / 8 }
    )

    // Expand/collapse
    val expand = expandVertically(
        animationSpec = tween(AiezzyDuration.normal, easing = AiezzyEasing.expoOut),
        expandFrom = androidx.compose.ui.Alignment.Top
    ) + fadeIn(
        animationSpec = tween(AiezzyDuration.normal, easing = AiezzyEasing.decelerate)
    )

    val collapse = shrinkVertically(
        animationSpec = tween(AiezzyDuration.fast, easing = AiezzyEasing.accelerate),
        shrinkTowards = androidx.compose.ui.Alignment.Top
    ) + fadeOut(
        animationSpec = tween(AiezzyDuration.fast, easing = AiezzyEasing.accelerate)
    )

    // Bottom sheet
    val sheetEnter = slideInVertically(
        animationSpec = tween(AiezzyDuration.slow, easing = AiezzyEasing.expoOut),
        initialOffsetY = { it }
    )

    val sheetExit = slideOutVertically(
        animationSpec = tween(AiezzyDuration.normal, easing = AiezzyEasing.accelerate),
        targetOffsetY = { it }
    )

    // Crossfade for content switching
    fun <T> crossfade(): ContentTransform = fadeIn(
        animationSpec = tween(AiezzyDuration.normal, easing = AiezzyEasing.decelerate)
    ) togetherWith fadeOut(
        animationSpec = tween(AiezzyDuration.fast, easing = AiezzyEasing.accelerate)
    )
}

// ============================================
// INFINITE TRANSITIONS
// ============================================

object AiezzyInfinite {

    // Pulse animation for loading
    val pulse = infiniteRepeatable<Float>(
        animation = tween(1000, easing = AiezzyEasing.smooth),
        repeatMode = RepeatMode.Reverse
    )

    // Rotation for spinners
    val rotate = infiniteRepeatable<Float>(
        animation = tween(1500, easing = AiezzyEasing.linear),
        repeatMode = RepeatMode.Restart
    )

    // Shimmer for skeleton loading
    val shimmer = infiniteRepeatable<Float>(
        animation = tween(1200, easing = AiezzyEasing.smooth),
        repeatMode = RepeatMode.Restart
    )

    // Gentle breathing for emphasis
    val breathe = infiniteRepeatable<Float>(
        animation = tween(2000, easing = AiezzyEasing.smooth),
        repeatMode = RepeatMode.Reverse
    )
}

// ============================================
// STAGGER ANIMATION HELPER
// ============================================

/**
 * Calculates stagger delay for list items.
 * @param index The index of the item in the list
 * @param baseDelay Base delay in milliseconds
 * @param maxItems Maximum items to apply stagger to
 */
fun staggerDelay(
    index: Int,
    baseDelay: Int = AiezzyDuration.staggerDelay,
    maxItems: Int = AiezzyDuration.staggerMax
): Int {
    return if (index < maxItems) index * baseDelay else maxItems * baseDelay
}

/**
 * Creates a tween animation spec with stagger delay.
 */
fun <T> staggeredTween(
    index: Int,
    durationMillis: Int = AiezzyDuration.normal,
    easing: Easing = AiezzyEasing.expoOut
): TweenSpec<T> = tween(
    durationMillis = durationMillis,
    delayMillis = staggerDelay(index),
    easing = easing
)

// ============================================
// BUTTON PRESS ANIMATION
// ============================================

object AiezzyInteraction {
    // Scale down on press
    const val pressedScale = 0.96f
    const val hoveredScale = 1.02f

    // Elevation changes
    const val defaultElevation = 2f
    const val pressedElevation = 1f
    const val hoveredElevation = 4f
}

// ============================================
// CONTENT SIZE ANIMATION
// ============================================

val contentSizeAnimSpec = spring<IntOffset>(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = Spring.StiffnessMedium
)
