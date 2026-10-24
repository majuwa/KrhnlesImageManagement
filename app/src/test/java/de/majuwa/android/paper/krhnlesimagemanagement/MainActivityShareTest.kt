package de.majuwa.android.paper.krhnlesimagemanagement

import android.content.Intent
import android.net.Uri
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
class MainActivityShareTest {
    @Test
    fun shareWithoutSupportedPhoto_showsNoPhotosNotice_andFinishesWithoutOpeningTheApp() {
        val intent =
            Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, Uri.parse("file:///sdcard/a.jpg"))
            }

        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        repeat(5) { shadowOf(Looper.getMainLooper()).idle() }

        val activity = controller.get()
        assertTrue(activity.isFinishing)
        assertEquals(activity.getString(R.string.share_no_photos), ShadowToast.getTextOfLatestToast())
        assertEquals(null, shadowOf(activity).nextStartedActivity)
    }
}
