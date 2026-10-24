package de.majuwa.android.paper.krhnlesimagemanagement

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import de.majuwa.android.paper.krhnlesimagemanagement.ui.share.ShareError
import de.majuwa.android.paper.krhnlesimagemanagement.ui.share.ShareFinish
import de.majuwa.android.paper.krhnlesimagemanagement.ui.share.shareFinishMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ShareFinishMessageTest {
    private val resources = ApplicationProvider.getApplicationContext<Application>().resources

    private fun message(finish: ShareFinish) = shareFinishMessage(resources, finish)

    @Test
    fun errors_mapToTheirOwnMessages() {
        assertEquals(resources.getString(R.string.share_no_photos), message(ShareFinish(ShareError.NoPhotos)))
        assertEquals(resources.getString(R.string.share_copy_failed), message(ShareFinish(ShareError.CopyFailed)))
        assertEquals(
            resources.getString(R.string.share_nothing_copied),
            message(ShareFinish(ShareError.NothingCopied)),
        )
        // All items skipped is not a storage problem.
        assertNotEquals(message(ShareFinish(ShareError.CopyFailed)), message(ShareFinish(ShareError.NothingCopied)))
    }

    @Test
    fun successWithoutSkips_showsNothing() {
        assertNull(message(ShareFinish()))
    }

    @Test
    fun skippedAndOverShareLimit_haveAccurateReasons() {
        val skipped = resources.getQuantityString(R.plurals.share_copy_skipped, 2, 2, 100L)
        val overLimit = resources.getQuantityString(R.plurals.share_over_share_limit, 1, 1, 1024L)

        assertEquals(skipped, message(ShareFinish(skipped = 2)))
        assertEquals(overLimit, message(ShareFinish(overShareLimit = 1)))
        assertEquals("$skipped\n$overLimit", message(ShareFinish(skipped = 2, overShareLimit = 1)))
        assertEquals(true, overLimit.contains("1024"))
    }
}
