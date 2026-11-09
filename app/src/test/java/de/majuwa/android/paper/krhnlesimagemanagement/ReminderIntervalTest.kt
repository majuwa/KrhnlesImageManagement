package de.majuwa.android.paper.krhnlesimagemanagement

import de.majuwa.android.paper.krhnlesimagemanagement.model.ReminderInterval
import org.junit.Assert.assertEquals
import org.junit.Test

class ReminderIntervalTest {
    @Test
    fun `every name round-trips`() {
        ReminderInterval.entries.forEach { assertEquals(it, ReminderInterval.fromStored(it.name)) }
    }

    @Test
    fun `null unknown and blank fall back to Weekly`() {
        listOf(null, "", " ", "Daily", "weekly").forEach {
            assertEquals(it, ReminderInterval.Weekly, ReminderInterval.fromStored(it))
        }
    }

    @Test
    fun `periods are 7, 14 and 30 days`() {
        assertEquals(listOf(7L, 14L, 30L), ReminderInterval.entries.map { it.days })
    }
}
