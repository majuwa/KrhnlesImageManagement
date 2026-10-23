package de.majuwa.android.paper.krhnlesimagemanagement

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.majuwa.android.paper.krhnlesimagemanagement.data.WebDavHttpException
import de.majuwa.android.paper.krhnlesimagemanagement.ui.albums.AlbumsError
import de.majuwa.android.paper.krhnlesimagemanagement.worker.FolderCreationError
import de.majuwa.android.paper.krhnlesimagemanagement.worker.folderCreationErrorMessage
import de.majuwa.android.paper.krhnlesimagemanagement.worker.toFolderCreationError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.SocketTimeoutException
import java.net.UnknownHostException

@RunWith(RobolectricTestRunner::class)
class FolderCreationErrorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun forbiddenNotFoundAndConflictAreFolderUnavailable() {
        listOf(403, 404, 409).forEach {
            assertEquals(FolderCreationError.FolderNotAvailable, WebDavHttpException(it, "x").toFolderCreationError())
        }
    }

    @Test
    fun unauthorizedIsAuthFailed() {
        assertEquals(
            FolderCreationError.Connection(AlbumsError.AuthFailed),
            WebDavHttpException(401, "x").toFolderCreationError(),
        )
    }

    @Test
    fun networkFailuresKeepTheirClassification() {
        assertEquals(
            FolderCreationError.Connection(AlbumsError.Timeout),
            SocketTimeoutException().toFolderCreationError(),
        )
        assertEquals(
            FolderCreationError.Connection(AlbumsError.UnknownHost),
            UnknownHostException().toFolderCreationError(),
        )
    }

    @Test
    fun otherHttpStatusIsGenericNotAlbumWording() {
        val error = WebDavHttpException(500, "boom").toFolderCreationError()
        assertEquals(FolderCreationError.Generic, error)
        val text = folderCreationErrorMessage(context.resources, error)
        assertEquals(context.getString(R.string.upload_error_generic), text)
        assertFalse(text.contains("500"))
    }

    @Test
    fun messagesAreUploadSpecific() {
        assertEquals(
            context.getString(R.string.upload_error_invalid_folder_name),
            folderCreationErrorMessage(context.resources, FolderCreationError.InvalidFolderName),
        )
        assertEquals(
            context.getString(R.string.upload_error_folder_unavailable),
            folderCreationErrorMessage(context.resources, FolderCreationError.FolderNotAvailable),
        )
        assertEquals(
            context.getString(R.string.upload_error_not_configured),
            folderCreationErrorMessage(context.resources, FolderCreationError.NotConfigured),
        )
    }
}
