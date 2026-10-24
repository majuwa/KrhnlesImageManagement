package de.majuwa.android.paper.krhnlesimagemanagement

import android.net.Uri
import de.majuwa.android.paper.krhnlesimagemanagement.util.isAllowedPhotoUri
import de.majuwa.android.paper.krhnlesimagemanagement.util.isValidFolderPath
import de.majuwa.android.paper.krhnlesimagemanagement.util.isValidOccasionName
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UploadPathsTest {
    @Test
    fun occasionName_acceptsPlainNames() {
        listOf("Summer 2026", "Trip.v2", "a..b", "  Hochzeit  ").forEach { assertTrue(it, isValidOccasionName(it)) }
    }

    @Test
    fun occasionName_rejectsDotsSlashesBackslashesBlank() {
        listOf(".", "..", " .. ", "a/b", "/", "a\\b", "\\", "", "   ", "a\u0000b").forEach {
            assertFalse(it, isValidOccasionName(it))
        }
    }

    @Test
    fun folderPath_acceptsAutoDateAndSingleNames() {
        listOf("2026/05-May", "2026/05/14", "Trip", "a/b/c").forEach { assertTrue(it, isValidFolderPath(it)) }
    }

    @Test
    fun folderPath_rejectsTraversalAndEmptySegments() {
        listOf("..", ".", "a/../b", "a//b", "/a", "a/", "a\\b", "").forEach { assertFalse(it, isValidFolderPath(it)) }
    }

    @Test
    fun photoUri_onlyForeignContentUris() {
        val pkg = "com.example.app"
        assertTrue(isAllowedPhotoUri(Uri.parse("content://media/external/images/media/1"), pkg))
        assertTrue(isAllowedPhotoUri(Uri.parse("content://com.example.apple/x"), pkg))
        assertFalse(isAllowedPhotoUri(Uri.parse("file:///data/data/com.example.app/files/x.json"), pkg))
        assertFalse(isAllowedPhotoUri(Uri.parse("http://host/x.jpg"), pkg))
        assertFalse(isAllowedPhotoUri(Uri.parse("content://com.example.app/x"), pkg))
        assertFalse(isAllowedPhotoUri(Uri.parse("content://COM.EXAMPLE.APP.files/x"), pkg))
        assertFalse(isAllowedPhotoUri(Uri.parse("content://0@com.example.app.files/x"), pkg))
        assertFalse(isAllowedPhotoUri(Uri.parse("relative/path"), pkg))
    }
}
