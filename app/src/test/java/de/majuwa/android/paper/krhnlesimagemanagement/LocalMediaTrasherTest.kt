package de.majuwa.android.paper.krhnlesimagemanagement

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import de.majuwa.android.paper.krhnlesimagemanagement.data.MAX_TRASH_REQUEST_URIS
import de.majuwa.android.paper.krhnlesimagemanagement.data.MediaStoreTrasher
import de.majuwa.android.paper.krhnlesimagemanagement.data.chunkForTrash
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeMediaStoreProvider
import de.majuwa.android.paper.krhnlesimagemanagement.util.MediaFileState
import de.majuwa.android.paper.krhnlesimagemanagement.util.mediaFileStateOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LocalMediaTrasherTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Before
    fun setUp() {
        Robolectric.setupContentProvider(FakeMediaStoreProvider::class.java, FakeMediaStoreProvider.AUTHORITY)
        FakeMediaStoreProvider.reset()
    }

    private fun uri(id: Long): Uri = Uri.parse("content://media/external/images/media/$id")

    @Test
    fun `uris are split into requests of at most 2000 in order`() {
        val uris = (1L..4_500L).map(::uri)

        val chunks = chunkForTrash(uris)

        assertEquals(listOf(2000, 2000, 500), chunks.map { it.size })
        assertEquals(uris, chunks.flatten())
        assertEquals(2000, MAX_TRASH_REQUEST_URIS)
    }

    @Test
    fun `exactly 2000 uris are one request and none are none`() {
        assertEquals(1, chunkForTrash((1L..2000L).map(::uri)).size)
        assertEquals(emptyList<List<Uri>>(), chunkForTrash(emptyList()))
    }

    @Test
    fun `a request for more than 2000 uris is refused`() {
        val trasher = MediaStoreTrasher(context.contentResolver)
        assertThrows(IllegalArgumentException::class.java) {
            trasher.createRequest((1L..2001L).map(::uri))
        }
    }

    @Test
    fun `only items that are in the trash are confirmed`() =
        runTest {
            FakeMediaStoreProvider.itemRows[uri(1).toString()] = FakeMediaStoreProvider.item(1, 1, trashed = true)
            FakeMediaStoreProvider.itemRows[uri(2).toString()] = FakeMediaStoreProvider.item(1, 1, trashed = false)
            // 3 has no row at all (gone).

            val trashed = MediaStoreTrasher(context.contentResolver).trashedAmong(listOf(uri(1), uri(2), uri(3)))

            assertEquals(setOf(uri(1)), trashed)
        }

    @Test
    fun `a failing query of one uri never loses the confirmation of the others`() =
        runTest {
            listOf(1L, 2L, 3L).forEach {
                FakeMediaStoreProvider.itemRows[uri(it).toString()] = FakeMediaStoreProvider.item(1, 1, trashed = true)
            }
            FakeMediaStoreProvider.itemFailures[uri(2).toString()] = IllegalStateException("provider crashed")

            val trashed = MediaStoreTrasher(context.contentResolver).trashedAmong(listOf(uri(1), uri(2), uri(3)))

            assertEquals(setOf(uri(1), uri(3)), trashed)
        }

    @Test
    fun `mediaFileStateOf treats trashed and pending items as missing although the query returns them`() {
        val resolver = context.contentResolver
        FakeMediaStoreProvider.itemRows[uri(1).toString()] = FakeMediaStoreProvider.item(10, 5)
        FakeMediaStoreProvider.itemRows[uri(2).toString()] = FakeMediaStoreProvider.item(10, 5, trashed = true)
        FakeMediaStoreProvider.itemRows[uri(3).toString()] = FakeMediaStoreProvider.item(10, 5, pending = true)
        FakeMediaStoreProvider.itemRows[uri(4).toString()] = FakeMediaStoreProvider.item(10, 5)
        FakeMediaStoreProvider.itemFailures[uri(4).toString()] = IllegalStateException("provider crashed")

        assertEquals(MediaFileState(10, 5), resolver.mediaFileStateOf(uri(1)))
        assertNull(resolver.mediaFileStateOf(uri(2)))
        assertNull(resolver.mediaFileStateOf(uri(3)))
        assertNull("any provider failure is null, never a crash", resolver.mediaFileStateOf(uri(4)))
    }
}
