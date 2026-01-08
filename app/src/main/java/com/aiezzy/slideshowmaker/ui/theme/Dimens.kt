package com.aiezzy.slideshowmaker.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Premium Spacing & Dimension System - Aiezzy Slideshow Maker
 *
 * Design principles:
 * - All spacing must be multiples of 4px (8px base grid)
 * - Generous padding inside cards (16-24dp)
 * - Consistent gaps between list items (12-16dp)
 * - Section spacing should feel luxurious (32-48dp)
 */

// ============================================
// SPACING SCALE (8px base grid)
// ============================================

object AiezzySpacing {
    val none = 0.dp
    val xxs = 2.dp      // Micro spacing
    val xs = 4.dp       // Tight elements
    val sm = 8.dp       // Related items
    val md = 12.dp      // Default gaps
    val base = 16.dp    // Component padding
    val lg = 20.dp      // Comfortable spacing
    val xl = 24.dp      // Section gaps
    val xxl = 32.dp     // Major sections
    val xxxl = 40.dp    // Large sections
    val huge = 48.dp    // Page sections
    val massive = 64.dp // Hero spacing
}

// ============================================
// COMPONENT DIMENSIONS
// ============================================

object AiezzyDimens {

    // Touch targets (minimum 44dp for accessibility)
    object Touch {
        val min = 44.dp          // Minimum touch target
        val comfortable = 48.dp  // Comfortable touch target
        val large = 56.dp        // Large touch target
    }

    // Buttons
    object Button {
        val heightSmall = 32.dp
        val height = 44.dp
        val heightMedium = 48.dp
        val heightLarge = 56.dp

        val paddingHorizontalSmall = 12.dp
        val paddingHorizontal = 16.dp
        val paddingHorizontalLarge = 24.dp

        val iconSize = 20.dp
        val iconSizeLarge = 24.dp
        val iconSpacing = 8.dp

        val minWidth = 64.dp
        val fabSize = 56.dp
        val fabSizeSmall = 40.dp
    }

    // Cards
    object Card {
        val paddingSmall = 12.dp
        val padding = 16.dp
        val paddingLarge = 20.dp
        val paddingXL = 24.dp

        val minHeight = 80.dp
        val elevation = 2.dp
        val elevationPressed = 1.dp
    }

    // Inputs
    object Input {
        val height = 48.dp
        val heightSmall = 40.dp
        val heightLarge = 56.dp

        val paddingHorizontal = 16.dp
        val paddingVertical = 12.dp

        val iconSize = 20.dp
        val iconSpacing = 12.dp

        val borderWidth = 1.dp
        val borderWidthFocused = 2.dp
    }

    // Icons
    object Icon {
        val sizeXS = 12.dp
        val sizeSM = 16.dp
        val sizeMD = 20.dp
        val size = 24.dp
        val sizeLG = 28.dp
        val sizeXL = 32.dp
        val sizeXXL = 40.dp
        val sizeHuge = 48.dp
        val sizeDisplay = 64.dp
    }

    // Avatars
    object Avatar {
        val sizeXS = 24.dp
        val sizeSM = 32.dp
        val size = 40.dp
        val sizeMD = 48.dp
        val sizeLG = 56.dp
        val sizeXL = 72.dp
        val sizeXXL = 96.dp
    }

    // Images / Thumbnails
    object Image {
        val thumbnailSmall = 48.dp
        val thumbnail = 72.dp
        val thumbnailLarge = 96.dp
        val card = 160.dp
        val hero = 240.dp
    }

    // Navigation
    object Navigation {
        val bottomBarHeight = 64.dp
        val topBarHeight = 56.dp
        val topBarHeightLarge = 64.dp
        val drawerWidth = 280.dp
        val tabHeight = 48.dp
    }

    // Lists
    object List {
        val itemHeight = 56.dp
        val itemHeightSmall = 48.dp
        val itemHeightLarge = 72.dp
        val itemPadding = 16.dp
        val itemSpacing = 8.dp
        val dividerHeight = 1.dp
        val sectionSpacing = 24.dp
    }

    // Modals / Sheets
    object Modal {
        val width = 320.dp
        val widthLarge = 400.dp
        val maxWidth = 560.dp
        val padding = 24.dp
        val handleWidth = 32.dp
        val handleHeight = 4.dp
        val handleTopSpacing = 8.dp
    }

    // Screen margins
    object Screen {
        val horizontalPadding = 16.dp
        val horizontalPaddingLarge = 20.dp
        val verticalPadding = 16.dp
        val contentMaxWidth = 640.dp
    }

    // Progress indicators
    object Progress {
        val linearHeight = 4.dp
        val linearHeightLarge = 8.dp
        val circularSize = 40.dp
        val circularSizeSmall = 24.dp
        val circularSizeLarge = 56.dp
        val circularStroke = 4.dp
    }

    // Chips / Tags
    object Chip {
        val height = 32.dp
        val heightSmall = 24.dp
        val paddingHorizontal = 12.dp
        val paddingHorizontalSmall = 8.dp
        val iconSize = 18.dp
        val spacing = 8.dp
    }

    // Badges
    object Badge {
        val size = 20.dp
        val sizeSmall = 16.dp
        val sizeLarge = 24.dp
        val dotSize = 8.dp
    }

    // Dividers
    object Divider {
        val thickness = 1.dp
        val thicknessBold = 2.dp
        val indent = 16.dp
    }

    // Skeleton / Loading
    object Skeleton {
        val textHeight = 16.dp
        val textHeightSmall = 12.dp
        val avatarSize = 40.dp
        val cardHeight = 160.dp
    }
}

// ============================================
// CONTENT WIDTH CONSTRAINTS
// ============================================

object AiezzyConstraints {
    val minTouchTarget = 44.dp
    val maxContentWidth = 640.dp
    val maxDialogWidth = 560.dp
    val maxCardWidth = 400.dp
    val maxButtonWidth = 320.dp
}

// ============================================
// HELPER EXTENSIONS
// ============================================

/**
 * Converts a Dp value to grid-aligned value (multiples of 4dp).
 */
fun Dp.toGrid(): Dp {
    val value = this.value
    return ((value / 4).toInt() * 4).dp
}

/**
 * Ensures a dimension meets minimum touch target size.
 */
fun Dp.ensureTouchTarget(): Dp {
    return this.coerceAtLeast(44.dp)
}
