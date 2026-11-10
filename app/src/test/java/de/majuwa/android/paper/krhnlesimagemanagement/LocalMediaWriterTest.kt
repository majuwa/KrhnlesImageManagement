package de.majuwa.android.paper.krhnlesimagemanagement

import android.content.Context
import android.net.Uri
import android.system.ErrnoException
import android.system.OsConstants
import androidx.test.core.app.ApplicationProvider
import de.majuwa.android.paper.krhnlesimagemanagement.data.LocalMediaWriteException
import de.majuwa.android.paper.krhnlesimagemanagement.data.LocalMediaWriter
import de.majuwa.android.paper.krhnlesimagemanagement.data.StorageFullException
import de.majuwa.android.paper.krhnlesimagemanagement.fakes.FakeMediaStoreProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.io.OutputStream

@RunWith(RobolectricTestRunner::class)
class LocalMediaWriterTest {
    private lateinit var context: Context
    private lateinit var writer: LocalMediaWriter

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Robolectric.setupContentProvider(FakeMediaStoreProvider::class.java, FakeMediaStoreProvider.AUTHORITY)
        FakeMediaStoreProvider.reset()
        writer = LocalMediaWriter(context)
    }

    private val path = "Pictures/KroehnSync/Album/"

    @Test
    fun `an inserted item is pending and carries path name type and owner`() {
        val item = writer.insertPending(path, "a.jpg", "image/jpeg")

        val stored = FakeMediaStoreProvider.inserted.getValue(item.uriString)
        assertTrue(stored.pending)
        assertEquals(path, stored.relativePath)
        assertEquals("a.jpg", stored.displayName)
        assertEquals("image/jpeg", stored.mimeType)
        assertEquals(context.packageName, stored.owner)
    }

    @Test
    fun `publish clears pending and the bytes are written`() {
        val item = writer.insertPending(path, "a.jpg", "image/jpeg")
        item.write { it.write(byteArrayOf(1, 2, 3)) }
        item.publish()

        val stored = FakeMediaStoreProvider.inserted.getValue(item.uriString)
        assertFalse(stored.pending)
        assertArrayEquals(byteArrayOf(1, 2, 3), stored.bytes)
    }

    @Test
    fun `discard removes the item`() =
        runTest {
            val item = writer.insertPending(path, "a.jpg", "image/jpeg")

            assertTrue(item.discard())

            assertTrue(FakeMediaStoreProvider.inserted.isEmpty())
        }

    @Test
    fun `a taken name gets a unique variant and the original is untouched`() {
        val first = writer.insertPending(path, "IMG_1.jpg", "image/jpeg")
        first.write { it.write(byteArrayOf(9)) }
        first.publish()
        val second = writer.insertPending(path, "IMG_1.jpg", "image/jpeg")
        val third = writer.insertPending(path, "img_1.JPG", "image/jpeg")

        assertEquals("IMG_1.jpg", FakeMediaStoreProvider.inserted.getValue(first.uriString).displayName)
        assertEquals("IMG_1 (1).jpg", FakeMediaStoreProvider.inserted.getValue(second.uriString).displayName)
        assertEquals("img_1 (2).JPG", FakeMediaStoreProvider.inserted.getValue(third.uriString).displayName)
        assertArrayEquals(byteArrayOf(9), FakeMediaStoreProvider.inserted.getValue(first.uriString).bytes)
    }

    @Test
    fun `a leftover is discarded only when pending and owned by this package`() =
        runTest {
            val pending = writer.insertPending(path, "p.jpg", "image/jpeg")
            val published = writer.insertPending(path, "q.jpg", "image/jpeg").also { it.publish() }
            FakeMediaStoreProvider.ownerPackage = "com.other.app"
            val foreign = writer.insertPending(path, "f.jpg", "image/jpeg")
            FakeMediaStoreProvider.ownerPackage = null

            assertFalse(writer.discardLeftover(published.uriString))
            assertFalse(writer.discardLeftover(foreign.uriString))
            assertTrue(writer.discardLeftover(pending.uriString))

            assertEquals(setOf(published.uriString, foreign.uriString), FakeMediaStoreProvider.inserted.keys)
            assertEquals(listOf(pending.uriString), FakeMediaStoreProvider.deletes)
        }

    @Test
    fun `a leftover with another authority or a video or malformed uri is refused`() =
        runTest {
            val pending = writer.insertPending(path, "p.jpg", "image/jpeg")
            val id = Uri.parse(pending.uriString).lastPathSegment
            val refused =
                listOf(
                    "content://other.provider/external_primary/images/media/$id",
                    "content://media/external_primary/video/media/$id",
                    "content://media/external_primary/images/media/$id?x=1",
                    "content://media/external_primary/images/media/-1",
                    "content://media/external_primary/images/media/abc",
                    "content://media/images/media/$id",
                    "file:///data/data/x/files/a.jpg",
                    "",
                    "not a uri",
                )
            refused.forEach { assertFalse(it, writer.discardLeftover(it)) }

            assertTrue(FakeMediaStoreProvider.deletes.isEmpty())
            assertTrue(FakeMediaStoreProvider.inserted.containsKey(pending.uriString))
        }

    @Test
    fun `a refused insert open and publish are write failures`() {
        FakeMediaStoreProvider.insertFailure = IllegalStateException("provider")
        assertThrows(LocalMediaWriteException::class.java) { writer.insertPending(path, "a.jpg", "image/jpeg") }
        FakeMediaStoreProvider.insertFailure = null

        val item = writer.insertPending(path, "a.jpg", "image/jpeg")
        FakeMediaStoreProvider.openFailure = java.io.FileNotFoundException()
        assertThrows(LocalMediaWriteException::class.java) { item.write { } }
        FakeMediaStoreProvider.openFailure = null

        FakeMediaStoreProvider.updateFailure = SecurityException("no")
        assertThrows(LocalMediaWriteException::class.java) { item.publish() }
    }

    @Test
    fun `a full device while writing is a storage full failure`() {
        val failing =
            LocalMediaWriter(context) { _, _ ->
                object : OutputStream() {
                    override fun write(b: Int) = throw IOException("write", ErrnoException("write", OsConstants.ENOSPC))
                }
            }
        val item = failing.insertPending(path, "a.jpg", "image/jpeg")

        assertThrows(StorageFullException::class.java) { item.write { it.write(1) } }
    }

    @Test
    fun `another write error is a write failure but a failure of the source keeps its type`() {
        val failing =
            LocalMediaWriter(context) { _, _ ->
                object : OutputStream() {
                    override fun write(b: Int) = throw IOException("disk")
                }
            }
        val item = failing.insertPending(path, "a.jpg", "image/jpeg")

        assertThrows(LocalMediaWriteException::class.java) { item.write { it.write(1) } }
        assertThrows(
            java.net.SocketTimeoutException::class.java,
        ) { item.write { throw java.net.SocketTimeoutException() } }
    }

    @Test
    fun `any provider failure is a write failure whatever its type`() {
        listOf<RuntimeException>(
            UnsupportedOperationException("no"),
            NullPointerException("npe"),
            ArithmeticException("x"),
        ).forEach { failure ->
            FakeMediaStoreProvider.insertFailure = failure
            assertThrows(failure.toString(), LocalMediaWriteException::class.java) {
                writer.insertPending(path, "a.jpg", "image/jpeg")
            }
        }
        FakeMediaStoreProvider.insertFailure = null

        val item = writer.insertPending(path, "a.jpg", "image/jpeg")
        FakeMediaStoreProvider.updateFailure = UnsupportedOperationException("no")
        assertThrows(LocalMediaWriteException::class.java) { item.publish() }
    }

    @Test
    fun `a destination that fails with a runtime error is a write failure`() {
        val opening = LocalMediaWriter(context) { _, _ -> throw UnsupportedOperationException("no stream") }
        val item = opening.insertPending(path, "a.jpg", "image/jpeg")
        assertThrows(LocalMediaWriteException::class.java) { item.write { } }

        val writing =
            LocalMediaWriter(context) { _, _ ->
                object : OutputStream() {
                    override fun write(b: Int) = throw UnsupportedOperationException("provider died")
                }
            }
        val second = writing.insertPending(path, "b.jpg", "image/jpeg")
        assertThrows(LocalMediaWriteException::class.java) { second.write { it.write(1) } }
    }

    @Test
    fun `a failing delete reports false instead of throwing`() =
        runTest {
            val item = writer.insertPending(path, "a.jpg", "image/jpeg")
            FakeMediaStoreProvider.deleteFailure = IllegalStateException("provider")

            assertFalse(item.discard())

            FakeMediaStoreProvider.deleteFailure = UnsupportedOperationException("no")
            assertFalse(item.discard())
            FakeMediaStoreProvider.deleteFailure = null
            assertTrue(item.discard())
        }
}
