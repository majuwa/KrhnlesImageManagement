package de.majuwa.android.paper.krhnlesimagemanagement

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialStore
import de.majuwa.android.paper.krhnlesimagemanagement.model.WebDavConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import okhttp3.Credentials
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class KrhnlesApplication :
    Application(),
    SingletonImageLoader.Factory {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
                    if (config.isValid) {
                        requestBuilder.header(
                            "Authorization",
                            Credentials.basic(config.username, config.password),
                        )
                    }
                    chain.proceed(requestBuilder.build())
                }.build()

        return ImageLoader
            .Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { authenticatedClient }))
            }.build()
    }
}
