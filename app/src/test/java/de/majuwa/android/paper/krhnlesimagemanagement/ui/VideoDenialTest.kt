package de.majuwa.android.paper.krhnlesimagemanagement.ui

import android.Manifest
import de.majuwa.android.paper.krhnlesimagemanagement.ui.photogrid.MediaAccess
import de.majuwa.android.paper.krhnlesimagemanagement.ui.photogrid.VideoDenial
import de.majuwa.android.paper.krhnlesimagemanagement.ui.photogrid.videoDenial
import org.junit.Assert.assertEquals
import org.junit.Test

/** A denial without rationale is permanent only after a rationale was offered; a dismissal is not. */
class VideoDenialTest {
    private val imagesOnly = MediaAccess(images = true, video = false, userSelected = false)
    private val denied = mapOf(Manifest.permission.READ_MEDIA_VIDEO to false)

    @Test
    fun firstDialogDismissed_isNotPermanent() {
        assertEquals(
            VideoDenial.Dismissed,
            videoDenial(imagesOnly, denied, rationaleNow = false, rationaleShownBefore = false),
        )
    }

    @Test
    fun firstDenial_offersTheRationale() {
        assertEquals(
            VideoDenial.AskAgain,
            videoDenial(imagesOnly, denied, rationaleNow = true, rationaleShownBefore = false),
        )
    }

    @Test
    fun denialAfterARationale_isPermanent() {
        assertEquals(
            VideoDenial.Permanent,
            videoDenial(imagesOnly, denied, rationaleNow = false, rationaleShownBefore = true),
        )
    }

    @Test
    fun dismissalAfterOneDenial_keepsTheRationale() {
        assertEquals(
            VideoDenial.AskAgain,
            videoDenial(imagesOnly, denied, rationaleNow = true, rationaleShownBefore = true),
        )
    }

    @Test
    fun grantedOrNotAskedOrNotMissing_isNotDenied() {
        val full = MediaAccess(images = true, video = true, userSelected = false)
        val granted = mapOf(Manifest.permission.READ_MEDIA_VIDEO to true)

        assertEquals(
            VideoDenial.NotDenied,
            videoDenial(full, granted, rationaleNow = false, rationaleShownBefore = true),
        )
        assertEquals(
            VideoDenial.NotDenied,
            videoDenial(imagesOnly, emptyMap(), rationaleNow = false, rationaleShownBefore = true),
        )
        assertEquals(
            VideoDenial.NotDenied,
            videoDenial(full, denied, rationaleNow = false, rationaleShownBefore = true),
        )
    }
}
