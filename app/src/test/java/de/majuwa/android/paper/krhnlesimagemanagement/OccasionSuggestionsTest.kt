package de.majuwa.android.paper.krhnlesimagemanagement

import android.net.Uri
import de.majuwa.android.paper.krhnlesimagemanagement.model.Photo
import de.majuwa.android.paper.krhnlesimagemanagement.model.UploadHistoryEntry
import de.majuwa.android.paper.krhnlesimagemanagement.ui.share.MAX_OCCASION_SUGGESTIONS
import de.majuwa.android.paper.krhnlesimagemanagement.ui.share.filterOccasionSuggestions
import de.majuwa.android.paper.krhnlesimagemanagement.ui.share.mergeOccasionCandidates
import de.majuwa.android.paper.krhnlesimagemanagement.ui.share.recentOccasionNames
import de.majuwa.android.paper.krhnlesimagemanagement.upload.resolveAutoDateUploadBatch
import de.majuwa.android.paper.krhnlesimagemanagement.util.isValidOccasionName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class OccasionSuggestionsTest {
    private fun entry(
        id: Long,
        name: String,
        time: Long = id,
    ) = UploadHistoryEntry(id = id, occasionName = name, timestampMillis = time, photoCount = 1, failedCount = 0)

    @Test
    fun recent_isNewestFirstRegardlessOfInputOrder() {
        val result = recentOccasionNames(listOf(entry(1, "A", 10), entry(2, "C", 30), entry(3, "B", 20)))
        assertEquals(listOf("C", "B", "A"), result)
    }

    @Test
    fun recent_dedupesCaseInsensitivelyKeepingNewestSpelling() {
        val result = recentOccasionNames(listOf(entry(1, "summer", 10), entry(2, "Summer", 20), entry(3, "Other", 5)))
        assertEquals(listOf("Summer", "Other"), result)
    }

    @Test
    fun recent_excludesInvalidAndOverlongNames() {
        val long = "a".repeat(256)
        val result =
            recentOccasionNames(
                listOf(
                    entry(1, ""),
                    entry(2, "   "),
                    entry(3, "."),
                    entry(4, ".."),
                    entry(5, "a/b"),
                    entry(6, "a\\b"),
                    entry(7, long),
                    entry(8, "2026/05/14"),
                    entry(9, "2026/04/30 – 2026/05/01"),
                    entry(10, " Trip "),
                ),
            )
        assertEquals(listOf("Trip"), result)
    }

    @Test
    fun merge_remoteSpellingReplacesCaseVariantInPlace() {
        assertEquals(listOf("Trip", "B"), mergeOccasionCandidates(listOf("trip", "B"), listOf("Trip")))
    }

    @Test
    fun merge_exactRemoteSpellingKeptWhenServerHasCaseVariant() {
        assertEquals(listOf("trip"), mergeOccasionCandidates(listOf("trip"), listOf("Trip", "trip")))
    }

    @Test
    fun merge_remoteAppendedAlphabeticallyAndDeduped() {
        val result = mergeOccasionCandidates(listOf("Zed"), listOf("beta", "Alpha", "ALPHA", "zed"))
        assertEquals(listOf("zed", "ALPHA", "beta"), result)
    }

    @Test
    fun filter_blankQueryReturnsFirstFive() {
        val c = listOf("a", "b", "c", "d", "e", "f", "g")
        assertEquals(c.take(MAX_OCCASION_SUGGESTIONS), filterOccasionSuggestions(c, ""))
        assertEquals(c.take(5), filterOccasionSuggestions(c, "   "))
    }

    @Test
    fun filter_caseInsensitiveSubstringPrefixFirst() {
        val c = listOf("Summer Trip", "Winter", "Trip to Rome", "tripod")
        assertEquals(listOf("Trip to Rome", "tripod", "Summer Trip"), filterOccasionSuggestions(c, "TRIP"))
    }

    @Test
    fun filter_hidesExactMatchButShowsCaseVariant() {
        assertEquals(listOf("Trip"), filterOccasionSuggestions(listOf("trip", "Trip"), "trip"))
    }

    @Test
    fun filter_limitsCount() {
        val c = (1..10).map { "Trip $it" }
        assertEquals(5, filterOccasionSuggestions(c, "trip").size)
    }

    @Test
    fun autoDateLabelsAreNeverValidOccasionNames() {
        fun photo(
            id: Long,
            date: LocalDate,
        ) = Photo(id, Uri.parse("content://media/external/images/media/$id"), "p", date, 1, "image/jpeg")
        val single = resolveAutoDateUploadBatch(listOf(photo(1, LocalDate.of(2026, 5, 14))))
        val range =
            resolveAutoDateUploadBatch(listOf(photo(1, LocalDate.of(2026, 4, 30)), photo(2, LocalDate.of(2026, 5, 1))))
        assertFalse(isValidOccasionName(single.label))
        assertFalse(isValidOccasionName(range.label))
    }
}
