package de.majuwa.android.paper.krhnlesimagemanagement.ui.albums

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.majuwa.android.paper.krhnlesimagemanagement.R

/**
 * Selection of a duplicate / blur review (hrefs to delete), pre-filled with [defaultSelection]
 * once per scan result. The selection and the "already pre-filled" flag survive recreation
 * (rotation), so the user's choices are not reset for the same result; [isScanning] marks the
 * start of a new scan, whose result is pre-filled again. [defaultSelection] is `null` while no
 * result is shown.
 */
@Composable
internal fun rememberReviewSelection(
    isScanning: Boolean,
    defaultSelection: Set<String>?,
): MutableState<Set<String>> {
    val selection = rememberSaveable { mutableStateOf(emptySet<String>()) }
    var seeded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(isScanning, defaultSelection) {
        if (isScanning) {
            seeded = false
        } else if (defaultSelection != null && !seeded) {
            selection.value = defaultSelection
            seeded = true
        }
    }
    return selection
}

/**
 * Album lifecycle shared by the duplicate and blur review; returns `true` once the album the
 * review was opened from is fully loaded.
 *
 * - Makes sure the album is loaded ([AlbumDetailViewModel.loadAlbum] is a no-op when it already is).
 * - After logout or a server change ([AlbumsError.NotConfigured] / [AlbumsError.ConfigurationChanged])
 *   calls [onAlbumUnavailable] (stop the scan, drop its results) — not while a deletion runs, which
 *   leaves its busy state by itself, after which this runs again.
 * - Calls [onAlbumReady] with the loaded album when it becomes ready (the ViewModels ignore it
 *   unless they are idle).
 */
@Composable
internal fun rememberReviewAlbumReady(
    detailViewModel: AlbumDetailViewModel,
    albumHref: String,
    isDeleting: Boolean,
    onAlbumUnavailable: () -> Unit,
    onAlbumReady: (AlbumDetailState) -> Unit,
): Boolean {
    val detailState by detailViewModel.detailState.collectAsStateWithLifecycle()
    val albumReady = detailState.isLoaded && !detailState.isLoading && detailState.error == null
    val albumUnavailable =
        detailState.error == AlbumsError.NotConfigured || detailState.error == AlbumsError.ConfigurationChanged
    val currentOnUnavailable by rememberUpdatedState(onAlbumUnavailable)
    val currentOnReady by rememberUpdatedState(onAlbumReady)
    LaunchedEffect(albumHref) { detailViewModel.loadAlbum(albumHref) }
    LaunchedEffect(albumUnavailable, isDeleting) {
        if (albumUnavailable && !isDeleting) currentOnUnavailable()
    }
    LaunchedEffect(albumReady) {
        if (albumReady) currentOnReady(detailViewModel.detailState.value)
    }
    return albumReady
}

/** Error state of a review scan with a Retry button (shared by the duplicate and blur review). */
@Composable
internal fun ScanErrorContent(
    error: AlbumsError,
    onRetry: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            albumsErrorMessage(LocalResources.current, error),
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
    }
}

/**
 * Frame of a review's content: the album error (logout / server change / load failure) takes
 * precedence unless a deletion runs, [showLoading] shows a progress indicator while the album is
 * not loaded yet, otherwise [content] (the scan states) is shown.
 */
@Composable
internal fun ReviewAlbumGate(
    albumError: AlbumsError?,
    isDeleting: Boolean,
    showLoading: Boolean,
    modifier: Modifier,
    onRetryAlbum: () -> Unit,
    content: @Composable () -> Unit,
) {
    when {
        albumError != null && !isDeleting -> {
            AlbumDetailError(error = albumError, modifier = modifier, onRetry = onRetryAlbum)
        }

        showLoading -> {
            Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }

        else -> {
            content()
        }
    }
}
