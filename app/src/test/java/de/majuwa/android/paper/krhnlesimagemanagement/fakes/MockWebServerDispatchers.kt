package de.majuwa.android.paper.krhnlesimagemanagement.fakes

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest

/** Path of the Nextcloud Memories availability check sent by `AlbumsRepository`. */
const val MEMORIES_DESCRIBE_PATH = "/index.php/apps/memories/api/describe"

/**
 * Answers the Memories availability check with [memories] (default 404 = no Memories) and every
 * other request with [response]. `listPhotos` runs the check concurrently with its PROPFIND, so
 * enqueue order is not deterministic.
 */
fun memoriesAwareDispatcher(
    response: MockResponse,
    memories: MockResponse = MockResponse().setResponseCode(404),
): Dispatcher =
    object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
            if (request.path == MEMORIES_DESCRIBE_PATH) memories else response
    }
