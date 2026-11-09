package de.majuwa.android.paper.krhnlesimagemanagement.ui.navigation

import android.content.Intent
import androidx.core.net.toUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LaunchRequestTest {
    @Test
    fun `the reminder action is a ShowNewMedia request`() {
        assertEquals(AppLaunchRequest.ShowNewMedia, launchRequestOf(Intent(ACTION_SHOW_NEW_MEDIA)))
    }

    @Test
    fun `launcher share and unknown actions are no request`() {
        assertNull(launchRequestOf(Intent(Intent.ACTION_MAIN)))
        assertNull(launchRequestOf(Intent(Intent.ACTION_SEND)))
        assertNull(launchRequestOf(Intent(Intent.ACTION_SEND_MULTIPLE)))
        assertNull(launchRequestOf(Intent("evil.action.SHOW_NEW_MEDIA")))
        assertNull(launchRequestOf(Intent()))
        assertNull(launchRequestOf(null))
    }

    @Test
    fun `an intent re-delivered from the recents screen is no request`() {
        val fromHistory = Intent(ACTION_SHOW_NEW_MEDIA).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        assertNull(launchRequestOf(fromHistory))
        assertEquals(AppLaunchRequest.ShowNewMedia, launchRequestOf(Intent(ACTION_SHOW_NEW_MEDIA)))
    }

    @Test
    fun `extras and data are ignored`() {
        val intent =
            Intent(ACTION_SHOW_NEW_MEDIA)
                .setData("content://evil/1".toUri())
                .putExtra("filter", false)
                .putExtra(Intent.EXTRA_STREAM, "x")
        assertEquals(AppLaunchRequest.ShowNewMedia, launchRequestOf(intent))
        assertNull(launchRequestOf(Intent(Intent.ACTION_MAIN).putExtra("action", ACTION_SHOW_NEW_MEDIA)))
    }

    @Test
    fun `the action is namespaced by the package`() {
        assertEquals(
            "de.majuwa.android.paper.krhnlesimagemanagement.action.SHOW_NEW_MEDIA",
            ACTION_SHOW_NEW_MEDIA,
        )
    }

    @Test
    fun `view model holds the request until it is consumed`() {
        val viewModel = LaunchRequestViewModel()
        assertNull(viewModel.pending.value)
        viewModel.offer(AppLaunchRequest.ShowNewMedia)
        assertEquals(AppLaunchRequest.ShowNewMedia, viewModel.pending.value)
        viewModel.consume()
        assertNull(viewModel.pending.value)
    }
}
