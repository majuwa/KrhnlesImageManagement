package de.majuwa.android.paper.krhnlesimagemanagement

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import de.majuwa.android.paper.krhnlesimagemanagement.data.CredentialStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class KrhnlesApplication :
    Application(),
    SingletonImageLoader.Factory {
    override fun newImageLoader(context: Context): ImageLoader {
        val credentialStore = CredentialStore(context)
        val authenticatedClient =
            OkHttpClient
                .Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    // runBlocking is intentional here: OkHttp dispatches on IO threads,
                    // and DataStore returns cached values after the first read (fast path).
                    val config = runBlocking { credentialStore.webDavConfig.first() }
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
