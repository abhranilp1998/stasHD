package com.abhranil.stashd.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.abhranil.stashd.ui.screens.BrowserScreen
import com.abhranil.stashd.ui.screens.DownloadsScreen
import com.abhranil.stashd.ui.screens.HistoryScreen
import com.abhranil.stashd.ui.screens.HomeScreen
import com.abhranil.stashd.ui.screens.NotificationPreviewScreen
import com.abhranil.stashd.ui.screens.WelcomeScreen
import kotlinx.serialization.Serializable

@Serializable object WelcomeRoute
@Serializable object HomeRoute
@Serializable data class BrowserRoute(val folderId: String, val resourceKey: String? = null)
@Serializable object DownloadsRoute
@Serializable object NotificationPreviewRoute
@Serializable object HistoryRoute

/**
 * Screens 1–7. Screen 4 (destination and options) is a ModalBottomSheet inside BrowserScreen, as planned.
 */
@Composable
fun StashdNavHost() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = WelcomeRoute) {
        composable<WelcomeRoute> {
            WelcomeScreen(onSignedIn = { nav.navigate(HomeRoute) { popUpTo(WelcomeRoute) { inclusive = true } } })
        }
        composable<HomeRoute> {
            HomeScreen(
                onOpenFolder = { id, key -> nav.navigate(BrowserRoute(id, key)) },
                onHistory = { nav.navigate(HistoryRoute) },
                onDownloads = { nav.navigate(DownloadsRoute) },
            )
        }
        composable<BrowserRoute> { entry ->
            val route = entry.toRoute<BrowserRoute>()
            BrowserScreen(
                folderId = route.folderId,
                onBack = { nav.popBackStack() },
                onStarted = { nav.navigate(DownloadsRoute) },
            )
        }
        composable<DownloadsRoute> {
            DownloadsScreen(
                onBack = { nav.popBackStack() },
                onNotificationPreview = { nav.navigate(NotificationPreviewRoute) },
                onHistory = { nav.navigate(HistoryRoute) },
            )
        }
        composable<NotificationPreviewRoute> { NotificationPreviewScreen(onBack = { nav.popBackStack() }) }
        composable<HistoryRoute> { HistoryScreen(onBack = { nav.popBackStack() }) }
    }
}
