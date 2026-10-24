package de.majuwa.android.paper.krhnlesimagemanagement

import de.majuwa.android.paper.krhnlesimagemanagement.data.MediaRepository
import de.majuwa.android.paper.krhnlesimagemanagement.model.Photo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

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

    private fun dayIn(
        utc: String,
        zone: String,
    ): LocalDate = MediaRepository.dateTakenOf(Instant.parse(utc).toEpochMilli(), ZoneId.of(zone))

    @Test
    fun `capture day uses the given zone east of UTC`() {
        assertEquals(LocalDate.of(2026, 5, 14), dayIn("2026-05-13T22:30:00Z", "Europe/Berlin"))
    }

    @Test
    fun `capture day across the DST change in Berlin`() {
        assertEquals(LocalDate.of(2026, 3, 29), dayIn("2026-03-29T00:30:00Z", "Europe/Berlin"))
        assertEquals(LocalDate.of(2026, 3, 30), dayIn("2026-03-29T22:30:00Z", "Europe/Berlin"))
    }

    @Test
    fun `capture day uses the given zone west of UTC`() {
        assertEquals(LocalDate.of(2026, 5, 13), dayIn("2026-05-14T06:59:00Z", "America/Los_Angeles"))
    }

    @Test
    fun `null or zero is unknown in every zone`() {
        val zone = ZoneId.of("America/Los_Angeles")
        assertEquals(Photo.UNKNOWN_DATE, MediaRepository.dateTakenOf(null, zone))
        assertEquals(Photo.UNKNOWN_DATE, MediaRepository.dateTakenOf(0L, zone))
    }
}
