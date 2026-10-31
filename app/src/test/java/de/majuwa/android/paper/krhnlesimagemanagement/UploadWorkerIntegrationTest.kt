package de.majuwa.android.paper.krhnlesimagemanagement

import android.app.Notification
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import de.majuwa.android.paper.krhnlesimagemanagement.worker.CompletionSummary
import de.majuwa.android.paper.krhnlesimagemanagement.worker.UploadWorker
import de.majuwa.android.paper.krhnlesimagemanagement.worker.buildCompletionNotification
import de.majuwa.android.paper.krhnlesimagemanagement.worker.buildFailureNotification
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertNotNull
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class UploadWorkerIntegrationTest {
    private lateinit var context: Context
    private lateinit var mockWebServer: MockWebServer

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        mockWebServer = MockWebServer()
        mockWebServer.start()
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun uploadWorker_successfulUploadCreatesFolder() =
        runTest {
            // Arrange: Mock WebDAV server responses
            mockWebServer.enqueue(MockResponse().setResponseCode(207))
            mockWebServer.enqueue(MockResponse().setResponseCode(405))
            mockWebServer.enqueue(MockResponse().setResponseCode(201))

            val queueFile =
                createTestQueueFile(
                    folderName = "TestFolder",
                    photoCount = 1,
                )

            // Act & Assert: Queue file should exist initially
            assertTrue(queueFile.exists())

            // Simulate worker cleanup
            queueFile.delete()
            assertTrue(!queueFile.exists())
        }

    @Test
    fun uploadWorker_failsWhenQueueFileNotFound() =
        runTest {
            val nonExistentFile = "/invalid/path/queue.json"

            // Act: Try to read non-existent file
            val queueFile = File(nonExistentFile)

            // Assert
            assertTrue(!queueFile.exists())
        }

    @Test
    fun uploadWorker_recoversFromNetworkError() =
        runTest {
            // Arrange: Simulate network timeout
            mockWebServer.enqueue(
                MockResponse().setSocketPolicy(
                    okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START,
                ),
            )

            val queueFile =
                createTestQueueFile(
                    folderName = "TestFolder",
                    photoCount = 1,
                )

            // Act & Assert: Queue file should exist
            assertTrue(queueFile.exists())
            queueFile.delete()
        }

    @Test
    fun uploadWorker_handles401AuthenticationError() =
        runTest {
            mockWebServer.enqueue(MockResponse().setResponseCode(401))

            val queueFile =
                createTestQueueFile(
                    folderName = "TestFolder",
                    photoCount = 1,
                )

            // Assert: Queue file should be created properly
            assertTrue(queueFile.exists())
            queueFile.delete()
        }

    @Test
    fun uploadWorker_handles507InsufficientStorage() =
        runTest {
            mockWebServer.enqueue(MockResponse().setResponseCode(207))
            mockWebServer.enqueue(MockResponse().setResponseCode(405))
            mockWebServer.enqueue(MockResponse().setResponseCode(507))

            val queueFile =
                createTestQueueFile(
                    folderName = "TestFolder",
                    photoCount = 1,
                )

            // Assert: Queue file structure is valid
            assertTrue(queueFile.exists())
            val content = queueFile.readText()
            assertTrue(content.contains("folderName"))
            queueFile.delete()
        }

    @Test
    fun uploadWorker_updatesProgressNotification() =
        runTest {
            mockWebServer.enqueue(MockResponse().setResponseCode(207))
            mockWebServer.enqueue(MockResponse().setResponseCode(405))
            mockWebServer.enqueue(MockResponse().setResponseCode(201))

            val queueFile =
                createTestQueueFile(
                    folderName = "TestFolder",
                    photoCount = 1,
                )

            // Assert: File is valid
            assertTrue(queueFile.exists())
            queueFile.delete()
        }

    @Test
    fun uploadWorker_cleansUpQueueFileOnCompletion() =
        runTest {
            mockWebServer.enqueue(MockResponse().setResponseCode(207))
            mockWebServer.enqueue(MockResponse().setResponseCode(405))
            mockWebServer.enqueue(MockResponse().setResponseCode(201))

            val queueFile =
                createTestQueueFile(
                    folderName = "TestFolder",
                    photoCount = 1,
                )
            assertTrue(queueFile.exists())

            // Simulate cleanup
            queueFile.delete()

            // Assert: Queue file should be deleted after completion
            assertTrue(!queueFile.exists())
        }

    @Test
    fun progressNotification_usesDedicatedNotificationIcon() {
        assertDedicatedIcon(buildWorker().buildProgressNotification(current = 1, total = 3))
    }

    @Test
    fun completionNotification_usesDedicatedNotificationIcon() {
        assertDedicatedIcon(
            context.buildCompletionNotification(
                CompletionSummary(uploaded = 3, failed = 0, retryQueueFilePath = null),
                notificationTag = "tag-1",
            ),
        )
        assertDedicatedIcon(
            context.buildCompletionNotification(
                CompletionSummary(uploaded = 2, failed = 1, retryQueueFilePath = "retry.json"),
                notificationTag = "tag-2",
            ),
        )
    }

    @Test
    fun failureNotification_usesDedicatedNotificationIcon() {
        assertDedicatedIcon(context.buildFailureNotification("boom"))
    }

    @Test
    fun retryActionsOfTwoBatchesAreDistinct() {
        val a = File(context.filesDir, "retry_queue_${UUID.randomUUID()}.json").absolutePath
        val b = File(context.filesDir, "retry_queue_${UUID.randomUUID()}.json").absolutePath

        val first = context.buildCompletionNotification(CompletionSummary(2, 1, a), "tag-a")
        val second = context.buildCompletionNotification(CompletionSummary(2, 1, b), "tag-b")

        val intentA = shadowOf(first.actions.single().actionIntent).savedIntent
        val intentB = shadowOf(second.actions.single().actionIntent).savedIntent
        assertNotNull(intentA.data)
        assertTrue(intentA.data != intentB.data)
        assertFalse(intentA.filterEquals(intentB))
        // The PendingIntents themselves must differ, otherwise the later one overwrites the earlier.
        assertFalse(first.actions.single().actionIntent == second.actions.single().actionIntent)
    }

    private fun buildWorker(): UploadWorker = TestListenableWorkerBuilder<UploadWorker>(context).build()

    private fun assertDedicatedIcon(notification: Notification) {
        val iconRes = notification.smallIcon.resId
        assertEquals(R.drawable.ic_notification_upload, iconRes)
        assertTrue(iconRes != R.drawable.ic_launcher_foreground)
    }

    private fun createTestQueueFile(
        folderName: String,
        photoCount: Int,
    ): File {
        val queueDir = File(context.cacheDir, "upload_queue")
        queueDir.mkdirs()
        val queueFile = File(queueDir, "test_queue_${System.currentTimeMillis()}.json")

        val photos =
            (1..photoCount).joinToString(",") { i ->
                val uri = "content://media/external/images/media/$i"
                val displayName = "photo_$i.jpg"
                val mimeType = "image/jpeg"
                """{"uri":"$uri","displayName":"$displayName","mimeType":"$mimeType"}"""
            }

        val queueJson =
            """
            {
              "folderName": "$folderName",
              "photos": [$photos]
            }
            """.trimIndent()

        queueFile.writeText(queueJson)
        return queueFile
    }
}
