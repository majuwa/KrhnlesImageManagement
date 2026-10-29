package de.majuwa.android.paper.krhnlesimagemanagement.fakes

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.activity.result.IntentSenderRequest
import androidx.test.core.app.ApplicationProvider
import de.majuwa.android.paper.krhnlesimagemanagement.data.LocalMediaTrasher
import de.majuwa.android.paper.krhnlesimagemanagement.data.RemovalCandidate
import de.majuwa.android.paper.krhnlesimagemanagement.data.RemovalCandidateChecker
import de.majuwa.android.paper.krhnlesimagemanagement.data.RemovalCheck
import de.majuwa.android.paper.krhnlesimagemanagement.data.VerifiedUpload
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import kotlinx.coroutines.CompletableDeferred

/** A verified record for the MediaStore image [id]. */
fun verifiedRecord(
    id: Long,
    serverUrl: String = "https://cloud.example.com/remote.php/dav/files/u/",
) = VerifiedUpload(
    id = id,
    uri = "content://media/external/images/media/$id",
    size = 100L,
    dateModified = 1_700_000_000L,
    serverUrl = serverUrl,
    folder = "Trip",
    fileName = "img_$id.jpg",
    verifiedAt = id,
)

class FakeRemovalChecker : RemovalCandidateChecker {
    /** Record ids that pass [check]; others are kept. */
    var passing: Set<Long> = emptySet()

    /** Record ids that still pass [recheckLocal]; null = the same as [passing]. */
    var passingLocal: Set<Long>? = null
    var checkCalls = 0
    var recheckCalls = 0

    /** When set, [check] suspends until it completes (simulates a slow check). */
    var gate: CompletableDeferred<Unit>? = null

    /** When non-null, [check] throws this. */
    var failure: Throwable? = null

    /** When non-null, [recheckLocal] throws this. */
    var recheckFailure: Throwable? = null

    /** When true, [check] reports an unreachable server (no candidates). */
    var serverUnreachable = false
    var lastConfig: WebDavConfig? = null

    /** The records passed to the last [check]. */
    var lastRecords: List<VerifiedUpload> = emptyList()

    override suspend fun check(
        records: List<VerifiedUpload>,
        config: WebDavConfig,
        onProgress: (done: Int, total: Int) -> Unit,
    ): RemovalCheck {
        checkCalls++
        lastConfig = config
        lastRecords = records
        failure?.let { throw it }
        onProgress(0, records.size)
        gate?.await()
        if (serverUnreachable) return RemovalCheck(emptyList(), serverUnreachable = true)
        records.forEachIndexed { i, _ -> onProgress(i + 1, records.size) }
        return RemovalCheck(records.filter { it.id in passing }.map { RemovalCandidate(it, Uri.parse(it.uri)) })
    }

    override suspend fun recheckLocal(candidates: List<RemovalCandidate>): List<RemovalCandidate> {
        recheckCalls++
        recheckFailure?.let { throw it }
        val allowed = passingLocal ?: passing
        return candidates.filter { it.record.id in allowed }
    }
}

class FakeLocalMediaTrasher : LocalMediaTrasher {
    /** The uris of every created request, in order. */
    val requests = mutableListOf<List<Uri>>()

    /** Uris reported as trashed by [trashedAmong]. */
    val trashed = mutableSetOf<Uri>()
    var createFailure: Throwable? = null
    var confirmFailure: Throwable? = null

    override fun createRequest(uris: List<Uri>): IntentSenderRequest {
        createFailure?.let { throw it }
        requests.add(uris)
        val pending =
            PendingIntent.getActivity(
                ApplicationProvider.getApplicationContext(),
                requests.size,
                Intent("test.TRASH"),
                PendingIntent.FLAG_IMMUTABLE,
            )
        return IntentSenderRequest.Builder(pending.intentSender).build()
    }

    /** Number of [trashedAmong] calls. */
    var confirmCalls = 0

    override suspend fun trashedAmong(uris: List<Uri>): Set<Uri> {
        confirmCalls++
        confirmFailure?.let { throw it }
        return uris.filterTo(mutableSetOf()) { it in trashed }
    }
}
