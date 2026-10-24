package de.majuwa.android.paper.krhnlesimagemanagement.upload

import android.net.Uri
import de.majuwa.android.paper.krhnlesimagemanagement.data.MediaRepository
import de.majuwa.android.paper.krhnlesimagemanagement.model.Photo
import de.majuwa.android.paper.krhnlesimagemanagement.util.isValidFolderPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class UploadBatchTest {
    private lateinit var originalLocale: Locale

    @Before
    fun rememberLocale() {
        originalLocale = Locale.getDefault()
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    private fun photo(
        id: Long,
        dateTaken: LocalDate,
    ) = Photo(
        id = id,
        uri = Uri.parse("content://media/external/images/media/$id"),
        displayName = "$id.jpg",
        dateTaken = dateTaken,
        size = 1_024,
        mimeType = "image/jpeg",
    )

    private fun foldersOf(vararg dates: LocalDate): List<String> =
        resolveAutoDateUploadBatch(dates.mapIndexed { i, d -> photo(i.toLong(), d) }).folders

    @Test
    fun `day folders are zero padded digits`() {
        assertEquals(listOf("2026/03/09"), foldersOf(LocalDate.of(2026, 3, 9)))
        assertEquals(listOf("2026/12/31"), foldersOf(LocalDate.of(2026, 12, 31)))
    }

    @Test
    fun `folder name does not depend on an Arabic default locale`() {
        val arabic = Locale.forLanguageTag("ar-EG-u-nu-arab")
        // Precondition: this locale really formats digits differently, otherwise the test proves nothing.
        assertNotEquals("1", String.format(arabic, "%d", 1))
        Locale.setDefault(arabic)

        assertEquals(listOf("2026/03/09"), foldersOf(LocalDate.of(2026, 3, 9)))
        assertEquals("2026/03/09", resolveAutoDateUploadBatch(listOf(photo(1, LocalDate.of(2026, 3, 9)))).label)
    }

    @Test
    fun `folder name does not depend on a German default locale`() {
        Locale.setDefault(Locale.GERMANY)

        assertEquals(listOf("2026/03/09"), foldersOf(LocalDate.of(2026, 3, 9)))
    }

    @Test
    fun `day month and year boundaries give different folders in chronological order`() {
        val batch =
            resolveAutoDateUploadBatch(
                listOf(
                    photo(1, LocalDate.of(2026, 2, 1)),
                    photo(2, LocalDate.of(2025, 12, 31)),
                    photo(3, LocalDate.of(2026, 1, 31)),
                    photo(4, LocalDate.of(2026, 1, 1)),
                ),
            )

        assertEquals(listOf(2L, 4L, 3L, 1L), batch.photos.map { it.id })
        assertEquals(listOf("2025/12/31", "2026/01/01", "2026/01/31", "2026/02/01"), batch.folders)
    }

    @Test
    fun `photos of one day are ordered by id`() {
        val day = LocalDate.of(2026, 5, 14)
        val batch = resolveAutoDateUploadBatch(listOf(photo(9, day), photo(2, day), photo(5, day)))

        assertEquals(listOf(2L, 5L, 9L), batch.photos.map { it.id })
        assertEquals(List(3) { "2026/05/14" }, batch.folders)
    }

    @Test
    fun `one day selection is one batch labelled with its day folder`() {
        val day = LocalDate.of(2026, 5, 14)
        val batch = resolveAutoDateUploadBatch(listOf(photo(1, day), photo(2, day)))

        assertEquals("2026/05/14", batch.label)
    }

    @Test
    fun `multi day selection is exactly one batch labelled first to last day`() {
        val batch: UploadBatch =
            resolveAutoDateUploadBatch(
                listOf(
                    photo(3, LocalDate.of(2026, 5, 1)),
                    photo(1, LocalDate.of(2026, 4, 30)),
                    photo(2, LocalDate.of(2026, 4, 30)),
                ),
            )

        val perPhoto = batch as UploadBatch.PerPhoto
        assertEquals(listOf(1L, 2L, 3L), perPhoto.photos.map { it.id })
        assertEquals(listOf("2026/04/30", "2026/04/30", "2026/05/01"), perPhoto.folders)
        assertEquals("2026/04/30 – 2026/05/01", perPhoto.label)
    }

    /** Unknown capture dates (`Photo.UNKNOWN_DATE`) fall into the `1970/01/01` bucket; see docs/architecture.md. */
    @Test
    fun `photos without capture date are uploaded to 1970-01-01`() {
        assertEquals(listOf("1970/01/01"), foldersOf(Photo.UNKNOWN_DATE))
    }

    @Test
    fun `pre-1970 photos keep their real date`() {
        val batch =
            resolveAutoDateUploadBatch(listOf(photo(2, Photo.UNKNOWN_DATE), photo(1, LocalDate.of(1969, 12, 31))))

        assertEquals(listOf("1969/12/31", "1970/01/01"), batch.folders)
        assertEquals("1969/12/31 – 1970/01/01", batch.label)
    }

    @Test
    fun `every resolved folder is a valid folder path`() {
        val dates =
            listOf(
                LocalDate.of(2026, 3, 9),
                LocalDate.of(1969, 12, 31),
                Photo.UNKNOWN_DATE,
                LocalDate.of(2026, 12, 31),
            )
        foldersOf(*dates.toTypedArray()).forEach { assertTrue(it, isValidFolderPath(it)) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `per photo batch requires one folder per photo`() {
        UploadBatch.PerPhoto(listOf(photo(1, LocalDate.of(2026, 5, 1))), emptyList(), "x")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `per photo batch rejects blank folders`() {
        UploadBatch.PerPhoto(listOf(photo(1, LocalDate.of(2026, 5, 1))), listOf(" "), "x")
    }

    @Test
    fun `day folder equals the date the grid groups the photo under`() {
        val berlin = ZoneId.of("Europe/Berlin")
        val date = MediaRepository.dateTakenOf(Instant.parse("2026-05-13T22:30:00Z").toEpochMilli(), berlin)
        val photo = photo(1, date)
        // The grid groups by Photo.dateTaken (PhotoGridViewModel.loadPhotos).
        val gridHeader = listOf(photo).groupBy { it.dateTaken }.keys.single()

        assertEquals(LocalDate.of(2026, 5, 14), gridHeader)
        assertEquals(listOf("2026/05/14"), resolveAutoDateUploadBatch(listOf(photo)).folders)
    }

    @Test
    fun `previewUploadPath prefixes base folder when present`() {
        assertEquals("Photos/2026/05/14/", previewUploadPath("Photos", "2026/05/14"))
        assertEquals("Photos/Krohn/2026/05/14/", previewUploadPath("/Photos/Krohn/", "/2026/05/14/"))
        assertEquals("2026/05/14/", previewUploadPath("", "2026/05/14"))
        assertEquals("2026/05/14/", previewUploadPath("   ", "2026/05/14"))
    }
}
