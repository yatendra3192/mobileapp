package com.aiezzy.slideshowmaker.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Baseline Profile Generator for Aiezzy Slideshow Maker
 *
 * This class generates baseline profiles by exercising the app's critical user journeys.
 * The generated profile enables AOT compilation of frequently-used code paths, resulting in:
 * - 30-50% faster cold start
 * - Reduced jank during first interactions
 * - Better overall app responsiveness
 *
 * To generate the baseline profile, run:
 * ./gradlew :app:generateBaselineProfile
 *
 * Or for CI with managed devices:
 * ./gradlew :benchmark:pixel6Api31BenchmarkAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=BaselineProfile
 *
 * The generated profile will be saved to:
 * app/src/main/baseline-prof.txt
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    /**
     * Generates the baseline profile by exercising critical app journeys:
     * 1. App startup and home screen rendering
     * 2. Navigation through main screens
     * 3. Settings screen interactions
     * 4. Template browsing (if visible)
     */
    @Test
    fun generateBaselineProfile() {
        rule.collect(
            packageName = PACKAGE_NAME,
            includeInStartupProfile = true,
            profileBlock = {
                // Start the app - this captures startup code paths
                pressHome()
                startActivityAndWait()

                // Wait for the home screen to fully render
                device.waitForIdle()

                // Exercise the home screen - scroll through content if available
                exerciseHomeScreen()

                // Navigate to settings and exercise that screen
                exerciseSettingsScreen()

                // Navigate back and exercise template selection if available
                exerciseTemplateSelection()

                // Final wait to ensure all code paths are captured
                device.waitForIdle()
            }
        )
    }

    /**
     * Generates a startup-only profile for faster cold start.
     * This is a lighter profile focusing only on startup code paths.
     */
    @Test
    fun generateStartupProfile() {
        rule.collect(
            packageName = PACKAGE_NAME,
            includeInStartupProfile = true,
            profileBlock = {
                // Just start the app and wait for it to be ready
                pressHome()
                startActivityAndWait()

                // Wait for the home screen to fully render
                device.waitForIdle()

                // Minimal interaction to ensure UI is fully drawn
                Thread.sleep(STARTUP_WAIT_MS)
            }
        )
    }

    /**
     * Exercises the home screen by scrolling and interacting with visible elements.
     */
    private fun MacroScope.exerciseHomeScreen() {
        // Wait for home screen content to load
        device.wait(Until.hasObject(By.pkg(PACKAGE_NAME)), WAIT_TIMEOUT_MS)

        // Try to scroll if there's scrollable content
        val scrollable = device.findObject(By.scrollable(true))
        if (scrollable != null) {
            // Scroll down and up to exercise list rendering
            scrollable.scroll(Direction.DOWN, 0.8f)
            device.waitForIdle()

            scrollable.scroll(Direction.UP, 0.8f)
            device.waitForIdle()
        }

        // Small delay to ensure all rendering completes
        Thread.sleep(INTERACTION_DELAY_MS)
    }

    /**
     * Navigates to and exercises the settings screen.
     */
    private fun MacroScope.exerciseSettingsScreen() {
        // Look for settings button/icon
        val settingsButton = device.findObject(By.desc("Settings"))
            ?: device.findObject(By.text("Settings"))
            ?: device.findObject(By.res(PACKAGE_NAME, "settings"))

        if (settingsButton != null) {
            settingsButton.click()
            device.waitForIdle()

            // Wait for settings screen to load
            Thread.sleep(SCREEN_TRANSITION_MS)

            // Scroll through settings if scrollable
            val scrollable = device.findObject(By.scrollable(true))
            if (scrollable != null) {
                scrollable.scroll(Direction.DOWN, 0.5f)
                device.waitForIdle()
            }

            // Go back to home
            device.pressBack()
            device.waitForIdle()
        }
    }

    /**
     * Exercises template selection UI if available.
     */
    private fun MacroScope.exerciseTemplateSelection() {
        // Look for template-related UI elements
        val templateButton = device.findObject(By.text("Templates"))
            ?: device.findObject(By.desc("Templates"))

        if (templateButton != null) {
            templateButton.click()
            device.waitForIdle()

            Thread.sleep(SCREEN_TRANSITION_MS)

            // Scroll through templates
            val scrollable = device.findObject(By.scrollable(true))
            if (scrollable != null) {
                scrollable.scroll(Direction.RIGHT, 0.5f)
                device.waitForIdle()
            }

            // Go back
            device.pressBack()
            device.waitForIdle()
        }
    }

    companion object {
        private const val PACKAGE_NAME = "com.aiezzy.slideshowmaker"

        // Timing constants
        private const val WAIT_TIMEOUT_MS = 5000L
        private const val STARTUP_WAIT_MS = 2000L
        private const val SCREEN_TRANSITION_MS = 500L
        private const val INTERACTION_DELAY_MS = 300L
    }
}

/**
 * Type alias for the macro benchmark scope.
 * Provides access to the device for UI interactions.
 */
private typealias MacroScope = androidx.benchmark.macro.MacrobenchmarkScope
