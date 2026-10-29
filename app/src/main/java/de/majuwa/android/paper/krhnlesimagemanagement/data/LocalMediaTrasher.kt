package de.majuwa.android.paper.krhnlesimagemanagement.data

import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.activity.result.IntentSenderRequest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/** The platform allows at most 2000 uris per trash request for apps targeting API 36+. */
const val MAX_TRASH_REQUEST_URIS = 2000

/** [uris] in order, in requests of at most [MAX_TRASH_REQUEST_URIS]. */
fun chunkForTrash(uris: List<Uri>): List<List<Uri>> = uris.chunked(MAX_TRASH_REQUEST_URIS)

/**
 * Moves MediaStore items to the system trash (never deletes them). The platform only completes a
 * trash request from a visible Activity (`startIntentSenderForResult`), so this only builds the
 * request; the screen launches it. Spike result: a background worker cannot do it.
 */
interface LocalMediaTrasher {
    /** The system confirmation request for [uris] (at most [MAX_TRASH_REQUEST_URIS]). */
    fun createRequest(uris: List<Uri>): IntentSenderRequest

    /** The subset of [uris] confirmed in the trash (`IS_TRASHED = 1`); never an assumed result. */
    suspend fun trashedAmong(uris: List<Uri>): Set<Uri>
}

class MediaStoreTrasher(
    private val resolver: ContentResolver,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LocalMediaTrasher {
    override fun createRequest(uris: List<Uri>): IntentSenderRequest {
        require(uris.size <= MAX_TRASH_REQUEST_URIS) { "Too many uris for one trash request" }
        val pendingIntent = MediaStore.createTrashRequest(resolver, uris, true)
        return IntentSenderRequest.Builder(pendingIntent.intentSender).build()
    }

    override suspend fun trashedAmong(uris: List<Uri>): Set<Uri> =
        withContext(ioDispatcher) {
            uris.filterTo(mutableSetOf()) { uri ->
                ensureActive()
                isTrashed(uri)
            }
        }

    /** One uri at a time: a failing uri is "not confirmed", it never loses the result of the others. */
    private fun isTrashed(uri: Uri): Boolean {
        val args = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) }
        return try {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_TRASHED), args, null)?.use { cursor ->
                cursor.moveToFirst() && !cursor.isNull(0) && cursor.getInt(0) == 1
            } ?: false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("MediaStoreTrasher", "Trash check failed: ${e.javaClass.simpleName}")
            false
        }
    }
}
