package de.majuwa.android.paper.krhnlesimagemanagement

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import de.majuwa.android.paper.krhnlesimagemanagement.model.Photo
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.AlbumDetailScreen
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.AlbumsScreen
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.AlbumsViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.DuplicateReviewScreen
import de.majuwa.android.paper.krhnlesimagemanagement.ui.photogrid.PhotoGridScreen
import de.majuwa.android.paper.krhnlesimagemanagement.ui.photogrid.PhotoGridViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.ui.settings.SettingsScreen
import de.majuwa.android.paper.krhnlesimagemanagement.ui.settings.SettingsViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.ui.viewer.FullscreenViewerScreen

private const val ROUTE_PHOTOS = "photos"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_ALBUMS = "albums"
private const val ROUTE_ALBUM_DETAIL = "albums/detail"
private const val ROUTE_VIEWER = "albums/viewer"
private const val ROUTE_DUPLICATES = "albums/duplicates"

@Composable
fun KrhnlesApp(onStartUpload: (occasionName: String, photos: List<Photo>) -> Unit) {
    val navController = rememberNavController()
    val currentEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentEntry?.destination?.route

    // Bottom bar only on top-level tabs
    val showBottomBar = currentRoute == ROUTE_PHOTOS || currentRoute == ROUTE_ALBUMS

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    NavigationBarItem(
                        selected = currentRoute == ROUTE_PHOTOS,
                        onClick = {
                            navController.navigate(ROUTE_PHOTOS) {
                                launchSingleTop = true
                                restoreState = true
                                popUpTo(ROUTE_PHOTOS) { saveState = true }
                            }
                        },
                        icon = { Icon(Icons.Default.PhotoLibrary, contentDescription = null) },
                        label = { Text("Photos") },
                    )
                    NavigationBarItem(
                        selected = currentRoute == ROUTE_ALBUMS,
                        onClick = {
                            navController.navigate(ROUTE_ALBUMS) {
                                launchSingleTop = true
                                restoreState = true
                                popUpTo(ROUTE_PHOTOS) { saveState = true }
                            }
                        },
                        icon = { Icon(Icons.Default.Collections, contentDescription = null) },
                        label = { Text("Albums") },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = ROUTE_PHOTOS,
            modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding),
        ) {
            composable(ROUTE_PHOTOS) {
                val vm: PhotoGridViewModel = viewModel()
                PhotoGridScreen(
                    viewModel = vm,
                    onNavigateToSettings = { navController.navigate(ROUTE_SETTINGS) },
                    onStartUpload = onStartUpload,
                )
            }

            composable(ROUTE_SETTINGS) {
                val vm: SettingsViewModel = viewModel()
                SettingsScreen(
                    viewModel = vm,
                    onNavigateBack = { navController.popBackStack() },
                )
            }

            // ── Albums tab ────────────────────────────────────────────────────

            composable(ROUTE_ALBUMS) {
                val vm: AlbumsViewModel = viewModel()
                AlbumsScreen(
                    viewModel = vm,
                    onOpenAlbum = { href ->
                        navController.navigate("$ROUTE_ALBUM_DETAIL?href=${android.net.Uri.encode(href)}")
                    },
                )
            }

            composable(
                route = "$ROUTE_ALBUM_DETAIL?href={href}",
                arguments =
                    listOf(
                        navArgument("href") {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                    ),
            ) { backStack ->
                val albumHref = backStack.arguments?.getString("href") ?: ""
                // Share the AlbumsViewModel scoped to the "albums" back-stack entry
                val albumsEntry = navController.getBackStackEntry(ROUTE_ALBUMS)
                val vm: AlbumsViewModel = viewModel(albumsEntry)
                AlbumDetailScreen(
                    viewModel = vm,
                    albumHref = albumHref,
                    onOpenPhoto = { index ->
                        navController.navigate(
                            "$ROUTE_VIEWER?href=${android.net.Uri.encode(albumHref)}&index=$index",
                        )
                    },
                    onFindDuplicates = {
                        navController.navigate(
                            "$ROUTE_DUPLICATES?href=${android.net.Uri.encode(albumHref)}",
                        )
                    },
                    onNavigateBack = { navController.popBackStack() },
                )
            }

            composable(
                route = "$ROUTE_DUPLICATES?href={href}",
                arguments =
                    listOf(
                        navArgument("href") {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                    ),
            ) {
                val albumsEntry = navController.getBackStackEntry(ROUTE_ALBUMS)
                val vm: AlbumsViewModel = viewModel(albumsEntry)
                DuplicateReviewScreen(
                    viewModel = vm,
                    onNavigateBack = { navController.popBackStack() },
                )
            }

            composable(
                route = "$ROUTE_VIEWER?href={href}&index={index}",
                arguments =
                    listOf(
                        navArgument("href") {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                        navArgument("index") {
                            type = NavType.IntType
                            defaultValue = 0
                        },
                    ),
            ) { backStack ->
                val initialIndex = backStack.arguments?.getInt("index") ?: 0
                val albumsEntry = navController.getBackStackEntry(ROUTE_ALBUMS)
                val vm: AlbumsViewModel = viewModel(albumsEntry)
                FullscreenViewerScreen(
                    viewModel = vm,
                    initialIndex = initialIndex,
                    onNavigateBack = { navController.popBackStack() },
                )
            }
        }
    }
}
