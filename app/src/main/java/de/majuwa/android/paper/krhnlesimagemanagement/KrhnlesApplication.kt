package de.majuwa.android.paper.krhnlesimagemanagement

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.work.WorkManager
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.serviceLoaderEnabled
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialStore
import de.majuwa.android.paper.krhnlesimagemanagement.data.UploadHistoryStore
import de.majuwa.android.paper.krhnlesimagemanagement.data.hasSameOriginAs
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import de.majuwa.android.paper.krhnlesimagemanagement.upload.UploadEnqueuer
import de.majuwa.android.paper.krhnlesimagemanagement.worker.recoverCancelledUploads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.Credentials
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

class KrhnlesApplication :
    Application(),
    SingletonImageLoader.Factory {
    /** Process-scoped coroutine scope for work that must outlive any activity. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Enqueues uploads in [appScope] so they survive the calling activity being destroyed. */
    val uploadEnqueuer: UploadEnqueuer by lazy { UploadEnqueuer(this, CredentialStore(this), appScope) }

    override fun onCreate() {
        super.onCreate()
        // Finalizes uploads the user cancelled that could not finalize themselves (once per process).
        if (!recoveryStarted.compareAndSet(false, true)) return
        appScope.launch {
            runCatching {
                recoverCancelledUploads(
                    this@KrhnlesApplication,
                    WorkManager.getInstance(this@KrhnlesApplication),
                    UploadHistoryStore(this@KrhnlesApplication),
                )
            }.onFailure {
                if (it is CancellationException) throw it
                Log.w("KrhnlesApplication", "Cancelled-upload recovery failed: ${it.javaClass.simpleName}")
            }
        }
    }

    private companion object {
        val recoveryStarted = AtomicBoolean(false)
    }

    override fun newImageLoader(context: Context): ImageLoader {
        val credentialStore = CredentialStore(context)
        val cachedConfig =
            credentialStore.webDavConfig.stateIn(
                scope = appScope,
                started = SharingStarted.Eagerly,
                initialValue = WebDavConfig("", "", "", ""),
            )

        val authenticatedClient =
            OkHttpClient
                .Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val config = cachedConfig.value
                    val requestBuilder = chain.request().newBuilder()
                    // Credentials only go to the configured server origin (defence in depth).
                    if (config.isValid && chain.request().url.hasSameOriginAs(config.url)) {
                        requestBuilder.header(
                            "Authorization",
                            Credentials.basic(config.username, config.password),
                        )
                    }
                    chain.proceed(requestBuilder.build())
                }.build()

        // No service-loader components: they would add an unauthenticated network fetcher and a
        // video decoder for every source; both are registered explicitly instead.
        return ImageLoader
            .Builder(context)
            .serviceLoaderEnabled(false)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { authenticatedClient }))
                add(LocalVideoFrameDecoderFactory())
            }.build()
    }
}
