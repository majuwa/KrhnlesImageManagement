package de.majuwa.android.paper.krhnlesimagemanagement

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.majuwa.android.paper.krhnlesimagemanagement.ui.photogrid.durationDescription
import de.majuwa.android.paper.krhnlesimagemanagement.ui.photogrid.formatDurationBadge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Duration badge and spoken duration of video tiles (issues #15 / #34). */
@RunWith(RobolectricTestRunner::class)
class VideoDurationTest {
    private val resources = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun `badge uses m ss below one hour and h mm ss from one hour`() {
        assertEquals("0:05", formatDurationBadge(5_000))
        assertEquals("1:05", formatDurationBadge(65_000))
        assertEquals("59:59", formatDurationBadge(3_599_999))
        assertEquals("1:00:00", formatDurationBadge(3_600_000))
        assertEquals("2:03:04", formatDurationBadge((2 * 3600 + 3 * 60 + 4) * 1_000L))
    }

    @Test
    fun `sub-second durations show one second`() {
        assertEquals("0:01", formatDurationBadge(400))
        assertEquals("1 second", durationDescription(resources, 400))
    }

    @Test
    fun `unknown or zero duration has no badge and no description`() {
        assertNull(formatDurationBadge(null))
        assertNull(formatDurationBadge(0))
        assertNull(formatDurationBadge(-5))
        assertNull(durationDescription(resources, null))
        assertNull(durationDescription(resources, 0))
    }

    @Test
    fun `spoken duration uses plurals and leaves out zero parts`() {
        assertEquals("1 minute 5 seconds", durationDescription(resources, 65_000))
        assertEquals("2 minutes", durationDescription(resources, 120_000))
        assertEquals("1 hour 1 second", durationDescription(resources, 3_601_000))
        assertEquals("2 hours 3 minutes 4 seconds", durationDescription(resources, (2 * 3600 + 3 * 60 + 4) * 1_000L))
    }
}
