package de.majuwa.android.paper.krhnlesimagemanagement

import de.majuwa.android.paper.krhnlesimagemanagement.model.AlbumSort
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemoteAlbum
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.AlbumListIndex
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.albumNameKey
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.filterAlbums
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.sortAlbums
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.visibleAlbums
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AlbumListOrderingTest {
    private fun album(
        name: String,
        modified: Long? = null,
        href: String = "/$name/",
    ) = RemoteAlbum(name, href, modified)

    private fun names(
        albums: List<RemoteAlbum>,
        query: String = "",
        sort: AlbumSort = AlbumSort.NAME_ASC,
    ) = visibleAlbums(AlbumListIndex(albums), query, sort).map { it.displayName }

    private fun matches(
        name: String,
        query: String,
    ) = names(listOf(album(name)), query).isNotEmpty()

    // ── search ──────────────────────────────────────────────────────────────

    @Test
    fun `search ignores case`() {
        assertEquals(true, matches("Summer 2025", "SUMMER"))
        assertEquals(true, matches("summer", "Sum"))
    }

    @Test
    fun `search ignores diacritics in both directions`() {
        assertEquals(true, matches("Café", "cafe"))
        assertEquals(true, matches("Cafe", "café"))
        assertEquals(true, matches("Über", "uber"))
        assertEquals(true, matches("Ärger", "arger"))
        assertEquals(true, matches("Ärger", "ÄRG"))
    }

    @Test
    fun `sharp s matches ss for both cases`() {
        assertEquals(true, matches("Straße", "strasse"))
        assertEquals(true, matches("STRAẞE", "strasse"))
        assertEquals(true, matches("Strasse", "straße"))
        assertEquals(true, matches("Strasse", "STRAẞE"))
    }

    @Test
    fun `dotted and dotless i fold to i`() {
        assertEquals(true, matches("İstanbul", "istanbul"))
        assertEquals("ı".uppercase(java.util.Locale.ROOT).lowercase(java.util.Locale.ROOT), albumNameKey("ı"))
        assertEquals(true, matches("Isparta", "ısparta"))
    }

    @Test
    fun `letters without decomposition keep their form (documented limit)`() {
        assertEquals(false, matches("Łódź", "lodz"))
        assertEquals(true, matches("Łódź", "łodz"))
        assertEquals(false, matches("Øresund", "oresund"))
    }

    @Test
    fun `blank and whitespace queries show everything`() {
        val albums = listOf(album("A"), album("B"))
        assertEquals(listOf("A", "B"), names(albums, ""))
        assertEquals(listOf("A", "B"), names(albums, "   "))
    }

    @Test
    fun `query is trimmed but inner spaces count`() {
        val albums = listOf(album("Summer 2025"), album("Summer2025"))
        assertEquals(listOf("Summer 2025"), names(albums, "  summer 2025  "))
        assertEquals(listOf("Summer2025"), names(albums, "summer2"))
    }

    @Test
    fun `no match gives an empty list`() {
        assertEquals(emptyList<String>(), names(listOf(album("A")), "zzz"))
    }

    // ── ordering ────────────────────────────────────────────────────────────

    @Test
    fun `name order ignores case and diacritics`() {
        val albums = listOf(album("b"), album("Ä"), album("A"), album("C"))
        assertEquals(listOf("A", "Ä", "b", "C"), names(albums, sort = AlbumSort.NAME_ASC))
        assertEquals(listOf("C", "b", "Ä", "A"), names(albums, sort = AlbumSort.NAME_DESC))
    }

    @Test
    fun `equal folded names are ordered deterministically for every input permutation`() {
        val a = album("Cafe", href = "/1/")
        val b = album("Café", href = "/2/")
        val c = album("Café", href = "/3/")
        val d = album("cafe", href = "/0/")
        val all = listOf(a, b, c, d)
        val expected = names(all)
        val hrefs = visibleAlbums(AlbumListIndex(all), "", AlbumSort.NAME_ASC).map { it.href }
        permutations(all).forEach { perm ->
            assertEquals(hrefs, visibleAlbums(AlbumListIndex(perm), "", AlbumSort.NAME_ASC).map { it.href })
            assertEquals(expected, names(perm))
        }
        assertEquals(listOf("/1/", "/2/", "/3/", "/0/"), hrefs)
    }

    @Test
    fun `newest first puts undated albums last`() {
        val albums = listOf(album("U1"), album("Old", 100), album("New", 300), album("Mid", 200), album("A-undated"))
        assertEquals(
            listOf("New", "Mid", "Old", "A-undated", "U1"),
            names(albums, sort = AlbumSort.MODIFIED_NEWEST),
        )
    }

    @Test
    fun `oldest first puts undated albums last too`() {
        val albums = listOf(album("U1"), album("Old", 100), album("New", 300), album("Mid", 200))
        assertEquals(listOf("Old", "Mid", "New", "U1"), names(albums, sort = AlbumSort.MODIFIED_OLDEST))
    }

    @Test
    fun `equal dates are ordered by name ascending in both directions`() {
        val albums = listOf(album("b", 5), album("a", 5), album("c", 5))
        assertEquals(listOf("a", "b", "c"), names(albums, sort = AlbumSort.MODIFIED_NEWEST))
        assertEquals(listOf("a", "b", "c"), names(albums, sort = AlbumSort.MODIFIED_OLDEST))
    }

    // ── combination ─────────────────────────────────────────────────────────

    @Test
    fun `filter and sort combine and filtering a sorted list equals sorting the filtered list`() {
        val albums =
            listOf(album("Trip B", 1), album("Home", 9), album("Trip A", 5), album("trip C"), album("Café Trip", 7))
        AlbumSort.entries.forEach { sort ->
            val index = AlbumListIndex(albums)
            val filteredAfterSort = filterAlbums(sortAlbums(index, sort), "trip").map { it.album }
            val sortedAfterFilter =
                sortAlbums(AlbumListIndex(albums.filter { it.displayName.contains("trip", ignoreCase = true) }), sort)
                    .map { it.album }
            assertEquals(sort.name, sortedAfterFilter, filteredAfterSort)
            assertEquals(filteredAfterSort, visibleAlbums(index, "trip", sort))
        }
        assertNotEquals(names(albums, "trip", AlbumSort.NAME_ASC), names(albums, "trip", AlbumSort.NAME_DESC))
    }

    @Test
    fun `empty input gives empty output for every sort`() {
        AlbumSort.entries.forEach { assertEquals(emptyList<String>(), names(emptyList(), "x", it)) }
    }

    private fun <T> permutations(items: List<T>): List<List<T>> =
        if (items.size <= 1) {
            listOf(items)
        } else {
            items.flatMapIndexed { i, item ->
                permutations(items.filterIndexed { j, _ -> j != i }).map { listOf(item) + it }
            }
        }
}
