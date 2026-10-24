package de.majuwa.android.paper.krhnlesimagemanagement.ui

import android.content.res.Configuration
import android.net.Uri
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performSemanticsAction
import coil3.ColorImage
import coil3.annotation.ExperimentalCoilApi
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import de.majuwa.android.paper.krhnlesimagemanagement.model.MediaKind
import de.majuwa.android.paper.krhnlesimagemanagement.model.Photo
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemotePhoto
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.PhotoGridItem
import de.majuwa.android.paper.krhnlesimagemanagement.ui.components.photoPositionDescription
import de.majuwa.android.paper.krhnlesimagemanagement.ui.photogrid.DateHeader
import de.majuwa.android.paper.krhnlesimagemanagement.ui.photogrid.PhotoGrid
import de.majuwa.android.paper.krhnlesimagemanagement.ui.photogrid.PhotoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.Locale

/** TalkBack semantics of the photo grids (issues #30 / #49). */
@OptIn(ExperimentalCoilApi::class)
@RunWith(RobolectricTestRunner::class)
class PhotoGridAccessibilityTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val displayName = "IMG_20260501_123456.jpg"
    private val may1 = LocalDate.of(2026, 5, 1)

    private fun photo(
        id: Long,
        date: LocalDate = may1,
    ) = Photo(
        id = id,
        uri = Uri.parse("content://media/external/images/media/$id"),
        displayName = displayName,
        dateTaken = date,
        size = 1L,
        mimeType = "image/jpeg",
    )

    /** Coil renders a colour instead of loading anything, so no request is made in tests. */
    private fun setUiContent(content: @androidx.compose.runtime.Composable () -> Unit) {
        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalInspectionMode provides true,
                LocalAsyncImagePreviewHandler provides AsyncImagePreviewHandler { ColorImage(Color.Gray.toArgb()) },
            ) { content() }
        }
    }

    private val clickable = hasClickAction()

    private fun SemanticsNodeInteraction.config() = fetchSemanticsNode().config

    private fun SemanticsNodeInteraction.clickLabel() = config()[SemanticsActions.OnClick].label

    private fun SemanticsNodeInteraction.texts() =
        config().getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    private fun item(
        selected: Boolean = false,
        uploaded: Boolean = false,
        date: LocalDate = may1,
        position: Int = 2,
        total: Int = 3,
        formattedDate: String = "May 1, 2026",
        onClick: () -> Unit = {},
    ) = setUiContent {
        PhotoItem(photo(1, date), position, total, formattedDate, selected, uploaded, onClick)
    }

    @Test
    fun `unselected item is one checkbox with position date state and select label`() {
        item()

        val node = composeTestRule.onNode(clickable)
        val config = node.config()
        assertEquals(listOf("Photo 2 of 3, taken on May 1, 2026"), config[SemanticsProperties.ContentDescription])
        assertEquals("Not selected", config[SemanticsProperties.StateDescription])
        assertEquals(ToggleableState.Off, config[SemanticsProperties.ToggleableState])
        assertEquals(Role.Checkbox, config[SemanticsProperties.Role])
        assertEquals("Select", node.clickLabel())
        assertFalse(config.contains(SemanticsProperties.Selected))
    }

    @Test
    fun `selected item reports selected state and deselect label`() {
        item(selected = true)
        composeTestRule.onNode(clickable).config().let {
            assertEquals("Selected", it[SemanticsProperties.StateDescription])
            assertEquals(ToggleableState.On, it[SemanticsProperties.ToggleableState])
        }
        assertEquals("Deselect", composeTestRule.onNode(clickable).clickLabel())
    }

    @Test
    fun `uploaded item keeps the uploaded flag in the state description`() {
        item(uploaded = true)
        composeTestRule.onNode(clickable).config().let {
            assertEquals("Not selected, already uploaded", it[SemanticsProperties.StateDescription])
            assertEquals(ToggleableState.Off, it[SemanticsProperties.ToggleableState])
        }
    }

    @Test
    fun `selected and uploaded item combines both`() {
        item(selected = true, uploaded = true)
        composeTestRule.onNode(clickable).config().let {
            assertEquals("Selected, already uploaded", it[SemanticsProperties.StateDescription])
            assertEquals(ToggleableState.On, it[SemanticsProperties.ToggleableState])
        }
    }

    @Test
    fun `toggling changes state and label but not the description`() {
        var selected by mutableStateOf(false)
        setUiContent { PhotoItem(photo(1), 1, 3, "May 1, 2026", selected, false) { selected = !selected } }
        val before = composeTestRule.onNode(clickable).config()[SemanticsProperties.ContentDescription]

        composeTestRule.onNode(clickable).performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.waitForIdle()

        val node = composeTestRule.onNode(clickable)
        assertTrue(selected)
        assertEquals(before, node.config()[SemanticsProperties.ContentDescription])
        assertEquals("Selected", node.config()[SemanticsProperties.StateDescription])
        assertEquals("Deselect", node.clickLabel())
    }

    @Test
    fun `semantic click on an item invokes the real callback exactly once`() {
        var clicks = 0
        item(onClick = { clicks++ })

        composeTestRule.onNode(clickable).performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(1, clicks)
    }

    @Test
    fun `items of one day are distinguishable by position`() {
        val photos = mapOf(may1 to listOf(photo(1), photo(2), photo(3)))
        setGrid({ emptySet() }, {}, photos)

        // [0] is the day header; the three items follow in order.
        val descriptions =
            (1..3).map {
                composeTestRule
                    .onAllNodes(
                        clickable,
                    )[it]
                    .config()[SemanticsProperties.ContentDescription]
                    .single()
            }
        assertEquals(
            listOf(
                "Photo 1 of 3, taken on May 1, 2026",
                "Photo 2 of 3, taken on May 1, 2026",
                "Photo 3 of 3, taken on May 1, 2026",
            ),
            descriptions,
        )
    }

    @Test
    fun `item without capture date is announced as date unknown`() {
        item(date = Photo.UNKNOWN_DATE, position = 1, total = 2)

        assertEquals(
            listOf("Photo 1 of 2, date unknown"),
            composeTestRule.onNode(clickable).config()[SemanticsProperties.ContentDescription],
        )
    }

    @Test
    fun `item exposes exactly one click node and never the file name`() {
        item(selected = true, uploaded = true)

        composeTestRule.onAllNodes(clickable).assertCountEquals(1)
        val unmerged = composeTestRule.onAllNodes(SemanticsMatcher("any") { true }, true)
        unmerged.fetchSemanticsNodes().forEach { node ->
            val descriptions = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            val texts =
                node.config
                    .getOrNull(SemanticsProperties.Text)
                    .orEmpty()
                    .map { it.text }
            assertFalse(descriptions.any { displayName in it })
            assertFalse(texts.any { displayName in it })
        }
    }

    @Test
    fun `header exposes merged texts heading checkbox state and day label`() {
        setUiContent { DateHeader("May 1, 2026", 3, allSelected = false, onClick = {}) }

        val node = composeTestRule.onNode(clickable)
        val config = node.config()
        assertEquals(listOf("May 1, 2026", "3 items"), node.texts())
        assertTrue(config.contains(SemanticsProperties.Heading))
        assertEquals(Role.Checkbox, config[SemanticsProperties.Role])
        assertEquals(ToggleableState.Off, config[SemanticsProperties.ToggleableState])
        assertEquals("Not selected", config[SemanticsProperties.StateDescription])
        assertEquals("Select all items of this day", node.clickLabel())
        composeTestRule.onAllNodes(clickable).assertCountEquals(1)
        composeTestRule
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState))
            .assertCountEquals(1)
    }

    @Test
    fun `header texts use singular for one item`() {
        setUiContent { DateHeader("May 1, 2026", 1, allSelected = true, onClick = {}) }

        val node = composeTestRule.onNode(clickable)
        assertEquals(listOf("May 1, 2026", "1 item"), node.texts())
        assertEquals(ToggleableState.On, node.config()[SemanticsProperties.ToggleableState])
        assertEquals("Selected", node.config()[SemanticsProperties.StateDescription])
        assertEquals("Deselect all items of this day", node.clickLabel())
    }

    @Test
    fun `semantic click on a header invokes the real callback exactly once`() {
        var clicks = 0
        setUiContent { DateHeader("May 1, 2026", 3, allSelected = false, onClick = { clicks++ }) }

        composeTestRule.onNode(clickable).performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(1, clicks)
    }

    private fun setGrid(
        selected: () -> Set<Long>,
        onHeader: (LocalDate) -> Unit,
        photos: Map<LocalDate, List<Photo>>,
    ) = setUiContent {
        PhotoGrid(
            photosByDate = photos,
            selectedPhotoIds = selected(),
            uploadedPhotoIds = emptySet(),
            onPhotoClick = {},
            onDateHeaderClick = onHeader,
        )
    }

    @Test
    fun `grid exposes one header per day and one node per photo and header toggles the day`() {
        val day2 = LocalDate.of(2026, 5, 2)
        val photos = mapOf(may1 to listOf(photo(1), photo(2), photo(3)), day2 to listOf(photo(4, day2)))
        var selected by mutableStateOf(setOf(1L))
        setGrid({ selected }, { date ->
            val ids = photos.getValue(date).map { it.id }.toSet()
            selected = if (selected.containsAll(ids)) selected - ids else selected + ids
        }, photos)

        // 2 headers + 4 photos, each a single click node
        composeTestRule.onAllNodes(clickable).assertCountEquals(6)
        composeTestRule
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            .assertCountEquals(2)

        val firstHeader = composeTestRule.onAllNodes(clickable)[0]
        assertEquals("Select all items of this day", firstHeader.clickLabel())
        firstHeader.performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.waitForIdle()
        assertEquals(setOf(1L, 2L, 3L), selected)
        assertEquals(
            ToggleableState.On,
            composeTestRule.onAllNodes(clickable)[0].config()[SemanticsProperties.ToggleableState],
        )

        composeTestRule.onAllNodes(clickable)[0].performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.waitForIdle()
        assertTrue(selected.isEmpty())
    }

    @Test
    fun `grid shows the unknown date header and descriptions`() {
        val photos = mapOf(Photo.UNKNOWN_DATE to listOf(photo(1, Photo.UNKNOWN_DATE), photo(2, Photo.UNKNOWN_DATE)))
        setGrid({ emptySet() }, {}, photos)

        assertEquals(listOf("Unknown date", "2 items"), composeTestRule.onAllNodes(clickable)[0].texts())
        assertEquals(
            listOf("Photo 2 of 2, date unknown"),
            composeTestRule.onAllNodes(clickable)[2].config()[SemanticsProperties.ContentDescription],
        )
    }

    @Test
    @Config(qualifiers = "de")
    fun `grid renders the date in the device locale`() {
        setGrid({ emptySet() }, {}, mapOf(may1 to listOf(photo(1))))

        assertEquals(listOf("1. Mai 2026", "1 item"), composeTestRule.onAllNodes(clickable)[0].texts())
        assertTrue(
            (composeTestRule.onAllNodes(clickable)[1].config()[SemanticsProperties.ContentDescription])
                .single()
                .endsWith("taken on 1. Mai 2026"),
        )
    }

    @Test
    fun `grid re-renders the date when the locale changes`() {
        var locale by mutableStateOf(Locale.US)
        setUiContent {
            val config = Configuration(LocalConfiguration.current).apply { setLocale(locale) }
            CompositionLocalProvider(LocalConfiguration provides config) {
                PhotoGrid(mapOf(may1 to listOf(photo(1))), emptySet(), emptySet(), {}, {})
            }
        }
        assertEquals("May 1, 2026", composeTestRule.onAllNodes(clickable)[0].texts().first())

        locale = Locale.GERMANY
        composeTestRule.waitForIdle()

        assertEquals("1. Mai 2026", composeTestRule.onAllNodes(clickable)[0].texts().first())
    }

    @Test
    fun `photoPositionDescription formats one-based position and total`() {
        var text = ""
        setUiContent { text = photoPositionDescription(2, 5) }
        composeTestRule.waitForIdle()

        assertEquals("Photo 2 of 5", text)
    }

    @Test
    fun `album detail thumbnail is described by position and not by file name`() {
        val remote =
            RemotePhoto(
                displayName = displayName,
                href = "/dav/a/$displayName",
                fileId = null,
                contentType = "image/jpeg",
            )
        setUiContent {
            PhotoGridItem(
                photo = remote,
                position = 3,
                total = 5,
                thumbUrl = "https://example.org/thumb.jpg",
                isSelected = false,
                onClick = {},
                onLongClick = {},
            )
        }

        val nodes =
            composeTestRule
                .onAllNodes(
                    SemanticsMatcher("all") { true },
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
        val descriptions = nodes.flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
        assertTrue(descriptions.contains("Photo 3 of 5"))
        assertFalse(descriptions.any { displayName in it })
    }

    private val videoName = "VID_20260501_123456.mp4"

    private fun video(
        id: Long,
        date: LocalDate = may1,
        durationMs: Long? = 65_000L,
    ) = Photo(
        id = id or Photo.VIDEO_ID_FLAG,
        uri = Uri.parse("content://media/external/video/media/$id"),
        displayName = videoName,
        dateTaken = date,
        size = 1L,
        mimeType = "video/mp4",
        mediaKind = MediaKind.Video,
        durationMs = durationMs,
    )

    private fun videoItem(
        video: Photo,
        position: Int = 1,
        total: Int = 2,
    ) = setUiContent { PhotoItem(video, position, total, "May 1, 2026", false, false) {} }

    @Test
    fun `unselected video is one checkbox described as video with duration and date`() {
        videoItem(video(1))

        val node = composeTestRule.onNode(clickable)
        val config = node.config()
        assertEquals(
            listOf("Video 1 of 2, 1 minute 5 seconds, taken on May 1, 2026"),
            config[SemanticsProperties.ContentDescription],
        )
        assertEquals("Not selected", config[SemanticsProperties.StateDescription])
        assertEquals(Role.Checkbox, config[SemanticsProperties.Role])
        assertEquals("Select", node.clickLabel())
        // The duration badge and play icon are decorative: no merged text, one click node.
        assertTrue(node.texts().isEmpty())
        composeTestRule.onAllNodes(clickable).assertCountEquals(1)
    }

    @Test
    fun `video tile exposes neither the badge text nor the file name`() {
        videoItem(video(1))

        val unmerged = composeTestRule.onAllNodes(SemanticsMatcher("any") { true }, true).fetchSemanticsNodes()
        val texts =
            unmerged.flatMap { node ->
                node.config
                    .getOrNull(SemanticsProperties.Text)
                    .orEmpty()
                    .map { it.text }
            }
        val descriptions = unmerged.flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
        assertFalse(texts.any { videoName in it })
        assertFalse(descriptions.any { videoName in it })
        // Only the tile describes itself; play icon and badge ("1:05") are decorative.
        assertEquals(1, descriptions.size)
        assertFalse(texts.any { it == "1:05" })
    }

    @Test
    fun `video without capture date says date unknown`() {
        videoItem(video(1, date = Photo.UNKNOWN_DATE))

        assertEquals(
            listOf("Video 1 of 2, 1 minute 5 seconds, date unknown"),
            composeTestRule.onNode(clickable).config()[SemanticsProperties.ContentDescription],
        )
    }

    @Test
    fun `video without or with zero duration omits the duration`() {
        var current by mutableStateOf(video(1, durationMs = null))
        setUiContent { PhotoItem(current, 1, 2, "May 1, 2026", false, false) {} }
        assertEquals(
            listOf("Video 1 of 2, taken on May 1, 2026"),
            composeTestRule.onNode(clickable).config()[SemanticsProperties.ContentDescription],
        )

        current = video(1, durationMs = 0L, date = Photo.UNKNOWN_DATE)
        composeTestRule.waitForIdle()
        assertEquals(
            listOf("Video 1 of 2, date unknown"),
            composeTestRule.onNode(clickable).config()[SemanticsProperties.ContentDescription],
        )
    }

    @Test
    fun `mixed day header counts photos and videos and is on only when all are selected`() {
        val photos = mapOf(may1 to listOf(photo(1), video(1), photo(2)))
        var selected by mutableStateOf(setOf(1L, 2L))
        setGrid({ selected }, {}, photos)

        val header = composeTestRule.onAllNodes(clickable)[0]
        assertEquals(listOf("May 1, 2026", "3 items"), header.texts())
        assertEquals(ToggleableState.Off, header.config()[SemanticsProperties.ToggleableState])
        assertEquals(
            listOf("Video 2 of 3, 1 minute 5 seconds, taken on May 1, 2026"),
            composeTestRule.onAllNodes(clickable)[2].config()[SemanticsProperties.ContentDescription],
        )

        selected = setOf(1L, 2L, 1L or Photo.VIDEO_ID_FLAG)
        composeTestRule.waitForIdle()
        assertEquals(
            ToggleableState.On,
            composeTestRule.onAllNodes(clickable)[0].config()[SemanticsProperties.ToggleableState],
        )
        assertEquals("Deselect all items of this day", composeTestRule.onAllNodes(clickable)[0].clickLabel())
    }
}
