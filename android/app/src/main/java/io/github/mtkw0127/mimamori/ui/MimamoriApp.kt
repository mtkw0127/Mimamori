package io.github.mtkw0127.mimamori.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.mtkw0127.mimamori.ui.debug.ManualP2PRole
import io.github.mtkw0127.mimamori.ui.debug.ManualP2PScreen
import io.github.mtkw0127.mimamori.ui.recorder.RecorderScreen
import io.github.mtkw0127.mimamori.ui.role.RoleSelectScreen
import io.github.mtkw0127.mimamori.ui.viewer.ViewerScreen

private object Routes {
    const val ROLE_SELECT = "role_select"
    const val RECORDER = "recorder"
    const val VIEWER = "viewer"
    const val MANUAL_P2P = "manual_p2p/{role}"

    fun manualP2P(role: ManualP2PRole) = "manual_p2p/${role.name}"
}

@Composable
fun MimamoriApp() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.ROLE_SELECT) {
        composable(Routes.ROLE_SELECT) {
            RoleSelectScreen(
                onRecorderSelected = { navController.navigate(Routes.RECORDER) },
                onViewerSelected = { navController.navigate(Routes.VIEWER) },
            )
        }
        composable(Routes.RECORDER) {
            RecorderScreen(
                onBack = navController::popBackStack,
                onOpenManualP2P = { navController.navigate(Routes.manualP2P(ManualP2PRole.Offerer)) },
            )
        }
        composable(Routes.VIEWER) {
            ViewerScreen(
                onBack = navController::popBackStack,
                onOpenManualP2P = { navController.navigate(Routes.manualP2P(ManualP2PRole.Answerer)) },
            )
        }
        composable(
            route = Routes.MANUAL_P2P,
            arguments = listOf(navArgument("role") { type = NavType.StringType }),
        ) { entry ->
            val role = ManualP2PRole.valueOf(entry.arguments?.getString("role") ?: ManualP2PRole.Offerer.name)
            ManualP2PScreen(role = role, onBack = navController::popBackStack)
        }
    }
}
