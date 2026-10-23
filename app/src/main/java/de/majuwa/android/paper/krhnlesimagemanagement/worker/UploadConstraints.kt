package de.majuwa.android.paper.krhnlesimagemanagement.worker

import androidx.work.Constraints
import androidx.work.NetworkType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

internal const val WIFI_ONLY_READ_TIMEOUT_MS = 3_000L

/** Network constraint of an upload: unmetered when Wi-Fi-only is enabled, otherwise none. */
internal fun uploadConstraints(wifiOnly: Boolean): Constraints =
    Constraints
        .Builder()
        .apply { if (wifiOnly) setRequiredNetworkType(NetworkType.UNMETERED) }
        .build()

/**
 * Reads the current Wi-Fi-only setting, bounded by [timeoutMs]. A timeout or any non-cancellation
 * failure yields `true` (unmetered), so an unreadable setting never uses mobile data.
 */
internal suspend fun readWifiOnly(
    wifiOnly: Flow<Boolean>,
    timeoutMs: Long = WIFI_ONLY_READ_TIMEOUT_MS,
): Boolean =
    runCatching { withTimeoutOrNull(timeoutMs) { wifiOnly.first() } }
        .onFailure { if (it is CancellationException) throw it }
        .getOrNull() ?: true
