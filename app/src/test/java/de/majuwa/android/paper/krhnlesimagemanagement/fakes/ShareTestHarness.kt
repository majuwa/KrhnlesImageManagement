package de.majuwa.android.paper.krhnlesimagemanagement.fakes

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Looper
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.ui.share.ShareViewModel
import de.majuwa.android.paper.krhnlesimagemanagement.upload.ShareStaging
import de.majuwa.android.paper.krhnlesimagemanagement.upload.StagingLimits
import de.majuwa.android.paper.krhnlesimagemanagement.upload.UploadBatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Assert.assertTrue
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Builds share intents backed by [FakeShareProvider] and a [ShareViewModel] with fakes. */
class ShareTestHarness(
    private val app: Application,
    val stagingDir: File,
    queueDir: File,
    var freeBytes: Long = Long.MAX_VALUE / 4,
    limits: StagingLimits = StagingLimits(),
) {
    val enqueued = CopyOnWriteArrayList<UploadBatch>()

    /** When true, the fake enqueuer reports a failure (runs the failure handler). */
    @Volatile
    var failEnqueue = false

    val staging = ShareStaging(stagingDir, listOf(queueDir), freeBytes = { freeBytes }, limits = limits)

    fun configured(autoDate: Boolean = false) =
        FakeCredentialRepository(
            initialConfig = WebDavConfig(url = "https://cloud.example", username = "u", password = "p"),
            initialAutoDateFoldersEnabled = autoDate,
        )

    fun viewModel(
        repo: FakeCredentialRepository = configured(),
        configTimeoutMillis: Long = 3_000,
    ) = ShareViewModel(
        app,
        repo,
        staging,
        { batch, onFailure ->
            if (failEnqueue) CoroutineScope(Dispatchers.IO).launch { onFailure() } else enqueued += batch
        },
        workDispatcher = Dispatchers.IO,
        configTimeoutMillis = configTimeoutMillis,
    )

    /** Registers image [id] with [content] (or no content: it cannot be opened). */
    fun image(
        id: String,
        content: ByteArray? = byteArrayOf(1, 2, 3),
        type: String? = "image/jpeg",
        name: String? = "$id.jpg",
    ): Uri {
        FakeShareProvider.items[id] =
            FakeShareProvider.Item(type = type, name = name, size = content?.size?.toLong(), content = content)
        return FakeShareProvider.uri(id)
    }

    /**
     * Image [id] whose open blocks on the returned gate until released or (if [honoursCancel])
     * until the open's `CancellationSignal` is cancelled.
     */
    fun gatedImage(
        id: String,
        honoursCancel: Boolean = true,
    ): Pair<Uri, ProviderGate> {
        val gate = ProviderGate(honoursCancel)
        FakeShareProvider.items[id] =
            FakeShareProvider.Item(name = "$id.jpg", content = byteArrayOf(9, 9), openGate = gate)
        return FakeShareProvider.uri(id) to gate
    }

    fun stagedFiles(): List<File> = stagingDir.listFiles().orEmpty().toList()

    companion object {
        fun share(vararg uris: Uri): Intent =
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris.toList()))
            }

        /** Polls [condition], draining the paused main looper (viewModelScope runs there). */
        fun waitUntil(
            timeoutMillis: Long = 10_000,
            condition: () -> Boolean,
        ) {
            val deadline = System.currentTimeMillis() + timeoutMillis
            while (!condition() && System.currentTimeMillis() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(5)
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("condition not met in time", condition())
        }
    }
}
