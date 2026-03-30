package de.majuwa.android.paper.krhnlesimagemanagement

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import de.majuwa.android.paper.krhnlesimagemanagement.model.Photo
import de.majuwa.android.paper.krhnlesimagemanagement.ui.photogrid.PhotoGridScreen
import de.majuwa.android.paper.krhnlesimagemanagement.ui.photogrid.PhotoGridViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.ui.settings.SettingsScreen
import de.majuwa.android.paper.krhnlesimagemanagement.ui.settings.SettingsViewModel

@Composable
fun KrhnlesApp(onStartUpload: (occasionName: String, photos: List<Photo>) -> Unit) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "photos") {
        composable("photos") {
            val viewModel: PhotoGridViewModel = viewModel()
            PhotoGridScreen(
                viewModel = viewModel,
                onNavigateToSettings = { navController.navigate("settings") },
                onStartUpload = onStartUpload,
            )
        }
        composable("settings") {
            val viewModel: SettingsViewModel = viewModel()
            SettingsScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() },
            )
        }
    }
}
