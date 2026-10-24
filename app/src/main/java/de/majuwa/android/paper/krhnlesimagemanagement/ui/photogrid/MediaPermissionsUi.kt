package de.majuwa.android.paper.krhnlesimagemanagement.ui.photogrid

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.majuwa.android.paper.krhnlesimagemanagement.R

/** Media permission state of the grid (see [rememberMediaPermissions]). */
internal class MediaPermissionsUi(
    val access: MediaAccess,
    /** Set after a denied video request the system will not show again: the banner opens the settings. */
    val bannerOpensSettings: Boolean,
    /** Launches the combined media permission request. */
    val request: () -> Unit,
    val onBannerAction: () -> Unit,
) {
    val banner: MediaAccessBanner? get() = access.banner()
}

/**
 * Requests images, video and partial access together and loads the media whenever access exists.
 * Without any access the request is launched once; images-only users (upgrade from the photos-only
 * version) are asked for video access once (persisted flag, read before any request, plus a
 * per-session guard); partial-access users never automatically. Every permission result that
 * keeps access reloads the media (also a widened one). The banner opens the app settings only after
 * a video denial without rationale once a rationale was offered before (persisted), never after a
 * merely dismissed dialog. Access is re-read on resume, so a grant in the system settings applies
 * when the user comes back.
 */
@Composable
internal fun rememberMediaPermissions(viewModel: PhotoGridViewModel): MediaPermissionsUi {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var access by remember { mutableStateOf(MediaAccess.of(context)) }
    var videoDeniedPermanently by rememberSaveable { mutableStateOf(false) }
    var videoRequestedThisSession by rememberSaveable { mutableStateOf(false) }
    val videoAccessRequested by viewModel.videoAccessRequested.collectAsStateWithLifecycle()
    val videoRationaleShown by viewModel.videoRationaleShown.collectAsStateWithLifecycle()

    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val updated = MediaAccess.of(context)
            val denial = videoDenial(updated, result, activity.showsVideoRationale(), videoRationaleShown)
            videoDeniedPermanently = denial == VideoDenial.Permanent
            if (denial == VideoDenial.AskAgain) viewModel.markVideoRationaleShown()
            // Access kept but possibly widened (images-only -> + video, partial -> full) or the
            // partial selection changed: reload. Gaining access at all reloads through the effect.
            if (updated.hasAccess && access.hasAccess) viewModel.loadPhotos()
            access = updated
        }

    LaunchedEffect(access.hasAccess) {
        if (access.hasAccess) viewModel.loadPhotos() else launcher.launch(MEDIA_PERMISSIONS)
    }

    // A rationale offered already (e.g. a denial in an earlier session) is remembered too.
    LaunchedEffect(access.videoMissing) {
        if (access.videoMissing && activity.showsVideoRationale()) viewModel.markVideoRationaleShown()
    }

    LaunchedEffect(access.videoMissing, videoAccessRequested) {
        if (access.videoMissing && videoAccessRequested == false && !videoRequestedThisSession) {
            videoRequestedThisSession = true
            viewModel.markVideoAccessRequested()
            launcher.launch(MEDIA_PERMISSIONS)
        }
    }

    LifecycleResumeEffect(Unit) {
        val updated = MediaAccess.of(context)
        if (updated != access) {
            if (updated.hasAccess && access.hasAccess) viewModel.loadPhotos()
            if (!updated.videoMissing) videoDeniedPermanently = false
            access = updated
        }
        onPauseOrDispose {}
    }

    val request = { launcher.launch(MEDIA_PERMISSIONS) }
    return MediaPermissionsUi(
        access = access,
        bannerOpensSettings = videoDeniedPermanently,
        request = request,
        onBannerAction = { context.onMediaBannerAction(access, videoDeniedPermanently, request) },
    )
}

/** How a permission result left video access (see [videoDenial]). */
internal enum class VideoDenial {
    /** Granted, or not missing (no images-only access). */
    NotDenied,

    /** Denied, and the system would show the dialog again with a rationale (first denial). */
    AskAgain,

    /** Denied without rationale after a rationale was offered before: the dialog will not show again. */
    Permanent,

    /** Denied without any rationale so far: the dialog was dismissed, asking again still works. */
    Dismissed,
}

/**
 * Classifies a result: `shouldShowRequestPermissionRationale` false right after a denial is
 * permanent only if a rationale was offered before ([rationaleShownBefore], persisted); a first
 * dismissed dialog also leaves it false.
 */
internal fun videoDenial(
    access: MediaAccess,
    result: Map<String, Boolean>,
    rationaleNow: Boolean,
    rationaleShownBefore: Boolean,
): VideoDenial =
    when {
        !access.videoMissing || result[Manifest.permission.READ_MEDIA_VIDEO] != false -> VideoDenial.NotDenied
        rationaleNow -> VideoDenial.AskAgain
        rationaleShownBefore -> VideoDenial.Permanent
        else -> VideoDenial.Dismissed
    }

/** The banner action: the app settings for permanently denied video access, else the request. */
private fun Context.onMediaBannerAction(
    access: MediaAccess,
    videoDeniedPermanently: Boolean,
    request: () -> Unit,
) {
    if (access.banner() == MediaAccessBanner.VideoMissing && videoDeniedPermanently) openAppSettings() else request()
}

/** Whether the system would show the video permission dialog again with a rationale. */
private fun Activity?.showsVideoRationale(): Boolean =
    this != null && ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.READ_MEDIA_VIDEO)

@Composable
internal fun MediaAccessBannerRow(
    banner: MediaAccessBanner,
    opensSettings: Boolean,
    onAction: () -> Unit,
) {
    val message =
        when (banner) {
            MediaAccessBanner.Partial -> R.string.banner_media_partial_access
            MediaAccessBanner.VideoMissing -> R.string.banner_video_access_missing
        }
    val action =
        when {
            banner == MediaAccessBanner.Partial -> R.string.action_select_more_media
            opensSettings -> R.string.action_open_app_settings
            else -> R.string.action_grant_video_access
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.tertiaryContainer)
                .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(message),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onAction) {
            Text(stringResource(action))
        }
    }
}

/** Opens this app's system settings page (permissions); ignored if no settings activity exists. */
private fun Context.openAppSettings() {
    val intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
    try {
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Log.w("PhotoGridScreen", "App settings unavailable: ${e.javaClass.simpleName}")
    }
}
