package de.majuwa.android.paper.krhnlesimagemanagement

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialStore
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.AlbumDetailScreen
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.AlbumDetailViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.AlbumsScreen
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.AlbumsViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.BlurReviewScreen
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.BlurViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.DuplicateReviewScreen
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.DuplicatesViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.ui.navigation.AppLaunchRequest
import de.majuwa.android.paper.krhnlesimagemanagement.ui.navigation.rememberParentEntry
import de.majuwa.android.paper.krhnlesimagemanagement.ui.onboarding.OnboardingExit
import de.majuwa.android.paper.krhnlesimagemanagement.ui.onboarding.OnboardingScreen
import de.majuwa.android.paper.krhnlesimagemanagement.ui.onboarding.OnboardingViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.ui.onboarding.ROUTE_ONBOARDING
import de.majuwa.android.paper.krhnlesimagemanagement.ui.onboarding.ROUTE_PHOTOS
import de.majuwa.android.paper.krhnlesimagemanagement.ui.onboarding.StartupViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.ui.photogrid.PhotoGridScreen
import de.majuwa.android.paper.krhnlesimagemanagement.ui.photogrid.PhotoGridViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.ui.settings.SettingsScreen
import de.majuwa.android.paper.krhnlesimagemanagement.ui.settings.SettingsViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.ui.theme.KrhnlesImageManagementTheme
import de.majuwa.android.paper.krhnlesimagemanagement.ui.uploadhistory.UploadHistoryScreen
import de.majuwa.android.paper.krhnlesimagemanagement.ui.uploadhistory.UploadHistoryViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.ui.viewer.FullscreenViewerScreen
import de.majuwa.android.paper.krhnlesimagemanagement.upload.UploadBatch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal const val ROUTE_SETTINGS = "settings"
private const val ROUTE_UPLOAD_HISTORY = "photos/history"
private const val ROUTE_ALBUMS = "albums"
private const val ROUTE_ALBUM_DETAIL = "albums/detail"

/** Route pattern of the album detail destination; its back-stack entry owns [AlbumDetailViewModel]. */
private const val ROUTE_ALBUM_DETAIL_PATTERN = "$ROUTE_ALBUM_DETAIL?href={href}"
private const val ROUTE_VIEWER = "albums/viewer"
private const val ROUTE_DUPLICATES = "albums/duplicates"
private const val ROUTE_BLUR = "albums/blur"

/**
 * Resolves the start destination once (see [StartupViewModel]) and keeps it in saveable state so a
 * recreated activity reuses it without waiting. Until it is known only the theme background is shown,
 * never a possibly wrong destination.
 */
@Composable
fun KrhnlesApp(
    onStartUpload: (batches: List<UploadBatch>) -> Unit,
    startupViewModel: StartupViewModel = viewModel(),
    launchRequests: StateFlow<AppLaunchRequest?> = NO_LAUNCH_REQUESTS,
    onLaunchRequestConsumed: () -> Unit = {},
) {
    val resolved by startupViewModel.destination.collectAsStateWithLifecycle()
    var savedStart by rememberSaveable { mutableStateOf<String?>(null) }
    val startRoute = savedStart ?: resolved?.route
    LaunchedEffect(startRoute) {
        if (savedStart == null && startRoute != null) savedStart = startRoute
    }
    if (startRoute == null) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
    } else {
        KrhnlesNavigation(startRoute, onStartUpload, launchRequests, onLaunchRequestConsumed)
    }
}

private val NO_LAUNCH_REQUESTS: StateFlow<AppLaunchRequest?> = MutableStateFlow(null)

/**
 * Leaves the current screen for the photo grid to apply a launch request, never through the graph start
 * (it may be onboarding), like the Photos tab:
 *  - the Albums subtree is on the back stack: the Photos-tab navigation *without* `restoreState` (it would
 *    restore the just-saved subtree right back on top of the grid). The subtree is *saved*, not
 *    destroyed, so a running photo deletion (which blocks Back) keeps running in its retained
 *    `AlbumDetailViewModel` and its outcome is shown when the user returns through the Albums tab;
 *  - otherwise (Settings / Upload history above the grid, or the grid itself): pop to the grid without
 *    saving, like Back (saving Settings would retain a ViewModel under a key that is never restored).
 */
internal fun NavController.openPhotosForLaunchRequest() {
    // The Albums list is the bottom of the Albums subtree, so its entry is on the back stack iff the subtree is.
    if (isOnBackStack(ROUTE_ALBUMS)) {
        navigate(ROUTE_PHOTOS) {
            launchSingleTop = true
            popUpTo(ROUTE_PHOTOS) { saveState = true }
        }
    } else {
        popBackStack(ROUTE_PHOTOS, inclusive = false)
    }
}

/** True while the onboarding is on the back stack: a launch request is dropped then. */
private fun NavController.isOnboardingShown(): Boolean = isOnBackStack(ROUTE_ONBOARDING)

/** Whether an entry of [route] is on the back stack (`getBackStackEntry` throws for a missing one). */
private fun NavController.isOnBackStack(route: String): Boolean =
    try {
        getBackStackEntry(route)
        true
    } catch (_: IllegalArgumentException) {
        false
    }

/**
 * Leaves onboarding: the photo grid becomes the root of the back stack (onboarding is removed) and,
 * for [OnboardingExit.Settings], Settings is put on top so Back from Settings lands on the photos.
 */
internal fun NavController.completeOnboarding(exit: OnboardingExit) {
    navigate(ROUTE_PHOTOS) { popUpTo(ROUTE_ONBOARDING) { inclusive = true } }
    if (exit == OnboardingExit.Settings) navigate(ROUTE_SETTINGS)
}

@Composable
private fun KrhnlesNavigation(
    startRoute: String,
    onStartUpload: (batches: List<UploadBatch>) -> Unit,
    launchRequests: StateFlow<AppLaunchRequest?>,
    onLaunchRequestConsumed: () -> Unit,
) {
    val navController = rememberNavController()
    val pendingRequest by launchRequests.collectAsStateWithLifecycle()
    LaunchedEffect(pendingRequest) {
        if (pendingRequest == null) return@LaunchedEffect
        // The photo grid applies and consumes the request; during onboarding there is nothing to apply it to.
        if (navController.isOnboardingShown()) onLaunchRequestConsumed() else navController.openPhotosForLaunchRequest()
    }
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
                            // popUpTo(ROUTE_PHOTOS), never the graph start destination: the start may
                            // be onboarding, which is not on the back stack once onboarding was left.
                            navController.navigate(ROUTE_PHOTOS) {
                                launchSingleTop = true
                                restoreState = true
                                popUpTo(ROUTE_PHOTOS) { saveState = true }
                            }
                        },
                        icon = { Icon(Icons.Default.PhotoLibrary, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_photos)) },
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
                        label = { Text(stringResource(R.string.nav_albums)) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startRoute,
            modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding),
        ) {
            composable(ROUTE_ONBOARDING) {
                val vm: OnboardingViewModel = viewModel()
                OnboardingScreen(
                    viewModel = vm,
                    onExit = { navController.completeOnboarding(it) },
                )
            }

            composable(ROUTE_PHOTOS) {
                val vm: PhotoGridViewModel = viewModel()
                // Applied only here, so the filter is never lost when navigation and composition race.
                LaunchedEffect(pendingRequest) {
                    if (pendingRequest == AppLaunchRequest.ShowNewMedia) {
                        vm.showOnlyNewPhotos()
                        onLaunchRequestConsumed()
                    }
                }
                PhotoGridScreen(
                    viewModel = vm,
                    onNavigateToSettings = { navController.navigate(ROUTE_SETTINGS) },
                    onNavigateToUploadHistory = { navController.navigate(ROUTE_UPLOAD_HISTORY) },
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

            composable(ROUTE_UPLOAD_HISTORY) {
                val vm: UploadHistoryViewModel = viewModel()
                UploadHistoryScreen(
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
                route = ROUTE_ALBUM_DETAIL_PATTERN,
                arguments =
                    listOf(
                        navArgument("href") {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                    ),
            ) { backStack ->
                val albumHref = backStack.arguments?.getString("href") ?: ""
                // Owned by this entry; shared with the viewer and the reviews opened from it.
                val vm: AlbumDetailViewModel = viewModel()
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
                    onFindBlurry = {
                        navController.navigate(
                            "$ROUTE_BLUR?href=${android.net.Uri.encode(albumHref)}",
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
            ) { backStack ->
                // Scan state is scoped to this review entry and discarded when it is left.
                val vm: DuplicatesViewModel = viewModel()
                DuplicateReviewScreen(
                    viewModel = vm,
                    detailViewModel = sharedAlbumDetailViewModel(navController, backStack),
                    albumHref = backStack.arguments?.getString("href") ?: "",
                    onNavigateBack = { navController.popBackStack() },
                )
            }

            composable(
                route = "$ROUTE_BLUR?href={href}",
                arguments =
                    listOf(
                        navArgument("href") {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                    ),
            ) { backStack ->
                // Scan state is scoped to this review entry and discarded when it is left.
                val vm: BlurViewModel = viewModel()
                val appContext = LocalContext.current.applicationContext
                val blurSensitivity = remember(appContext) { CredentialStore(appContext).blurSensitivity }
                BlurReviewScreen(
                    viewModel = vm,
                    detailViewModel = sharedAlbumDetailViewModel(navController, backStack),
                    albumHref = backStack.arguments?.getString("href") ?: "",
                    sensitivity = blurSensitivity,
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
                val vm = sharedAlbumDetailViewModel(navController, backStack)
                FullscreenViewerScreen(
                    viewModel = vm,
                    initialIndex = initialIndex,
                    onNavigateBack = { navController.popBackStack() },
                )
            }
        }
    }
}

/**
 * Returns the [AlbumDetailViewModel] scoped to the album detail back-stack entry so the viewer
 * and the duplicate / blur review share the live state of the album they were opened from.
 * [currentEntry] is the calling destination's own entry.
 */
@Composable
private fun sharedAlbumDetailViewModel(
    navController: NavController,
    currentEntry: NavBackStackEntry,
): AlbumDetailViewModel = viewModel(rememberParentEntry(navController, currentEntry, ROUTE_ALBUM_DETAIL_PATTERN))
