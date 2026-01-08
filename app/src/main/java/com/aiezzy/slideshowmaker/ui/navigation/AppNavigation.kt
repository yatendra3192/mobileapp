package com.aiezzy.slideshowmaker.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.aiezzy.slideshowmaker.data.models.MusicMood
import com.aiezzy.slideshowmaker.ui.screens.HomeScreen
import com.aiezzy.slideshowmaker.ui.screens.MusicLibraryScreen
import com.aiezzy.slideshowmaker.ui.screens.PreviewScreen
import com.aiezzy.slideshowmaker.ui.screens.ProcessingScreen
import com.aiezzy.slideshowmaker.ui.screens.SettingsScreen
import com.aiezzy.slideshowmaker.viewmodel.SlideshowViewModel
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Settings : Screen("settings")
    object Processing : Screen("processing")
    object Preview : Screen("preview/{videoPath}") {
        fun createRoute(videoPath: String): String {
            val encoded = URLEncoder.encode(videoPath, StandardCharsets.UTF_8.toString())
            return "preview/$encoded"
        }
    }
    object MusicLibrary : Screen("music_library?mood={mood}") {
        fun createRoute(mood: MusicMood? = null): String {
            return if (mood != null) "music_library?mood=${mood.name}" else "music_library"
        }
    }
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val viewModel: SlideshowViewModel = viewModel()

    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                viewModel = viewModel,
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
                },
                onNavigateToProcessing = {
                    // Start video generation and navigate to processing screen
                    viewModel.generateVideo()
                    navController.navigate(Screen.Processing.route)
                },
                onNavigateToPreview = { videoPath ->
                    navController.navigate(Screen.Preview.createRoute(videoPath))
                }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                viewModel = viewModel,
                onNavigateBack = {
                    navController.popBackStack()
                },
                onGenerateVideo = {
                    navController.navigate(Screen.Processing.route)
                },
                onNavigateToMusicLibrary = { mood ->
                    navController.navigate(Screen.MusicLibrary.createRoute(mood))
                }
            )
        }

        composable(Screen.Processing.route) {
            ProcessingScreen(
                viewModel = viewModel,
                onNavigateBack = {
                    navController.popBackStack(Screen.Home.route, inclusive = false)
                },
                onVideoReady = { videoPath ->
                    navController.navigate(Screen.Preview.createRoute(videoPath)) {
                        popUpTo(Screen.Home.route)
                    }
                }
            )
        }

        composable(
            route = Screen.Preview.route,
            arguments = listOf(navArgument("videoPath") { type = NavType.StringType })
        ) { backStackEntry ->
            val encodedPath = backStackEntry.arguments?.getString("videoPath") ?: ""
            val videoPath = URLDecoder.decode(encodedPath, StandardCharsets.UTF_8.toString())
            PreviewScreen(
                videoPath = videoPath,
                onNavigateBack = {
                    navController.popBackStack(Screen.Home.route, inclusive = false)
                },
                onCreateNew = {
                    viewModel.reset()
                    navController.popBackStack(Screen.Home.route, inclusive = false)
                }
            )
        }

        composable(
            route = Screen.MusicLibrary.route,
            arguments = listOf(
                navArgument("mood") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val moodString = backStackEntry.arguments?.getString("mood")
            val mood = moodString?.let {
                try { MusicMood.valueOf(it) } catch (e: Exception) { null }
            }
            MusicLibraryScreen(
                recommendedMood = mood,
                onMusicSelected = { track, localPath ->
                    viewModel.setAudioFromFile(localPath, track.title)
                    navController.popBackStack()
                },
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}
