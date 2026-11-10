package de.majuwa.android.paper.krhnlesimagemanagement

import de.majuwa.android.paper.krhnlesimagemanagement.model.AlbumSort
import org.junit.Assert.assertEquals
import org.junit.Test

class AlbumSortTest {
    @Test
    fun `default is name ascending`() {
        assertEquals(AlbumSort.NAME_ASC, AlbumSort.DEFAULT)
    }

    @Test
    fun `fromStored round trips every option`() {
        AlbumSort.entries.forEach { assertEquals(it, AlbumSort.fromStored(it.name)) }
    }

    @Test
    fun `fromStored falls back to the default for null blank and unknown`() {
        listOf(null, "", "  ", "newest", "name_asc").forEach {
            assertEquals(AlbumSort.DEFAULT, AlbumSort.fromStored(it))
        }
    }
}
