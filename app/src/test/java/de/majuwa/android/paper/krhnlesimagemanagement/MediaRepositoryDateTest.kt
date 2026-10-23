package de.majuwa.android.paper.krhnlesimagemanagement

import de.majuwa.android.paper.krhnlesimagemanagement.data.MediaRepository
import de.majuwa.android.paper.krhnlesimagemanagement.model.Photo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** A missing MediaStore DATE_TAKEN (null reads as 0) must not become a 1970 date (issues #30 / #49). */
class MediaRepositoryDateTest {
    @Test
    fun `null or zero date taken maps to the unknown sentinel`() {
        assertEquals(Photo.UNKNOWN_DATE, MediaRepository.dateTakenOf(null))
        assertEquals(Photo.UNKNOWN_DATE, MediaRepository.dateTakenOf(0L))
    }

    @Test
    fun `negative date taken is a real pre-1970 date and keeps its date`() {
        // 400 days before the epoch is in 1968-1969 in every time zone.
        val date = MediaRepository.dateTakenOf(-86_400_000L * 400)
        assertNotEquals(Photo.UNKNOWN_DATE, date)
        assertTrue(date.isBefore(LocalDate.of(1970, 1, 1)))
        assertEquals(1968, date.year)
    }

    @Test
    fun `real timestamps map to a known date`() {
        val date = MediaRepository.dateTakenOf(1_777_000_000_000L)
        assertNotEquals(Photo.UNKNOWN_DATE, date)
    }
}
