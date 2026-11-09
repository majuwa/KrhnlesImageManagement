package de.majuwa.android.paper.krhnlesimagemanagement.reminder

import de.majuwa.android.paper.krhnlesimagemanagement.model.MediaKind
import de.majuwa.android.paper.krhnlesimagemanagement.model.Photo
import de.majuwa.android.paper.krhnlesimagemanagement.model.UploadHistoryEntry
import de.majuwa.android.paper.krhnlesimagemanagement.model.mediaItemId
import de.majuwa.android.paper.krhnlesimagemanagement.ui.photogrid.MediaAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NewMediaCountTest {
    private fun row(
        id: Long,
        seconds: Long,
    ) = NewMediaRow(id, seconds)

    private fun count(
        rows: List<NewMediaRow>,
        uploaded: Set<Long> = emptySet(),
        baselineMillis: Long = 100_000L,
        lastShownMillis: Long = 0L,
        capReached: Boolean = false,
    ) = countNewMedia(rows, uploaded, baselineMillis, lastShownMillis, capReached)

    @Test
    fun `marked items are not counted`() {
        val rows = (1L..5L).map { row(it, 200) }
        assertEquals(3, count(rows, uploaded = setOf(1L, 2L)).total)
    }

    @Test
    fun `items at or before the baseline second are not counted`() {
        val rows = listOf(row(1, 99), row(2, 100), row(3, 101))
        // baseline 100_999 ms is still second 100: only the item of second 101 is later.
        assertEquals(1, count(rows, baselineMillis = 100_999L).total)
        assertEquals(2, count(rows, baselineMillis = 99_000L).total)
    }

    @Test
    fun `a video with the raw id of a marked image is counted`() {
        val videoId = requireNotNull(mediaItemId(42, MediaKind.Video))
        val result = count(listOf(row(videoId, 200)), uploaded = setOf(42L))
        assertEquals(1, result.total)
        assertTrue(videoId and Photo.VIDEO_ID_FLAG != 0L)
    }

    @Test
    fun `nothing newer than the last reminder means no new items since it`() {
        val rows = listOf(row(1, 200), row(2, 210))
        assertFalse(count(rows, lastShownMillis = 210_000L).hasNewSinceLastReminder)
        assertTrue(count(rows, lastShownMillis = 205_000L).hasNewSinceLastReminder)
        assertTrue(count(rows, lastShownMillis = 0L).hasNewSinceLastReminder)
    }

    @Test
    fun `row cap makes the count a lower bound`() {
        assertTrue(count(listOf(row(1, 200)), capReached = true).isLowerBound)
        assertFalse(count(listOf(row(1, 200))).isLowerBound)
    }

    private fun entry(
        timestamp: Long,
        count: Int,
        failed: Int,
        cancelled: Boolean = false,
    ) = UploadHistoryEntry(timestamp, "x", timestamp, count, failed, cancelled)

    @Test
    fun `baseline is since without history`() {
        assertEquals(500L, reminderBaseline(500L, emptyList()))
    }

    @Test
    fun `failed-only run is ignored`() {
        val history = listOf(entry(900, count = 3, failed = 3), entry(700, count = 3, failed = 1))
        assertEquals(700L, reminderBaseline(500L, history))
    }

    @Test
    fun `a cancelled run with an uploaded item moves the baseline`() {
        assertEquals(900L, reminderBaseline(500L, listOf(entry(900, count = 10, failed = 7, cancelled = true))))
    }

    @Test
    fun `an upload before the reminder was turned on does not lower the baseline`() {
        assertEquals(500L, reminderBaseline(500L, listOf(entry(300, count = 1, failed = 0))))
    }

    @Test
    fun `query kinds follow the access`() {
        val both = setOf(MediaKind.Image, MediaKind.Video)
        assertEquals(both, queryableKinds(MediaAccess(images = true, video = true, userSelected = false)))
        assertEquals(
            setOf(MediaKind.Image),
            queryableKinds(MediaAccess(images = true, video = false, userSelected = false)),
        )
        assertEquals(
            setOf(MediaKind.Video),
            queryableKinds(MediaAccess(images = false, video = true, userSelected = false)),
        )
        assertEquals(both, queryableKinds(MediaAccess(images = false, video = false, userSelected = true)))
        assertEquals(both, queryableKinds(MediaAccess(images = true, video = false, userSelected = true)))
        assertTrue(queryableKinds(MediaAccess(false, false, false, location = true)).isEmpty())
        assertTrue(queryableKinds(MediaAccess(false, false, false)).isEmpty())
    }

    @Test
    fun `selection has the arguments in order and one path clause per prefix`() {
        val selection = reminderSelection(123L, "my.pkg", listOf("Pictures/A/", "DCIM/B/"))
        assertEquals(listOf("123", "my.pkg", "Pictures/A/%", "DCIM/B/%"), selection.args.toList())
        assertEquals(4, "\\?".toRegex().findAll(selection.selection).count())
        assertEquals(2, "relative_path NOT LIKE \\?".toRegex().findAll(selection.selection).count())
        assertTrue(selection.selection.contains("is_pending = 0 AND is_trashed = 0"))
        assertTrue(selection.selection.contains("owner_package_name IS NULL OR owner_package_name != ?"))
    }
}
