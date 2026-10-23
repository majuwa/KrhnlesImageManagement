package de.majuwa.android.paper.krhnlesimagemanagement

import de.majuwa.android.paper.krhnlesimagemanagement.model.BlurSensitivity
import de.majuwa.android.paper.krhnlesimagemanagement.util.BlurDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlurSensitivityTest {
    @Test
    fun `Medium is the default and equals the detector's default threshold`() {
        assertEquals(BlurSensitivity.MEDIUM, BlurSensitivity.DEFAULT)
        assertEquals(BlurDetector.DEFAULT_THRESHOLD, BlurSensitivity.MEDIUM.threshold, 0.0)
    }

    @Test
    fun `a photo is blurry only when strictly below the threshold`() {
        val threshold = BlurSensitivity.MEDIUM.threshold
        assertTrue(BlurSensitivity.MEDIUM.isBlurry(threshold - 0.001))
        assertFalse(BlurSensitivity.MEDIUM.isBlurry(threshold))
        assertFalse(BlurSensitivity.MEDIUM.isBlurry(threshold + 0.001))
    }

    @Test
    fun `fromStored maps names and falls back to Medium`() {
        BlurSensitivity.entries.forEach { assertEquals(it, BlurSensitivity.fromStored(it.name)) }
        listOf(null, "", " ", "UNKNOWN", "low").forEach {
            assertEquals(BlurSensitivity.MEDIUM, BlurSensitivity.fromStored(it))
        }
    }
}
