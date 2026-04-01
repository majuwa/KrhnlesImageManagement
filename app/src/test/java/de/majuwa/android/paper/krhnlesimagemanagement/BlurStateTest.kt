package de.majuwa.android.paper.krhnlesimagemanagement

import de.majuwa.android.paper.krhnlesimagemanagement.model.RemotePhoto
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.BlurState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlurStateTest {
    private val photo1 = RemotePhoto("img1.jpg", "/photos/img1.jpg", null, "image/jpeg")
    private val photo2 = RemotePhoto("img2.jpg", "/photos/img2.jpg", null, "image/jpeg")

    @Test
    fun `Idle is the default state`() {
        val state: BlurState = BlurState.Idle
        assertTrue(state is BlurState.Idle)
    }

    @Test
    fun `Scanning tracks progress`() {
        val state = BlurState.Scanning(processed = 5, total = 10)
        assertEquals(5, state.processed)
        assertEquals(10, state.total)
    }

    @Test
    fun `Found contains blurry photos and scores`() {
        val scores = mapOf(photo1.href to 120.0, photo2.href to 80.0)
        val state = BlurState.Found(blurryPhotos = listOf(photo1, photo2), scores = scores)

        assertEquals(2, state.blurryPhotos.size)
        assertEquals(120.0, state.scores[photo1.href]!!, 0.01)
        assertEquals(80.0, state.scores[photo2.href]!!, 0.01)
    }

    @Test
    fun `Found with empty list is valid`() {
        val state = BlurState.Found(blurryPhotos = emptyList(), scores = emptyMap())
        assertTrue(state.blurryPhotos.isEmpty())
        assertTrue(state.scores.isEmpty())
    }

    @Test
    fun `Error carries message`() {
        val state = BlurState.Error("Network failed")
        assertEquals("Network failed", state.message)
    }

    @Test
    fun `Scanning progress at zero`() {
        val state = BlurState.Scanning(processed = 0, total = 50)
        assertEquals(0, state.processed)
        assertEquals(50, state.total)
    }

    @Test
    fun `Scanning progress at completion`() {
        val state = BlurState.Scanning(processed = 50, total = 50)
        assertEquals(state.processed, state.total)
    }
}
